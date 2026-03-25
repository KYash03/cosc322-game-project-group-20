package ubc.cosc322;

import java.util.*;

public class AlphaBetaSearch {
    private static final int WIN_SCORE = 1_000_000;
    private static final int NEG_INF = -WIN_SCORE * 2;
    private static final int POS_INF = WIN_SCORE * 2;

    private static final int DEFAULT_MAX_DEPTH = 5;
    private static final long DEFAULT_SOFT_LIMIT_MILLIS = 1_000L;

    private static final int DEPTH_ONE_ROOT_MOVE_LIMIT = 192;
    private static final int ROOT_MOVE_LIMIT = 120;   // ↑ slightly increased
    private static final int CHILD_MOVE_LIMIT = 36;   // ↑ more tactical coverage
    private static final int CHILD_PREFILTER_LIMIT = 80;

    private final long softLimitMillis;
    private final int maxDepth;

    // 🚀 Transposition table
    private final Map<Long, TTEntry> tt = new HashMap<>(200_000);

    private static class TTEntry {
        int depth;
        int score;

        TTEntry(int depth, int score) {
            this.depth = depth;
            this.score = score;
        }
    }

    public AlphaBetaSearch() {
        this(DEFAULT_SOFT_LIMIT_MILLIS, DEFAULT_MAX_DEPTH);
    }

    public AlphaBetaSearch(long softLimitMillis, int maxDepth) {
        this.softLimitMillis = softLimitMillis;
        this.maxDepth = maxDepth;
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

        public AmazonsMove getMove() { return move; }
        public int getScore() { return score; }
        public int getDepth() { return depth; }
        public long getNodes() { return nodes; }
        public long getElapsedMillis() { return elapsedMillis; }
    }

    public SearchResult chooseMove(AmazonsBoardState board, int sideToMove) {
        tt.clear(); // 🔥 reset cache each move

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

        for (int depth = 1; depth <= maxDepth; depth++) {
            if (depth > 1 && System.currentTimeMillis() >= deadlineMillis) break;

            long workerDeadline = depth == 1 ? Long.MAX_VALUE : deadlineMillis;
            List<AmazonsMove> orderedMoves = orderRootMoves(board, sideToMove, preferredMove, depth);

            boolean timedOut = false;
            AmazonsMove depthBestMove = null;
            int depthBestScore = NEG_INF;
            long depthNodes = 0;

            for (AmazonsMove move : orderedMoves) {
                board.applyMove(move, sideToMove);

                SearchWorker worker = new SearchWorker(board, workerDeadline);
                int score = -worker.negamax(
                        AmazonsBoardState.opponent(sideToMove),
                        depth - 1,
                        NEG_INF, POS_INF, 1
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
            }

            if (!timedOut && depthBestMove != null) {
                bestMove = depthBestMove;
                bestScore = depthBestScore;
                bestDepth = depth;
                bestNodes = depthNodes;
                preferredMove = bestMove;
            }
        }

        return new SearchResult(bestMove, bestScore, bestDepth, bestNodes,
                System.currentTimeMillis() - startMillis);
    }

    private List<AmazonsMove> orderRootMoves(AmazonsBoardState board, int player,
                                            AmazonsMove prioritizedMove, int depthRemaining) {

        List<AmazonsMove> moves = board.generateMoves(player);
        if (moves.size() <= 1) return moves;

        List<ScoredMove> scoredMoves = new ArrayList<>();

        for (AmazonsMove move : moves) {
            board.applyMove(move, player);
            int score = quickMoveScore(board, player, move, prioritizedMove);
            board.undoMove(move, player);
            scoredMoves.add(new ScoredMove(move, score));
        }

        scoredMoves.sort(Comparator.comparingInt(ScoredMove::getScore).reversed());

        int limit = depthRemaining <= 1
                ? Math.min(scoredMoves.size(), DEPTH_ONE_ROOT_MOVE_LIMIT)
                : Math.min(scoredMoves.size(), ROOT_MOVE_LIMIT);

        List<AmazonsMove> ordered = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            ordered.add(scoredMoves.get(i).move);
        }
        return ordered;
    }

