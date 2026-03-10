package ubc.cosc322;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import ygraph.ai.smartfox.games.amazons.AmazonsGameMessage;

public class AmazonsMove {
    private final int fromRow;
    private final int fromCol;
    private final int toRow;
    private final int toCol;
    private final int arrowRow;
    private final int arrowCol;

    public AmazonsMove(int fromRow, int fromCol, int toRow, int toCol, int arrowRow, int arrowCol) {
        this.fromRow = fromRow;
        this.fromCol = fromCol;
        this.toRow = toRow;
        this.toCol = toCol;
        this.arrowRow = arrowRow;
        this.arrowCol = arrowCol;
    }

    public int getFromRow() {
        return fromRow;
    }

    public int getFromCol() {
        return fromCol;
    }

    public int getToRow() {
        return toRow;
    }

    public int getToCol() {
        return toCol;
    }

    public int getArrowRow() {
        return arrowRow;
    }

    public int getArrowCol() {
        return arrowCol;
    }

    public ArrayList<Integer> toCurrentPosition() {
        return pair(fromRow, fromCol);
    }

    public ArrayList<Integer> toNewPosition() {
        return pair(toRow, toCol);
    }

    public ArrayList<Integer> toArrowPosition() {
        return pair(arrowRow, arrowCol);
    }

    public Map<String, Object> toMessageDetails() {
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put(AmazonsGameMessage.QUEEN_POS_CURR, toCurrentPosition());
        payload.put(AmazonsGameMessage.QUEEN_POS_NEXT, toNewPosition());
        payload.put(AmazonsGameMessage.ARROW_POS, toArrowPosition());
        return payload;
    }

    public static AmazonsMove fromMessage(Map<String, Object> msgDetails) {
        ArrayList<Integer> current = castPair(msgDetails.get(AmazonsGameMessage.QUEEN_POS_CURR));
        ArrayList<Integer> next = castPair(msgDetails.get(AmazonsGameMessage.QUEEN_POS_NEXT));
        ArrayList<Integer> arrow = castPair(msgDetails.get(AmazonsGameMessage.ARROW_POS));
        if (current == null || next == null || arrow == null) {
            throw new IllegalArgumentException("Incomplete move payload: " + msgDetails);
        }
        return new AmazonsMove(
            current.get(0).intValue(),
            current.get(1).intValue(),
            next.get(0).intValue(),
            next.get(1).intValue(),
            arrow.get(0).intValue(),
            arrow.get(1).intValue()
        );
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<Integer> castPair(Object value) {
        return value instanceof ArrayList ? (ArrayList<Integer>) value : null;
    }

    private static ArrayList<Integer> pair(int row, int col) {
        ArrayList<Integer> values = new ArrayList<Integer>(2);
        values.add(row);
        values.add(col);
        return values;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AmazonsMove)) {
            return false;
        }
        AmazonsMove move = (AmazonsMove) other;
        return fromRow == move.fromRow
            && fromCol == move.fromCol
            && toRow == move.toRow
            && toCol == move.toCol
            && arrowRow == move.arrowRow
            && arrowCol == move.arrowCol;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromRow, fromCol, toRow, toCol, arrowRow, arrowCol);
    }

    @Override
    public String toString() {
        return String.format(
            "(%d,%d)->(%d,%d) arrow (%d,%d)",
            fromRow, fromCol, toRow, toCol, arrowRow, arrowCol
        );
    }
}
