# dlml_hola — "Hello World" example

Minimal O-DLML example. It inserts `N=20` messages into the distributed queue and processes them across all threads. At the end it performs a reduction that sums the IDs.

## Layout

```
dlml_hola/
├── Data.java                  # POJO with a "mensaje" (message) field (implements DataLike)
├── HelloDLMLLocal.java        # Threads version (DLMLLocal)
├── build-local.sh             # Compiles and runs HelloDLMLLocal
├── benchmark_local_speedup.sh # Measures speedup and efficiency
└── logging.properties         # Log level (OFF by default)
```

## Build and run

```bash
# 4 threads (default)
./build-local.sh

# Custom number of threads
./build-local.sh 8
```

The script compiles against `../ODLML/dist/dlml-local-1.0.jar` and runs `HelloDLMLLocal`.

## What the program does

```
Thread 0 inserts: Mensaje0, Mensaje1, ..., Mensaje19
                          │
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
      Thread 0        Thread 1        Thread 2 ...
   Get() → process  Get() → process  Get() → process
          └───────────────┴───────────────┘
                          │
                 Reduce_Add(id+1)
                 Result = n*(n+1)/2
```

The auction protocol automatically redistributes the messages if one thread finishes before another.

## Thread model in DLMLLocal

With `np=4`, the program creates **8** Java threads:

```
dlml-worker-0    dlml-worker-1    dlml-worker-2    dlml-worker-3
dlml-protocol-0  dlml-protocol-1  dlml-protocol-2  dlml-protocol-3
```

Each worker runs the user's code. Each protocol thread handles the auction protocol with the other protocol threads through the `SharedBus`.

## Speedup benchmark

```bash
# Default configuration: N=40, sleep=200ms, np=1,2,4,8 | 3 repetitions
./benchmark_local_speedup.sh

# Custom
N=80 SLEEP_MS=100 NPS="1 2 4 8 16" REPS=5 ./benchmark_local_speedup.sh
```

The script measures speedup using np=1 as the baseline and varies the number of threads and the load-balancing strategy.
