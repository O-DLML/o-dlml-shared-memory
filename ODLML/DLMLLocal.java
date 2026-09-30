import java.util.ArrayList;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.logging.Logger;

/**
 * DLMLLocal: version of the DLML protocol that uses Java threads instead of MPI.
 *
 * Same public interface as DLML; only the initialization changes:
 *
 *   // Before (MPI):
 *   mpirun -np 4 java -cp ... MiApp
 *   DLML.Init(args);
 *   ... DLML.Get() / DLML.Reduce_Add() ...
 *   DLML.Finalize();
 *
 *   // Now (threads):
 *   DLMLLocal.setDataClass(MiDato.class);
 *   DLMLLocal.RunBody(4, () -> {
 *       ... DLMLLocal.Get() / DLMLLocal.Reduce_Add() ...
 *   });
 *
 * Advantages on a single node:
 *  - No JSON serialization to transfer DataLike between virtual processes.
 *  - No MPI sockets/pipes; control messages are inserts into a BlockingQueue.
 *  - Same load-balancing protocol (auction / round-robin / work-stealing).
 *  - No 2 GB limit from Jackson's ByteArrayBuilder on work data.
 */
public class DLMLLocal {

    private static final Logger logger = Logger.getLogger(DLMLLocal.class.getName());

    // ---- Message tags (identical to DLML so the protocol stays the same) ----
    static final int NO_HAY_DATOS       = 100;
    static final int PETICION_TAM_LISTA = 101;
    static final int INFORMACION_LISTA  = 102;
    static final int DAME_DATOS         = 103;
    static final int DATOS              = 104;
    static final int LISTA_VACIA        = 105;
    static final int DATOS_REMOTOS      = 106;
    static final int TAM_BUFFER         = 107;
    static final int LISTA_DE_DATOS     = 108;
    static final int FINALIZE           = 109;
    static final int TAM_LISTA          = 110;

    static final int ROOT = 0;

    // ---- Global configuration (set before RunBody) ----
    private static volatile Class<? extends DataLike> DATA_CLASS;
    private static volatile LoadBalancingStrategy     STRATEGY =
            StrategyFactory.create(StrategyType.AUCTION);

    // ---- Per-thread state: each virtual process has its own ----
    private static final ThreadLocal<ProcessState> TL_STATE = new ThreadLocal<>();

    // ---- Message bus shared by all virtual processes ----
    private static volatile SharedBus bus;

    // ---- Shared buffers for Reduce / Gather (indexed by id) ----
    // The thread barrier guarantees visibility before/after writing.
    private static Object[]  gatherBuffer;
    private static long[]    reduceLongBuffer;
    private static double[]  reduceDoubleBuffer;
    private static float[]   reduceFloatBuffer;

    // -----------------------------------------------------------------------
    //  Configuration (call BEFORE RunBody)
    // -----------------------------------------------------------------------

    public static void setDataClass(Class<? extends DataLike> cls) {
        DATA_CLASS = cls;
    }

    public static void setStrategy(LoadBalancingStrategy s) {
        if (s != null) STRATEGY = s;
    }

    public static Class<? extends DataLike> getDataClass() {
        return DATA_CLASS;
    }

    public static LoadBalancingStrategy getStrategy() {
        return STRATEGY;
    }

    private static void configureStrategyFromEnv() {
        String prop = System.getProperty("odlml.strategy");
        String env  = System.getenv("ODLML_STRATEGY");
        String pick = (prop != null && !prop.isEmpty()) ? prop : env;
        StrategyType t = StrategyType.fromString(pick);
        STRATEGY = StrategyFactory.create(t);
    }

    // -----------------------------------------------------------------------
    //  Per-thread accessors (equivalent to DLML.id / DLML.total as static fields)
    // -----------------------------------------------------------------------

    /** Rank of the current virtual process (0..n-1). */
    public static int id() {
        ProcessState s = TL_STATE.get();
        return (s != null) ? s.id : 0;
    }

    /** Total number of virtual processes. */
    public static int total() {
        ProcessState s = TL_STATE.get();
        return (s != null) ? s.total : 1;
    }

    // -----------------------------------------------------------------------
    //  Main entry point
    // -----------------------------------------------------------------------

