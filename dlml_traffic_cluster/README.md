# dlml_traffic_cluster — Traffic analysis with geographic clustering

Analyzes Waze traffic alerts (Mexico City, 2019) in four phases:

1. **Event consolidation** — merges repeated occurrences of the same event across snapshots
2. **Per-cell density** — counts events per geographic cell (~200 m × 200 m)
3. **Cell clustering** — BFS over dense cells to detect congestion zones
4. **Per-cluster accumulation** — final metrics per zone (duration, observations, center)

Available in two variants:

| File | Description |
|---|---|
| `TrafficDLMLLocal.java` | Parallel, with O-DLML (Java threads, shared memory) |
| `TrafficLocalSeq.java` | Sequential — baseline without DLML |

## Layout

```
dlml_traffic_cluster/
├── Data.java                  # Work item: path to a JSON file (implements DataLike)
├── AlertAgg.java              # Aggregated traffic event (implements DataLike)
├── CellAgg.java               # Geographic cell with counter and coordinates (implements DataLike)
├── ClusterAgg.java            # Metrics of a geographic cluster (implements DataLike)
├── TrafficDLMLLocal.java      # Parallel version with O-DLML
├── TrafficLocalSeq.java       # Sequential version (baseline)
├── build-local.sh             # Compiles and runs TrafficDLMLLocal
├── benchmark_local_speedup.sh # Measures speedup vs. sequential
├── logging.properties         # Log level (protocol OFF by default)
├── cellToCluster.json         # Clustering model (generated at run time)
└── clusters.csv               # Final per-cluster results (generated at run time)
```

## Input data

The traffic JSON files must be in `/home/local/2019/` and follow this naming format:

```
CDMX_2019-01-20-09_15.json
```

Each file is a Waze snapshot with an `alerts` array containing events with the fields `uuid`, `type`, `subtype`, `location.x`, `location.y`.

## Build and run

```bash
# 4 threads (default)
./build-local.sh

# Custom number of threads
./build-local.sh 8

# Sequential version only
javac -cp "../ODLML/dist/dlml-local-1.0.jar:../ODLML/lib/json-20180813.jar" \
    AlertAgg.java CellAgg.java ClusterAgg.java TrafficLocalSeq.java
java -cp ".:../ODLML/dist/dlml-local-1.0.jar:../ODLML/lib/json-20180813.jar" TrafficLocalSeq
```

## Speedup and efficiency benchmark

The `benchmark_local_speedup.sh` script compares:

| Variant | Description |
|---|---|
| `sequential` | `TrafficLocalSeq` — a single thread, no DLML |
| `odlml` | `TrafficDLMLLocal` — np Java threads with DLML |

```bash
# Default configuration: np=1,2,4,8,16 | 5 repetitions | 3 strategies
./benchmark_local_speedup.sh

# Custom
NPS="1 2 4 8" REPS=3 STRATEGIES="auction" ./benchmark_local_speedup.sh
```

Output in `speedup_results.csv`:

```
version,strategy,np,rep,time_s,speedup,efficiency
sequential,-,1,1,45.123,1.0000,1.0000
odlml,auction,1,1,52.456,0.8603,0.8603
odlml,auction,4,1,15.234,2.9617,0.7404
odlml,auction,8,1,9.102,4.9575,0.6197
```

## Execution model

### TrafficDLMLLocal

With `np=4`:

```
Thread 0 inserts files (round-robin) for PHASE 1
              │
┌─────────────┼─────────────┐
▼             ▼             ▼
Worker 0   Worker 1   Worker 2 ...
contar()   contar()   contar()
              │
         ReduceMap() → consolidated events
              │
        Thread 0 inserts AlertAgg for PHASE 2
              │
┌─────────────┼─────────────┐
densidad_celda() per worker
              │
         ReduceMap() → densidadGlobal
              │
        Thread 0 runs BFS clustering (PHASE 3)
              │
        Thread 0 inserts AlertAgg for PHASE 4
              │
┌─────────────┼─────────────┐
acumulador_cluster() per worker
              │
         ReduceMap() → globalClusters → clusters.csv
```

Each phase uses a `DLMLLocal.ReduceMap()` to combine each thread's partial results.

### TrafficLocalSeq

Runs the same four phases sequentially, without DLML, on a single thread.
It serves as the reference point for measuring the speedup of the parallel version.
