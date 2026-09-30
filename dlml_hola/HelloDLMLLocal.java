/**
 * "Hello World" example using DLMLLocal (Java threads, no MPI).
 *
 * Equivalent to the former MPI HelloDLML.java, but run with:
 *   java -cp ... HelloDLMLLocal [num_threads]
 * instead of:
 *   mpirun -np N java -cp ... HelloDLML
 */
public class HelloDLMLLocal {

    public static void main(String[] args) throws InterruptedException {
        int  np      = (args.length > 0) ? Integer.parseInt(args[0]) : 4;
        int  N       = Integer.getInteger("hola.n", 20);
        long sleepMs = Long.getLong("hola.sleep", 1500);

        DLMLLocal.setDataClass(Data.class);

        long inicio = System.currentTimeMillis();

        DLMLLocal.RunBody(np, () -> {
            int id    = DLMLLocal.id();
            int total = DLMLLocal.total();

            DLMLLocal.OnlyOne(() -> {
                for (int i = 0; i < N; i++) {
                    DLMLLocal.Insert(new Data("Mensaje" + i));
                }
            });

            Data dato;
            while ((dato = DLMLLocal.Get(Data.class)) != null) {
                System.out.println("Thread " + id + " received: " + dato.getMensaje());
                try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }

            int parcial   = id + 1;
            int sumaTotal = DLMLLocal.Reduce_Add(parcial);

            DLMLLocal.OnlyOne(() ->
                System.out.println("Global sum of IDs+1 = " + sumaTotal
                        + "  (expected " + (total * (total + 1) / 2) + ")")
            );
        });

        long ms = System.currentTimeMillis() - inicio;
        System.out.println("Total time: " + ms + " ms");
    }
}
