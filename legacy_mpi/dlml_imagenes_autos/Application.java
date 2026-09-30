import mpi.MPIException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

class Application {

    /** Directorio con imagenes de entrada. */
    static final String DIRECTORIO_IMAGENES = "/home/local/D1_V2_B_2";

    /** Script Python que clasifica un lote. */
    static final String SCRIPT_PYTHON = "clasificar_lote.py";

    /** Tamano del lote de imagenes. */
    static final int TAM_LOTE = 50;

    /**
     * Obtiene todas las rutas de imagenes validas dentro del directorio dado.
     *
     * @param directorio carpeta con imagenes
     * @return lista de rutas
     * @throws IOException si ocurre un error al leer archivos
     */
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

    /**
     * Divide una lista de rutas en lotes de tamano fijo.
     *
     * @param rutas lista completa de rutas
     * @param tamLote tamano del lote
     * @return lista de lotes Data
     */
    static List<Data> construirLotes(List<String> rutas, int tamLote) {
        List<Data> lotes = new ArrayList<>();
        int id = 0;

        for (int i = 0; i < rutas.size(); i += tamLote) {
            int fin = Math.min(i + tamLote, rutas.size());
            List<String> sublista = new ArrayList<>(rutas.subList(i, fin));
            lotes.add(new Data(id++, sublista));
        }

        return lotes;
    }

    /**
     * Escribe un archivo JSON simple con las rutas del lote.
     *
     * Formato:
     * [
     *   {"ruta":"img1.jpg"},
     *   {"ruta":"img2.jpg"}
     * ]
     *
     * @param archivo ruta de salida
     * @param lote lote a serializar
     * @throws IOException si ocurre un error al escribir
     */
    static void escribirJsonEntrada(Path archivo, Data lote) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");

        List<String> rutas = lote.getRutas();
        for (int i = 0; i < rutas.size(); i++) {
            sb.append("  {\"ruta\":\"")
              .append(rutas.get(i).replace("\\", "\\\\"))
              .append("\"}");
            if (i < rutas.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append("]\n");
        Files.writeString(archivo, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Ejecuta el clasificador Python para un lote y cuenta cuantas imagenes
     * fueron clasificadas como "con_auto".
     *
     * @param lote lote de imagenes
     * @return numero de imagenes clasificadas como con_auto
     * @throws IOException si ocurre un error de E/S
     * @throws InterruptedException si la ejecucion es interrumpida
     */
    static int procesarLote(Data lote) throws IOException, InterruptedException {
        Path entradaJson = Path.of("lote_" + lote.getIdLote() + ".json");
        Path salidaJson = Path.of("resultado_" + lote.getIdLote() + ".json");

        escribirJsonEntrada(entradaJson, lote);

        ProcessBuilder pb = new ProcessBuilder(
                "python3",
                SCRIPT_PYTHON,
                entradaJson.toString(),
                salidaJson.toString()
        );

        pb.redirectErrorStream(true);
        Process proceso = pb.start();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(proceso.getInputStream(), StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                System.out.println("[PYTHON] " + linea);
            }
        }

        int exitCode = proceso.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("El script Python termino con error para lote " + lote.getIdLote());
        }

        return contarConAuto(salidaJson);
    }

    /**
     * Cuenta ocurrencias de "con_auto" en el JSON de salida.
     *
     * Nota: para una version inicial se usa conteo simple de texto.
     * Despues puedes sustituirlo por Jackson si prefieres parseo formal.
     *
     * @param archivoJson archivo generado por Python
     * @return total de imagenes con auto
     * @throws IOException si ocurre un error al leer
     */
    static int contarConAuto(Path archivoJson) throws IOException {
        String contenido = Files.readString(archivoJson, StandardCharsets.UTF_8);
        int contador = 0;
        int pos = 0;

        while ((pos = contenido.indexOf("\"clase\":\"con_auto\"", pos)) != -1) {
            contador++;
            pos += "\"clase\":\"con_auto\"".length();
        }

        return contador;
    }

    /**
     * Cada worker consume lotes desde DLML y los procesa localmente.
     *
     * @return total local de imagenes con auto
     * @throws MPIException si ocurre un error en DLML/MPI
     */
    static int clasificarImagenes() throws MPIException {
        int totalLocal = 0;
        Data lote;

        while ((lote = DLML.Get(Data.class)) != null) {
            try {
                totalLocal += procesarLote(lote);
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Error procesando lote " + lote.getIdLote(), e);
            }
        }

        return totalLocal;
    }

    public static void main(String[] args) throws MPIException {
        DLML.setDataClass(Data.class);
        DLML.Init(args);

        long inicio = System.nanoTime();

        DLML.OnlyOne(() -> {
            try {
                List<String> imagenes = obtenerImagenes(DIRECTORIO_IMAGENES);
                List<Data> lotes = construirLotes(imagenes, TAM_LOTE);

                for (Data lote : lotes) {
                    DLML.Insert(lote);
                }

                System.out.println("Total de imagenes: " + imagenes.size());
                System.out.println("Total de lotes: " + lotes.size());
            } catch (IOException e) {
                throw new RuntimeException("No se pudieron cargar las imagenes", e);
            }
        });

        int totalLocal = clasificarImagenes();
        int totalGlobal = DLML.Reduce_Add(totalLocal);

        long fin = System.nanoTime();

        DLML.OnlyOne(() -> System.out.println("Imagenes clasificadas como con_auto: " + totalGlobal));

        long segundos = TimeUnit.SECONDS.convert(fin - inicio, TimeUnit.NANOSECONDS);
        System.out.println("Tiempo segundos: " + segundos);

        DLML.Finalize();
    }
}
