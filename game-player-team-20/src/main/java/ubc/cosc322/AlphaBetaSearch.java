package ubc.cosc322;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
    private static final int ASPIRATION_WINDOW = 250;
    private static final int SOLVED_SCORE_MARGIN = 1_000;
    private static final int MATE_SCORE_THRESHOLD = WIN_SCORE - 10_000;
    private static final int ROOT_HINT_SCORE = 50_000;
    private static final int KILLER_MOVE_SCORE = 3_000_000;
    private static final int HISTORY_SCORE_FACTOR = 64;
    private static final int REPETITION_PENALTY = 400;
    private static final long MIN_SEARCH_BUDGET_MILLIS = 4_000L;
    private static final int STABLE_SCORE_DELTA = 90;
    private static final int UNSTABLE_SCORE_DELTA = 300;
    private static final int EARLY_STOP_SCORE = 5_000;
    private static final int DEPTH_ONE_ROOT_RESCORING_LIMIT = 72;
    private static final int ROOT_RESCORING_LIMIT = 48;
    private static final int SHALLOW_CHILD_RESCORING_LIMIT = 32;
    private static final int CHILD_RESCORING_LIMIT = 20;

    private final long softLimitMillis;
    private final int maxDepth;
    private final Map<Long, TranspositionEntry> transpositionTable;
    private final AmazonsMove[][] killerMoves;
    private final Map<Integer, Integer> historyScores;
    private final Map<Long, Integer> recentStateCounts;

    public AlphaBetaSearch() {
        this(DEFAULT_SOFT_LIMIT_MILLIS, DEFAULT_MAX_DEPTH);
    }

    public AlphaBetaSearch(long softLimitMillis, int maxDepth) {
        this.softLimitMillis = softLimitMillis;
        this.maxDepth = maxDepth;
        this.transpositionTable = createTranspositionTable();
        this.killerMoves = new AmazonsMove[maxDepth + 4][2];
        this.historyScores = new HashMap<Integer, Integer>();
        this.recentStateCounts = new HashMap<Long, Integer>();
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

    private static class DepthResult {
        final AmazonsMove move;
        final int score;
        final long nodes;
        final boolean timedOut;
        final List<AmazonsMove> orderedMoves;

        DepthResult(AmazonsMove move, int score, long nodes, boolean timedOut, List<AmazonsMove> orderedMoves) {
            this.move = move;
            this.score = score;
            this.nodes = nodes;
            this.timedOut = timedOut;
            this.orderedMoves = orderedMoves;
        }
    }

    public SearchResult chooseMove(AmazonsBoardState board, int sideToMove, List<Long> recentStates) {
        long startMillis = System.currentTimeMillis();
        List<AmazonsMove> legalMoves = board.generateMoves(sideToMove);
        if (legalMoves.isEmpty()) {
            return new SearchResult(null, -WIN_SCORE, 0, 0L, System.currentTimeMillis() - startMillis);
        }
        updateRecentStateCounts(recentStates);
        long baseBudgetMillis = computeBudgetMillis(board, legalMoves.size());
        long deadlineMillis = startMillis + baseBudgetMillis;
        long hardDeadlineMillis = startMillis + softLimitMillis;

        AmazonsMove bestMove = legalMoves.get(0);
        int bestScore = NEG_INF;
        int bestDepth = 0;
        long bestNodes = 0L;
        AmazonsMove preferredMove = null;
        long rootHash = board.getZobristHash(sideToMove);
        Map<Integer, Integer> rootHint = null;
        AmazonsMove previousCompletedMove = null;
        int previousCompletedScore = NEG_INF;
        int stableDepths = 0;

        for (int depth = 1; depth <= maxDepth; depth++) {
            if (depth > 1 && System.currentTimeMillis() >= deadlineMillis) {
                break;
            }

            DepthResult depthResult;
            if (bestDepth > 0) {
                depthResult = searchDepth(
                    board, sideToMove, depth, deadlineMillis, preferredMove, rootHint,
                    bestScore - ASPIRATION_WINDOW, bestScore + ASPIRATION_WINDOW
                );
                if (!depthResult.timedOut
                    && (depthResult.score <= bestScore - ASPIRATION_WINDOW
                        || depthResult.score >= bestScore + ASPIRATION_WINDOW)) {
                    depthResult = searchDepth(
                        board, sideToMove, depth, deadlineMillis, preferredMove, rootHint, NEG_INF, POS_INF
                    );
                }
            } else {
                depthResult = searchDepth(
                    board, sideToMove, depth, deadlineMillis, preferredMove, rootHint, NEG_INF, POS_INF
                );
            }

            if (!depthResult.timedOut && depthResult.move != null) {
                bestMove = depthResult.move;
                bestScore = depthResult.score;
                bestDepth = depth;
                bestNodes = depthResult.nodes;
                preferredMove = bestMove;
                rootHint = toOrderHint(depthResult.orderedMoves);
                storeEntry(rootHash, bestScore, depth, Bound.EXACT, bestMove, 0);
                if (Math.abs(bestScore) >= WIN_SCORE - SOLVED_SCORE_MARGIN) {
                    break;
                }

                if (previousCompletedMove != null) {
                    boolean sameMove = bestMove.equals(previousCompletedMove);
                    int scoreDelta = Math.abs(bestScore - previousCompletedScore);
                    if (sameMove && scoreDelta <= STABLE_SCORE_DELTA) {
                        stableDepths++;
                    } else {
                        stableDepths = 0;
                    }
                    if ((!sameMove || scoreDelta >= UNSTABLE_SCORE_DELTA) && depth >= 4 && deadlineMillis < hardDeadlineMillis) {
                        long extensionMillis = Math.max(1_500L, softLimitMillis / 8);
                        deadlineMillis = Math.min(hardDeadlineMillis, deadlineMillis + extensionMillis);
                    }
                }

                long elapsedMillis = System.currentTimeMillis() - startMillis;
                if (depth >= 5
                    && stableDepths >= 2
                    && elapsedMillis >= (baseBudgetMillis * 3) / 4) {
                    break;
                }
                if (depth >= 6
                    && stableDepths >= 1
                    && Math.abs(bestScore) >= EARLY_STOP_SCORE
                    && elapsedMillis >= baseBudgetMillis / 2) {
                    break;
                }

                previousCompletedMove = bestMove;
                previousCompletedScore = bestScore;
            }
        }

        return new SearchResult(bestMove, bestScore, bestDepth, bestNodes, System.currentTimeMillis() - startMillis);
    }

    public void clearTranspositionTable() {
        transpositionTable.clear();
        historyScores.clear();
        for (int i = 0; i < killerMoves.length; i++) {
            killerMoves[i][0] = null;
            killerMoves[i][1] = null;
        }
    }

    private DepthResult searchDepth(AmazonsBoardState board, int sideToMove, int depth, long deadlineMillis,
                                    AmazonsMove preferredMove, Map<Integer, Integer> rootHint,
                                    int alpha, int beta) {
        long workerDeadline = depth == 1 ? Long.MAX_VALUE : deadlineMillis;
        List<AmazonsMove> orderedMoves = orderRootMoves(
            board, sideToMove, preferredMove, tableMove(board.getZobristHash(sideToMove)), rootHint, depth
        );
        boolean timedOut = false;
        int runningAlpha = alpha;
        AmazonsMove depthBestMove = null;
        int depthBestScore = NEG_INF;
        long depthNodes = 0L;

        for (AmazonsMove move : orderedMoves) {
            board.applyMove(move, sideToMove);
            SearchWorker worker = new SearchWorker(board, workerDeadline);
            int score;
            if (depthBestMove == null) {
                score = -worker.negamax(
                    AmazonsBoardState.opponent(sideToMove), depth - 1,
                    -beta, -runningAlpha, 1,
                    board.getZobristHash(AmazonsBoardState.opponent(sideToMove))
                );
            } else {
                score = -worker.negamax(
                    AmazonsBoardState.opponent(sideToMove), depth - 1,
                    -runningAlpha - 1, -runningAlpha, 1,
                    board.getZobristHash(AmazonsBoardState.opponent(sideToMove))
                );
                if (!worker.timedOut && score > runningAlpha && score < beta) {
                    score = -worker.negamax(
                        AmazonsBoardState.opponent(sideToMove), depth - 1,
                        -beta, -runningAlpha, 1,
                        board.getZobristHash(AmazonsBoardState.opponent(sideToMove))
                    );
                }
            }
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
            if (score > runningAlpha) {
                runningAlpha = score;
            }
            if (runningAlpha >= beta) {
                break;
            }
        }

        return new DepthResult(depthBestMove, depthBestScore, depthNodes, timedOut, orderedMoves);
    }

    private List<AmazonsMove> orderRootMoves(AmazonsBoardState board, int player,
                                             AmazonsMove prioritizedMove, AmazonsMove ttMove,
                                             Map<Integer, Integer> rootHint,
                                             int depthRemaining) {
        List<AmazonsMove> moves = board.generateMoves(player);
        if (moves.size() <= 1) {
            return moves;
        }

        List<ScoredMove> scoredMoves = new ArrayList<ScoredMove>(moves.size());
        for (AmazonsMove move : moves) {
            int score = cheapMoveScore(move, prioritizedMove, ttMove, 0);
            if (rootHint != null) {
                Integer hintIndex = rootHint.get(Integer.valueOf(moveKey(move)));
                if (hintIndex != null) {
                    score += Math.max(0, ROOT_HINT_SCORE - hintIndex.intValue() * 256);
                }
            }
            scoredMoves.add(new ScoredMove(move, score));
        }

        Collections.sort(scoredMoves, Comparator.comparingInt(ScoredMove::getScore).reversed());

        int rootLimit = moves.size() > 1000 ? ROOT_MOVE_LIMIT - 32 : ROOT_MOVE_LIMIT;
        int limit = depthRemaining <= 1
            ? Math.min(scoredMoves.size(), DEPTH_ONE_ROOT_MOVE_LIMIT)
            : Math.min(scoredMoves.size(), rootLimit);
        int rescoringLimit = depthRemaining <= 1 ? DEPTH_ONE_ROOT_RESCORING_LIMIT : ROOT_RESCORING_LIMIT;
        return refineOrderedMoves(board, player, scoredMoves, limit, rescoringLimit, 0);
    }

    private int cheapMoveScore(AmazonsMove move, AmazonsMove prioritizedMove, AmazonsMove ttMove, int ply) {
        int score = 6 * centerBias(move) + 2 * centerBias(move.getArrowRow(), move.getArrowCol());
        if (move.equals(ttMove)) {
            score += 4_000_000;
        } else if (move.equals(prioritizedMove)) {
            score += 2_000_000;
        }
        score += killerScore(ply, move);
        score += historyScore(move);
        return score;
    }

    private int refinedMoveScore(AmazonsBoardState board, int player, AmazonsMove move, int baseScore) {
        int opponent = AmazonsBoardState.opponent(player);
        int score = baseScore;
        score -= repetitionPenalty(board.getZobristHash(opponent));
        if (!board.hasAnyMoves(opponent)) {
            score += 1_000_000;
        }
        score += 6 * board.countDestinationsFrom(move.getToRow(), move.getToCol());
        score += 20 * (board.countActiveQueens(player) - board.countActiveQueens(opponent));
        return score;
    }

    private List<AmazonsMove> refineOrderedMoves(AmazonsBoardState board, int player, List<ScoredMove> orderedByCheapScore,
                                                 int limit, int rescoringLimit, int ply) {
        if (limit <= 0) {
            return new ArrayList<AmazonsMove>(0);
        }

        int candidateCount = Math.min(orderedByCheapScore.size(), limit);
        int refinedCount = Math.min(candidateCount, rescoringLimit);
        List<ScoredMove> refined = new ArrayList<ScoredMove>(refinedCount);

        for (int i = 0; i < refinedCount; i++) {
            AmazonsMove move = orderedByCheapScore.get(i).move;
            board.applyMove(move, player);
            int score = refinedMoveScore(board, player, move, orderedByCheapScore.get(i).score);
            board.undoMove(move, player);
            refined.add(new ScoredMove(move, score));
        }
        Collections.sort(refined, Comparator.comparingInt(ScoredMove::getScore).reversed());

        List<AmazonsMove> ordered = new ArrayList<AmazonsMove>(candidateCount);
        for (int i = 0; i < refined.size(); i++) {
            ordered.add(refined.get(i).move);
        }
        for (int i = refinedCount; i < candidateCount; i++) {
            ordered.add(orderedByCheapScore.get(i).move);
        }
        return ordered;
    }

    private static int centerBias(AmazonsMove move) {
        return centerBias(move.getToRow(), move.getToCol());
    }

    private static int centerBias(int row, int col) {
        int rowDistance = Math.abs(5 - row);
        int colDistance = Math.abs(5 - col);
        return 20 - (rowDistance + colDistance);
    }

    private int killerScore(int ply, AmazonsMove move) {
        if (ply >= killerMoves.length) {
            return 0;
        }
        if (move.equals(killerMoves[ply][0])) {
            return KILLER_MOVE_SCORE;
        }
        if (move.equals(killerMoves[ply][1])) {
            return KILLER_MOVE_SCORE / 2;
        }
        return 0;
    }

    private int historyScore(AmazonsMove move) {
        Integer score = historyScores.get(Integer.valueOf(moveKey(move)));
        return score == null ? 0 : score.intValue();
    }

    private static int moveKey(AmazonsMove move) {
        return (move.getFromRow() << 20)
            | (move.getFromCol() << 16)
            | (move.getToRow() << 12)
            | (move.getToCol() << 8)
            | (move.getArrowRow() << 4)
            | move.getArrowCol();
    }

    private Map<Integer, Integer> toOrderHint(List<AmazonsMove> orderedMoves) {
        Map<Integer, Integer> hint = new HashMap<Integer, Integer>(orderedMoves.size());
        for (int i = 0; i < orderedMoves.size(); i++) {
            hint.put(Integer.valueOf(moveKey(orderedMoves.get(i))), Integer.valueOf(i));
        }
        return hint;
    }

    private AmazonsMove tableMove(long stateHash) {
        TranspositionEntry entry = transpositionTable.get(stateHash);
        return entry == null ? null : entry.bestMove;
    }

    private long computeBudgetMillis(AmazonsBoardState board, int legalMoveCount) {
        long budget = softLimitMillis * 17 / 20;
        if (legalMoveCount <= 12) {
            budget = softLimitMillis / 2;
        } else if (legalMoveCount <= 32) {
            budget = softLimitMillis * 2 / 3;
        } else if (legalMoveCount >= 500) {
            budget = softLimitMillis * 19 / 20;
        }
        if (board.countArrows() <= 8 && legalMoveCount >= 200) {
            budget = softLimitMillis;
        } else if (board.countArrows() >= 40) {
            budget = Math.min(budget, softLimitMillis * 3 / 5);
        }
        return Math.max(MIN_SEARCH_BUDGET_MILLIS, Math.min(softLimitMillis, budget));
    }

    private void updateRecentStateCounts(List<Long> recentStates) {
        recentStateCounts.clear();
        if (recentStates == null) {
            return;
        }
        for (Long state : recentStates) {
            if (state == null) {
                continue;
            }
            Integer previous = recentStateCounts.get(state);
            recentStateCounts.put(state, Integer.valueOf(previous == null ? 1 : previous.intValue() + 1));
        }
    }

    private int repetitionPenalty(long stateHash) {
        Integer visits = recentStateCounts.get(Long.valueOf(stateHash));
        return visits == null ? 0 : visits.intValue() * REPETITION_PENALTY;
    }

    private int scoreToTable(int score, int ply) {
        if (score >= MATE_SCORE_THRESHOLD) {
            return score + ply;
        }
        if (score <= -MATE_SCORE_THRESHOLD) {
            return score - ply;
        }
        return score;
    }

    private int scoreFromTable(int score, int ply) {
        if (score >= MATE_SCORE_THRESHOLD) {
            return score - ply;
        }
        if (score <= -MATE_SCORE_THRESHOLD) {
            return score + ply;
        }
        return score;
    }

    private void storeEntry(long stateHash, int score, int depth, Bound bound, AmazonsMove bestMove, int ply) {
        if (bestMove != null) {
            transpositionTable.put(stateHash, new TranspositionEntry(scoreToTable(score, ply), depth, bound, bestMove));
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
                int entryScore = scoreFromTable(entry.score, ply);
                if (entry.bound == Bound.EXACT) {
                    return entryScore;
                }
                if (entry.bound == Bound.LOWER && entryScore > alpha) {
                    alpha = entryScore;
                } else if (entry.bound == Bound.UPPER && entryScore < beta) {
                    beta = entryScore;
                }
                if (alpha >= beta) {
                    return entryScore;
                }
            }

            if (depth == 0) {
                return board.evaluate(player) - repetitionPenalty(stateHash);
            }

            List<AmazonsMove> orderedMoves = orderMoves(player, depth, entry == null ? null : entry.bestMove);
            int bestScore = NEG_INF;
            AmazonsMove bestMove = null;

            for (AmazonsMove move : orderedMoves) {
                board.applyMove(move, player);
                int score;
                if (bestMove == null) {
                    score = -negamax(
                        AmazonsBoardState.opponent(player), depth - 1,
                        -beta, -alpha, ply + 1,
                        board.getZobristHash(AmazonsBoardState.opponent(player))
                    );
                } else {
                    score = -negamax(
                        AmazonsBoardState.opponent(player), depth - 1,
                        -alpha - 1, -alpha, ply + 1,
                        board.getZobristHash(AmazonsBoardState.opponent(player))
                    );
                    if (!timedOut && score > alpha && score < beta) {
                        score = -negamax(
                            AmazonsBoardState.opponent(player), depth - 1,
                            -beta, -alpha, ply + 1,
                            board.getZobristHash(AmazonsBoardState.opponent(player))
                        );
                    }
                }
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
                    registerKiller(ply, move);
                    addHistory(move, depth);
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
            storeEntry(stateHash, bestScore, depth, bound, bestMove, ply);
            return bestScore;
        }

        private List<AmazonsMove> orderMoves(int player, int depthRemaining, AmazonsMove ttMove) {
            List<AmazonsMove> moves = board.generateMoves(player);
            if (moves.size() <= 1) {
                return moves;
            }

            List<ScoredMove> prefilter = new ArrayList<ScoredMove>(moves.size());
            for (AmazonsMove move : moves) {
                int score = cheapMoveScore(move, null, ttMove, maxDepth - depthRemaining);
                prefilter.add(new ScoredMove(move, score));
            }
            Collections.sort(prefilter, Comparator.comparingInt(ScoredMove::getScore).reversed());

            int candidateLimit = depthRemaining <= 2 ? SHALLOW_CHILD_PREFILTER_LIMIT : CHILD_PREFILTER_LIMIT;
            int candidateCount = Math.min(prefilter.size(), candidateLimit);
            int moveLimit = Math.min(candidateCount, depthRemaining <= 2 ? SHALLOW_CHILD_MOVE_LIMIT : CHILD_MOVE_LIMIT);
            int rescoringLimit = depthRemaining <= 2 ? SHALLOW_CHILD_RESCORING_LIMIT : CHILD_RESCORING_LIMIT;
            return refineOrderedMoves(
                board,
                player,
                prefilter,
                moveLimit,
                rescoringLimit,
                maxDepth - depthRemaining
            );
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

    private void registerKiller(int ply, AmazonsMove move) {
        if (ply >= killerMoves.length) {
            return;
        }
        if (move.equals(killerMoves[ply][0])) {
            return;
        }
        killerMoves[ply][1] = killerMoves[ply][0];
        killerMoves[ply][0] = move;
    }

    private void addHistory(AmazonsMove move, int depth) {
        Integer key = Integer.valueOf(moveKey(move));
        Integer previous = historyScores.get(key);
        int bonus = depth * depth * HISTORY_SCORE_FACTOR;
        historyScores.put(key, Integer.valueOf((previous == null ? 0 : previous.intValue()) + bonus));
    }
}
