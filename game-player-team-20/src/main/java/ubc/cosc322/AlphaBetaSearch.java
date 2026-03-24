package ubc.cosc322;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stronger Amazons engine (fast + strong under fixed time):
 * - Iterative deepening + aspiration windows
 * - PVS (principal variation search)
 * - LMR (late move reductions)
 * - TT (fixed-size) with bounds + PV move
 * - Killer + history ordering
 * - Top-K move generation (beam) for speed/strength balance
 */
public final class AlphaBetaSearch {

    private static final int WIN_SCORE = 1_000_000;
    private static final int NEG_INF = -2 * WIN_SCORE;
    private static final int POS_INF = 2 * WIN_SCORE;

    private static final int TT_SIZE = 1 << 21;

    private final int timeBudgetMs;
    private final int maxDepth;

    private final TranspositionTable tt;

    private final int[][] killers;
    private final int[][] history; // [playerIndex][to*121+arrow]

    public AlphaBetaSearch(int timeBudgetMs, int maxDepth) {
        this.timeBudgetMs = Math.max(20, timeBudgetMs);
        this.maxDepth = Math.max(1, maxDepth);
        this.tt = new TranspositionTable(TT_SIZE);
        this.killers = new int[this.maxDepth + 8][2];
        this.history = new int[2][121 * 121];
    }

    public static final class SearchResult {
        private final AmazonsMove move;
        private final int score;
        private final int depth;
        private final long nodes;
        private final long elapsedMs;

        SearchResult(AmazonsMove move, int score, int depth, long nodes, long elapsedMs) {
            this.move = move;
            this.score = score;
            this.depth = depth;
            this.nodes = nodes;
            this.elapsedMs = elapsedMs;
        }

        public AmazonsMove getMove() { return move; }
        public int getScore() { return score; }
        public int getDepth() { return depth; }
        public long getNodes() { return nodes; }
        public long getElapsedMillis() { return elapsedMs; }
    }

