import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Solves the N-queens problem in a distributed way using DLMLLocal (Java threads, no MPI).
 *
 * Equivalent to the former MPI Application.java, but run with:
 *   java -cp ... ApplicationLocal [num_threads]
 * instead of:
 *   mpirun -np N java -cp ... Application
 *
 * The board size is set with -Dnqueens.tam=N (16 by default).
 */
class ApplicationLocal {

    /**
     * Checks whether placing a queen in column {@code col} of the current row
     * conflicts with the queens already placed.
     */
    static boolean esComida(int col, Data tablero) {
        boolean comida = true;
        int i, j;
        int reng = tablero.getRenglon() - 1;

        i = reng - 1;
        while ((i >= 0) && (tablero.getTablero()[i] != col)) {
            i--;
        }
        if (i < 0) {
            i = reng - 1;
            j = col - 1;
            while ((i >= 0) && (j >= 0) && (tablero.getTablero()[i] != j)) {
                i--;
                j--;
            }
            if ((i < 0) || (j < 0)) {
                i = reng - 1;
                j = col + 1;
                while ((i >= 0) && (j <= Data.TAM) && (tablero.getTablero()[i] != j)) {
                    i--;
                    j++;
                }
                if ((i < 0) || (j > Data.TAM)) {
                    comida = false;
                }
            }
        }
        return comida;
    }

    /**
     * Explores the solution space by taking states from DLMLLocal.
     *
     * @return {numSol, datosProcessados} — solutions found and states consumed by this thread
     */
    static long[] calcularReinas() {
        int numSol = 0;
        long datosProcessados = 0;
        Data elem;
        while ((elem = DLMLLocal.Get(Data.class)) != null) {
            datosProcessados++;
            for (int col = 1; col <= Data.TAM; col++) {
                if (elem.getRenglon() < Data.TAM) {
                    if (!esComida(col, elem)) {
                        elem.getTablero()[elem.getRenglon() - 1] = col;
                        elem.setRenglon(elem.getRenglon() + 1);

                        Data nuevo = new Data(elem.getTablero().clone(), elem.getRenglon());
                        DLMLLocal.Insert(nuevo);

                        elem.setRenglon(elem.getRenglon() - 1);
                    }
                } else {
                    if (!esComida(col, elem)) {
                        elem.getTablero()[elem.getRenglon() - 1] = col;
                        numSol += 1;
                    }
                }
            }
        }
        return new long[]{numSol, datosProcessados};
    }

    public static void main(String[] args) throws InterruptedException {
        int np = (args.length > 0) ? Integer.parseInt(args[0]) : 4;

        DLMLLocal.setDataClass(Data.class);

        long inicio = System.nanoTime();

        DLMLLocal.RunBody(np, () -> {
            DLMLLocal.OnlyOne(() -> DLMLLocal.Insert(new Data(1)));
            long[] resultado = calcularReinas();
            int solParciales = (int) resultado[0];
            long datosLocal  = resultado[1];

            int solTotal = DLMLLocal.Reduce_Add(solParciales);
            ArrayList<Long> datosPorHilo = DLMLLocal.Gather(datosLocal);

            DLMLLocal.OnlyOne(() -> {
                System.out.println("TAM=" + Data.TAM + " Solutions: " + solTotal);
                StringBuilder sb = new StringBuilder("items_per_thread:");
                for (long cnt : datosPorHilo) sb.append(" ").append(cnt);
                System.out.println(sb);
            });
        });

        long ms = TimeUnit.MILLISECONDS.convert(System.nanoTime() - inicio, TimeUnit.NANOSECONDS);
        System.out.println("Total time: " + ms + " ms");
    }
}
