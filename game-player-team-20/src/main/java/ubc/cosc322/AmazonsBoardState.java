package ubc.cosc322;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AmazonsBoardState {
    public static final int BOARD_DIMENSION = 11;
    public static final int MIN_INDEX = 1;
    public static final int MAX_INDEX = 10;

    public static final int EMPTY = 0;
    public static final int WHITE = 1;
    public static final int BLACK = 2;
    public static final int ARROW = 3;
    public static final int NONE = 0;

    public static int opponent(int color) {
        return color == BLACK ? WHITE : BLACK;
    }

    private static final int INF = 1_000_000;
    private static final int[][] DIRECTIONS = {
        {-1, -1}, {-1, 0}, {-1, 1},
        {0, -1},           {0, 1},
        {1, -1},  {1, 0},  {1, 1}
    };

    private final int[][] board;

    // Cached queen positions — always exactly 4 per side in Amazons
    private final int[] wQueenRows = new int[4];
    private final int[] wQueenCols = new int[4];
    private final int[] bQueenRows = new int[4];
    private final int[] bQueenCols = new int[4];
    private int arrowCount;

    // Static scratch buffers — search is single-threaded so safe to share
    private static final int[][] SCRATCH_DIST_A = new int[BOARD_DIMENSION][BOARD_DIMENSION];
    private static final int[][] SCRATCH_DIST_B = new int[BOARD_DIMENSION][BOARD_DIMENSION];
    private static final int[] BFS_QUEUE = new int[BOARD_DIMENSION * BOARD_DIMENSION];

    public AmazonsBoardState() {
        this.board = new int[BOARD_DIMENSION][BOARD_DIMENSION];
    }

    public static AmazonsBoardState fromServerState(List<Integer> encodedState) {
        if (encodedState == null || encodedState.size() < BOARD_DIMENSION * BOARD_DIMENSION) {
            throw new IllegalArgumentException("Expected 121 integers for board state");
        }

        AmazonsBoardState state = new AmazonsBoardState();
        int wi = 0, bi = 0;
        for (int row = MIN_INDEX; row <= MAX_INDEX; row++) {
            for (int col = MIN_INDEX; col <= MAX_INDEX; col++) {
                int cell = encodedState.get(BOARD_DIMENSION * row + col).intValue();
                state.board[row][col] = cell;
                if (cell == WHITE) {
                    state.wQueenRows[wi] = row;
                    state.wQueenCols[wi++] = col;
                } else if (cell == BLACK) {
                    state.bQueenRows[bi] = row;
                    state.bQueenCols[bi++] = col;
                } else if (cell == ARROW) {
                    state.arrowCount++;
                }
            }
        }
        return state;
    }

    public AmazonsBoardState copy() {
        AmazonsBoardState copy = new AmazonsBoardState();
        for (int row = 0; row < BOARD_DIMENSION; row++) {
            System.arraycopy(board[row], 0, copy.board[row], 0, BOARD_DIMENSION);
        }
        System.arraycopy(wQueenRows, 0, copy.wQueenRows, 0, 4);
        System.arraycopy(wQueenCols, 0, copy.wQueenCols, 0, 4);
        System.arraycopy(bQueenRows, 0, copy.bQueenRows, 0, 4);
        System.arraycopy(bQueenCols, 0, copy.bQueenCols, 0, 4);
        copy.arrowCount = this.arrowCount;
        return copy;
    }

    public int get(int row, int col) {
        return board[row][col];
    }

    public void set(int row, int col, int value) {
        ensurePlayable(row, col);
        board[row][col] = value;
    }

    public ArrayList<Integer> toServerState() {
        ArrayList<Integer> encoded = new ArrayList<Integer>(BOARD_DIMENSION * BOARD_DIMENSION);
        for (int row = 0; row < BOARD_DIMENSION; row++) {
            for (int col = 0; col < BOARD_DIMENSION; col++) {
                encoded.add(row >= MIN_INDEX && col >= MIN_INDEX ? board[row][col] : 0);
            }
        }
        return encoded;
    }

    public int inferSideToMove() {
        return arrowCount % 2 == 0 ? BLACK : WHITE;
    }

    public int countArrows() {
        return arrowCount;
    }

    public boolean hasAnyMoves(int player) {
        int[] rows = player == WHITE ? wQueenRows : bQueenRows;
        int[] cols = player == WHITE ? wQueenCols : bQueenCols;
        for (int i = 0; i < 4; i++) {
            if (queenHasDestination(rows[i], cols[i])) {
                return true;
            }
        }
        return false;
    }

    public List<AmazonsMove> generateMoves(int player) {
        ArrayList<AmazonsMove> moves = new ArrayList<AmazonsMove>();
        int[] rows = player == WHITE ? wQueenRows : bQueenRows;
        int[] cols = player == WHITE ? wQueenCols : bQueenCols;
        for (int i = 0; i < 4; i++) {
            addMovesForQueen(player, rows[i], cols[i], moves);
        }
        return moves;
    }

    public void applyMove(AmazonsMove move, int player) {
        int fromRow = move.getFromRow(), fromCol = move.getFromCol();
        int toRow   = move.getToRow(),   toCol   = move.getToCol();
        int arrRow  = move.getArrowRow(), arrCol  = move.getArrowCol();

        if (board[fromRow][fromCol] != player) {
            throw new IllegalArgumentException("Source square does not contain the expected piece");
        }
        board[fromRow][fromCol] = EMPTY;
        board[toRow][toCol]     = player;
        board[arrRow][arrCol]   = ARROW;
        arrowCount++;

        int[] rows = player == WHITE ? wQueenRows : bQueenRows;
        int[] cols = player == WHITE ? wQueenCols : bQueenCols;
        for (int i = 0; i < 4; i++) {
            if (rows[i] == fromRow && cols[i] == fromCol) {
                rows[i] = toRow;
                cols[i] = toCol;
                break;
            }
        }
    }

    public void undoMove(AmazonsMove move, int player) {
        int fromRow = move.getFromRow(), fromCol = move.getFromCol();
        int toRow   = move.getToRow(),   toCol   = move.getToCol();
        int arrRow  = move.getArrowRow(), arrCol  = move.getArrowCol();

        board[arrRow][arrCol]   = EMPTY;
        board[toRow][toCol]     = EMPTY;
        board[fromRow][fromCol] = player;
        arrowCount--;

        int[] rows = player == WHITE ? wQueenRows : bQueenRows;
        int[] cols = player == WHITE ? wQueenCols : bQueenCols;
        for (int i = 0; i < 4; i++) {
            if (rows[i] == toRow && cols[i] == toCol) {
                rows[i] = fromRow;
                cols[i] = fromCol;
                break;
            }
        }
    }

    public int countQueenDestinations(int player) {
        int[] rows = player == WHITE ? wQueenRows : bQueenRows;
        int[] cols = player == WHITE ? wQueenCols : bQueenCols;
        int destinations = 0;
        for (int i = 0; i < 4; i++) {
            destinations += countDestinationsFrom(rows[i], cols[i]);
        }
        return destinations;
    }

    public int countActiveQueens(int player) {
        int[] rows = player == WHITE ? wQueenRows : bQueenRows;
        int[] cols = player == WHITE ? wQueenCols : bQueenCols;
        int active = 0;
        for (int i = 0; i < 4; i++) {
            if (queenHasDestination(rows[i], cols[i])) {
                active++;
            }
        }
        return active;
    }

    public int countDestinationsFrom(int row, int col) {
        int destinations = 0;
        for (int[] direction : DIRECTIONS) {
            int r = row + direction[0];
            int c = col + direction[1];
            while (isPlayable(r, c) && board[r][c] == EMPTY) {
                destinations++;
                r += direction[0];
                c += direction[1];
            }
        }
        return destinations;
    }

    public int evaluate(int perspective) {
        int opponent = opponent(perspective);
        int[] myRows  = perspective == WHITE ? wQueenRows : bQueenRows;
        int[] myCols  = perspective == WHITE ? wQueenCols : bQueenCols;
        int[] oppRows = opponent   == WHITE ? wQueenRows : bQueenRows;
        int[] oppCols = opponent   == WHITE ? wQueenCols : bQueenCols;

        queenDistances(myRows,  myCols,  SCRATCH_DIST_A);
        queenDistances(oppRows, oppCols, SCRATCH_DIST_B);

        int territoryScore = 0;
        int contestedScore = 0;
        int myReachable = 0;
        int opponentReachable = 0;
        boolean separated = true;

        for (int row = MIN_INDEX; row <= MAX_INDEX; row++) {
            for (int col = MIN_INDEX; col <= MAX_INDEX; col++) {
                if (board[row][col] != EMPTY) {
                    continue;
                }

                int myDistance       = SCRATCH_DIST_A[row][col];
                int opponentDistance = SCRATCH_DIST_B[row][col];
                boolean myFinite       = myDistance       < INF;
                boolean opponentFinite = opponentDistance < INF;

                if (myFinite)       myReachable++;
                if (opponentFinite) opponentReachable++;

                if (myFinite && !opponentFinite) {
                    territoryScore++;
                } else if (!myFinite && opponentFinite) {
                    territoryScore--;
                } else if (myFinite && opponentFinite) {
                    separated = false;
                    if (myDistance < opponentDistance) {
                        territoryScore++;
                    } else if (opponentDistance < myDistance) {
                        territoryScore--;
                    } else {
                        contestedScore += contestedPressure(row, col, myRows, myCols, oppRows, oppCols);
                    }
                }
            }
        }

        if (separated) {
            int myMoves       = countFillMoves(SCRATCH_DIST_A);
            int opponentMoves = countFillMoves(SCRATCH_DIST_B);
            return (myMoves - opponentMoves) * 500;
        }

        int mobilityScore    = countQueenDestinations(perspective) - countQueenDestinations(opponent);
        int activeQueenScore = countActiveQueens(perspective) - countActiveQueens(opponent);
        int reachabilityScore = myReachable - opponentReachable;
        int trapScore         = countTrappedQueens(opponent) - countTrappedQueens(perspective);

        int phase           = Math.min(arrowCount, 40);
        int territoryWeight = 60 + phase * 3;
        int mobilityWeight  = 12 - phase / 5;

        return territoryScore  * territoryWeight
             + mobilityScore   * mobilityWeight
             + activeQueenScore * 15
             + contestedScore  * 2
             + reachabilityScore
             + trapScore       * 120;
    }

    // --- Private helpers ---

    private int countFillMoves(int[][] distances) {
        int moves = 0;
        for (int row = MIN_INDEX; row <= MAX_INDEX; row++) {
            for (int col = MIN_INDEX; col <= MAX_INDEX; col++) {
                if (board[row][col] == EMPTY && distances[row][col] < INF) {
                    moves++;
                }
            }
        }
        return moves;
    }

    private int contestedPressure(int row, int col,
                                   int[] myRows, int[] myCols,
                                   int[] oppRows, int[] oppCols) {
        int myP  = nearbyQueenPressure(row, col, myRows,  myCols);
        int oppP = nearbyQueenPressure(row, col, oppRows, oppCols);
        if (myP == oppP) return 0;
        return myP > oppP ? 1 : -1;
    }

    private int nearbyQueenPressure(int targetRow, int targetCol,
                                     int[] queenRows, int[] queenCols) {
        int pressure = 0;
        for (int i = 0; i < 4; i++) {
            int distance = Math.max(
                Math.abs(queenRows[i] - targetRow),
                Math.abs(queenCols[i] - targetCol)
            );
            if (distance <= 2) {
                pressure += 3 - distance;
            }
        }
        return pressure;
    }

    // Fills `out` with queen-move distances from the given queen set.
    // Uses static BFS_QUEUE to avoid per-call allocation.
    private void queenDistances(int[] queenRows, int[] queenCols, int[][] out) {
        for (int row = 0; row < BOARD_DIMENSION; row++) {
            Arrays.fill(out[row], INF);
        }

        int head = 0, tail = 0;
        for (int i = 0; i < 4; i++) {
            int r = queenRows[i], c = queenCols[i];
            out[r][c] = 0;
            BFS_QUEUE[tail++] = r * BOARD_DIMENSION + c;
        }

        while (head < tail) {
            int idx  = BFS_QUEUE[head++];
            int r    = idx / BOARD_DIMENSION;
            int c    = idx % BOARD_DIMENSION;
            int next = out[r][c] + 1;
            for (int[] dir : DIRECTIONS) {
                int nr = r + dir[0];
                int nc = c + dir[1];
                while (isPlayable(nr, nc) && board[nr][nc] == EMPTY) {
                    if (next < out[nr][nc]) {
                        out[nr][nc] = next;
                        BFS_QUEUE[tail++] = nr * BOARD_DIMENSION + nc;
                    }
                    nr += dir[0];
                    nc += dir[1];
                }
            }
        }
    }

    private void addMovesForQueen(int player, int fromRow, int fromCol, List<AmazonsMove> moves) {
        for (int[] direction : DIRECTIONS) {
            int toRow = fromRow + direction[0];
            int toCol = fromCol + direction[1];
            while (isPlayable(toRow, toCol) && board[toRow][toCol] == EMPTY) {
                board[fromRow][fromCol] = EMPTY;
                board[toRow][toCol] = player;
                addArrowMoves(fromRow, fromCol, toRow, toCol, moves);
                board[toRow][toCol] = EMPTY;
                board[fromRow][fromCol] = player;

                toRow += direction[0];
                toCol += direction[1];
            }
        }
    }

    private void addArrowMoves(int fromRow, int fromCol, int toRow, int toCol, List<AmazonsMove> moves) {
        for (int[] direction : DIRECTIONS) {
            int arrowRow = toRow + direction[0];
            int arrowCol = toCol + direction[1];
            while (isPlayable(arrowRow, arrowCol) && board[arrowRow][arrowCol] == EMPTY) {
                moves.add(new AmazonsMove(fromRow, fromCol, toRow, toCol, arrowRow, arrowCol));
                arrowRow += direction[0];
                arrowCol += direction[1];
            }
        }
    }

    private int countTrappedQueens(int player) {
        int[] rows = player == WHITE ? wQueenRows : bQueenRows;
        int[] cols = player == WHITE ? wQueenCols : bQueenCols;
        int trapped = 0;
        for (int i = 0; i < 4; i++) {
            if (!queenHasDestination(rows[i], cols[i])) {
                trapped++;
            }
        }
        return trapped;
    }

    private boolean queenHasDestination(int row, int col) {
        for (int[] direction : DIRECTIONS) {
            int nextRow = row + direction[0];
            int nextCol = col + direction[1];
            if (isPlayable(nextRow, nextCol) && board[nextRow][nextCol] == EMPTY) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlayable(int row, int col) {
        return row >= MIN_INDEX && row <= MAX_INDEX && col >= MIN_INDEX && col <= MAX_INDEX;
    }

    private static void ensurePlayable(int row, int col) {
        if (!isPlayable(row, col)) {
            throw new IllegalArgumentException("Coordinate out of bounds: (" + row + "," + col + ")");
        }
    }
}
