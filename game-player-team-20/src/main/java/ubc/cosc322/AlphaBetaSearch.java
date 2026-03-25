package ubc.cosc322;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AlphaBetaSearch {

    private static final int WIN_SCORE = 1_000_000;
    private static final int NEG_INF = -2 * WIN_SCORE;
    private static final int POS_INF = 2 * WIN_SCORE;

    private static final long DEFAULT_SOFT_LIMIT_MILLIS = 25_000L;

    // If you want even faster moves, lower these; for stronger play, raise them.
    private static final int ROOT_BEAM = 140;
    private static final int CHILD_BEAM = 36;
    private static final int CHILD_PREFILTER = 72;

    private static final int DEFAULT_MAX_DEPTH = 16;

    private final long softLimitMillis;
    private final int maxDepth;

    private final TranspositionTable tt;

    // Heuristics (reset each root search)
    private final int[][] killerMoves; // [ply][2] packed moves
    private final int[][] history;     // [playerIndex][toIndex*121 + arrowIndex]

    public AlphaBetaSearch() {
        this(DEFAULT_SOFT_LIMIT_MILLIS, DEFAULT_MAX_DEPTH, 1 << 20);
    }

    public AlphaBetaSearch(long softLimitMillis, int maxDepth) {
        this(softLimitMillis, maxDepth, 1 << 20);
    }

    public AlphaBetaSearch(long softLimitMillis, int maxDepth, int ttSizePowerOfTwo) {
        this.softLimitMillis = softLimitMillis;
        this.maxDepth = maxDepth;
        this.tt = new TranspositionTable(ttSizePowerOfTwo);
        this.killerMoves = new int[maxDepth + 8][2];
        this.history = new int[2][121 * 121];
    }

    public static final class SearchResult {
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
        resetHeuristics();

        long start = System.currentTimeMillis();
        long deadline = start + softLimitMillis;

        List<AmazonsMove> rootMoves = board.generateMoves(sideToMove);
        if (rootMoves.isEmpty()) {
            return new SearchResult(null, -WIN_SCORE, 0, 0L, System.currentTimeMillis() - start);
        }

        AmazonsMove bestMove = rootMoves.get(0);
        int bestScore = NEG_INF;
        int bestDepth = 0;
        long bestNodes = 0L;

        int pvPacked = 0;

        for (int depth = 1; depth <= maxDepth; depth++) {
            if (System.currentTimeMillis() >= deadline) break;

            SearchWorker w = new SearchWorker(board, deadline);
            List<AmazonsMove> ordered = orderRoot(board, sideToMove, pvPacked, depth);

            int localBestScore = NEG_INF;
            int localBestPacked = AmazonsMove.pack(ordered.get(0));
            AmazonsMove localBestMove = ordered.get(0);

            int alpha = NEG_INF;
            int beta = POS_INF;

            for (AmazonsMove move : ordered) {
                board.applyMove(move, sideToMove);

                int score = -w.negamax(
                    AmazonsBoardState.opponent(sideToMove),
                    depth - 1,
                    -beta,
                    -alpha,
                    1
                );

                board.undoMove(move, sideToMove);

                if (w.timedOut) break;

                if (score > localBestScore) {
                    localBestScore = score;
                    localBestMove = move;
                    localBestPacked = AmazonsMove.pack(move);
                }
                if (score > alpha) alpha = score;
            }

            if (!w.timedOut) {
                bestMove = localBestMove;
                bestScore = localBestScore;
                bestDepth = depth;
                bestNodes = w.nodes;
                pvPacked = localBestPacked;
            } else {
                break;
            }
        }

        return new SearchResult(bestMove, bestScore, bestDepth, bestNodes, System.currentTimeMillis() - start);
    }

    private void resetHeuristics() {
        tt.clear();
        for (int ply = 0; ply < killerMoves.length; ply++) {
            killerMoves[ply][0] = 0;
            killerMoves[ply][1] = 0;
        }
        for (int p = 0; p < history.length; p++) {
            for (int i = 0; i < history[p].length; i++) {
                history[p][i] = 0;
            }
        }
    }

    private List<AmazonsMove> orderRoot(AmazonsBoardState board, int player, int pvPacked, int depth) {
        List<AmazonsMove> moves = board.generateMoves(player);
        if (moves.size() <= 1) return moves;

        ArrayList<ScoredMove> scored = new ArrayList<>(moves.size());
        int opponent = AmazonsBoardState.opponent(player);

        for (AmazonsMove m : moves) {
            int s = 8 * centerBias(m.getToRow(), m.getToCol())
                + 3 * centerBias(m.getArrowRow(), m.getArrowCol());

            int packed = AmazonsMove.pack(m);
            if (packed == pvPacked) s += 5_000_000;

            board.applyMove(m, player);
            if (!board.hasAnyMoves(opponent)) s += 1_500_000;
            s += 4 * board.countDestinationsFrom(m.getToRow(), m.getToCol());
            board.undoMove(m, player);

            scored.add(new ScoredMove(m, packed, s));
        }

        scored.sort(Comparator.comparingInt(ScoredMove::score).reversed());

        int limit = Math.min(scored.size(), depth <= 1 ? Math.max(ROOT_BEAM, 192) : ROOT_BEAM);
        ArrayList<AmazonsMove> ordered = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) ordered.add(scored.get(i).move);
        return ordered;
    }

    private static int centerBias(int row, int col) {
        return 20 - (Math.abs(5 - row) + Math.abs(5 - col));
    }

    private final class SearchWorker {
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

            if (!board.hasAnyMoves(player)) {
                return -WIN_SCORE + ply;
            }

            long key = board.getHash() ^ (player == AmazonsBoardState.WHITE ? 0x9E3779B97F4A7C15L : 0L);

            TTProbe probe = tt.probe(key);
            if (probe.hit && probe.depth >= depth) {
                if (probe.flag == TTFlag.EXACT) return probe.score;
                if (probe.flag == TTFlag.LOWER && probe.score >= beta) return probe.score;
                if (probe.flag == TTFlag.UPPER && probe.score <= alpha) return probe.score;
            }

            if (depth == 0) {
                int eval = board.fastEvaluate(player);
                tt.store(key, depth, eval, TTFlag.EXACT, 0);
                return eval;
            }

            int bestScore = NEG_INF;
            int bestMovePacked = 0;

            List<AmazonsMove> moves = orderNodeMoves(player, ply, probe.bestMovePacked);

            int originalAlpha = alpha;

            for (AmazonsMove move : moves) {
                int packed = AmazonsMove.pack(move);

                board.applyMove(move, player);
                int score = -negamax(
                    AmazonsBoardState.opponent(player),
                    depth - 1,
                    -beta,
                    -alpha,
                    ply + 1
                );
                board.undoMove(move, player);

                if (timedOut) return 0;

                if (score > bestScore) {
                    bestScore = score;
                    bestMovePacked = packed;
                }
                if (score > alpha) alpha = score;
                if (alpha >= beta) {
                    rememberCutoff(player, ply, packed, depth);
                    break;
                }
            }

            TTFlag flag = TTFlag.EXACT;
            if (bestScore <= originalAlpha) flag = TTFlag.UPPER;
            else if (bestScore >= beta) flag = TTFlag.LOWER;

            tt.store(key, depth, bestScore, flag, bestMovePacked);
            return bestScore;
        }

        private List<AmazonsMove> orderNodeMoves(int player, int ply, int ttBestPacked) {
            List<AmazonsMove> moves = board.generateMoves(player);
            if (moves.size() <= 1) return moves;

            int opponent = AmazonsBoardState.opponent(player);

            ArrayList<ScoredMove> pre = new ArrayList<>(Math.min(moves.size(), CHILD_PREFILTER));
            for (AmazonsMove m : moves) {
                int s = 7 * centerBias(m.getToRow(), m.getToCol())
                    + 2 * centerBias(m.getArrowRow(), m.getArrowCol());
                pre.add(new ScoredMove(m, AmazonsMove.pack(m), s));
            }
            pre.sort(Comparator.comparingInt(ScoredMove::score).reversed());

            int candidates = Math.min(pre.size(), CHILD_PREFILTER);
            ArrayList<ScoredMove> scored = new ArrayList<>(candidates);

            int killer1 = killerMoves[ply][0];
            int killer2 = killerMoves[ply][1];
            int playerIndex = player == AmazonsBoardState.WHITE ? 0 : 1;

            for (int i = 0; i < candidates; i++) {
                ScoredMove sm = pre.get(i);
                AmazonsMove m = sm.move;
                int packed = sm.packed;

                int s = sm.score;

                if (packed == ttBestPacked) s += 4_000_000;
                if (packed == killer1) s += 2_000_000;
                else if (packed == killer2) s += 1_000_000;

                s += history[playerIndex][historyIndex(m)] / 8;

                board.applyMove(m, player);
                if (!board.hasAnyMoves(opponent)) s += 1_500_000;
                s += 3 * board.countDestinationsFrom(m.getToRow(), m.getToCol());
                board.undoMove(m, player);

                scored.add(new ScoredMove(m, packed, s));
            }

            scored.sort(Comparator.comparingInt(ScoredMove::score).reversed());

            int limit = Math.min(scored.size(), CHILD_BEAM);
            ArrayList<AmazonsMove> ordered = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) ordered.add(scored.get(i).move);
            return ordered;
        }

        private int historyIndex(AmazonsMove m) {
            int to = m.getToRow() * AmazonsBoardState.BOARD_DIMENSION + m.getToCol();
            int ar = m.getArrowRow() * AmazonsBoardState.BOARD_DIMENSION + m.getArrowCol();
            return to * 121 + ar;
        }

        private int historyIndexFromPacked(int packed) {
            int toRow = (packed >>> 12) & 0xF;
            int toCol = (packed >>> 8) & 0xF;
            int arRow = (packed >>> 4) & 0xF;
            int arCol = packed & 0xF;
            int to = toRow * AmazonsBoardState.BOARD_DIMENSION + toCol;
            int ar = arRow * AmazonsBoardState.BOARD_DIMENSION + arCol;
            return to * 121 + ar;
        }

        private void rememberCutoff(int player, int ply, int packed, int depth) {
            if (packed == killerMoves[ply][0]) return;
            killerMoves[ply][1] = killerMoves[ply][0];
            killerMoves[ply][0] = packed;

            int p = player == AmazonsBoardState.WHITE ? 0 : 1;
            int bonus = depth * depth;
            int idx = historyIndexFromPacked(packed);
            history[p][idx] += bonus;
            if (history[p][idx] > 5_000_000) history[p][idx] = 5_000_000;
        }

        private boolean isExpired() {
            if (timedOut) return true;
            if ((nodes & 0xFF) == 0 && System.currentTimeMillis() >= deadlineMillis) {
                timedOut = true;
                return true;
            }
            return false;
        }
    }

    private static final class ScoredMove {
        final AmazonsMove move;
        final int packed;
        final int score;

        ScoredMove(AmazonsMove move, int packed, int score) {
            this.move = move;
            this.packed = packed;
            this.score = score;
        }

        int score() { return score; }
    }

    private enum TTFlag {
        EXACT, LOWER, UPPER
    }

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
                depths[i] = 0;
                scores[i] = 0;
                flags[i] = 0;
                bestMoves[i] = 0;
            }
        }

        TTProbe probe(long key) {
            int idx = index(key);
            if (keys[idx] != key) {
                return new TTProbe(false, 0, 0, TTFlag.EXACT, 0);
            }
            TTFlag flag = decodeFlag(flags[idx]);
            return new TTProbe(true, depths[idx], scores[idx], flag, bestMoves[idx]);
        }

        void store(long key, int depth, int score, TTFlag flag, int bestMovePacked) {
            int idx = index(key);
            if (keys[idx] == 0L || depths[idx] <= depth) {
                keys[idx] = key;
                depths[idx] = (short) depth;
                scores[idx] = score;
                flags[idx] = encodeFlag(flag);
                bestMoves[idx] = bestMovePacked;
            }
        }

        private int index(long key) {
            long x = key ^ (key >>> 33) ^ (key >>> 17);
            return ((int) x) & mask;
        }

        private static byte encodeFlag(TTFlag f) {
            switch (f) {
                case LOWER: return 1;
                case UPPER: return 2;
                default: return 0;
            }
        }

        private static TTFlag decodeFlag(byte b) {
            if (b == 1) return TTFlag.LOWER;
            if (b == 2) return TTFlag.UPPER;
            return TTFlag.EXACT;
        }
    }
}