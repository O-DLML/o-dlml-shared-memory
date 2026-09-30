import java.util.LinkedList;
import java.util.concurrent.Semaphore;

/**
 * Per-virtual-process state (equivalent to the static fields of DLML,
 * but instantiated once per thread/process in DLMLLocal).
 */
class ProcessState {
    final int id;
    final int total;

    /** Local work queue — equivalent to DLML.data. */
    final LinkedList<DataLike> data = new LinkedList<>();

    /** Coordination flags — equivalent to DLML.flag / flagInfo / flagEnd. */
    volatile boolean flag     = false;
    volatile boolean flagInfo = false;
    volatile boolean flagEnd  = false;

    /** Synchronization semaphores — equivalent to DLML.mutex / mutexEnd. */
    final Semaphore mutex    = new Semaphore(0);
    final Semaphore mutexEnd = new Semaphore(0);

    /** Class of the data type (for the cast in DLMLLocal.Get). */
    Class<? extends DataLike> dataClass;

    /** Load-balancing strategy active for this process. */
    LoadBalancingStrategy strategy;

    ProcessState(int id, int total) {
        this.id    = id;
        this.total = total;
    }
}
