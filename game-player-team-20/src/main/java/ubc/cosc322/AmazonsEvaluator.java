package ubc.cosc322;

import java.util.Arrays;
import java.util.List;

public class AmazonsEvaluator {

    private static final int INF = 1_000_000;
    private static final ThreadLocal<EvalScratch> EVAL_SCRATCH = ThreadLocal.withInitial(EvalScratch::new);

    public static int evaluate(AmazonsBoardState board, int perspective) {
        int opponent = AmazonsBoardState.opponent(perspective);
        List<int[]> myQueens = board.getQueenPositions(perspective);
        List<int[]> opponentQueens = board.getQueenPositions(opponent);
        EvalScratch scratch = EVAL_SCRATCH.get();
        int[][] myDistances = scratch.distancesA;
        int[][] opponentDistances = scratch.distancesB;
        queenDistances(board, myQueens, myDistances, scratch);
        queenDistances(board, opponentQueens, opponentDistances, scratch);

        int territoryScore = 0;
        int contestedScore = 0;
        int frontierScore = 0;
        int myReachable = 0;
        int opponentReachable = 0;
        boolean separated = true;

        for (int row = AmazonsBoardState.MIN_INDEX; row <= AmazonsBoardState.MAX_INDEX; row++) {
            for (int col = AmazonsBoardState.MIN_INDEX; col <= AmazonsBoardState.MAX_INDEX; col++) {
                if (board.get(row, col) != AmazonsBoardState.EMPTY) {
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
                    int frontierWeight = frontierWeight(board, row, col);
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
            int myMoves = countFillMoves(board, myDistances);
            int opponentMoves = countFillMoves(board, opponentDistances);
            return (myMoves - opponentMoves) * 500;
        }

        int arrowCount = board.countArrows();
        int mobilityScore = board.countQueenDestinations(perspective) - board.countQueenDestinations(opponent);
        int activeQueenScore = board.countActiveQueens(perspective) - board.countActiveQueens(opponent);
        int reachabilityScore = myReachable - opponentReachable;
        int trapScore = countTrappedQueens(board, opponentQueens) - countTrappedQueens(board, myQueens);
        int localMobilityScore = localMobilityScore(board, myQueens) - localMobilityScore(board, opponentQueens);
        int nearTrapScore = countNearTrappedQueens(board, opponentQueens) - countNearTrappedQueens(board, myQueens);
        int escapeScore = queenEscapeScore(board, myQueens) - queenEscapeScore(board, opponentQueens);
        int regionScore = regionControlScore(board, myDistances, opponentDistances, scratch);

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

    private static int countFillMoves(AmazonsBoardState board, int[][] distances) {
        int moves = 0;
        for (int row = AmazonsBoardState.MIN_INDEX; row <= AmazonsBoardState.MAX_INDEX; row++) {
            for (int col = AmazonsBoardState.MIN_INDEX; col <= AmazonsBoardState.MAX_INDEX; col++) {
                if (board.get(row, col) == AmazonsBoardState.EMPTY && distances[row][col] < INF) {
                    moves++;
                }
            }
        }
        return moves;
    }

    private static int contestedPressure(int row, int col, List<int[]> myQueens, List<int[]> opponentQueens) {
        int myPressure = nearbyQueenPressure(row, col, myQueens);
        int opponentPressure = nearbyQueenPressure(row, col, opponentQueens);
        if (myPressure == opponentPressure) {
            return 0;
        }
        return myPressure > opponentPressure ? 1 : -1;
    }

    private static int nearbyQueenPressure(int targetRow, int targetCol, List<int[]> queens) {
        int pressure = 0;
        for (int[] queen : queens) {
            int distance = Math.max(Math.abs(queen[0] - targetRow), Math.abs(queen[1] - targetCol));
            if (distance <= 2) {
                pressure += 3 - distance;
            }
        }
        return pressure;
    }

    private static void queenDistances(AmazonsBoardState board, List<int[]> queens, int[][] distances, EvalScratch scratch) {
        for (int row = 0; row < AmazonsBoardState.BOARD_DIMENSION; row++) {
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
            for (int[] direction : AmazonsBoardState.DIRECTIONS) {
                int row = currentRow + direction[0];
                int col = currentCol + direction[1];
                while (AmazonsBoardState.isPlayable(row, col) && board.get(row, col) == AmazonsBoardState.EMPTY) {
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

    private static int countTrappedQueens(AmazonsBoardState board, List<int[]> queens) {
        int trapped = 0;
        for (int[] queen : queens) {
            if (board.countDestinationsFrom(queen[0], queen[1]) == 0) {
                trapped++;
            }
        }
        return trapped;
    }

    private static int countNearTrappedQueens(AmazonsBoardState board, List<int[]> queens) {
        int nearTrapped = 0;
        for (int[] queen : queens) {
            if (board.countDestinationsFrom(queen[0], queen[1]) <= 4) {
                nearTrapped++;
            }
        }
        return nearTrapped;
    }

    private static int localMobilityScore(AmazonsBoardState board, List<int[]> queens) {
        int score = 0;
        for (int[] queen : queens) {
            score += Math.min(12, board.countDestinationsFrom(queen[0], queen[1]));
        }
        return score;
    }

    private static int queenEscapeScore(AmazonsBoardState board, List<int[]> queens) {
        int score = 0;
        for (int[] queen : queens) {
            int openDirections = 0;
            int longDirections = 0;
            for (int[] direction : AmazonsBoardState.DIRECTIONS) {
                int nextRow = queen[0] + direction[0];
                int nextCol = queen[1] + direction[1];
                if (AmazonsBoardState.isPlayable(nextRow, nextCol) && board.get(nextRow, nextCol) == AmazonsBoardState.EMPTY) {
                    openDirections++;
                    int secondRow = nextRow + direction[0];
                    int secondCol = nextCol + direction[1];
                    if (AmazonsBoardState.isPlayable(secondRow, secondCol) && board.get(secondRow, secondCol) == AmazonsBoardState.EMPTY) {
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

    private static int frontierWeight(AmazonsBoardState board, int row, int col) {
        int blockedNeighbors = 0;
        for (int[] direction : AmazonsBoardState.DIRECTIONS) {
            int nextRow = row + direction[0];
            int nextCol = col + direction[1];
            if (!AmazonsBoardState.isPlayable(nextRow, nextCol) || board.get(nextRow, nextCol) == AmazonsBoardState.ARROW) {
                blockedNeighbors++;
            }
        }
        return blockedNeighbors >= 2 ? blockedNeighbors - 1 : 0;
    }

    private static int regionControlScore(AmazonsBoardState board, int[][] myDistances, int[][] opponentDistances, EvalScratch scratch) {
        int mark = scratch.nextRegionMark();
        int score = 0;

        for (int row = AmazonsBoardState.MIN_INDEX; row <= AmazonsBoardState.MAX_INDEX; row++) {
            for (int col = AmazonsBoardState.MIN_INDEX; col <= AmazonsBoardState.MAX_INDEX; col++) {
                if (board.get(row, col) != AmazonsBoardState.EMPTY || scratch.regionMarks[row][col] == mark) {
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

                    for (int[] direction : AmazonsBoardState.DIRECTIONS) {
                        int nextRow = currentRow + direction[0];
                        int nextCol = currentCol + direction[1];
                        if (AmazonsBoardState.isPlayable(nextRow, nextCol)
                            && board.get(nextRow, nextCol) == AmazonsBoardState.EMPTY
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

    private static final class EvalScratch {
        final int[][] distancesA = new int[AmazonsBoardState.BOARD_DIMENSION][AmazonsBoardState.BOARD_DIMENSION];
        final int[][] distancesB = new int[AmazonsBoardState.BOARD_DIMENSION][AmazonsBoardState.BOARD_DIMENSION];
        final int[] queueRows = new int[AmazonsBoardState.BOARD_DIMENSION * AmazonsBoardState.BOARD_DIMENSION];
        final int[] queueCols = new int[AmazonsBoardState.BOARD_DIMENSION * AmazonsBoardState.BOARD_DIMENSION];
        final int[][] regionMarks = new int[AmazonsBoardState.BOARD_DIMENSION][AmazonsBoardState.BOARD_DIMENSION];
        int regionMark;

        int nextRegionMark() {
            regionMark++;
            if (regionMark == Integer.MAX_VALUE) {
                for (int row = 0; row < AmazonsBoardState.BOARD_DIMENSION; row++) {
                    Arrays.fill(regionMarks[row], 0);
                }
                regionMark = 1;
            }
            return regionMark;
        }
    }
}
