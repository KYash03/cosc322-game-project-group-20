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
    private int sideToMove = AmazonsBoardState.NONE;
    private boolean gameActive;
    private String blackPlayerName;
    private String whitePlayerName;

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
            gameActive = false;
            log("Game over notification received: %s", msgDetails);
        } else if (GameMessage.GAME_TEXT_MESSAGE.equals(messageType)) {
            log("Text message: %s", msgDetails);
        } else if (USER_COUNT_CHANGE.equals(messageType) || GameMessage.GAME_STATE_JOIN.equals(messageType)) {
            refreshRoomInformation();
        } else {
            log("Unhandled message type %s: %s", messageType, msgDetails);
        }

        if (shouldMove) {
            maybeSendMove(messageType);
        }
        return true;
    }

    @Override
    public boolean handleMessage(String msg) {
        log("Server message: %s", msg);
        return true;
    }

    @Override
    public String userName() {
        return userName;
    }

    @Override
    public GameClient getGameClient() {
        return gameClient;
    }

    @Override
    public BaseGameGUI getGameGUI() {
        return gamegui;
    }

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
        sideToMove = currentState.inferSideToMove();
        if (gamegui != null) {
            gamegui.setGameState(encodedState);
        }

        log("Board synced. inferredTurn=%d arrows=%d", sideToMove, currentState.countArrows());
        return shouldAutoPlay();
    }

    private boolean handleGameStart(Map<String, Object> msgDetails) {
        blackPlayerName = stringValue(msgDetails.get(AmazonsGameMessage.PLAYER_BLACK));
        whitePlayerName = stringValue(msgDetails.get(AmazonsGameMessage.PLAYER_WHITE));
        mySide = resolveMySide();
        gameActive = true;

        ArrayList<Integer> encodedState = coerceIntegerList(msgDetails.get(AmazonsGameMessage.GAME_STATE));
        if (encodedState != null) {
            currentState = AmazonsBoardState.fromServerState(encodedState);
            if (gamegui != null) {
                gamegui.setGameState(encodedState);
            }
        }
        sideToMove = AmazonsBoardState.BLACK;

        log("Game start. black=%s white=%s mySide=%d turn=%d", blackPlayerName, whitePlayerName, mySide, sideToMove);
        return shouldAutoPlay();
    }

    private boolean handleMoveMessage(Map<String, Object> msgDetails) {
        AmazonsMove move = AmazonsMove.fromMessage(msgDetails);
        int mover = sideToMove;

        if (currentState != null && mover != AmazonsBoardState.NONE) {
            currentState.applyMove(move, mover);
            sideToMove = AmazonsBoardState.opponent(mover);
        }

        if (gamegui != null) {
            gamegui.updateGameState(msgDetails);
        }

        log("Move received from %d: %s. nextTurn=%d", mover, move, sideToMove);
        return shouldAutoPlay();
    }

    private void maybeSendMove(String trigger) {
        if (!shouldAutoPlay()) {
            return;
        }

        final AmazonsBoardState searchState = currentState.copy();
        final int turn = sideToMove;
        final AlphaBetaSearch search = new AlphaBetaSearch();
        final AlphaBetaSearch.SearchResult result = search.chooseMove(searchState, turn);

        if (result.getMove() == null) {
            gameActive = false;
            log("No legal move available on trigger %s. score=%d", trigger, result.getScore());
            return;
        }

        log(
            "Search(%s) depth=%d score=%d nodes=%d time=%dms move=%s",
            trigger,
            result.getDepth(),
            result.getScore(),
            result.getNodes(),
            result.getElapsedMillis(),
            result.getMove()
        );

        if (!shouldAutoPlay()) {
            return;
        }

        gameClient.sendMoveMessage(
                result.getMove().toCurrentPosition(),
                result.getMove().toNewPosition(),
                result.getMove().toArrowPosition()
            );
            if (currentState != null) {
                currentState.applyMove(result.getMove(), turn);
                sideToMove = AmazonsBoardState.opponent(turn);
            }
            if (gamegui != null) {
                gamegui.updateGameState(result.getMove().toMessageDetails());
            }
    }

    private int resolveMySide() {
        if (userName == null) {
            return AmazonsBoardState.NONE;
        }
        if (userName.equals(blackPlayerName)) {
            return AmazonsBoardState.BLACK;
        }
        if (userName.equals(whitePlayerName)) {
            return AmazonsBoardState.WHITE;
        }
        return AmazonsBoardState.NONE;
    }

    private void refreshRoomInformation() {
        if (gameClient != null && gamegui != null && gameClient.getRoomList() != null) {
            gamegui.setRoomInformation(gameClient.getRoomList());
        }
    }

    private boolean shouldAutoPlay() {
        return gameActive
            && currentState != null
            && mySide != AmazonsBoardState.NONE
            && sideToMove == mySide;
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
