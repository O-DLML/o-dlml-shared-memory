# dlml_nqueens — The N-queens problem

Solves the N-queens problem in a distributed way using O-DLML.
The search is breadth-first (BFS): the root process inserts the initial state into the
distributed list, and each worker takes partial states, expands the valid positions
and reinserts the new states. At the end, the partial solution counts are reduced
into the global total.

## Layout

```
dlml_nqueens/
├── Data.java                  # Board state (implements DataLike)
├── ApplicationLocal.java      # Threads version (DLMLLocal)
├── QueensSeq.java             # Sequential version — baseline
├── build-local.sh             # Compiles and runs ApplicationLocal
├── benchmark_local_speedup.sh # Measures speedup and efficiency
└── logging.properties         # Log level (protocol OFF by default)
```

## Board size configuration

The `TAM` value (board size) is set with the system property `-Dnqueens.tam=N` in both variants. The default is `TAM=16`.

Known solution counts for reference:

| TAM | Solutions |
|-----|-----------|
| 10  | 724 |
| 12  | 14,200 |
| 13  | 73,712 |
| 14  | 365,596 |
| 15  | 2,279,184 |
| 16  | 14,772,512 |

## Sequential version

```bash
# Compile and run (TAM=16 by default)
javac QueensSeq.java
java QueensSeq

# With a custom TAM
java -Dnqueens.tam=14 QueensSeq
```

Expected output:
```
TAM=14 Solutions: 365596
Total time: 312 ms
```

## Threads version — build and run

```bash
# 4 threads, TAM=16 (default)
./build-local.sh

# Custom number of threads
./build-local.sh 8

# With a different TAM
TAM=14 ./build-local.sh

# Or by hand
javac -cp "../ODLML/dist/dlml-local-1.0.jar" ApplicationLocal.java Data.java
java -Djava.util.logging.config.file=logging.properties -Dnqueens.tam=14 -cp ".:../ODLML/dist/dlml-local-1.0.jar" ApplicationLocal 4
```

Expected output:
```
TAM=14 Solutions: 365596
Total time: 134 ms
```

## Speedup and efficiency benchmark

The `benchmark_local_speedup.sh` script compares the two variants:

| Variant | Description |
|---|---|
| `sequential` | `QueensSeq` — a single thread, no DLML |
| `odlml` | `ApplicationLocal` — np Java threads with DLML |

It runs each combination `REPS` times and computes:
- **Speedup** = T_sequential / T_parallel
- **Efficiency** = Speedup / np

```bash
# Default configuration: TAM=17 | np=1,2,4,8 | 1 repetition
./benchmark_local_speedup.sh

# Skip the sequential baseline (slow for large TAM); speedup is then reported as "-"
RUN_SEQ=false ./benchmark_local_speedup.sh

# Custom
TAMS="14 15" NPS="1 2 4 8 16" REPS=5 ./benchmark_local_speedup.sh
```

The results are written to `speedup_results.csv`.

> **Note:** For small TAM values (≤ 12), the overhead of the auction protocol outweighs
> the gain from parallelism — real speedup shows up from TAM ≥ 14.

## DLMLLocal execution model

With `np=4`, the program creates **8** Java threads:

```
dlml-worker-0    dlml-worker-1    dlml-worker-2    dlml-worker-3
dlml-protocol-0  dlml-protocol-1  dlml-protocol-2  dlml-protocol-3
```

Each worker runs `calcularReinas()`. The auction protocol automatically redistributes
board states among workers when the load is uneven.

```
Process 0 inserts: Data(row=1)
                         │
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
     Worker 0        Worker 1        Worker 2 ...
     Get() → expand  Get() → expand  Get() → expand
     Insert(new)     Insert(new)     Insert(new)
         └───────────────┴───────────────┘
                         │
                 Reduce_Add(solParciales)
                 → global solTotal
```