    /**
     * Runs {@code body} on {@code n} parallel threads that emulate n MPI processes.
     * Blocks until all threads finish.
     *
     * Equivalent to: mpirun -np n java ... + DLML.Init(args) + body + DLML.Finalize()
     *
     * @param n    number of virtual processes
     * @param body process code (lambda or method reference)
     */
    public static void RunBody(int n, DLMLOne body) throws InterruptedException {
        configureStrategyFromEnv();
        bus                = new SharedBus(n);
        gatherBuffer       = new Object[n];
        reduceLongBuffer   = new long[n];
        reduceDoubleBuffer = new double[n];
        reduceFloatBuffer  = new float[n];

        // Create and register the states before starting the threads
        ProcessState[] states = new ProcessState[n];
        for (int i = 0; i < n; i++) {
            states[i]          = new ProcessState(i, n);
            states[i].dataClass = DATA_CLASS;
            states[i].strategy  = STRATEGY;
            bus.registerState(i, states[i]);
        }

        Thread[] workers = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int rank = i;
            workers[i] = new Thread(() -> {
                TL_STATE.set(states[rank]);
                startProtocol(states[rank]);
                body.run();
                // Get() already waited on mutexEnd before returning null;
                // it must not be acquired again here.
            }, "dlml-worker-" + i);
        }

