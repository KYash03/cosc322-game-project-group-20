package ubc.cosc322;

import java.util.ArrayDeque;
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

    public AmazonsBoardState() {
        this.board = new int[BOARD_DIMENSION][BOARD_DIMENSION];
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
        return state;
    }

    public AmazonsBoardState copy() {
        AmazonsBoardState copy = new AmazonsBoardState();
        for (int row = 0; row < BOARD_DIMENSION; row++) {
            System.arraycopy(board[row], 0, copy.board[row], 0, BOARD_DIMENSION);
        }
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
        return countArrows() % 2 == 0 ? BLACK : WHITE;
    }

    public int countArrows() {
        int arrows = 0;
        for (int row = MIN_INDEX; row <= MAX_INDEX; row++) {
            for (int col = MIN_INDEX; col <= MAX_INDEX; col++) {
                if (board[row][col] == ARROW) {
                    arrows++;
                }
            }
        }
        return arrows;
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
        board[move.getFromRow()][move.getFromCol()] = EMPTY;
        board[move.getToRow()][move.getToCol()] = player;
        board[move.getArrowRow()][move.getArrowCol()] = ARROW;
    }

    public void undoMove(AmazonsMove move, int player) {
        board[move.getArrowRow()][move.getArrowCol()] = EMPTY;
        board[move.getToRow()][move.getToCol()] = EMPTY;
        board[move.getFromRow()][move.getFromCol()] = player;
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
        int[][] myDistances = queenDistances(myQueens);
        int[][] opponentDistances = queenDistances(opponentQueens);

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
                    if (myDistance < opponentDistance) {
                        territoryScore++;
                    } else if (opponentDistance < myDistance) {
                        territoryScore--;
                    } else {
                        contestedScore += contestedPressure(row, col, myQueens, opponentQueens);
                    }
                }
            }
        }

        if (separated) {
            int myMoves = countFillMoves(myQueens, myDistances);
            int opponentMoves = countFillMoves(opponentQueens, opponentDistances);
            return (myMoves - opponentMoves) * 500;
        }

        int mobilityScore = countQueenDestinations(myQueens) - countQueenDestinations(opponentQueens);
        int activeQueenScore = countActiveQueens(myQueens) - countActiveQueens(opponentQueens);
        int reachabilityScore = myReachable - opponentReachable;
        int trapScore = countTrappedQueens(opponentQueens) - countTrappedQueens(myQueens);

        int arrows = countArrows();
        int phase = Math.min(arrows, 40);
        int territoryWeight = 60 + phase * 3;
        int mobilityWeight = 12 - phase / 5;

        return territoryScore * territoryWeight
            + mobilityScore * mobilityWeight
            + activeQueenScore * 15
            + contestedScore * 2
            + reachabilityScore
            + trapScore * 120;
    }

    private int countFillMoves(List<int[]> queens, int[][] distances) {
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

    private int[][] queenDistances(List<int[]> queens) {
        int[][] distances = new int[BOARD_DIMENSION][BOARD_DIMENSION];
        for (int row = 0; row < BOARD_DIMENSION; row++) {
            Arrays.fill(distances[row], INF);
        }

        ArrayDeque<int[]> frontier = new ArrayDeque<int[]>();
        for (int[] queen : queens) {
            distances[queen[0]][queen[1]] = 0;
            frontier.addLast(new int[] {queen[0], queen[1]});
        }

        while (!frontier.isEmpty()) {
            int[] current = frontier.removeFirst();
            int nextDistance = distances[current[0]][current[1]] + 1;
            for (int[] direction : DIRECTIONS) {
                int row = current[0] + direction[0];
                int col = current[1] + direction[1];
                while (isPlayable(row, col) && board[row][col] == EMPTY) {
                    if (nextDistance < distances[row][col]) {
                        distances[row][col] = nextDistance;
                        frontier.addLast(new int[] {row, col});
                    }
                    row += direction[0];
                    col += direction[1];
                }
            }
        }

        return distances;
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

    private List<int[]> getQueenPositions(int player) {
        ArrayList<int[]> queens = new ArrayList<int[]>(4);
        for (int row = MIN_INDEX; row <= MAX_INDEX; row++) {
            for (int col = MIN_INDEX; col <= MAX_INDEX; col++) {
                if (board[row][col] == player) {
                    queens.add(new int[] {row, col});
                }
            }
        }
        return queens;
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
