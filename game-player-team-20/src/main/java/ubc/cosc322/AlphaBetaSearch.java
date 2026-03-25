package ubc.cosc322;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AlphaBetaSearch {
    private static final int WIN_SCORE = 1_000_000;
    private static final int NEG_INF = -WIN_SCORE * 2;
    private static final int POS_INF = WIN_SCORE * 2;
    private static final int DEFAULT_MAX_DEPTH = 64;
    private static final long DEFAULT_SOFT_LIMIT_MILLIS = 27_000L;
    private static final int DEPTH_ONE_ROOT_MOVE_LIMIT = 256;
    private static final int ROOT_MOVE_LIMIT = 160;
    private static final int SHALLOW_CHILD_MOVE_LIMIT = 40;
    private static final int CHILD_MOVE_LIMIT = 32;
    private static final int SHALLOW_CHILD_PREFILTER_LIMIT = 128;
    private static final int CHILD_PREFILTER_LIMIT = 96;
    private static final int TRANSPOSITION_TABLE_LIMIT = 200_000;

    private final long softLimitMillis;
    private final int maxDepth;
    private final Map<Long, TranspositionEntry> transpositionTable;

    public AlphaBetaSearch() {
        this(DEFAULT_SOFT_LIMIT_MILLIS, DEFAULT_MAX_DEPTH);
    }

    public AlphaBetaSearch(long softLimitMillis, int maxDepth) {
        this.softLimitMillis = softLimitMillis;
        this.maxDepth = maxDepth;
        this.transpositionTable = createTranspositionTable();
    }

    public static class SearchResult {
        private final AmazonsMove move;
        private final int score;
        private final int depth;
        private final long nodes;
        private final long elapsedMillis;

        SearchResult(AmazonsMove move, int score, int depth, long nodes, long elapsedMillis) {
            this.move = move;
            this.score = score;
            this.depth = depth;
            this.nodes = nodes;
            this.elapsedMillis = elapsedMillis;
        }

        public AmazonsMove getMove() {
            return move;
        }

        public int getScore() {
            return score;
        }

        public int getDepth() {
            return depth;
        }

        public long getNodes() {
            return nodes;
        }

        public long getElapsedMillis() {
            return elapsedMillis;
        }
    }

    private enum Bound {
        EXACT,
        LOWER,
        UPPER
    }

    private static class TranspositionEntry {
        final int score;
        final int depth;
        final Bound bound;
        final AmazonsMove bestMove;

        TranspositionEntry(int score, int depth, Bound bound, AmazonsMove bestMove) {
            this.score = score;
            this.depth = depth;
            this.bound = bound;
            this.bestMove = bestMove;
        }
    }

    private static class ScoredMove {
        final AmazonsMove move;
        final int score;

        ScoredMove(AmazonsMove move, int score) {
            this.move = move;
            this.score = score;
        }

        int getScore() {
            return score;
        }
    }

    public SearchResult chooseMove(AmazonsBoardState board, int sideToMove) {
        long startMillis = System.currentTimeMillis();
        long deadlineMillis = startMillis + softLimitMillis;
        List<AmazonsMove> legalMoves = board.generateMoves(sideToMove);
        if (legalMoves.isEmpty()) {
            return new SearchResult(null, -WIN_SCORE, 0, 0L, System.currentTimeMillis() - startMillis);
        }

        AmazonsMove bestMove = legalMoves.get(0);
        int bestScore = NEG_INF;
        int bestDepth = 0;
        long bestNodes = 0L;
        AmazonsMove preferredMove = null;
        long rootHash = board.getZobristHash(sideToMove);

        for (int depth = 1; depth <= maxDepth; depth++) {
            if (depth > 1 && System.currentTimeMillis() >= deadlineMillis) {
                break;
            }

            long workerDeadline = depth == 1 ? Long.MAX_VALUE : deadlineMillis;
            List<AmazonsMove> orderedMoves = orderRootMoves(
                board, sideToMove, preferredMove, tableMove(rootHash), depth
            );
            boolean timedOut = false;
            int alpha = NEG_INF;

            AmazonsMove depthBestMove = null;
            int depthBestScore = NEG_INF;
            long depthNodes = 0L;

            for (AmazonsMove move : orderedMoves) {
                board.applyMove(move, sideToMove);
                SearchWorker worker = new SearchWorker(board, workerDeadline);
                int score = -worker.negamax(
                    AmazonsBoardState.opponent(sideToMove), depth - 1,
                    -POS_INF, -alpha, 1,
                    board.getZobristHash(AmazonsBoardState.opponent(sideToMove))
                );
                board.undoMove(move, sideToMove);
                depthNodes += worker.nodes;

                if (worker.timedOut) {
                    timedOut = true;
                    break;
                }

                if (score > depthBestScore) {
                    depthBestScore = score;
                    depthBestMove = move;
                }
                if (score > alpha) {
                    alpha = score;
                }
            }

            if (!timedOut && depthBestMove != null) {
                bestMove = depthBestMove;
                bestScore = depthBestScore;
                bestDepth = depth;
                bestNodes = depthNodes;
                preferredMove = bestMove;
                storeEntry(rootHash, depthBestScore, depth, Bound.EXACT, depthBestMove);
            }
        }

        return new SearchResult(bestMove, bestScore, bestDepth, bestNodes, System.currentTimeMillis() - startMillis);
    }

    public void clearTranspositionTable() {
        transpositionTable.clear();
    }

    private List<AmazonsMove> orderRootMoves(AmazonsBoardState board, int player,
                                             AmazonsMove prioritizedMove, AmazonsMove ttMove,
                                             int depthRemaining) {
        List<AmazonsMove> moves = board.generateMoves(player);
        if (moves.size() <= 1) {
            return moves;
        }

        List<ScoredMove> scoredMoves = new ArrayList<ScoredMove>(moves.size());
        for (AmazonsMove move : moves) {
            board.applyMove(move, player);
            int score = quickMoveScore(board, player, move, prioritizedMove, ttMove);
            board.undoMove(move, player);
            scoredMoves.add(new ScoredMove(move, score));
        }

        Collections.sort(scoredMoves, Comparator.comparingInt(ScoredMove::getScore).reversed());

        int rootLimit = moves.size() > 1000 ? ROOT_MOVE_LIMIT - 32 : ROOT_MOVE_LIMIT;
        int limit = depthRemaining <= 1
            ? Math.min(scoredMoves.size(), DEPTH_ONE_ROOT_MOVE_LIMIT)
            : Math.min(scoredMoves.size(), rootLimit);

        List<AmazonsMove> ordered = new ArrayList<AmazonsMove>(limit);
        for (int i = 0; i < limit; i++) {
            ordered.add(scoredMoves.get(i).move);
        }
        return ordered;
    }

    private static int quickMoveScore(AmazonsBoardState board, int player,
                                      AmazonsMove move, AmazonsMove prioritizedMove,
                                      AmazonsMove ttMove) {
        int opponent = AmazonsBoardState.opponent(player);
        int score = 6 * centerBias(move) + 2 * centerBias(move.getArrowRow(), move.getArrowCol());
        if (move.equals(ttMove)) {
            score += 4_000_000;
        } else if (move.equals(prioritizedMove)) {
            score += 2_000_000;
        }
        if (!board.hasAnyMoves(opponent)) {
            score += 1_000_000;
        }
        score += 6 * board.countDestinationsFrom(move.getToRow(), move.getToCol());
        score += 20 * (board.countActiveQueens(player) - board.countActiveQueens(opponent));
        return score;
    }

    private static int centerBias(AmazonsMove move) {
        return centerBias(move.getToRow(), move.getToCol());
    }

    private static int centerBias(int row, int col) {
        int rowDistance = Math.abs(5 - row);
        int colDistance = Math.abs(5 - col);
        return 20 - (rowDistance + colDistance);
    }

    private AmazonsMove tableMove(long stateHash) {
        TranspositionEntry entry = transpositionTable.get(stateHash);
        return entry == null ? null : entry.bestMove;
    }

    private void storeEntry(long stateHash, int score, int depth, Bound bound, AmazonsMove bestMove) {
        if (bestMove != null) {
            transpositionTable.put(stateHash, new TranspositionEntry(score, depth, bound, bestMove));
        }
    }

    private static Map<Long, TranspositionEntry> createTranspositionTable() {
        return new LinkedHashMap<Long, TranspositionEntry>(TRANSPOSITION_TABLE_LIMIT, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, TranspositionEntry> eldest) {
                return size() > TRANSPOSITION_TABLE_LIMIT;
            }
        };
    }

    private class SearchWorker {
        private final AmazonsBoardState board;
        private final long deadlineMillis;
        boolean timedOut;
        long nodes;

        SearchWorker(AmazonsBoardState board, long deadlineMillis) {
            this.board = board;
            this.deadlineMillis = deadlineMillis;
        }

        int negamax(int player, int depth, int alpha, int beta, int ply, long stateHash) {
            nodes++;
            if (isExpired()) {
                return 0;
            }

            if (!board.hasAnyMoves(player)) {
                return -WIN_SCORE + ply;
            }

            int originalAlpha = alpha;
            int originalBeta = beta;
            TranspositionEntry entry = transpositionTable.get(stateHash);
            if (entry != null && entry.depth >= depth) {
                if (entry.bound == Bound.EXACT) {
                    return entry.score;
                }
                if (entry.bound == Bound.LOWER && entry.score > alpha) {
                    alpha = entry.score;
                } else if (entry.bound == Bound.UPPER && entry.score < beta) {
                    beta = entry.score;
                }
                if (alpha >= beta) {
                    return entry.score;
                }
            }

            if (depth == 0) {
                return board.evaluate(player);
            }

            List<AmazonsMove> orderedMoves = orderMoves(player, depth, entry == null ? null : entry.bestMove);
            int bestScore = NEG_INF;
            AmazonsMove bestMove = null;

            for (AmazonsMove move : orderedMoves) {
                board.applyMove(move, player);
                int score = -negamax(
                    AmazonsBoardState.opponent(player), depth - 1,
                    -beta, -alpha, ply + 1,
                    board.getZobristHash(AmazonsBoardState.opponent(player))
                );
                board.undoMove(move, player);

                if (timedOut) {
                    return 0;
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestMove = move;
                }
                if (score > alpha) {
                    alpha = score;
                }
                if (alpha >= beta) {
                    break;
                }
            }

            Bound bound;
            if (bestScore <= originalAlpha) {
                bound = Bound.UPPER;
            } else if (bestScore >= originalBeta) {
                bound = Bound.LOWER;
            } else {
                bound = Bound.EXACT;
            }
            storeEntry(stateHash, bestScore, depth, bound, bestMove);
            return bestScore;
        }

        private List<AmazonsMove> orderMoves(int player, int depthRemaining, AmazonsMove ttMove) {
            List<AmazonsMove> moves = board.generateMoves(player);
            if (moves.size() <= 1) {
                return moves;
            }

            List<ScoredMove> prefilter = new ArrayList<ScoredMove>(moves.size());
            for (AmazonsMove move : moves) {
                int score = 6 * centerBias(move) + 2 * centerBias(move.getArrowRow(), move.getArrowCol());
                if (move.equals(ttMove)) {
                    score += 4_000_000;
                }
                prefilter.add(new ScoredMove(move, score));
            }
            Collections.sort(prefilter, Comparator.comparingInt(ScoredMove::getScore).reversed());

            int candidateLimit = depthRemaining <= 2 ? SHALLOW_CHILD_PREFILTER_LIMIT : CHILD_PREFILTER_LIMIT;
            int candidateCount = Math.min(prefilter.size(), candidateLimit);

            List<ScoredMove> scored = new ArrayList<ScoredMove>(candidateCount);
            int opponent = AmazonsBoardState.opponent(player);
            for (int i = 0; i < candidateCount; i++) {
                AmazonsMove move = prefilter.get(i).move;
                board.applyMove(move, player);
                int score = 6 * centerBias(move) + 2 * centerBias(move.getArrowRow(), move.getArrowCol());
                if (move.equals(ttMove)) {
                    score += 4_000_000;
                }
                if (!board.hasAnyMoves(opponent)) {
                    score += 1_000_000;
                }
                score += 6 * board.countDestinationsFrom(move.getToRow(), move.getToCol());
                score += 20 * (board.countActiveQueens(player) - board.countActiveQueens(opponent));
                board.undoMove(move, player);
                scored.add(new ScoredMove(move, score));
            }

            Collections.sort(scored, Comparator.comparingInt(ScoredMove::getScore).reversed());
            int moveLimit = Math.min(scored.size(), depthRemaining <= 2 ? SHALLOW_CHILD_MOVE_LIMIT : CHILD_MOVE_LIMIT);
            List<AmazonsMove> ordered = new ArrayList<AmazonsMove>(moveLimit);
            for (int i = 0; i < moveLimit; i++) {
                ordered.add(scored.get(i).move);
            }
            return ordered;
        }

        private boolean isExpired() {
            if (timedOut) {
                return true;
            }
            if (nodes % 100 == 0 && System.currentTimeMillis() >= deadlineMillis) {
                timedOut = true;
                return true;
            }
            return false;
        }
    }
}
