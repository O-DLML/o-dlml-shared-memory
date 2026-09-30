# dlml_imagenes_autos — Image classification with car detection

Walks through a directory of images, groups them into **batches** and distributes the
batches among the O-DLML threads. Each thread invokes a Python script
(`clasificar_lote.py`) that runs YOLOv8 and splits the images into *with car* /
*without car*.

This is an irregular workload: the time a batch takes depends on the number of
detections and on image size, so dynamic distribution beats a static partition.

## Layout

```
dlml_imagenes_autos/
├── Data.java                  # Work batch: id + list of paths (implements DataLike)
├── ApplicationLocal.java      # Parallel version with O-DLML (Java threads)
├── ApplicationLocalSeq.java   # Sequential version — baseline
├── clasificar_lote.py         # Per-batch YOLO classifier (invoked by the Java versions)
├── clasificar.py              # Single-image classifier (manual use / debugging)
├── build-local.sh             # Compiles and runs ApplicationLocal
├── benchmark_local_speedup.sh # Measures speedup vs. sequential
└── logging.properties         # Log level (protocol OFF by default)
```

## Required configuration before running

The paths are constants at the top of `ApplicationLocal.java` **and**
`ApplicationLocalSeq.java` (they must be adjusted in both):

| Constant | Default value | Meaning |
|---|---|---|
| `DIRECTORIO_IMAGENES` | `/home/local/D1_V2_B_2` | Directory with the input images |
| `PYTHON_BIN` | `/home/proyecto_radar/traffic-image-filter-/.venv/bin/python3` | Python interpreter with `ultralytics` installed |
| `TAM_LOTE_DEFAULT` | `10` | Images per batch |

In `clasificar_lote.py`:

| Constant | Default value | Meaning |
|---|---|---|
| `MODELO_YOLO` | `yolov8n.pt` | Model weights (downloaded automatically the first time) |
| `CARPETA_SALIDA` | `/home/local/salida` | Output directory; `con_auto/` (with car) and `sin_auto/` (without car) are created in it |

Recognized image formats: `.jpg`, `.jpeg`, `.png`, `.bmp`, `.webp`.

### Python environment

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install ultralytics opencv-python numpy
```

Then point `PYTHON_BIN` to `<path>/.venv/bin/python3`.

## Build and run

```bash
# 4 threads, batches of 10 (defaults)
./build-local.sh

# 8 threads
./build-local.sh 8
```

The arguments of `ApplicationLocal` are `[num_threads] [batch_size]`:

```bash
javac -cp ../ODLML/dist/dlml-local-1.0.jar Data.java ApplicationLocal.java
java -Djava.util.logging.config.file=logging.properties \
     -cp ".:../ODLML/dist/dlml-local-1.0.jar" ApplicationLocal 8 20
```

### Sequential version

```bash
javac -cp ../ODLML/dist/dlml-local-1.0.jar Data.java ApplicationLocalSeq.java
java -cp ".:../ODLML/dist/dlml-local-1.0.jar" ApplicationLocalSeq 10   # batch_size
```

## How the work is distributed

```
images in the directory
        │
        ▼  construirLotes(paths, TAM_LOTE)
  [batch 0][batch 1][batch 2] ... [batch k]
        │
        ▼  thread 0 inserts them into the distributed list (OnlyOne)
   ┌────┴────┬─────────┬─────────┐
   ▼         ▼         ▼         ▼
Thread 0  Thread 1  Thread 2  Thread 3
   │         │         │         │
   └─ Get() → writes lote_t<id>.json
              → runs clasificar_lote.py
              → reads resultado_t<id>.json
   │
   ▼  a thread that runs out of work requests more from the others (auction)
```

Temporary files are **per thread** (`lote_t<id>.json`, `resultado_t<id>.json`) so that
threads do not overwrite each other. They are not versioned: they are regenerated on
every run.

## Benchmark

```bash
./benchmark_local_speedup.sh
```

Runs the sequential and the parallel version with different thread counts, and writes
`speedup_results.csv` (time, speedup, efficiency) and `load_distribution.csv`
(batches processed per thread, to see how even the distribution turned out).
