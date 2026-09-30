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
 * Sequential version of the image classifier.
 * Serves as the baseline for measuring the speedup of ApplicationLocal.
 *
 * Processes all batches one by one on a single thread, without DLMLLocal or MPI.
 */
class ApplicationLocalSeq {

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

    static int procesarLote(Data lote) throws IOException, InterruptedException {
        Path entradaJson = Path.of("lote_seq.json");
        Path salidaJson  = Path.of("resultado_seq.json");

        escribirJsonEntrada(entradaJson, lote);

        ProcessBuilder pb = new ProcessBuilder(
                PYTHON_BIN, SCRIPT_PYTHON,
                entradaJson.toString(),
                salidaJson.toString()
        );
        pb.redirectErrorStream(true);
        Process proceso = pb.start();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(proceso.getInputStream(), StandardCharsets.UTF_8))) {
            while (br.readLine() != null) { /* drain stdout */ }
        }

        int exitCode = proceso.waitFor();
        if (exitCode != 0)
            throw new RuntimeException("clasificar_lote.py failed for batch " + lote.getIdLote());

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

    public static void main(String[] args) throws IOException, InterruptedException {
        int tamLote = (args.length > 0) ? Integer.parseInt(args[0]) : TAM_LOTE_DEFAULT;

        long inicio = System.nanoTime();

        List<String> imagenes = obtenerImagenes(DIRECTORIO_IMAGENES);
        List<Data> lotes = construirLotes(imagenes, tamLote);
        System.out.println("Total images:  " + imagenes.size());
        System.out.println("Batch size:    " + tamLote);
        System.out.println("Total batches: " + lotes.size());

        int totalConAuto = 0;
        int i = 0;
        for (Data lote : lotes) {
            totalConAuto += procesarLote(lote);
            System.out.printf("  [%d/%d] batch %d processed%n", ++i, lotes.size(), lote.getIdLote());
        }

        System.out.println("Images with a car: " + totalConAuto);

        long ms = TimeUnit.MILLISECONDS.convert(System.nanoTime() - inicio, TimeUnit.NANOSECONDS);
        System.out.println("Total time: " + ms + " ms");
    }
}
