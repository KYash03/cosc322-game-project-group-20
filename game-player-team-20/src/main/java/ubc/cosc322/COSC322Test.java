package ubc.cosc322;

import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.Map;

import ygraph.ai.smartfox.games.BaseGameGUI;
import ygraph.ai.smartfox.games.GameClient;
import ygraph.ai.smartfox.games.GameMessage;
import ygraph.ai.smartfox.games.GamePlayer;
import ygraph.ai.smartfox.games.amazons.AmazonsGameMessage;

public class COSC322Test extends GamePlayer {
    private static final String USER_COUNT_CHANGE = "user-count-change";

    private final String password;

    private GameClient gameClient;
    private BaseGameGUI gamegui;
    private String userName;

    private AmazonsBoardState currentState;
    private int mySide = AmazonsBoardState.NONE;

    // Track turn deterministically: BLACK moves first, then alternates each completed move.
    private int sideToMove = AmazonsBoardState.NONE;
    private boolean gameActive;

    private String blackPlayerName;
    private String whitePlayerName;

    private int plyCount;
    private long matchStartMillis;

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: COSC322Test <username> <password>");
            return;
        }

        BaseGameGUI.sys_setup();
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                new COSC322Test(args[0], args[1]).Go();
            }
        });
    }

    public COSC322Test(String userName, String password) {
        this.userName = userName;
        this.password = password;
        this.gamegui = new BaseGameGUI(this);
    }

    @Override
    public void onLogin() {
        this.userName = gameClient.getUserName();
        log("Logged in as %s", this.userName);
        refreshRoomInformation();
    }

    @Override
    public boolean handleGameMessage(String messageType, Map<String, Object> msgDetails) {
        boolean shouldMove = false;

        if (GameMessage.GAME_STATE_BOARD.equals(messageType)) {
            shouldMove = handleBoardState(msgDetails);
        } else if (GameMessage.GAME_ACTION_START.equals(messageType)) {
            shouldMove = handleGameStart(msgDetails);
        } else if (GameMessage.GAME_ACTION_MOVE.equals(messageType)) {
            shouldMove = handleMoveMessage(msgDetails);
        } else if (GameMessage.GAME_STATE_PLAYER_LOST.equals(messageType)) {
            handleGameOver(msgDetails);
        } else if (GameMessage.GAME_TEXT_MESSAGE.equals(messageType)) {
            log("Text message: %s", msgDetails);
        } else if (USER_COUNT_CHANGE.equals(messageType) || GameMessage.GAME_STATE_JOIN.equals(messageType)) {
            refreshRoomInformation();
        } else {
            log("Unhandled message type %s: %s", messageType, msgDetails);
        }

        if (shouldMove) maybeSendMove(messageType);
        return true;
    }

    @Override
    public boolean handleMessage(String msg) {
        log("Server message: %s", msg);
        return true;
    }

    @Override
    public String userName() { return userName; }

    @Override
    public GameClient getGameClient() { return gameClient; }

    @Override
    public BaseGameGUI getGameGUI() { return gamegui; }

    @Override
    public void connect() {
        gameClient = new GameClient(userName, password, this);
    }

    private boolean handleBoardState(Map<String, Object> msgDetails) {
        ArrayList<Integer> encodedState = coerceIntegerList(msgDetails.get(AmazonsGameMessage.GAME_STATE));
        if (encodedState == null) {
            log("Board message missing state payload: %s", msgDetails);
            return false;
        }

        currentState = AmazonsBoardState.fromServerState(encodedState);

        if (sideToMove == AmazonsBoardState.NONE) {
            sideToMove = AmazonsBoardState.BLACK; // force BLACK first
        }

        if (gamegui != null) gamegui.setGameState(encodedState);

        log("Board synced. arrows=%d trackedTurn=%s ply=%d", currentState.countArrows(), sideLabel(sideToMove), plyCount);
        printMatchSummary("BOARD", null, null, null);

        return shouldAutoPlay();
    }

    private boolean handleGameStart(Map<String, Object> msgDetails) {
        blackPlayerName = stringValue(msgDetails.get(AmazonsGameMessage.PLAYER_BLACK));
        whitePlayerName = stringValue(msgDetails.get(AmazonsGameMessage.PLAYER_WHITE));
        mySide = resolveMySide();

        gameActive = true;
        plyCount = 0;
        matchStartMillis = System.currentTimeMillis();

        ArrayList<Integer> encodedState = coerceIntegerList(msgDetails.get(AmazonsGameMessage.GAME_STATE));
        if (encodedState != null) {
            currentState = AmazonsBoardState.fromServerState(encodedState);
            if (gamegui != null) gamegui.setGameState(encodedState);
        }

        sideToMove = AmazonsBoardState.BLACK; // BLACK moves first

        System.out.println("=====================================");
        System.out.println("MATCH START");
        System.out.println("Black: " + (blackPlayerName != null ? blackPlayerName : "Black"));
        System.out.println("White: " + (whitePlayerName != null ? whitePlayerName : "White"));
        System.out.println("Me   : " + sideToName(mySide) + " (" + sideLabel(mySide) + ")");
        System.out.println("Turn : " + sideLabel(sideToMove));
        System.out.println("=====================================");

        printMatchSummary("START", null, null, null);
        return shouldAutoPlay();
    }

    private boolean handleMoveMessage(Map<String, Object> msgDetails) {
        AmazonsMove move = AmazonsMove.fromMessage(msgDetails);
        int mover = sideToMove;

        if (currentState != null && mover != AmazonsBoardState.NONE) {
            currentState.applyMove(move, mover);
        }

        plyCount++;
        sideToMove = (plyCount & 1) == 0 ? AmazonsBoardState.BLACK : AmazonsBoardState.WHITE;

        if (gamegui != null) gamegui.updateGameState(msgDetails);

        log("Move received from %s: %s. nextTurn=%s ply=%d", sideLabel(mover), move, sideLabel(sideToMove), plyCount);
        printMatchSummary("RECV", mover, move, null);

        maybeDeclareLocalGameOver("RECV");
        return shouldAutoPlay();
    }

    private void maybeSendMove(String trigger) {
        if (!shouldAutoPlay()) return;

        int budgetMs = 5000; // 1 second per move for testing
        int maxDepth = 18;

        AlphaBetaSearch search = new AlphaBetaSearch(budgetMs, maxDepth);
        AlphaBetaSearch.SearchResult result = search.chooseMove(currentState.copy(), sideToMove);

        if (result.getMove() == null) {
            gameActive = false;
            System.out.println("GAME OVER (no legal move). Loser: " + sideToName(sideToMove) + " (" + sideLabel(sideToMove) + ")");
            return;
        }

        gameClient.sendMoveMessage(
            result.getMove().toCurrentPosition(),
            result.getMove().toNewPosition(),
            result.getMove().toArrowPosition()
        );

        if (currentState != null) currentState.applyMove(result.getMove(), sideToMove);

        int mover = sideToMove;
        plyCount++;
        sideToMove = (plyCount & 1) == 0 ? AmazonsBoardState.BLACK : AmazonsBoardState.WHITE;

        if (gamegui != null) gamegui.updateGameState(result.getMove().toMessageDetails());

        log("Search(%s) depth=%d score=%d nodes=%d time=%dms move=%s",
            trigger, result.getDepth(), result.getScore(), result.getNodes(), result.getElapsedMillis(), result.getMove());

        printMatchSummary("SEND:" + trigger, mover, result.getMove(), result);
        maybeDeclareLocalGameOver("SEND");
    }

    private void maybeDeclareLocalGameOver(String source) {
        if (!gameActive || currentState == null || sideToMove == AmazonsBoardState.NONE) return;

        if (!currentState.hasAnyMoves(sideToMove)) {
            int loser = sideToMove;
            int winner = AmazonsBoardState.opponent(loser);

            gameActive = false;

            System.out.println("=====================================");
            System.out.println("GAME OVER (local detection)");
            System.out.println("Winner: " + sideToName(winner) + " (" + sideLabel(winner) + ")");
            System.out.println("Loser : " + sideToName(loser) + " (" + sideLabel(loser) + ")");
            System.out.println("=====================================");

            log("Local game over detected (%s). Winner=%s Loser=%s", source, sideLabel(winner), sideLabel(loser));
        }
    }

    private void handleGameOver(Map<String, Object> msgDetails) {
        gameActive = false;

        int losingSide = inferLosingSide(msgDetails);
        int winningSide = losingSide == AmazonsBoardState.BLACK ? AmazonsBoardState.WHITE
            : losingSide == AmazonsBoardState.WHITE ? AmazonsBoardState.BLACK
            : AmazonsBoardState.NONE;

        if (losingSide == AmazonsBoardState.NONE) {
            System.out.println("=====================================");
            System.out.println("GAME OVER (server notification, unknown winner)");
            System.out.println("payload=" + msgDetails);
            System.out.println("=====================================");
            return;
        }

        System.out.println("=====================================");
        System.out.println("GAME OVER (server notification)");
        System.out.println("Winner: " + sideToName(winningSide) + " (" + sideLabel(winningSide) + ")");
        System.out.println("Loser : " + sideToName(losingSide) + " (" + sideLabel(losingSide) + ")");
        System.out.println("=====================================");
    }

    private void printMatchSummary(String tag, Integer lastMover, AmazonsMove lastMove, AlphaBetaSearch.SearchResult lastSearch) {
        int arrows = currentState != null ? currentState.countArrows() : -1;
        String moverLabel = lastMover == null ? "-" : sideLabel(lastMover);
        String moveStr = lastMove == null ? "-" : lastMove.toString();

        String searchStr = "-";
        if (lastSearch != null) {
            searchStr = String.format("d=%d score=%d nodes=%d t=%dms",
                lastSearch.getDepth(), lastSearch.getScore(), lastSearch.getNodes(), lastSearch.getElapsedMillis());
        }

        long elapsed = matchStartMillis == 0L ? 0L : (System.currentTimeMillis() - matchStartMillis);

        System.out.println(String.format(
            "[%s] ply=%d time=%dms arrows=%d turn=%s me=%s lastMover=%s lastMove=%s search={%s}",
            tag, plyCount, elapsed, arrows, sideLabel(sideToMove), sideLabel(mySide), moverLabel, moveStr, searchStr
        ));
    }

    private int inferLosingSide(Map<String, Object> msgDetails) {
        String s = firstString(msgDetails, "loser", "Loser", "player", "PLAYER", "username", "userName", "name", "user");
        if (s != null) {
            if (blackPlayerName != null && s.equalsIgnoreCase(blackPlayerName)) return AmazonsBoardState.BLACK;
            if (whitePlayerName != null && s.equalsIgnoreCase(whitePlayerName)) return AmazonsBoardState.WHITE;
            if ("black".equalsIgnoreCase(s) || "b".equalsIgnoreCase(s)) return AmazonsBoardState.BLACK;
            if ("white".equalsIgnoreCase(s) || "w".equalsIgnoreCase(s)) return AmazonsBoardState.WHITE;
        }

        if (currentState != null) {
            boolean blackHasMoves = currentState.hasAnyMoves(AmazonsBoardState.BLACK);
            boolean whiteHasMoves = currentState.hasAnyMoves(AmazonsBoardState.WHITE);

            if (!blackHasMoves && whiteHasMoves) return AmazonsBoardState.BLACK;
            if (!whiteHasMoves && blackHasMoves) return AmazonsBoardState.WHITE;

            if (!blackHasMoves && !whiteHasMoves && sideToMove != AmazonsBoardState.NONE) return sideToMove;
        }

        return AmazonsBoardState.NONE;
    }

    private String sideToName(int side) {
        if (side == AmazonsBoardState.BLACK) return blackPlayerName != null ? blackPlayerName : "Black";
        if (side == AmazonsBoardState.WHITE) return whitePlayerName != null ? whitePlayerName : "White";
        return "Unknown";
    }

    private String sideLabel(int side) {
        if (side == AmazonsBoardState.BLACK) return "BLACK";
        if (side == AmazonsBoardState.WHITE) return "WHITE";
        return "NONE";
    }

    private String firstString(Map<String, Object> m, String... keys) {
        if (m == null) return null;
        for (String k : keys) {
            Object v = m.get(k);
            if (v instanceof String && !((String) v).trim().isEmpty()) return (String) v;
        }
        for (Object v : m.values()) {
            if (v instanceof String && !((String) v).trim().isEmpty()) return (String) v;
        }
        return null;
    }

    private int resolveMySide() {
        if (userName == null) return AmazonsBoardState.NONE;
        if (userName.equals(blackPlayerName)) return AmazonsBoardState.BLACK;
        if (userName.equals(whitePlayerName)) return AmazonsBoardState.WHITE;
        return AmazonsBoardState.NONE;
    }

    private void refreshRoomInformation() {
        if (gameClient != null && gamegui != null && gameClient.getRoomList() != null) {
            gamegui.setRoomInformation(gameClient.getRoomList());
        }
    }

    private boolean shouldAutoPlay() {
        return gameActive && currentState != null && mySide != AmazonsBoardState.NONE && sideToMove == mySide;
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<Integer> coerceIntegerList(Object value) {
        return value instanceof ArrayList ? (ArrayList<Integer>) value : null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void log(String format, Object... args) {
        System.out.printf("[COSC322Test] " + format + "%n", args);
    }
}