    public SearchResult chooseMove(AmazonsBoardState board, int sideToMove) {
        resetHeuristics();

        long start = System.nanoTime();
        long deadline = start + (long) timeBudgetMs * 1_000_000L;

        if (!board.hasAnyMoves(sideToMove)) {
            return new SearchResult(null, -WIN_SCORE, 0, 0L, 0L);
        }

        int bestPacked = 0;
        AmazonsMove bestMove = null;
        int bestScore = NEG_INF;

        long nodesAtBest = 0L;
        int reachedDepth = 0;

        int prevScore = 0;
        int window = 80;

        for (int depth = 1; depth <= maxDepth; depth++) {
            if (System.nanoTime() >= deadline) break;

            int alpha = Math.max(NEG_INF, prevScore - window);
            int beta = Math.min(POS_INF, prevScore + window);

            SearchWorker w = new SearchWorker(board, deadline);

            TTProbe rootProbe = tt.probe(board.getHashWithSide(sideToMove));
            int ttBest = rootProbe.hit ? rootProbe.bestMovePacked : 0;

            int rootK = rootBeam(board.countArrows());
            List<AmazonsMove> rootMoves = board.generateTopMoves(sideToMove, rootK, ttBest);
            if (rootMoves.isEmpty()) break;

            RootResult rr = w.searchRoot(rootMoves, sideToMove, depth, alpha, beta, bestPacked);

            if (w.timedOut) break;

            if (rr.failHigh) {
                rr = w.searchRoot(rootMoves, sideToMove, depth, alpha, POS_INF, bestPacked);
                if (w.timedOut) break;
            } else if (rr.failLow) {
                rr = w.searchRoot(rootMoves, sideToMove, depth, NEG_INF, beta, bestPacked);
                if (w.timedOut) break;
            }

            bestPacked = rr.bestPacked;
            bestMove = rr.bestMove;
            bestScore = rr.bestScore;
            prevScore = bestScore;
            reachedDepth = depth;
            nodesAtBest = w.nodes;

            window = Math.min(260, window + 30);
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        return new SearchResult(bestMove, bestScore, reachedDepth, nodesAtBest, elapsedMs);
    }

    private static int rootBeam(int arrows) {
        int phase = Math.min(arrows, 44);
        if (phase < 10) return 160;
        if (phase < 28) return 120;
        return 90;
    }

    private static int nodeBeam(int arrows) {
        int phase = Math.min(arrows, 44);
        if (phase < 10) return 56;
        if (phase < 28) return 44;
        return 36;
    }

    private void resetHeuristics() {
        tt.clear();
        for (int i = 0; i < killers.length; i++) {
            killers[i][0] = 0;
            killers[i][1] = 0;
        }
        for (int p = 0; p < history.length; p++) {
            for (int i = 0; i < history[p].length; i++) history[p][i] = 0;
        }
    }

    private final class SearchWorker {
        private final AmazonsBoardState board;
        private final long deadline;

        long nodes;
        boolean timedOut;

        SearchWorker(AmazonsBoardState board, long deadline) {
            this.board = board;
            this.deadline = deadline;
        }

        RootResult searchRoot(List<AmazonsMove> rootMoves, int player, int depth, int alpha, int beta, int pvPacked) {
            int bestScore = NEG_INF;
            AmazonsMove bestMove = rootMoves.get(0);
            int bestPacked = AmazonsMove.pack(bestMove);

            int originalAlpha = alpha;
            boolean failHigh = false;
            boolean failLow = false;

            for (int i = 0; i < rootMoves.size(); i++) {
                if (isExpired()) break;

                AmazonsMove m = rootMoves.get(i);
                int packed = AmazonsMove.pack(m);

                board.applyMove(m, player);

                int score;
                if (i == 0 || packed == pvPacked) {
                    score = -negamax(AmazonsBoardState.opponent(player), depth - 1, -beta, -alpha, 1, true);
                } else {
                    score = -negamax(AmazonsBoardState.opponent(player), depth - 1, -alpha - 1, -alpha, 1, true);
                    if (!timedOut && score > alpha && score < beta) {
                        score = -negamax(AmazonsBoardState.opponent(player), depth - 1, -beta, -alpha, 1, true);
                    }
                }

                board.undoMove(m, player);

                if (timedOut) break;

                if (score > bestScore) {
                    bestScore = score;
                    bestMove = m;
                    bestPacked = packed;
                }
                if (score > alpha) alpha = score;
                if (alpha >= beta) {
                    rememberCutoff(player, 0, packed, depth);
                    failHigh = true;
                    break;
                }
            }

            if (bestScore <= originalAlpha) failLow = true;

            return new RootResult(bestMove, bestPacked, bestScore, failHigh, failLow);
        }

        int negamax(int player, int depth, int alpha, int beta, int ply, boolean allowLMR) {
            nodes++;
            if (isExpired()) return 0;

            if (!board.hasAnyMoves(player)) {
                return -WIN_SCORE + ply;
            }

            long key = board.getHashWithSide(player);
            TTProbe probe = tt.probe(key);
            if (probe.hit && probe.depth >= depth) {
                if (probe.flag == TTFlag.EXACT) return probe.score;
                if (probe.flag == TTFlag.LOWER && probe.score >= beta) return probe.score;
                if (probe.flag == TTFlag.UPPER && probe.score <= alpha) return probe.score;
            }

            if (depth <= 0) {
                int eval = leafEval(player, ply);
                tt.store(key, 0, eval, TTFlag.EXACT, 0);
                return eval;
            }

            int nodeK = nodeBeam(board.countArrows());
            int ttBest = probe.hit ? probe.bestMovePacked : 0;
            List<AmazonsMove> moves = board.generateTopMoves(player, nodeK, ttBest);
            if (moves.isEmpty()) return -WIN_SCORE + ply;

            int bestScore = NEG_INF;
            int bestPacked = 0;
            int originalAlpha = alpha;

            int killer1 = killers[ply][0];
            int killer2 = killers[ply][1];

            ArrayList<Scored> ordered = new ArrayList<>(moves.size());
            int pIdx = player == AmazonsBoardState.BLACK ? 0 : 1;

            for (AmazonsMove m : moves) {
                int packed = AmazonsMove.pack(m);
                int s = 0;
                if (packed == ttBest) s += 5_000_000;
                if (packed == killer1) s += 2_000_000;
                else if (packed == killer2) s += 1_000_000;
                s += history[pIdx][historyIndex(m)] / 4;
                s += cheapTacticalHint(player, m);
                ordered.add(new Scored(m, packed, s));
            }
            ordered.sort(Comparator.comparingInt((Scored sm) -> sm.score).reversed());

            boolean pv = true;
            for (int i = 0; i < ordered.size(); i++) {
                if (isExpired()) break;

                Scored sm = ordered.get(i);
                AmazonsMove m = sm.move;

                int reduction = 0;
                boolean doLMR = allowLMR && depth >= 3 && i >= 6 && !pv;
                if (doLMR) reduction = 1 + (i >= 14 ? 1 : 0);

                board.applyMove(m, player);

                int score;
                if (pv) {
                    score = -negamax(AmazonsBoardState.opponent(player), depth - 1, -beta, -alpha, ply + 1, true);
                } else {
                    int d = Math.max(0, depth - 1 - reduction);
                    score = -negamax(AmazonsBoardState.opponent(player), d, -alpha - 1, -alpha, ply + 1, true);
                    if (!timedOut && score > alpha && reduction > 0) {
                        score = -negamax(AmazonsBoardState.opponent(player), depth - 1, -alpha - 1, -alpha, ply + 1, true);
                    }
                    if (!timedOut && score > alpha && score < beta) {
                        score = -negamax(AmazonsBoardState.opponent(player), depth - 1, -beta, -alpha, ply + 1, true);
                    }
                }

                board.undoMove(m, player);

                if (timedOut) return 0;

                if (score > bestScore) {
                    bestScore = score;
                    bestPacked = sm.packed;
                }
                if (score > alpha) alpha = score;

                if (alpha >= beta) {
                    rememberCutoff(player, ply, sm.packed, depth);
                    break;
                }

                pv = false;
            }

            TTFlag flag = TTFlag.EXACT;
            if (bestScore <= originalAlpha) flag = TTFlag.UPPER;
            else if (bestScore >= beta) flag = TTFlag.LOWER;

            tt.store(key, depth, bestScore, flag, bestPacked);
            return bestScore;
        }

        private int leafEval(int player, int ply) {
            int arrows = board.countArrows();
            if (arrows < 10) return board.fastEvaluate(player);
            if (arrows < 26) return (ply <= 2) ? board.evaluate(player) : board.fastEvaluate(player);
            return (ply <= 1) ? board.evaluate(player) : board.fastEvaluate(player);
        }

        private int cheapTacticalHint(int player, AmazonsMove m) {
            int opp = AmazonsBoardState.opponent(player);
            int hint = 0;
            board.applyMove(m, player);
            if (!board.hasAnyMoves(opp)) hint += 1_500_000;
            hint += 4 * board.countDestinationsFrom(m.getToRow(), m.getToCol());
            board.undoMove(m, player);
            return hint;
        }

        private void rememberCutoff(int player, int ply, int packed, int depth) {
            if (packed != killers[ply][0]) {
                killers[ply][1] = killers[ply][0];
                killers[ply][0] = packed;
            }
            int pIdx = player == AmazonsBoardState.BLACK ? 0 : 1;
            int idx = historyIndexFromPacked(packed);
            int bonus = depth * depth * 6;
            int v = history[pIdx][idx] + bonus;
            history[pIdx][idx] = Math.min(v, 8_000_000);
        }

        private boolean isExpired() {
            if (timedOut) return true;
            if ((nodes & 0x3F) == 0 && System.nanoTime() >= deadline) {
                timedOut = true;
                return true;
            }
            return false;
        }
    }

    private static int historyIndex(AmazonsMove m) {
        int to = m.getToRow() * AmazonsBoardState.BOARD_DIMENSION + m.getToCol();
        int ar = m.getArrowRow() * AmazonsBoardState.BOARD_DIMENSION + m.getArrowCol();
        return to * 121 + ar;
    }

    private static int historyIndexFromPacked(int packed) {
        int tr = (packed >>> 12) & 0xF;
        int tc = (packed >>> 8) & 0xF;
        int ar = (packed >>> 4) & 0xF;
        int ac = packed & 0xF;

        int to = tr * AmazonsBoardState.BOARD_DIMENSION + tc;
        int a = ar * AmazonsBoardState.BOARD_DIMENSION + ac;
        return to * 121 + a;
    }

    private static final class RootResult {
        final AmazonsMove bestMove;
        final int bestPacked;
        final int bestScore;
        final boolean failHigh;
        final boolean failLow;

        RootResult(AmazonsMove bestMove, int bestPacked, int bestScore, boolean failHigh, boolean failLow) {
            this.bestMove = bestMove;
            this.bestPacked = bestPacked;
            this.bestScore = bestScore;
            this.failHigh = failHigh;
            this.failLow = failLow;
        }
    }

    private static final class Scored {
        final AmazonsMove move;
        final int packed;
        final int score;

        Scored(AmazonsMove move, int packed, int score) {
            this.move = move;
            this.packed = packed;
            this.score = score;
        }
    }

    private enum TTFlag { EXACT, LOWER, UPPER }

    private static final class TTProbe {
        final boolean hit;
        final int depth;
        final int score;
        final TTFlag flag;
        final int bestMovePacked;

        TTProbe(boolean hit, int depth, int score, TTFlag flag, int bestMovePacked) {
            this.hit = hit;
            this.depth = depth;
            this.score = score;
            this.flag = flag;
            this.bestMovePacked = bestMovePacked;
        }
    }

    private static final class TranspositionTable {
        private final long[] keys;
        private final int[] scores;
        private final short[] depths;
        private final byte[] flags;
        private final int[] bestMoves;
        private final int mask;

        TranspositionTable(int sizePowerOfTwo) {
            int size = 1;
            while (size < sizePowerOfTwo) size <<= 1;
            this.keys = new long[size];
            this.scores = new int[size];
            this.depths = new short[size];
            this.flags = new byte[size];
            this.bestMoves = new int[size];
            this.mask = size - 1;
        }

        void clear() {
            for (int i = 0; i < keys.length; i++) {
                keys[i] = 0L;
                scores[i] = 0;
                depths[i] = 0;
                flags[i] = 0;
                bestMoves[i] = 0;
            }
        }

        TTProbe probe(long key) {
            int idx = index(key);
            if (keys[idx] != key) return new TTProbe(false, 0, 0, TTFlag.EXACT, 0);
            return new TTProbe(true, depths[idx], scores[idx], decode(flags[idx]), bestMoves[idx]);
        }

        void store(long key, int depth, int score, TTFlag flag, int bestMovePacked) {
            int idx = index(key);
            if (keys[idx] == 0L || depths[idx] <= depth) {
                keys[idx] = key;
                depths[idx] = (short) depth;
                scores[idx] = score;
                flags[idx] = encode(flag);
                bestMoves[idx] = bestMovePacked;
            }
        }

        private int index(long key) {
            long x = key ^ (key >>> 33) ^ (key >>> 17);
            return ((int) x) & mask;
        }

        private static byte encode(TTFlag f) {
            switch (f) {
                case LOWER: return 1;
                case UPPER: return 2;
                default: return 0;
            }
        }

        private static TTFlag decode(byte b) {
            if (b == 1) return TTFlag.LOWER;
            if (b == 2) return TTFlag.UPPER;
            return TTFlag.EXACT;
        }
    }
}
