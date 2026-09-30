import java.util.concurrent.TimeUnit;

/**
 * Sequential version of the N-queens problem.
 * Serves as the baseline for computing the speedup of the DLMLLocal approach.
 *
 * Usage:
 *   java QueensSeq [TAM]
 *   java -Dnqueens.tam=14 QueensSeq
 */
class QueensSeq {

    static int TAM;
    static int[] tablero;

    static boolean esConflicto(int col, int reng) {
        for (int i = 0; i < reng; i++) {
            int c = tablero[i];
            if (c == col || c == col - (reng - i) || c == col + (reng - i))
                return true;
        }
        return false;
    }

    static long resolver(int reng) {
        if (reng == TAM) return 1L;
        long count = 0;
        for (int col = 1; col <= TAM; col++) {
            if (!esConflicto(col, reng)) {
                tablero[reng] = col;
                count += resolver(reng + 1);
            }
        }
        return count;
    }

    public static void main(String[] args) {
        TAM = (args.length > 0) ? Integer.parseInt(args[0])
                                : Integer.parseInt(System.getProperty("nqueens.tam", "16"));
        tablero = new int[TAM];

        long inicio = System.nanoTime();
        long soluciones = resolver(0);
        long fin = System.nanoTime();

        long ms = TimeUnit.MILLISECONDS.convert(fin - inicio, TimeUnit.NANOSECONDS);
        System.out.println("TAM=" + TAM + " Solutions: " + soluciones);
        System.out.println("Total time: " + ms + " ms");
    }
}
