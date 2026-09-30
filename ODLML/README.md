# ODLML — The O-DLML library

Implementation of the **O-DLML** library (*Object-based Dynamic Load-balancing Middleware Library*), which dynamically distributes work among Java threads using an auction protocol over shared memory.

## Execution model

Each virtual process uses exactly **2 threads**:

```
┌──────────────────────────────────────────────────────────┐
│  Virtual process  i                                      │
│                                                          │
│  ┌──────────────────┐       semaphore        ┌─────────┐ │
│  │ application      │◄──── mutex/mutexEnd ──►│protocol │ │
│  │ thread           │                        │thread   │ │
│  │ (dlml-worker-i)  │                        │(dlml-   │ │
│  │  · runs the      │                        │protocol-│ │
│  │    user's code   │                        │i)       │ │
│  │  · calls Get()   │                        │· auction│ │
│  │    / Insert()    │                        │· DAME_  │ │
│  └──────────────────┘                        │  DATOS  │ │
│                                              └────┬────┘ │
└───────────────────────────────────────────────────│──────┘
                                                    │
                              ┌─────────────────────┘
                              │  control messages
                              ▼
                    ┌──────────────────┐
                    │  protocol thread │
                    │  process j ≠ i   │
                    └──────────────────┘
```

- The **application thread** runs the user's code (`Get()`, `Insert()`, …). When it calls `Get()` and its local queue is empty, it posts a message on the `SharedBus` and blocks on `mutex.acquire()`.
- The **protocol thread** (`ProtocolThread`) listens for messages on the `SharedBus`, runs the auction protocol (LISTA_VACIA → PETICION_TAM_LISTA → INFORMACION_LISTA → DAME_DATOS → DATOS_REMOTOS, i.e. empty list → list-size request → list info → give me data → remote data) and wakes the application thread up when it obtains data or when the work is finished.
- With `n` virtual processes there are **2n threads** in total (`dlml-worker-0..n-1` + `dlml-protocol-0..n-1`).

## Layout

```
ODLML/
├── DLMLLocal.java         # Public API — Java threads version
├── DLMLOne.java           # Functional interface (Runnable without checked exceptions)
├── DataLike.java          # Interface that work items must implement
├── ProtocolThread.java    # Protocol thread for DLMLLocal
├── SharedBus.java         # In-memory message bus (one BlockingQueue per process)
├── ProcessState.java      # Per-virtual-process state in DLMLLocal
├── Msg.java               # Control message {source, tag, value}
├── LoadBalancingStrategy.java  # Load-balancing strategy interface
├── AuctionStrategy.java        # Auction strategy (default)
├── RoundRobinStrategy.java     # Round-robin strategy
├── WorkStealingStrategy.java   # Work-stealing strategy
├── StrategyFactory.java        # Strategy factory
├── StrategyType.java           # Enum AUCTION | ROUND_ROBIN | WORK_STEALING
├── build-dlml.sh          # Builds the JARs
├── dist/
│   ├── dlml-local-1.0.jar       # DLMLLocal JAR (no external dependencies)
│   └── dlml-local-fat-1.0.jar   # DLMLLocal + org.json fat JAR (for examples using JSON)
└── lib/                   # json-*, jackson-* (fat JAR only)
```

## Building the JARs

```bash
cd ODLML
./build-dlml.sh
```

This produces two JARs:
- `dlml-local-1.0.jar` — O-DLML classes only (no dependencies)
- `dlml-local-fat-1.0.jar` — O-DLML + org.json (for examples that read JSON)

## API

```java
DLMLLocal.setDataClass(MyData.class);
DLMLLocal.RunBody(4, () -> {
    int id    = DLMLLocal.id();
    int total = DLMLLocal.total();

    DLMLLocal.OnlyOne(() -> DLMLLocal.Insert(new MyData(...)));
    MyData d = DLMLLocal.Get(MyData.class);   // null → no more work
    int sum   = DLMLLocal.Reduce_Add(partial);
    DLMLLocal.Barrier();
});
```

## Load-balancing strategies

Selectable with the system property `-Dodlml.strategy=<value>`:

| Value | Description |
|---|---|
| `auction` (default) | Auction: the idle process asks for sizes and picks the largest donor |
| `round_robin` | The donor is chosen in circular order |
| `work_stealing` | The idle process steals from the queue of the most loaded neighbor |

Or directly in code:
```java
DLMLLocal.setStrategy(StrategyFactory.create(StrategyType.AUCTION));
```
