# legacy_mpi — Original MPI versions (obsolete)

Code from the period when O-DLML was implemented on top of **MPI** (Open MPI + `mpijavac`).
It is kept as a historical reference and to allow comparison with the current
shared-memory version.

> **This code does not compile with the current repository.** It depends on
> `mpi.MPIException` and on the `DLML.java` / `Protocol.java` classes, which were removed
> during the migration to Java threads. Those classes can be recovered from the git
> history:
>
> ```bash
> git show a6e1aa8:ODLML/DLML.java
> git show a6e1aa8:ODLML/Protocol.java
> ```

## Contents

```
legacy_mpi/
├── dlml_traffic/                  # First version of the traffic analysis (MPI)
│   ├── Traffic.java
│   ├── Data.java
│   └── logging.properties
│
├── dlml_traffic_maps/             # Variant with per-borough aggregation and folium maps
│   ├── Traffic.java
│   ├── AlcaldiaAgg.java           #   per-borough (alcaldía) aggregation
│   ├── AlcaldiaResolver.java      #   point → borough (point-in-polygon)
│   ├── AlertAgg.java
│   ├── TypeAgg.java
│   ├── Data.java
│   └── logging.properties
│
├── dlml_imagenes_autos/
│   └── Application.java           # MPI version of the image classifier
│
└── dlml_traffic_cluster/
    ├── benchmark_speedup.sh       # Benchmark using mpirun
    ├── AlcaldiaAgg.java           # Classes from the borough stage, no longer used
    ├── AlcaldiaResolver.java
    └── TypeAgg.java
```

## How it was run

```bash
export PATH=/opt/openmpi-5.0.8/bin:$PATH
export LD_LIBRARY_PATH=/opt/openmpi-5.0.8/lib:$LD_LIBRARY_PATH
export JAVA_HOME=/opt/jdk-21.0.1
export PATH=$JAVA_HOME/bin:$PATH

mpijavac -cp "../ODLML/dist/dlml-1.0-all.jar:." Data.java Application.java
mpirun -np 8 java --enable-native-access=ALL-UNNAMED \
    -Djava.util.logging.config.file=logging.properties \
    -Dodlml.strategy=auction \
    -cp "../ODLML/dist/dlml-1.0-all.jar:." Application
```

## Mapping to the current version

| MPI | Shared memory |
|---|---|
| `mpirun -np N java ... App` | `java ... AppLocal N` |
| `MPI.COMM_WORLD` | `SharedBus` |
| `MPI.COMM_WORLD.send/recv` | `SharedBus.send/recv` |
| `MPI.wtime()` | `System.nanoTime()` |
| `DLML.Init(args)` + body + `DLML.Finalize()` | `DLMLLocal.RunBody(n, () -> { ... })` |
| MPI process | Application thread + protocol thread |

For the current equivalent of each program, see the `dlml_*` directories at the root.
