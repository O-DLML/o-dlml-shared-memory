import java.util.LinkedList;
import java.util.logging.Logger;

/**
 * DLML protocol thread for shared-memory mode (no MPI).
 *
 * Implements exactly the same state machine as Protocol.java:
 * auction, DAME_DATOS, LISTA_DE_DATOS, FINALIZE, etc.
 *
 * The only difference from Protocol is the transport:
 *  - Control messages (int[1])  →  SharedBus.send / recv
 *  - Data transfer              →  SharedBus.transferData  (no JSON, no bytes)
 *
 * @param <T> data type implementing DataLike
 */
class ProtocolThread<T extends DataLike> extends Thread {

    private static final Logger logger = Logger.getLogger(ProtocolThread.class.getName());

    private final ProcessState state;
    private final SharedBus    bus;
    private final LoadBalancingStrategy strategy;

    ProtocolThread(ProcessState state, SharedBus bus) {
        this.state    = state;
        this.bus      = bus;
        this.strategy = (state.strategy != null)
                ? state.strategy
                : StrategyFactory.create(StrategyType.AUCTION);
    }

    @Override
    public void run() {
        int c, r;
        int[] info = new int[state.total];

        LinkedList<Integer> requests = new LinkedList<>();
        LinkedList<Integer> ptl      = new LinkedList<>();

        int  finalizeCounter = 0;
        int  requestAnswers  = 0;
        boolean fsubasta  = false;
        boolean ffinalize = false;
        int  csubastas    = 0;

        try {
            while (finalizeCounter != state.total) {

                Msg msg = bus.recv(state.id);

                switch (msg.tag) {

                    // ------- LISTA_VACIA -------
                    case DLMLLocal.LISTA_VACIA:
                        logger.info(state.id + " LISTA_VACIA from " + msg.source);
                        r = requests.size();
                        for (int i = 0; i < r; i++) {
                            int idRemote = requests.remove();
                            bus.send(state.id, idRemote, DLMLLocal.NO_HAY_DATOS, 0);
                        }
                        if (state.flagInfo) {
                            state.flagInfo = false;
                            for (int p : ptl)
                                bus.send(state.id, p, DLMLLocal.INFORMACION_LISTA, 0);
                            ptl.clear();
                        }
                        fsubasta = true;
                        for (int i = 0; i < state.total; i++)
                            if (i != state.id)
                                bus.send(state.id, i, DLMLLocal.PETICION_TAM_LISTA, 0);
                        break;

                    // ------- PETICION_TAM_LISTA -------
                    case DLMLLocal.PETICION_TAM_LISTA:
                        logger.info(state.id + " PETICION_TAM_LISTA from " + msg.source);
                        if (!ffinalize) {
                            if (!fsubasta) {
                                state.flagInfo = true;
                                ptl.add(msg.source);
                            } else {
                                bus.send(state.id, msg.source, DLMLLocal.INFORMACION_LISTA, 0);
                            }
                        } else {
                            bus.send(state.id, msg.source, DLMLLocal.INFORMACION_LISTA, 0);
                        }
                        break;

                    // ------- TAM_LISTA -------
                    case DLMLLocal.TAM_LISTA:
                        logger.info(state.id + " TAM_LISTA from " + msg.source);
                        state.flagInfo = false;
                        int listSize = state.data.size();
                        for (int p : ptl)
                            bus.send(state.id, p, DLMLLocal.INFORMACION_LISTA, listSize);
                        ptl.clear();
                        state.mutex.release();
                        break;

                    // ------- INFORMACION_LISTA -------
                    case DLMLLocal.INFORMACION_LISTA:
                        info[msg.source] = msg.value;
                        requestAnswers++;
                        logger.info(state.id + " INFORMACION_LISTA from " + msg.source
                                + " val=" + msg.value + " answers=" + requestAnswers);

                        if (requestAnswers == (state.total - 1)) {
                            requestAnswers = 0;

                            int donor = strategy.selectDonor(info, state.id);

                            if (donor >= 0) {
                                logger.info(state.id + " send DAME_DATOS to " + donor);
                                bus.send(state.id, donor, DLMLLocal.DAME_DATOS, 0);
                                csubastas = 0;
                            } else {
                                if (csubastas < 2) {
                                    bus.send(state.id, state.id, DLMLLocal.LISTA_VACIA, 0);
                                    csubastas++;
                                } else {
                                    ffinalize = true;
                                    state.mutex.release();
                                    for (int i = 0; i < state.total; i++)
                                        if (i != state.id)
                                            bus.send(state.id, i, DLMLLocal.FINALIZE, 0);
                                    finalizeCounter++;

                                    r = requests.size();
                                    for (int i = 0; i < r; i++) {
                                        int idRemote = requests.remove();
                                        bus.send(state.id, idRemote, DLMLLocal.NO_HAY_DATOS, 0);
                                    }
                                    for (int p : ptl)
                                        bus.send(state.id, p, DLMLLocal.INFORMACION_LISTA, 0);
                                    ptl.clear();
                                }
                            }
                        }
                        break;

                    // ------- DATOS_REMOTOS (receiver) -------
                    // The data has already been placed in state.data by SharedBus.transferData().
                    // All that is left is to wake up the worker thread.
                    case DLMLLocal.DATOS_REMOTOS:
                        logger.info(state.id + " DATOS_REMOTOS from " + msg.source
                                + " count=" + msg.value);
                        fsubasta = false;
                        state.mutex.release();
                        break;

                    // ------- LISTA_DE_DATOS (distributor) -------
                    case DLMLLocal.LISTA_DE_DATOS:
                        logger.info(state.id + " LISTA_DE_DATOS from " + msg.source);
                        if (state.data.size() >= (requests.size() + 1)) {
                            // More items than requests: split into balanced batches
                            c = state.data.size() / (requests.size() + 1);
                            r = requests.size();
                            for (int i = 0; i < r; i++) {
                                int idRemote = requests.remove();
                                LinkedList<DataLike> batch = new LinkedList<>();
                                synchronized (state.data) {
                                    for (int j = 0; j < c; j++)
                                        batch.add(state.data.removeFirst());
                                }
                                // Direct transfer: no JSON serialization
                                bus.transferData(idRemote, batch);
                                bus.send(state.id, idRemote, DLMLLocal.DATOS_REMOTOS, c);
                            }
                        } else {
                            // Fewer items than requests: 1 to the first ones, nothing to the rest
                            r = state.data.size();
                            for (int i = 0; i < (r - 1); i++) {
                                int idRemote = requests.remove();
                                LinkedList<DataLike> batch = new LinkedList<>();
                                synchronized (state.data) {
                                    batch.add(state.data.removeFirst());
                                }
                                bus.transferData(idRemote, batch);
                                bus.send(state.id, idRemote, DLMLLocal.DATOS_REMOTOS, 1);
                            }
                            r = requests.size();
                            for (int i = 0; i < r; i++) {
                                int idRemote = requests.remove();
                                bus.send(state.id, idRemote, DLMLLocal.NO_HAY_DATOS, 0);
                            }
                        }
                        state.flag = false;
                        state.mutex.release();
                        break;

                    // ------- DAME_DATOS -------
                    case DLMLLocal.DAME_DATOS:
                        logger.info(state.id + " DAME_DATOS from " + msg.source);
                        if (!ffinalize) {
                            state.flag = true;
                            requests.add(msg.source);
                        } else {
                            bus.send(state.id, msg.source, DLMLLocal.NO_HAY_DATOS, 0);
                        }
                        break;

                    // ------- NO_HAY_DATOS -------
                    case DLMLLocal.NO_HAY_DATOS:
                        logger.info(state.id + " NO_HAY_DATOS from " + msg.source);
                        fsubasta = false;
                        bus.send(state.id, state.id, DLMLLocal.LISTA_VACIA, 0);
                        break;

                    // ------- FINALIZE -------
                    case DLMLLocal.FINALIZE:
                        finalizeCounter++;
                        logger.info(state.id + " FINALIZE from " + msg.source
                                + " counter=" + finalizeCounter);
                        break;

                    default:
                        System.out.println("ProtocolThread: unrecognized message tag="
                                + msg.tag + " from=" + msg.source);
                }
            }

            // Protocol barrier: all ProtocolThreads have finished
            bus.syncProtocols();
            state.mutexEnd.release();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("ProtocolThread[" + state.id + "] interrumpido: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("ProtocolThread[" + state.id + "] error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
