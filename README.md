# O-DLML — Object-based Dynamic Load-balancing Middleware Library

A Java library for **dynamic load balancing over shared memory**. It distributes work
items among threads through an auction protocol, so a thread that runs out of work can
request more from the others at run time.

This repository contains the library (`ODLML/`) and four example applications that use
it, each with an equivalent sequential version for measuring *speedup*.

#> **Historical note.** O-DLML started as an MPI-based implementation (`mpirun`, `mpijavac`).
#> The current version uses **shared memory with Java threads**: it requires neither MPI
#> nor `mpijavac`. The original MPI code is kept in [`legacy_mpi/`](legacy_mpi/) for
#> reference.

## Requirements

| Component | Version used |
|---|---|
| JDK | 21 (`javac`, `java`, `jar`) |
| Python | 3.x — only for the plotting scripts and the image classifier |

Java dependencies (`org.json`, Jackson) are bundled in `ODLML/lib/`; nothing needs to
be downloaded.

Python dependencies per example:
- Plots (`plot_load_distribution.py`, `plot_speedup.py`): `pandas`, `matplotlib`
- Cluster map (`dlml_traffic_cluster/plot_clusters.py`): `pandas`, `folium`
- Image classifier (`clasificar*.py`): `ultralytics`, `opencv-python`, `numpy`

## Getting started

```bash
git clone git@github.com:O-DLML/o-dlml-shared-memory.git
cd o-dlml-shared-memory
```

### 1. Build the library

```bash
cd ODLML
./build-dlml.sh
```

This produces the following in `ODLML/dist/` (not versioned; regenerated on each build):

| JAR | Contents |
|---|---|
| `dlml-local-1.0.jar` | O-DLML classes only, no external dependencies |
| `dlml-local-fat-1.0.jar` | O-DLML + `org.json`, for the examples that read JSON |

### 2. Run an example

Each example ships a `build-local.sh` that compiles and runs in one step. The argument
is the number of threads (4 by default):

```bash
cd dlml_hola
./build-local.sh 8
```

## Repository layout

```
o-dlml-shared-memory/
├── ODLML/                  # The library (see ODLML/README.md)
│
├── dlml_hola/              # Minimal example: distributing 20 messages + a reduction
├── dlml_nqueens/           # N-queens via breadth-first search (irregular workload)
├── dlml_imagenes_autos/    # Car detection with YOLO over batches of images
└── dlml_traffic_cluster/   # Geographic clustering of traffic alerts (Waze, Mexico City)
#├── dlml_traffic_cluster/   # Geographic clustering of traffic alerts (Waze, Mexico City)
#│
#└── legacy_mpi/             # Original MPI versions (obsolete, do not compile)
```

## The examples

| Directory | Problem solved | Parallel version | Sequential baseline |
|---|---|---|---|
| [`dlml_hola`](dlml_hola/) | Message distribution and reduction — the API's "hello world" | `HelloDLMLLocal.java` | — |
| [`dlml_nqueens`](dlml_nqueens/) | N-queens (distributed BFS) | `ApplicationLocal.java` | `QueensSeq.java` |
| [`dlml_imagenes_autos`](dlml_imagenes_autos/) | Image classification with YOLO | `ApplicationLocal.java` | `ApplicationLocalSeq.java` |
| [`dlml_traffic_cluster`](dlml_traffic_cluster/) | Density and clustering of traffic alerts | `TrafficDLMLLocal.java` | `TrafficLocalSeq.java` |

`dlml_nqueens` and `dlml_traffic_cluster` are the interesting load-balancing cases: the
cost per item is highly uneven, so a static partition leaves threads idle.

## Load-balancing strategies

Selected with the system property `-Dodlml.strategy=<value>`:

| Value | Description |
|---|---|
| `auction` (default) | The idle thread asks for queue sizes and picks the most loaded donor |
| `round_robin` | The donor is chosen in circular order |
| `work_stealing` | The idle thread steals from the queue of the most loaded neighbor |

Protocol and API details are in [`ODLML/README.md`](ODLML/README.md).

## Measuring speedup

Each example includes `benchmark_local_speedup.sh`, which runs the sequential and the
parallel version with different thread counts and writes a CSV with time, *speedup* and
efficiency:

```bash
cd dlml_nqueens
./benchmark_local_speedup.sh
```

Result CSVs and logs are not versioned (they are regenerated on every run).

## Logging

The log level is set per example in `logging.properties`. To see the details of the
auction protocol, change the following in that file:

```properties
DLMLLocal.level = FINE
ProtocolThread.level = FINE
```

## Input data

The `dlml_imagenes_autos` and `dlml_traffic_cluster` examples read datasets that are
**not distributed with this repository** because of their size:

| Example | Expected data | Default path |
|---|---|---|
| `dlml_imagenes_autos` | Images to classify | `/home/local/D1_V2_B_2` |
| `dlml_traffic_cluster` | Waze JSON snapshots (Mexico City, 2019) | `/home/local/2019/` |

The paths are set in the constants at the top of each `ApplicationLocal.java` /
`TrafficDLMLLocal.java`. The other examples (`dlml_hola`, `dlml_nqueens`) need no
external data.
