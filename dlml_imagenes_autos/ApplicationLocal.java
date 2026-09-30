import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Car image classifier using DLMLLocal (Java threads, no MPI).
 *
 * Equivalent to the MPI Application.java (see legacy_mpi/), but run with:
 *   java -cp ... ApplicationLocal [num_threads] [batch_size]
 * instead of:
 *   mpirun -np N java -cp ... Application
 *
 * The image directory is set with the DIRECTORIO_IMAGENES constant.
 * The default batch size is set with TAM_LOTE_DEFAULT.
 */
class ApplicationLocal {

    static final String DIRECTORIO_IMAGENES = "/home/local/D1_V2_B_2";
    static final String SCRIPT_PYTHON = "clasificar_lote.py";
    static final String PYTHON_BIN = "/home/proyecto_radar/traffic-image-filter-/.venv/bin/python3";
    static final int TAM_LOTE_DEFAULT = 10;

    static List<String> obtenerImagenes(String directorio) throws IOException {
        List<String> rutas = new ArrayList<>();
        Files.list(Path.of(directorio))
                .filter(Files::isRegularFile)
                .map(Path::toString)
                .filter(ruta -> {
                    String r = ruta.toLowerCase();
                    return r.endsWith(".jpg") || r.endsWith(".jpeg") ||
                           r.endsWith(".png") || r.endsWith(".bmp") ||
                           r.endsWith(".webp");
                })
                .sorted()
                .forEach(rutas::add);
        return rutas;
    }

    static List<Data> construirLotes(List<String> rutas, int tamLote) {
        List<Data> lotes = new ArrayList<>();
        int id = 0;
        for (int i = 0; i < rutas.size(); i += tamLote) {
            int fin = Math.min(i + tamLote, rutas.size());
            lotes.add(new Data(id++, new ArrayList<>(rutas.subList(i, fin))));
        }
        return lotes;
    }

    static void escribirJsonEntrada(Path archivo, Data lote) throws IOException {
        StringBuilder sb = new StringBuilder("[\n");
        List<String> rutas = lote.getRutas();
        for (int i = 0; i < rutas.size(); i++) {
            sb.append("  {\"ruta\":\"")
              .append(rutas.get(i).replace("\\", "\\\\"))
              .append("\"}");
            if (i < rutas.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]\n");
        Files.writeString(archivo, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Runs the Python classifier on one batch.
     * Temporary files are per thread (lote_t<id>.json) to avoid conflicts
     * when several threads process in parallel.
     */
    static int procesarLote(Data lote) throws IOException, InterruptedException {
        int tid = DLMLLocal.id();
        Path entradaJson = Path.of("lote_t" + tid + ".json");
        Path salidaJson  = Path.of("resultado_t" + tid + ".json");

        escribirJsonEntrada(entradaJson, lote);

        ProcessBuilder pb = new ProcessBuilder(
                PYTHON_BIN, SCRIPT_PYTHON,
                entradaJson.toString(),
                salidaJson.toString()
        );
        pb.redirectErrorStream(true);
        Process proceso = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(proceso.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) output.append(line).append('\n');
        }

        int exitCode = proceso.waitFor();
        if (exitCode != 0)
            throw new RuntimeException("clasificar_lote.py failed for batch " + lote.getIdLote()
                    + " (exit=" + exitCode + "):\n" + output);

        return contarConAuto(salidaJson);
    }

    static int contarConAuto(Path archivoJson) throws IOException {
        String contenido = Files.readString(archivoJson, StandardCharsets.UTF_8);
        int contador = 0;
        int pos = 0;
        while ((pos = contenido.indexOf("\"clase\": \"con_auto\"", pos)) != -1) {
            contador++;
            pos += "\"clase\": \"con_auto\"".length();
        }
        return contador;
    }

    /**
     * Each thread consumes batches through DLMLLocal and processes them locally.
     * Returns {totalWithCar, batchesConsumed}.
     */
    static int[] clasificarImagenes() {
        int totalLocal = 0;
        int lotesLocal = 0;
        Data lote;
        while ((lote = DLMLLocal.Get(Data.class)) != null) {
            try {
                totalLocal += procesarLote(lote);
                lotesLocal++;
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Error processing batch " + lote.getIdLote(), e);
            }
        }
        return new int[]{totalLocal, lotesLocal};
    }

    public static void main(String[] args) throws InterruptedException {
        int np      = (args.length > 0) ? Integer.parseInt(args[0]) : 4;
        int tamLote = (args.length > 1) ? Integer.parseInt(args[1]) : TAM_LOTE_DEFAULT;

        DLMLLocal.setDataClass(Data.class);

        long inicio = System.nanoTime();

        DLMLLocal.RunBody(np, () -> {
            DLMLLocal.OnlyOne(() -> {
                try {
                    List<String> imagenes = obtenerImagenes(DIRECTORIO_IMAGENES);
                    List<Data> lotes = construirLotes(imagenes, tamLote);
                    for (Data lote : lotes) DLMLLocal.Insert(lote);
                    System.out.println("Total images:  " + imagenes.size());
                    System.out.println("Batch size:    " + tamLote);
                    System.out.println("Total batches: " + lotes.size());
                } catch (IOException e) {
                    throw new RuntimeException("Could not load the images", e);
                }
            });

            int[] resultado = clasificarImagenes();
            int totalLocal = resultado[0];
            int lotesLocal = resultado[1];

            int totalGlobal = DLMLLocal.Reduce_Add(totalLocal);

            // Gather how many batches each thread processed (for load analysis)
            ArrayList<Integer> lotesPorHilo = DLMLLocal.Gather(lotesLocal);

            DLMLLocal.OnlyOne(() -> {
                System.out.println("Images with a car: " + totalGlobal);
                // Format parsed by the benchmark for the load distribution
                StringBuilder sb = new StringBuilder("batches_per_thread:");
                for (int cnt : lotesPorHilo) sb.append(" ").append(cnt);
                System.out.println(sb);
            });
        });

        long ms = TimeUnit.MILLISECONDS.convert(System.nanoTime() - inicio, TimeUnit.NANOSECONDS);
        System.out.println("Total time: " + ms + " ms");
    }
}
