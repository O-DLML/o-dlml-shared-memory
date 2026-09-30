import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Shared-memory message bus: replaces MPI.COMM_WORLD.
 *
 * Each virtual process has a mailbox (BlockingQueue<Msg>).
 * send() puts into the destination's mailbox; recv() blocks until a message arrives.
 *
 * DataLike data is transferred with transferData(), which passes references
 * directly without serializing to JSON (the main advantage over MPI on shared memory).
 *
 * There are two separate barriers:
 *  - workerBarrier  : used by the worker threads (DLMLLocal.Barrier / Reduce / Gather)
 *  - protocolBarrier: used by the ProtocolThread threads when finishing
 */
class SharedBus {

    final int size;

    @SuppressWarnings("unchecked")
    private final BlockingQueue<Msg>[] queues;
    final ProcessState[] states;

    private final CyclicBarrier workerBarrier;
    private final CyclicBarrier protocolBarrier;

    @SuppressWarnings("unchecked")
    SharedBus(int size) {
        this.size  = size;
        queues     = new BlockingQueue[size];
        states     = new ProcessState[size];
        for (int i = 0; i < size; i++)
            queues[i] = new LinkedBlockingQueue<>();
        workerBarrier   = new CyclicBarrier(size);
        protocolBarrier = new CyclicBarrier(size);
    }

    void registerState(int id, ProcessState state) {
        states[id] = state;
    }

    /** Equivalent to MPI.COMM_WORLD.send(buf, 1, MPI.INT, dest, tag). */
    void send(int src, int dest, int tag, int value) {
        queues[dest].add(new Msg(src, tag, value));
    }

    /**
     * Equivalent to MPI.COMM_WORLD.recv(buf, 1, MPI.INT, MPI.ANY_SOURCE, MPI.ANY_TAG).
     * Blocks until any message is received.
     */
    Msg recv(int myId) throws InterruptedException {
        return queues[myId].take();
    }

    /**
     * Transfers a list of DataLike directly to the receiver's data mailbox,
     * without JSON serialization. Equivalent to the send(TAM_BUFFER) + send(DATOS_REMOTOS)
     * cycle of the MPI Protocol, but passes references instead of bytes.
     *
     * It is safe to call from the sender's ProtocolThread because the receiver's worker
     * thread is blocked on mutex.acquire() during the transfer.
     */
    void transferData(int toId, List<DataLike> items) {
        ProcessState receiver = states[toId];
        // The receiver's worker is blocked, so there is no race, but we synchronize
        // for clarity and to guarantee visibility of the write.
        synchronized (receiver.data) {
            receiver.data.addAll(items);
        }
    }

    /** Barrier for worker threads (DLMLLocal.Barrier / Reduce / Gather). */
    void syncWorkers() throws InterruptedException, BrokenBarrierException {
        workerBarrier.await();
    }

    /** Internal barrier for ProtocolThread threads when the protocol ends. */
    void syncProtocols() throws InterruptedException, BrokenBarrierException {
        protocolBarrier.await();
    }
}
