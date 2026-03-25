package ubc.cosc322;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class AmazonsBoardState {
    public static final int BOARD_DIMENSION = 11;
    public static final int MIN_INDEX = 1;
    public static final int MAX_INDEX = 10;

    public static final int EMPTY = 0;
    public static final int BLACK = 1;
    public static final int WHITE = 2;
    public static final int ARROW = 3;
    public static final int NONE = 0;

    private static final int INF = 1_000_000;
    private static final int[][] DIRECTIONS = {
        {-1, -1}, {-1, 0}, {-1, 1},
        {0, -1},           {0, 1},
        {1, -1},  {1, 0},  {1, 1}
    };
    private static final long[][][] ZOBRIST = initZobrist();
    private static final long[] SIDE_TO_MOVE_HASH = initSideHashes();
    private static final ThreadLocal<EvalScratch> EVAL_SCRATCH = ThreadLocal.withInitial(EvalScratch::new);

    private final int[][] board;
    private final ArrayList<int[]> blackQueens;
    private final ArrayList<int[]> whiteQueens;
    private long zobristHash;
    private int arrowCount;

    public AmazonsBoardState() {
        this.board = new int[BOARD_DIMENSION][BOARD_DIMENSION];
        this.blackQueens = new ArrayList<int[]>(4);
        this.whiteQueens = new ArrayList<int[]>(4);
    }

    public static int opponent(int color) {
        return color == BLACK ? WHITE : BLACK;
    }

    public static AmazonsBoardState fromServerState(List<Integer> encodedState) {
        if (encodedState == null || encodedState.size() < BOARD_DIMENSION * BOARD_DIMENSION) {
            throw new IllegalArgumentException("Expected 121 integers for board state");
        }

        AmazonsBoardState state = new AmazonsBoardState();
        for (int row = MIN_INDEX; row <= MAX_INDEX; row++) {
            for (int col = MIN_INDEX; col <= MAX_INDEX; col++) {
                state.board[row][col] = encodedState.get(BOARD_DIMENSION * row + col).intValue();
            }
        }
        state.rebuildMetadata();
        return state;
    }

    public AmazonsBoardState copy() {
        AmazonsBoardState copy = new AmazonsBoardState();
        for (int row = 0; row < BOARD_DIMENSION; row++) {
            System.arraycopy(board[row], 0, copy.board[row], 0, BOARD_DIMENSION);
        }
        copy.blackQueens.addAll(copyQueens(blackQueens));
        copy.whiteQueens.addAll(copyQueens(whiteQueens));
        copy.zobristHash = zobristHash;
        copy.arrowCount = arrowCount;
        return copy;
    }

    public int get(int row, int col) {
        return board[row][col];
    }

    public void set(int row, int col, int value) {
        ensurePlayable(row, col);

        int previous = board[row][col];
        if (previous == value) {
            return;
        }

        xorPiece(row, col, previous);
        removeTrackedPiece(previous, row, col);

        board[row][col] = value;
        xorPiece(row, col, value);
        addTrackedPiece(value, row, col);
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
        for (int[] queen : getQueenPositions(player)) {
            if (queenHasDestination(queen[0], queen[1])) {
                return true;
            }
        }
        return false;
    }

    public List<AmazonsMove> generateMoves(int player) {
        ArrayList<AmazonsMove> moves = new ArrayList<AmazonsMove>();
        for (int[] queen : getQueenPositions(player)) {
            addMovesForQueen(player, queen[0], queen[1], moves);
        }
        return moves;
    }

    public void applyMove(AmazonsMove move, int player) {
        if (board[move.getFromRow()][move.getFromCol()] != player) {
            throw new IllegalArgumentException("Source square does not contain the expected piece");
        }

        xorPiece(move.getFromRow(), move.getFromCol(), player);
        board[move.getFromRow()][move.getFromCol()] = EMPTY;

        xorPiece(move.getToRow(), move.getToCol(), player);
        board[move.getToRow()][move.getToCol()] = player;
        moveTrackedQueen(player, move.getFromRow(), move.getFromCol(), move.getToRow(), move.getToCol());

        xorPiece(move.getArrowRow(), move.getArrowCol(), ARROW);
        board[move.getArrowRow()][move.getArrowCol()] = ARROW;
        arrowCount++;
    }

    public void undoMove(AmazonsMove move, int player) {
        xorPiece(move.getArrowRow(), move.getArrowCol(), ARROW);
        board[move.getArrowRow()][move.getArrowCol()] = EMPTY;
        arrowCount--;

        xorPiece(move.getToRow(), move.getToCol(), player);
        board[move.getToRow()][move.getToCol()] = EMPTY;

        xorPiece(move.getFromRow(), move.getFromCol(), player);
        board[move.getFromRow()][move.getFromCol()] = player;
        moveTrackedQueen(player, move.getToRow(), move.getToCol(), move.getFromRow(), move.getFromCol());
    }

    public int countQueenDestinations(int player) {
        return countQueenDestinations(getQueenPositions(player));
    }

    public int countActiveQueens(int player) {
        return countActiveQueens(getQueenPositions(player));
    }

    public int countDestinationsFrom(int row, int col) {
        int destinations = 0;
        for (int[] direction : DIRECTIONS) {
            int nextRow = row + direction[0];
            int nextCol = col + direction[1];
            while (isPlayable(nextRow, nextCol) && board[nextRow][nextCol] == EMPTY) {
                destinations++;
                nextRow += direction[0];
                nextCol += direction[1];
            }
        }
        return destinations;
    }

    public int evaluate(int perspective) {
        int opponent = opponent(perspective);
        List<int[]> myQueens = getQueenPositions(perspective);
        List<int[]> opponentQueens = getQueenPositions(opponent);
        EvalScratch scratch = EVAL_SCRATCH.get();
        int[][] myDistances = scratch.distancesA;
        int[][] opponentDistances = scratch.distancesB;
        queenDistances(myQueens, myDistances, scratch);
        queenDistances(opponentQueens, opponentDistances, scratch);

        int territoryScore = 0;
        int contestedScore = 0;
        int frontierScore = 0;
        int myReachable = 0;
        int opponentReachable = 0;
        boolean separated = true;

        for (int row = MIN_INDEX; row <= MAX_INDEX; row++) {
            for (int col = MIN_INDEX; col <= MAX_INDEX; col++) {
                if (board[row][col] != EMPTY) {
                    continue;
                }

                int myDistance = myDistances[row][col];
                int opponentDistance = opponentDistances[row][col];
                boolean myFinite = myDistance < INF;
                boolean opponentFinite = opponentDistance < INF;

                if (myFinite) {
                    myReachable++;
                }
                if (opponentFinite) {
                    opponentReachable++;
                }

                if (myFinite && !opponentFinite) {
                    territoryScore++;
                } else if (!myFinite && opponentFinite) {
                    territoryScore--;
                } else if (myFinite && opponentFinite) {
                    separated = false;
                    int frontierWeight = frontierWeight(row, col);
                    if (myDistance < opponentDistance) {
                        territoryScore++;
                        if (frontierWeight > 0 && myDistance + 1 >= opponentDistance) {
                            frontierScore += frontierWeight;
                        }
                    } else if (opponentDistance < myDistance) {
                        territoryScore--;
                        if (frontierWeight > 0 && opponentDistance + 1 >= myDistance) {
                            frontierScore -= frontierWeight;
                        }
                    } else {
                        contestedScore += contestedPressure(row, col, myQueens, opponentQueens);
                        if (frontierWeight > 0) {
                            frontierScore += frontierWeight * contestedPressure(row, col, myQueens, opponentQueens);
                        }
                    }
                }
            }
        }

        if (separated) {
            int myMoves = countFillMoves(myDistances);
            int opponentMoves = countFillMoves(opponentDistances);
            return (myMoves - opponentMoves) * 500;
        }

        int mobilityScore = countQueenDestinations(myQueens) - countQueenDestinations(opponentQueens);
        int activeQueenScore = countActiveQueens(myQueens) - countActiveQueens(opponentQueens);
        int reachabilityScore = myReachable - opponentReachable;
        int trapScore = countTrappedQueens(opponentQueens) - countTrappedQueens(myQueens);
        int localMobilityScore = localMobilityScore(myQueens) - localMobilityScore(opponentQueens);
        int nearTrapScore = countNearTrappedQueens(opponentQueens) - countNearTrappedQueens(myQueens);
        int escapeScore = queenEscapeScore(myQueens) - queenEscapeScore(opponentQueens);
        int regionScore = regionControlScore(myDistances, opponentDistances, scratch);

        int phase = Math.min(arrowCount, 40);
        int territoryWeight = 60 + phase * 3;
        int mobilityWeight = 12 - phase / 5;
        int frontierWeight = arrowCount < 12 ? 0 : (arrowCount < 30 ? 4 : 7);
        int escapeWeight = arrowCount < 8 ? 2 : (arrowCount < 28 ? 7 : 10);

        return territoryScore * territoryWeight
            + mobilityScore * mobilityWeight
            + localMobilityScore * 5
            + activeQueenScore * 15
            + contestedScore * 2
            + frontierScore * frontierWeight
            + reachabilityScore
            + escapeScore * escapeWeight
            + trapScore * 120
            + nearTrapScore * 45
            + regionScore * 30;
    }

    public long getZobristHash(int sideToMove) {
        return zobristHash ^ SIDE_TO_MOVE_HASH[sideToMove];
    }

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

    private int contestedPressure(int row, int col, List<int[]> myQueens, List<int[]> opponentQueens) {
        int myPressure = nearbyQueenPressure(row, col, myQueens);
        int opponentPressure = nearbyQueenPressure(row, col, opponentQueens);
        if (myPressure == opponentPressure) {
            return 0;
        }
        return myPressure > opponentPressure ? 1 : -1;
    }

    private int nearbyQueenPressure(int targetRow, int targetCol, List<int[]> queens) {
        int pressure = 0;
        for (int[] queen : queens) {
            int distance = Math.max(Math.abs(queen[0] - targetRow), Math.abs(queen[1] - targetCol));
            if (distance <= 2) {
                pressure += 3 - distance;
            }
        }
        return pressure;
    }

    private void queenDistances(List<int[]> queens, int[][] distances, EvalScratch scratch) {
        for (int row = 0; row < BOARD_DIMENSION; row++) {
            Arrays.fill(distances[row], INF);
        }

        int head = 0;
        int tail = 0;
        for (int[] queen : queens) {
            distances[queen[0]][queen[1]] = 0;
            scratch.queueRows[tail] = queen[0];
            scratch.queueCols[tail] = queen[1];
            tail++;
        }

        while (head < tail) {
            int currentRow = scratch.queueRows[head];
            int currentCol = scratch.queueCols[head];
            head++;
            int nextDistance = distances[currentRow][currentCol] + 1;
            for (int[] direction : DIRECTIONS) {
                int row = currentRow + direction[0];
                int col = currentCol + direction[1];
                while (isPlayable(row, col) && board[row][col] == EMPTY) {
                    if (nextDistance < distances[row][col]) {
                        distances[row][col] = nextDistance;
                        scratch.queueRows[tail] = row;
                        scratch.queueCols[tail] = col;
                        tail++;
                    }
                    row += direction[0];
                    col += direction[1];
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

    private int countQueenDestinations(List<int[]> queens) {
        int destinations = 0;
        for (int[] queen : queens) {
            destinations += countDestinationsFrom(queen[0], queen[1]);
        }
        return destinations;
    }

    private int countActiveQueens(List<int[]> queens) {
        int active = 0;
        for (int[] queen : queens) {
            if (queenHasDestination(queen[0], queen[1])) {
                active++;
            }
        }
        return active;
    }

    private int countTrappedQueens(List<int[]> queens) {
        int trapped = 0;
        for (int[] queen : queens) {
            if (!queenHasDestination(queen[0], queen[1])) {
                trapped++;
            }
        }
        return trapped;
    }

    private int countNearTrappedQueens(List<int[]> queens) {
        int nearTrapped = 0;
        for (int[] queen : queens) {
            if (countDestinationsFrom(queen[0], queen[1]) <= 4) {
                nearTrapped++;
            }
        }
        return nearTrapped;
    }

    private int localMobilityScore(List<int[]> queens) {
        int score = 0;
        for (int[] queen : queens) {
            score += Math.min(12, countDestinationsFrom(queen[0], queen[1]));
        }
        return score;
    }

    private int queenEscapeScore(List<int[]> queens) {
        int score = 0;
        for (int[] queen : queens) {
            int openDirections = 0;
            int longDirections = 0;
            for (int[] direction : DIRECTIONS) {
                int nextRow = queen[0] + direction[0];
                int nextCol = queen[1] + direction[1];
                if (isPlayable(nextRow, nextCol) && board[nextRow][nextCol] == EMPTY) {
                    openDirections++;
                    int secondRow = nextRow + direction[0];
                    int secondCol = nextCol + direction[1];
                    if (isPlayable(secondRow, secondCol) && board[secondRow][secondCol] == EMPTY) {
                        longDirections++;
                    }
                }
            }
            score += openDirections * 3 + longDirections * 2;
            if (openDirections <= 2) {
                score -= (3 - openDirections) * 6;
            }
        }
        return score;
    }

    private int regionControlScore(int[][] myDistances, int[][] opponentDistances, EvalScratch scratch) {
        int mark = scratch.nextRegionMark();
        int score = 0;

        for (int row = MIN_INDEX; row <= MAX_INDEX; row++) {
            for (int col = MIN_INDEX; col <= MAX_INDEX; col++) {
                if (board[row][col] != EMPTY || scratch.regionMarks[row][col] == mark) {
                    continue;
                }

                int head = 0;
                int tail = 0;
                int regionSize = 0;
                boolean myReachable = false;
                boolean opponentReachable = false;

                scratch.regionMarks[row][col] = mark;
                scratch.queueRows[tail] = row;
                scratch.queueCols[tail] = col;
                tail++;

                while (head < tail) {
                    int currentRow = scratch.queueRows[head];
                    int currentCol = scratch.queueCols[head];
                    head++;
                    regionSize++;

                    if (myDistances[currentRow][currentCol] < INF) {
                        myReachable = true;
                    }
                    if (opponentDistances[currentRow][currentCol] < INF) {
                        opponentReachable = true;
                    }

                    for (int[] direction : DIRECTIONS) {
                        int nextRow = currentRow + direction[0];
                        int nextCol = currentCol + direction[1];
                        if (isPlayable(nextRow, nextCol)
                            && board[nextRow][nextCol] == EMPTY
                            && scratch.regionMarks[nextRow][nextCol] != mark) {
                            scratch.regionMarks[nextRow][nextCol] = mark;
                            scratch.queueRows[tail] = nextRow;
                            scratch.queueCols[tail] = nextCol;
                            tail++;
                        }
                    }
                }

                if (myReachable != opponentReachable) {
                    int regionValue = Math.max(1, regionSize / 3);
                    score += myReachable ? regionValue : -regionValue;
                }
            }
        }
        return score;
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

    private int frontierWeight(int row, int col) {
        int blockedNeighbors = 0;
        for (int[] direction : DIRECTIONS) {
            int nextRow = row + direction[0];
            int nextCol = col + direction[1];
            if (!isPlayable(nextRow, nextCol) || board[nextRow][nextCol] == ARROW) {
                blockedNeighbors++;
            }
        }
        return blockedNeighbors >= 2 ? blockedNeighbors - 1 : 0;
    }

    private List<int[]> getQueenPositions(int player) {
        if (player == BLACK) {
            return blackQueens;
        }
        if (player == WHITE) {
            return whiteQueens;
        }
        throw new IllegalArgumentException("Unknown player: " + player);
    }

    private void rebuildMetadata() {
        blackQueens.clear();
        whiteQueens.clear();
        zobristHash = 0L;
        arrowCount = 0;

        for (int row = MIN_INDEX; row <= MAX_INDEX; row++) {
            for (int col = MIN_INDEX; col <= MAX_INDEX; col++) {
                int piece = board[row][col];
                xorPiece(row, col, piece);
                addTrackedPiece(piece, row, col);
            }
        }
    }

    private void addTrackedPiece(int piece, int row, int col) {
        if (piece == BLACK) {
            blackQueens.add(new int[] {row, col});
        } else if (piece == WHITE) {
            whiteQueens.add(new int[] {row, col});
        } else if (piece == ARROW) {
            arrowCount++;
        }
    }

    private void removeTrackedPiece(int piece, int row, int col) {
        if (piece == BLACK) {
            removeTrackedQueen(blackQueens, row, col);
        } else if (piece == WHITE) {
            removeTrackedQueen(whiteQueens, row, col);
        } else if (piece == ARROW) {
            arrowCount--;
        }
    }

    private void moveTrackedQueen(int player, int fromRow, int fromCol, int toRow, int toCol) {
        for (int[] queen : getQueenPositions(player)) {
            if (queen[0] == fromRow && queen[1] == fromCol) {
                queen[0] = toRow;
                queen[1] = toCol;
                return;
            }
        }
        throw new IllegalArgumentException("Tracked queen not found at (" + fromRow + "," + fromCol + ")");
    }

    private static void removeTrackedQueen(List<int[]> queens, int row, int col) {
        for (int i = 0; i < queens.size(); i++) {
            int[] queen = queens.get(i);
            if (queen[0] == row && queen[1] == col) {
                queens.remove(i);
                return;
            }
        }
    }

    private void xorPiece(int row, int col, int piece) {
        if (piece != EMPTY) {
            zobristHash ^= ZOBRIST[row][col][piece];
        }
    }

    private static ArrayList<int[]> copyQueens(List<int[]> queens) {
        ArrayList<int[]> copy = new ArrayList<int[]>(queens.size());
        for (int[] queen : queens) {
            copy.add(new int[] {queen[0], queen[1]});
        }
        return copy;
    }

    private static long[][][] initZobrist() {
        Random random = new Random(3222026L);
        long[][][] table = new long[BOARD_DIMENSION][BOARD_DIMENSION][ARROW + 1];
        for (int row = 0; row < BOARD_DIMENSION; row++) {
            for (int col = 0; col < BOARD_DIMENSION; col++) {
                for (int piece = BLACK; piece <= ARROW; piece++) {
                    table[row][col][piece] = random.nextLong();
                }
            }
        }
        return table;
    }

    private static long[] initSideHashes() {
        Random random = new Random(3222027L);
        long[] hashes = new long[WHITE + 1];
        hashes[BLACK] = random.nextLong();
        hashes[WHITE] = random.nextLong();
        return hashes;
    }

    private static final class EvalScratch {
        final int[][] distancesA = new int[BOARD_DIMENSION][BOARD_DIMENSION];
        final int[][] distancesB = new int[BOARD_DIMENSION][BOARD_DIMENSION];
        final int[] queueRows = new int[BOARD_DIMENSION * BOARD_DIMENSION];
        final int[] queueCols = new int[BOARD_DIMENSION * BOARD_DIMENSION];
        final int[][] regionMarks = new int[BOARD_DIMENSION][BOARD_DIMENSION];
        int regionMark;

        int nextRegionMark() {
            regionMark++;
            if (regionMark == Integer.MAX_VALUE) {
                for (int row = 0; row < BOARD_DIMENSION; row++) {
                    Arrays.fill(regionMarks[row], 0);
                }
                regionMark = 1;
            }
            return regionMark;
        }
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
