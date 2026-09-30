// Traffic.java (versión sin Arbol.java)
// Requiere: Data.java (implements DataLike) y DLML con setDataClass(), Get(Data.class), Reduce_Add(int), Gather(Object).
// Lee waze/<i>.json con org.json; fusiona IDs de alertas en un TreeMap y reduce en root.

import mpi.MPI;
import mpi.MPIException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Map.Entry;
import java.util.TreeMap;
class Traffic {

    /** Total global de archivos procesados (reduce de enteros). */
    static int totalProcesados = 0;

    /** Reportes únicos locales (id -> ""). */
    static final TreeMap<String,String> reportesLocales = new TreeMap<>();

    /** Procesa los archivos asignados y llena reportesLocales con IDs únicos. */
    static int contar() throws MPIException {
        int procesados = 0;
        Data elem;

        System.out.println(DLML.id + ": Iniciando conteo...");

        while ((elem = DLML.Get(Data.class)) != null) {
            try (InputStream is = new FileInputStream(elem.getArchivo())) {
                if (is == null) {
                    throw new NullPointerException("No se puede abrir el archivo " + elem.getArchivo());
                }
                JSONTokener tokener = new JSONTokener(is);
                JSONObject object = new JSONObject(tokener);
                JSONArray alerts = object.getJSONArray("alerts");

                for (int i = 0; i < alerts.length(); i++) {
                    String id = alerts.getJSONObject(i).getString("id");
                    // Solo nos interesa la unicidad del id; guardamos "" como valor
                    reportesLocales.putIfAbsent(id, "");
                }
                procesados++;
            } catch (IOException e) {
                System.err.println("Error de lectura en " + elem.getArchivo() + ": " + e.getMessage());
            }
        }
        return procesados;
    }

    public static void main(String[] args) throws MPIException, IOException {
        DLML.setDataClass(Data.class);   // Indica a la lib qué Data concreta se usa
        DLML.Init(args);

        int id = DLML.id;
        int total = DLML.total;
        double t0 = MPI.wtime();

        System.out.println(id + ": Iniciando carga de archivos...");

        // Distribución round-robin de archivos
        final String prefijo = "waze/";
        final String extension = "json";
        final int N = 100; // ajusta si necesitas otro rango
        for (int i = id+1; i <= N; i += total) {
            String archivo = prefijo + i + "." + extension;
            DLML.Insert(new Data(archivo));
        }

        // Procesamiento local
        int locales = contar();
        System.out.println(id + ": Archivos procesados localmente: " + locales);

        // Reduce global del conteo (entero)
        totalProcesados = DLML.Reduce_Add(locales);

        @SuppressWarnings("unchecked")
        TreeMap<String,String> all = DLML.Reduce(reportesLocales, (Class<TreeMap<String,String>>) (Class<?>) TreeMap.class,
            (a,b)-> {
                b.forEach( (k,v)->a.putIfAbsent(k,v));
                return a;

            }
        );


        if (id == 0) {
            System.out.println("Total de entradas: " + all.size());
            System.out.println("Total de archivos procesados (global): " + totalProcesados);
        }

        // "Reduce" de objetos: Gather de TreeMap<String,String> y fusión en root
        // (Tu versión previa usaba Arbol y luego lo reunía y consolidaba. :contentReference[oaicite:2]{index=2}  También se define Arbol como envoltorio de TreeMap. :contentReference[oaicite:3]{index=3})
        @SuppressWarnings("unchecked")
        ArrayList<TreeMap<String,String>> mapas = DLML.<TreeMap<String,String>>Gather(reportesLocales);

        // En root, fusionar los mapas (reduce por unión de claves)
        DLML.OnlyOne(() -> {
            TreeMap<String,String> global = new TreeMap<>();
            for (TreeMap<String,String> m : mapas) {
                for (Entry<String,String> e : m.entrySet()) {
                    global.putIfAbsent(e.getKey(), e.getValue());
                }
            }
            System.out.println("Total de IDs únicos: " + global.size());
        });

        double t1 = MPI.wtime();
        System.out.printf("Tiempo total: %.3f segundos%n", (t1 - t0));

        DLML.Finalize();
    }
}