    private static int quickMoveScore(AmazonsBoardState board, int player,
                                     AmazonsMove move, AmazonsMove prioritizedMove) {

        int opponent = AmazonsBoardState.opponent(player);

        int score = 6 * centerBias(move)
                + 2 * centerBias(move.getArrowRow(), move.getArrowCol());

        if (move.equals(prioritizedMove)) score += 2_000_000;
        if (!board.hasAnyMoves(opponent)) score += 1_000_000;

        score += 6 * board.countDestinationsFrom(move.getToRow(), move.getToCol());
        score += 20 * (board.countActiveQueens(player) - board.countActiveQueens(opponent));

        return score;
    }

    private static int centerBias(int row, int col) {
        return 20 - (Math.abs(5 - row) + Math.abs(5 - col));
    }

    private static int centerBias(AmazonsMove move) {
        return centerBias(move.getToRow(), move.getToCol());
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

        int negamax(int player, int depth, int alpha, int beta, int ply) {
            nodes++;

            if (isExpired()) return 0;

            long key = board.computeHash() ^ player;
            TTEntry entry = tt.get(key);
            if (entry != null && entry.depth >= depth) {
                return entry.score;
            }

            if (!board.hasAnyMoves(player)) {
                return -WIN_SCORE + ply;
            }

            if (depth == 0) {
                int eval = board.countArrows() < 12
                        ? board.fastEvaluate(player)   // 🚀 faster early game
                        : board.evaluate(player);

                tt.put(key, new TTEntry(depth, eval));
                return eval;
            }

            List<AmazonsMove> moves = orderMoves(player);
            int bestScore = NEG_INF;

            for (AmazonsMove move : moves) {
                board.applyMove(move, player);

                int score = -negamax(
                        AmazonsBoardState.opponent(player),
                        depth - 1,
                        -beta, -alpha,
                        ply + 1
                );

                board.undoMove(move, player);

                if (score > bestScore) bestScore = score;
                if (score > alpha) alpha = score;
                if (alpha >= beta) break;
            }

            tt.put(key, new TTEntry(depth, bestScore));
            return bestScore;
        }

        private List<AmazonsMove> orderMoves(int player) {
            List<AmazonsMove> moves = board.generateMoves(player);
            if (moves.size() <= 1) return moves;

            List<ScoredMove> prefilter = new ArrayList<>();
            for (AmazonsMove move : moves) {
                int score = 6 * centerBias(move)
                        + 2 * centerBias(move.getArrowRow(), move.getArrowCol());
                prefilter.add(new ScoredMove(move, score));
            }

            prefilter.sort(Comparator.comparingInt(ScoredMove::getScore).reversed());

            int candidateCount = Math.min(prefilter.size(), CHILD_PREFILTER_LIMIT);
            List<ScoredMove> scored = new ArrayList<>();

            int opponent = AmazonsBoardState.opponent(player);

            for (int i = 0; i < candidateCount; i++) {
                AmazonsMove move = prefilter.get(i).move;

                board.applyMove(move, player);
                int score = 6 * centerBias(move)
                        + 2 * centerBias(move.getArrowRow(), move.getArrowCol());

                if (!board.hasAnyMoves(opponent)) score += 1_000_000;
                score += 6 * board.countDestinationsFrom(move.getToRow(), move.getToCol());
                score += 20 * (board.countActiveQueens(player) - board.countActiveQueens(opponent));
                board.undoMove(move, player);

                scored.add(new ScoredMove(move, score));
            }

            scored.sort(Comparator.comparingInt(ScoredMove::getScore).reversed());

            int limit = Math.min(scored.size(), CHILD_MOVE_LIMIT);
            List<AmazonsMove> ordered = new ArrayList<>();

            for (int i = 0; i < limit; i++) {
                ordered.add(scored.get(i).move);
            }

            return ordered;
        }

        private boolean isExpired() {
            if (timedOut) return true;
            if (nodes % 100 == 0 && System.currentTimeMillis() >= deadlineMillis) {
                timedOut = true;
                return true;
            }
            return false;
        }
    }

    private static class ScoredMove {
        final AmazonsMove move;
        final int score;

        ScoredMove(AmazonsMove move, int score) {
            this.move = move;
            this.score = score;
        }

        int getScore() { return score; }
    }
}