        for (Thread w : workers) w.start();
        for (Thread w : workers) w.join();
    }

    // -----------------------------------------------------------------------
    //  Data API — same signatures as DLML
    // -----------------------------------------------------------------------

    /**
     * Inserts an item at the head of the current process's local queue.
     * Equivalent to DLML.Insert(a).
     */
    public static <T extends DataLike> void Insert(T a) {
        ProcessState s = TL_STATE.get();
        synchronized (s.data) {
            s.data.addFirst(a);
        }
    }

    /**
     * Takes an item from the queue, starting the auction protocol if it is empty.
     * Returns null when no virtual process has any data left.
     * Equivalent to DLML.Get(cls).
     */
    public static <T extends DataLike> T Get(Class<T> cls) {
        ProcessState s = TL_STATE.get();

        // With a single process there is no auction: return straight from the local queue.
        // LISTA_VACIA with total=1 produces no PETICION_TAM_LISTA → deadlock.
        if (s.total == 1) {
            return tryRemoveFirst(s, cls);
        }

        // Restart ProtocolThread if another round is needed (same as DLML)
        if (s.flagEnd) {
            s.flagEnd = false;
            startProtocol(s);
        }

        if (s.flagInfo) {
            bus.send(s.id, s.id, TAM_LISTA, 0);
            acquireUninterruptibly(s.mutex);
        }

        if (!s.flag) {
            T item = tryRemoveFirst(s, cls);
            if (item != null) return item;

            bus.send(s.id, s.id, LISTA_VACIA, 0);
            acquireUninterruptibly(s.mutex);

            item = tryRemoveFirst(s, cls);
            if (item != null) return item;

            s.flagEnd = true;
            acquireUninterruptibly(s.mutexEnd);
            return null;
        } else {
            // flag=true: there is a pending DAME_DATOS from another process.
            // Do not take the item here (same as the MPI version): let the
            // ProtocolThread see the real size so it computes the correct split.
            boolean hasData;
            synchronized (s.data) { hasData = !s.data.isEmpty(); }
            bus.send(s.id, s.id, hasData ? LISTA_DE_DATOS : LISTA_VACIA, 0);
            acquireUninterruptibly(s.mutex);

            T item = tryRemoveFirst(s, cls);
            if (item != null) return item;

            s.flagEnd = true;
            acquireUninterruptibly(s.mutexEnd);
            return null;
        }
    }

    // -----------------------------------------------------------------------
    //  Collective operations — same signatures as DLML, no JSON serialization
    // -----------------------------------------------------------------------

    /** Sum of ints across all processes. Equivalent to DLML.Reduce_Add(int). */
    public static int Reduce_Add(int value) {
        ProcessState s = TL_STATE.get();
        if (s.total == 1) return value;
        reduceLongBuffer[s.id] = value;
        syncWorkersUninterruptibly();
        if (s.id != ROOT) return value;
        long acc = 0;
        for (long v : reduceLongBuffer) acc += v;
        return (int) acc;
    }

    /** Sum of doubles. Equivalent to DLML.Reduce_Add(double). */
    public static double Reduce_Add(double value) {
        ProcessState s = TL_STATE.get();
        if (s.total == 1) return value;
        reduceDoubleBuffer[s.id] = value;
        syncWorkersUninterruptibly();
        if (s.id != ROOT) return value;
        double acc = 0.0;
        for (double v : reduceDoubleBuffer) acc += v;
        return acc;
    }

    /** Sum of floats. Equivalent to DLML.Reduce_Add(float). */
    public static float Reduce_Add(float value) {
        ProcessState s = TL_STATE.get();
        if (s.total == 1) return value;
        reduceFloatBuffer[s.id] = value;
        syncWorkersUninterruptibly();
        if (s.id != ROOT) return value;
        float acc = 0.0f;
        for (float v : reduceFloatBuffer) acc += v;
        return acc;
    }

    /**
     * Generic reduction with a combiner. No serialization.
     * On root it returns the accumulated value; on non-root it returns the local one (same as DLML.Reduce).
     */
    @SuppressWarnings("unchecked")
    public static <T> T Reduce(T local, Class<T> cls, BinaryOperator<T> op) {
        ProcessState s = TL_STATE.get();
        if (s.total == 1) return local;
        gatherBuffer[s.id] = local;
        syncWorkersUninterruptibly();
        if (s.id != ROOT) return local;
        T acc = (T) gatherBuffer[0];
        for (int i = 1; i < s.total; i++)
            acc = op.apply(acc, (T) gatherBuffer[i]);
        return acc;
    }

    /**
     * Map reduction. Equivalent to DLML.ReduceMap.
     */
    @SuppressWarnings("unchecked")
    public static <K, V, M extends Map<K, V>> M ReduceMap(
            M local,
            Class<? extends Map> mapClass,
            Class<K> keyClass,
            Class<V> valueClass,
            BinaryOperator<M> op) {
        return (M) Reduce(local, (Class<M>) (Class<?>) mapClass,
                (a, b) -> op.apply((M) a, (M) b));
    }

    /**
     * Gathers one object from each process at root. No serialization.
     * Equivalent to DLML.Gather(o).
     */
    @SuppressWarnings("unchecked")
    public static <T> ArrayList<T> Gather(Object o) {
        ProcessState s = TL_STATE.get();
        ArrayList<T> result = new ArrayList<>();
        if (s.total == 1) { result.add((T) o); return result; }
        gatherBuffer[s.id] = o;
        syncWorkersUninterruptibly();
        if (s.id == ROOT)
            for (Object item : gatherBuffer) result.add((T) item);
        return result;
    }

    /** Synchronization barrier. Equivalent to DLML.Barrier(). */
    public static void Barrier() {
        ProcessState s = TL_STATE.get();
        if (s != null && s.total > 1) syncWorkersUninterruptibly();
    }

    /** Runs r only on the root process. Equivalent to DLML.OnlyOne(r). */
    public static void OnlyOne(DLMLOne r) {
        ProcessState s = TL_STATE.get();
        if (r != null && (s == null || s.id == ROOT)) r.run();
    }

    // -----------------------------------------------------------------------
    //  Internals
    // -----------------------------------------------------------------------

    private static void startProtocol(ProcessState state) {
        ProtocolThread<?> pt = new ProtocolThread<>(state, bus);
        pt.setDaemon(true);
        pt.setName("dlml-protocol-" + state.id);
        pt.start();
    }

    /** Safely takes the first element of s.data; null if it is empty. */
    @SuppressWarnings("unchecked")
    private static <T extends DataLike> T tryRemoveFirst(ProcessState s, Class<T> cls) {
        synchronized (s.data) {
            return s.data.isEmpty() ? null : cls.cast(s.data.removeFirst());
        }
    }

    private static void acquireUninterruptibly(java.util.concurrent.Semaphore sem) {
        try { sem.acquire(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void syncWorkersUninterruptibly() {
        try {
            bus.syncWorkers();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.BrokenBarrierException e) {
            throw new RuntimeException("Broken barrier in DLMLLocal", e);
        }
    }
}
