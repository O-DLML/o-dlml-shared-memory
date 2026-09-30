import java.util.Arrays;
import java.util.Objects;

/**
 * Represents the state of a board in the N-queens problem.
 *
 * Each object stores:
 *  - An array of size TAM that holds, for each row (index 0..TAM-1),
 *    the column (1..TAM) where a queen was placed.
 *  - The number of the row currently being processed.
 *
 * This class is a POJO that implements DataLike and is serializable
 * with Jackson for distributed exchange with DLML.
 */
public class Data implements DataLike {

    /** Board size (number of queens). Configurable with -Dnqueens.tam=N */
    public static final int TAM = Integer.parseInt(System.getProperty("nqueens.tam", "16"));

    /** Array holding the position of the queen in each row. */
    private int[] tablero = new int[TAM];

    /** Current row to process (1..TAM). */
    private int renglon;

    /** Default constructor (required by Jackson). */
    public Data() {
    }

    /**
     * Constructor that initializes a state with the starting row.
     *
     * @param renglon starting row number
     */
    public Data(int renglon) {
        this.renglon = renglon;
    }

    /**
     * Constructor that takes an existing board and the current row.
     *
     * @param tablero array with the queen positions
     * @param renglon current row
     */
    public Data(int[] tablero, int renglon) {
        this.tablero = (tablero != null) ? tablero : new int[TAM];
        this.renglon = renglon;
    }

    /**
     * Returns the board of positions.
     *
     * @return int array with the queen positions
     */
    public int[] getTablero() {
        return tablero;
    }

    /**
     * Sets a new board of positions.
     *
     * @param tablero new array; if null, an empty one is used
     */
    public void setTablero(int[] tablero) {
        this.tablero = (tablero != null) ? tablero : new int[TAM];
    }

    /**
     * Returns the current row number.
     *
     * @return current row (1..TAM)
     */
    public int getRenglon() {
        return renglon;
    }

    /**
     * Sets the current row number.
     *
     * @param renglon new row value
     */
    public void setRenglon(int renglon) {
        this.renglon = renglon;
    }

    /**
     * Text representation of the board.
     *
     * @return string with the queen positions
     */
    @Override
    public String toString() {
        return Arrays.toString(tablero);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(tablero), renglon);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Data)) return false;
        Data other = (Data) obj;
        return renglon == other.renglon && Arrays.equals(tablero, other.tablero);
    }
}

