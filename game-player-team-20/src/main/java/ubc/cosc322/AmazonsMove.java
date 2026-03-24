package ubc.cosc322;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import ygraph.ai.smartfox.games.amazons.AmazonsGameMessage;

public final class AmazonsMove {
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

    public int getFromRow() { return fromRow; }
    public int getFromCol() { return fromCol; }
    public int getToRow() { return toRow; }
    public int getToCol() { return toCol; }
    public int getArrowRow() { return arrowRow; }
    public int getArrowCol() { return arrowCol; }

    public ArrayList<Integer> toCurrentPosition() { return pair(fromRow, fromCol); }
    public ArrayList<Integer> toNewPosition() { return pair(toRow, toCol); }
    public ArrayList<Integer> toArrowPosition() { return pair(arrowRow, arrowCol); }

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

    /**
     * 24-bit packed move (6 x 4-bit): fr<<20|fc<<16|tr<<12|tc<<8|ar<<4|ac
     */
    public static int pack(AmazonsMove m) {
        return (m.fromRow << 20)
            | (m.fromCol << 16)
            | (m.toRow << 12)
            | (m.toCol << 8)
            | (m.arrowRow << 4)
            | m.arrowCol;
    }

    public static AmazonsMove unpack(int packed) {
        int fr = (packed >>> 20) & 0xF;
        int fc = (packed >>> 16) & 0xF;
        int tr = (packed >>> 12) & 0xF;
        int tc = (packed >>> 8) & 0xF;
        int ar = (packed >>> 4) & 0xF;
        int ac = packed & 0xF;
        return new AmazonsMove(fr, fc, tr, tc, ar, ac);
    }

    private static ArrayList<Integer> pair(int row, int col) {
        ArrayList<Integer> v = new ArrayList<Integer>(2);
        v.add(row);
        v.add(col);
        return v;
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<Integer> castPair(Object value) {
        return value instanceof ArrayList ? (ArrayList<Integer>) value : null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AmazonsMove)) return false;
        AmazonsMove m = (AmazonsMove) other;
        return fromRow == m.fromRow
            && fromCol == m.fromCol
            && toRow == m.toRow
            && toCol == m.toCol
            && arrowRow == m.arrowRow
            && arrowCol == m.arrowCol;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromRow, fromCol, toRow, toCol, arrowRow, arrowCol);
    }

    @Override
    public String toString() {
        return String.format("(%d,%d)->(%d,%d) arrow (%d,%d)", fromRow, fromCol, toRow, toCol, arrowRow, arrowCol);
    }
}
