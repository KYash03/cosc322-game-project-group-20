package ubc.cosc322;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public final class AmazonsBoardState {
    public static final int BOARD_DIMENSION = 11;
    public static final int MIN_INDEX = 1;
    public static final int MAX_INDEX = 10;

    public static final int EMPTY = 0;
    public static final int WHITE = 1;
    public static final int BLACK = 2;
    public static final int ARROW = 3;
    public static final int NONE = 0;

    public static int opponent(int color) {
        if (color == WHITE) return BLACK;
        if (color == BLACK) return WHITE;
        return NONE;
    }

    private static final int[][] DIRECTIONS = new int[][] {
        { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 },
        { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 }
    };

    private static final int INF = 1_000_000;
    private static final int[][] SCRATCH_A = new int[BOARD_DIMENSION][BOARD_DIMENSION];
    private static final int[][] SCRATCH_B = new int[BOARD_DIMENSION][BOARD_DIMENSION];
    private static final int[] BFS_Q = new int[BOARD_DIMENSION * BOARD_DIMENSION];

    private final int[][] board = new int[BOARD_DIMENSION][BOARD_DIMENSION];

    private final int[] wR = new int[4];
    private final int[] wC = new int[4];
    private final int[] bR = new int[4];
    private final int[] bC = new int[4];
    private int arrowCount;

    private static final long[][][] ZOBRIST = new long[BOARD_DIMENSION][BOARD_DIMENSION][4];
    private static final long SIDE_KEY = 0x9E3779B97F4A7C15L;

    static {
        long x = 0xC0C322A5A20A7E1L;
        for (int r = 0; r < BOARD_DIMENSION; r++) {
            for (int c = 0; c < BOARD_DIMENSION; c++) {
                for (int p = 0; p < 4; p++) {
                    x += 0x9E3779B97F4A7C15L;
                    long z = x;
                    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
                    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
                    z = z ^ (z >>> 31);
                    ZOBRIST[r][c][p] = z;
                }
            }
        }
    }

    private long zobrist;

    public AmazonsBoardState() {}

    public static AmazonsBoardState fromServerState(List<Integer> encodedState) {
        if (encodedState == null || encodedState.size() < BOARD_DIMENSION * BOARD_DIMENSION) {
            throw new IllegalArgumentException("Expected 121 integers for board state");
        }

        AmazonsBoardState s = new AmazonsBoardState();
        int wi = 0;
        int bi = 0;

        for (int row = MIN_INDEX; row <= MAX_INDEX; row++) {
            for (int col = MIN_INDEX; col <= MAX_INDEX; col++) {
                int cell = encodedState.get(BOARD_DIMENSION * row + col).intValue();
                s.board[row][col] = cell;

                if (cell == WHITE) {
                    s.wR[wi] = row;
                    s.wC[wi] = col;
                    wi++;
                } else if (cell == BLACK) {
                    s.bR[bi] = row;
                    s.bC[bi] = col;
                    bi++;
                } else if (cell == ARROW) {
                    s.arrowCount++;
                }
            }
        }

        s.zobrist = s.recomputeZobrist();
        return s;
    }

    public AmazonsBoardState copy() {
        AmazonsBoardState c = new AmazonsBoardState();
        for (int r = 0; r < BOARD_DIMENSION; r++) {
            System.arraycopy(this.board[r], 0, c.board[r], 0, BOARD_DIMENSION);
        }
        System.arraycopy(wR, 0, c.wR, 0, 4);
        System.arraycopy(wC, 0, c.wC, 0, 4);
        System.arraycopy(bR, 0, c.bR, 0, 4);
        System.arraycopy(bC, 0, c.bC, 0, 4);
        c.arrowCount = this.arrowCount;
        c.zobrist = this.zobrist;
        return c;
    }

    public int countArrows() { return arrowCount; }

    public int inferSideToMove() {
        // Your rule-set: BLACK moves first; each completed move places exactly one arrow.
        return (arrowCount & 1) == 0 ? BLACK : WHITE;
    }

    public long getHash() { return zobrist; }

    public long getHashWithSide(int playerToMove) {
        return playerToMove == BLACK ? (zobrist ^ SIDE_KEY) : zobrist;
    }

    public boolean hasAnyMoves(int player) {
        int[] rows = player == WHITE ? wR : bR;
        int[] cols = player == WHITE ? wC : bC;
        for (int i = 0; i < 4; i++) {
            if (queenHasDestination(rows[i], cols[i])) return true;
        }
        return false;
    }

    public List<AmazonsMove> generateMoves(int player) {
        ArrayList<AmazonsMove> moves = new ArrayList<>(512);
        int[] rows = player == WHITE ? wR : bR;
        int[] cols = player == WHITE ? wC : bC;
        for (int i = 0; i < 4; i++) {
            addMovesForQueenNoMutate(rows[i], cols[i], moves);
        }
        return moves;
    }

    public List<AmazonsMove> generateTopMoves(int player, int k, int ttBestPacked) {
        if (k <= 0) return Collections.emptyList();

        PriorityQueue<ScoredMove> pq = new PriorityQueue<>(k, Comparator.comparingInt((ScoredMove a) -> a.score));
        int[] rows = player == WHITE ? wR : bR;
        int[] cols = player == WHITE ? wC : bC;

        for (int i = 0; i < 4; i++) {
            int fr = rows[i];
            int fc = cols[i];
            for (int[] d : DIRECTIONS) {
                int tr = fr + d[0];
                int tc = fc + d[1];
                while (isPlayable(tr, tc) && board[tr][tc] == EMPTY) {
                    for (int[] ad : DIRECTIONS) {
                        int ar = tr + ad[0];
                        int ac = tc + ad[1];
                        while (isPlayable(ar, ac) && isEmptyAfterMove(fr, fc, ar, ac)) {
                            AmazonsMove m = new AmazonsMove(fr, fc, tr, tc, ar, ac);
                            int packed = AmazonsMove.pack(m);
                            int score = cheapOrderScore(m) + (packed == ttBestPacked ? 2_000_000 : 0);

                            if (pq.size() < k) {
                                pq.add(new ScoredMove(score, m));
                            } else if (score > pq.peek().score) {
                                pq.poll();
                                pq.add(new ScoredMove(score, m));
                            }

                            ar += ad[0];
                            ac += ad[1];
                        }
                    }
                    tr += d[0];
                    tc += d[1];
                }
            }
        }

        ArrayList<AmazonsMove> out = new ArrayList<>(pq.size());
        while (!pq.isEmpty()) out.add(pq.poll().move);
        Collections.reverse(out);
        return out;
    }

    public void applyMove(AmazonsMove move, int player) {
        int fr = move.getFromRow();
        int fc = move.getFromCol();
        int tr = move.getToRow();
        int tc = move.getToCol();
        int ar = move.getArrowRow();
        int ac = move.getArrowCol();

        if (board[fr][fc] != player) {
            throw new IllegalArgumentException("Source does not contain expected player");
        }

        zobrist ^= ZOBRIST[fr][fc][player];
        zobrist ^= ZOBRIST[tr][tc][player];
        zobrist ^= ZOBRIST[ar][ac][ARROW];

        board[fr][fc] = EMPTY;
        board[tr][tc] = player;
        board[ar][ac] = ARROW;
        arrowCount++;

        int[] rows = player == WHITE ? wR : bR;
        int[] cols = player == WHITE ? wC : bC;
        for (int i = 0; i < 4; i++) {
            if (rows[i] == fr && cols[i] == fc) {
                rows[i] = tr;
                cols[i] = tc;
                break;
            }
        }
    }

    public void undoMove(AmazonsMove move, int player) {
        int fr = move.getFromRow();
        int fc = move.getFromCol();
        int tr = move.getToRow();
        int tc = move.getToCol();
        int ar = move.getArrowRow();
        int ac = move.getArrowCol();

        zobrist ^= ZOBRIST[ar][ac][ARROW];
        zobrist ^= ZOBRIST[tr][tc][player];
        zobrist ^= ZOBRIST[fr][fc][player];

        board[ar][ac] = EMPTY;
        board[tr][tc] = EMPTY;
        board[fr][fc] = player;
        arrowCount--;

        int[] rows = player == WHITE ? wR : bR;
        int[] cols = player == WHITE ? wC : bC;
        for (int i = 0; i < 4; i++) {
            if (rows[i] == tr && cols[i] == tc) {
                rows[i] = fr;
                cols[i] = fc;
                break;
            }
        }
    }

    public int countDestinationsFrom(int row, int col) {
        int destinations = 0;
        for (int[] d : DIRECTIONS) {
            int r = row + d[0];
            int c = col + d[1];
            while (isPlayable(r, c) && board[r][c] == EMPTY) {
                destinations++;
                r += d[0];
                c += d[1];
            }
        }
        return destinations;
    }

    public int fastEvaluate(int perspective) {
        int opp = opponent(perspective);
        int mob = countQueenDestinations(perspective) - countQueenDestinations(opp);
        int active = countActiveQueens(perspective) - countActiveQueens(opp);
        int trapped = countTrappedQueens(opp) - countTrappedQueens(perspective);
        int phase = Math.min(arrowCount, 48);

        int mobW = 14 - phase / 6;
        int actW = 18;
        int trapW = 110 + phase;

        return mob * mobW + active * actW + trapped * trapW;
    }

    public int evaluate(int perspective) {
        int opp = opponent(perspective);
        int[] myR = perspective == WHITE ? wR : bR;
        int[] myC = perspective == WHITE ? wC : bC;
        int[] opR = opp == WHITE ? wR : bR;
        int[] opC = opp == WHITE ? wC : bC;

        queenDistances(myR, myC, SCRATCH_A);
        queenDistances(opR, opC, SCRATCH_B);

        int territory = 0;
        int contested = 0;
        int myReach = 0;
        int opReach = 0;
        boolean separated = true;

        for (int r = MIN_INDEX; r <= MAX_INDEX; r++) {
            for (int c = MIN_INDEX; c <= MAX_INDEX; c++) {
                if (board[r][c] != EMPTY) continue;

                int a = SCRATCH_A[r][c];
                int b = SCRATCH_B[r][c];
                boolean af = a < INF;
                boolean bf = b < INF;

                if (af) myReach++;
                if (bf) opReach++;

                if (af && !bf) territory++;
                else if (!af && bf) territory--;
                else if (af && bf) {
                    separated = false;
                    if (a < b) territory++;
                    else if (b < a) territory--;
                    else contested += contestedPressure(r, c, myR, myC, opR, opC);
                }
            }
        }

        if (separated) {
            int myFill = countFillMoves(SCRATCH_A);
            int opFill = countFillMoves(SCRATCH_B);
            return (myFill - opFill) * 500;
        }

        int mobility = countQueenDestinations(perspective) - countQueenDestinations(opp);
        int active = countActiveQueens(perspective) - countActiveQueens(opp);
        int trapped = countTrappedQueens(opp) - countTrappedQueens(perspective);
        int reach = myReach - opReach;

        int phase = Math.min(arrowCount, 44);
        int terrW = 70 + phase * 3;
        int mobW = 12 - phase / 6;

        return territory * terrW
            + mobility * mobW
            + active * 16
            + trapped * (120 + phase)
            + contested * 2
            + reach;
    }

    private void addMovesForQueenNoMutate(int fr, int fc, List<AmazonsMove> moves) {
        for (int[] d : DIRECTIONS) {
            int tr = fr + d[0];
            int tc = fc + d[1];
            while (isPlayable(tr, tc) && board[tr][tc] == EMPTY) {
                addArrowMovesNoMutate(fr, fc, tr, tc, moves);
                tr += d[0];
                tc += d[1];
            }
        }
    }

    private void addArrowMovesNoMutate(int fr, int fc, int tr, int tc, List<AmazonsMove> moves) {
        for (int[] d : DIRECTIONS) {
            int ar = tr + d[0];
            int ac = tc + d[1];
            while (isPlayable(ar, ac) && isEmptyAfterMove(fr, fc, ar, ac)) {
                moves.add(new AmazonsMove(fr, fc, tr, tc, ar, ac));
                ar += d[0];
                ac += d[1];
            }
        }
    }

    private boolean isEmptyAfterMove(int fr, int fc, int r, int c) {
        return board[r][c] == EMPTY || (r == fr && c == fc);
    }

    private int countQueenDestinations(int player) {
        int[] rows = player == WHITE ? wR : bR;
        int[] cols = player == WHITE ? wC : bC;
        int sum = 0;
        for (int i = 0; i < 4; i++) sum += countDestinationsFrom(rows[i], cols[i]);
        return sum;
    }

    private int countActiveQueens(int player) {
        int[] rows = player == WHITE ? wR : bR;
        int[] cols = player == WHITE ? wC : bC;
        int a = 0;
        for (int i = 0; i < 4; i++) {
            if (queenHasDestination(rows[i], cols[i])) a++;
        }
        return a;
    }

    private int countTrappedQueens(int player) {
        int[] rows = player == WHITE ? wR : bR;
        int[] cols = player == WHITE ? wC : bC;
        int t = 0;
        for (int i = 0; i < 4; i++) {
            if (!queenHasDestination(rows[i], cols[i])) t++;
        }
        return t;
    }

    private boolean queenHasDestination(int row, int col) {
        for (int[] d : DIRECTIONS) {
            int r = row + d[0];
            int c = col + d[1];
            if (isPlayable(r, c) && board[r][c] == EMPTY) return true;
        }
        return false;
    }

    private void queenDistances(int[] qR, int[] qC, int[][] out) {
        for (int r = 0; r < BOARD_DIMENSION; r++) Arrays.fill(out[r], INF);

        int head = 0;
        int tail = 0;

        for (int i = 0; i < 4; i++) {
            out[qR[i]][qC[i]] = 0;
            BFS_Q[tail++] = qR[i] * BOARD_DIMENSION + qC[i];
        }

        while (head < tail) {
            int idx = BFS_Q[head++];
            int r = idx / BOARD_DIMENSION;
            int c = idx % BOARD_DIMENSION;
            int nd = out[r][c] + 1;

            for (int[] d : DIRECTIONS) {
                int nr = r + d[0];
                int nc = c + d[1];
                while (isPlayable(nr, nc) && board[nr][nc] == EMPTY) {
                    if (nd < out[nr][nc]) {
                        out[nr][nc] = nd;
                        BFS_Q[tail++] = nr * BOARD_DIMENSION + nc;
                    }
                    nr += d[0];
                    nc += d[1];
                }
            }
        }
    }

    private int countFillMoves(int[][] dist) {
        int m = 0;
        for (int r = MIN_INDEX; r <= MAX_INDEX; r++) {
            for (int c = MIN_INDEX; c <= MAX_INDEX; c++) {
                if (board[r][c] == EMPTY && dist[r][c] < INF) m++;
            }
        }
        return m;
    }

    private int contestedPressure(int r, int c, int[] myR, int[] myC, int[] opR, int[] opC) {
        return nearbyQueenPressure(r, c, myR, myC) - nearbyQueenPressure(r, c, opR, opC);
    }

    private int nearbyQueenPressure(int r, int c, int[] rows, int[] cols) {
        int p = 0;
        for (int i = 0; i < 4; i++) {
            int dr = Math.abs(rows[i] - r);
            int dc = Math.abs(cols[i] - c);
            int d = Math.max(dr, dc);
            if (d <= 2) p += (3 - d) * 6;
        }
        return p;
    }

    private static int cheapOrderScore(AmazonsMove m) {
        int cTo = 20 - (Math.abs(5 - m.getToRow()) + Math.abs(5 - m.getToCol()));
        int cAr = 20 - (Math.abs(5 - m.getArrowRow()) + Math.abs(5 - m.getArrowCol()));
        return 9 * cTo + 3 * cAr;
    }

    private long recomputeZobrist() {
        long h = 0L;
        for (int r = MIN_INDEX; r <= MAX_INDEX; r++) {
            for (int c = MIN_INDEX; c <= MAX_INDEX; c++) {
                int cell = board[r][c];
                if (cell != EMPTY) h ^= ZOBRIST[r][c][cell];
            }
        }
        return h;
    }

    private static boolean isPlayable(int row, int col) {
        return row >= MIN_INDEX && row <= MAX_INDEX && col >= MIN_INDEX && col <= MAX_INDEX;
    }

    private static final class ScoredMove {
        final int score;
        final AmazonsMove move;

        ScoredMove(int score, AmazonsMove move) {
            this.score = score;
            this.move = move;
        }
    }
}
