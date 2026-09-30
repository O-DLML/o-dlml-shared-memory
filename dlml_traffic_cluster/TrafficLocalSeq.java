import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;

import java.nio.file.Paths;

import java.time.LocalDateTime;
import java.time.ZoneId;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;

public class TrafficLocalSeq {

    // =========================================================
    // General configuration
    // =========================================================

    static final String CARPETA = "/home/local/2019/";//"../dlml_traffic_maps/waze/";
    static final String ARCHIVO_DENSIDAD = "densidad_global.json";
    static final String ARCHIVO_CELL_TO_CLUSTER = "cellToCluster.json";
    static final String ARCHIVO_CLUSTERS = "clusters_finales.json";

    // Must match the configuration used in O-DLML
    static final double LAT0 = 19.194289;
    static final double LON0 = -99.321468;


    
    static final double DLAT = 0.0018;
    static final double DLON = 0.0021;
    static final int T = 5;    
    

    public static void main(String[] args) throws Exception {

        System.out.println("====================================");
        System.out.println("TrafficLocalSeq - sequential version without DLML");
        System.out.println("Folder: " + CARPETA);
        System.out.println("====================================");

        long t0 = System.nanoTime();

        File[] archivos = listarArchivosJson(CARPETA);
        System.out.println("JSON files detected: " + archivos.length);

        // -----------------------------------------------------
        // PHASE 1 - Consolidated events
        // -----------------------------------------------------
        long t1 = System.nanoTime();
        TreeMap<String, AlertAgg> eventos = contarSecuencial(archivos);
        long t2 = System.nanoTime();

        System.out.println("\n==============================");
        System.out.println(" PHASE 1 - CONSOLIDATED EVENTS");
        System.out.println("==============================");
        System.out.println("Total files processed: " + archivos.length);
        System.out.println("Total unique events: " + eventos.size());
        System.out.println("Top 10 events:");
        eventos.values().stream()
            .sorted((a, b) -> Integer.compare(b.getCount(), a.getCount()))
            .limit(10)
            .forEach(e -> {
                double durMin = e.getDuration() / 60000.0;
                String tipo = e.getType() == null ? "UNKNOWN" : e.getType();
                System.out.printf(
                    "%-25s %-15s obs=%6d durMin=%10.2f%n",
                    e.getEventId(), tipo, e.getCount(), durMin
                );
            });

        // -----------------------------------------------------
        // PHASE 2 - Density per cell
        // -----------------------------------------------------
        long t3 = System.nanoTime();
        TreeMap<String, CellAgg> densidadGlobal = densidadCeldaSecuencial(eventos.values());
        long t4 = System.nanoTime();

        guardarDensidadGlobal(densidadGlobal, ARCHIVO_DENSIDAD);

        System.out.println("\n==============================");
        System.out.println(" PHASE 2 - DENSITY PER CELL");
        System.out.println("==============================");
        System.out.println("Total cells with events: " + densidadGlobal.size());
        System.out.println("Top 10 cells by #events:");
        densidadGlobal.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue().getCount(), a.getValue().getCount()))
            .limit(10)
            .forEach(en -> System.out.println(en.getKey() + " -> " + en.getValue().getCount()));

        // -----------------------------------------------------
        // PHASE 3 - Clustering of dense cells
        // -----------------------------------------------------
        long t5 = System.nanoTime();
        TreeMap<String, Integer> cellToCluster = construirClustersDeCeldas(densidadGlobal, T);
        long t6 = System.nanoTime();

        guardarCellToCluster(ARCHIVO_CELL_TO_CLUSTER, cellToCluster);

        HashMap<Integer, Integer> clusterSizes = new HashMap<>();
        for (int c : cellToCluster.values()) {
            clusterSizes.merge(c, 1, Integer::sum);
        }

        System.out.println("\n==============================");
        System.out.println(" PHASE 3 - CELL CLUSTERING");
        System.out.println("==============================");
        System.out.println("Candidate cells (count >= " + T + "): " + cellToCluster.size());
        System.out.println("Clusters detected: " + clusterSizes.size());
        System.out.println("Top 10 clusters by #cells:");
        clusterSizes.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(10)
            .forEach(en ->
                System.out.println("cluster " + en.getKey() + " -> cells=" + en.getValue())
            );

        // -----------------------------------------------------
        // PHASE 4 - Final clusters
        // -----------------------------------------------------
        long t7 = System.nanoTime();
        TreeMap<Integer, ClusterAgg> globalClusters =
            acumularClustersSecuencial(eventos.values(), cellToCluster);
        long t8 = System.nanoTime();

        guardarClustersFinales(globalClusters, cellToCluster, ARCHIVO_CLUSTERS);

        System.out.println("\n==============================");
        System.out.println(" PHASE 4 - FINAL CLUSTERS");
        System.out.println("==============================");

        globalClusters.entrySet().stream()
            .filter(en -> en.getKey() >= 0)
            .sorted((a, b) -> Integer.compare(b.getValue().getEventos(), a.getValue().getEventos()))
            .limit(10)
            .forEach(en -> {
                ClusterAgg c = en.getValue();
                double minTotal = c.getDuracionTotalMs() / 60000.0;
                System.out.printf(
                    "Cluster %d -> events=%d  obs=%d  minTotal=%.2f%n",
                    en.getKey(),
                    c.getEventos(),
                    c.getObservaciones(),
                    minTotal
                );
            });

        long t9 = System.nanoTime();

        System.out.println("\n==============================");
        System.out.println(" TIMINGS");
        System.out.println("==============================");
        System.out.printf("Phase 1 - event consolidation:  %.3f s%n", (t2 - t1) / 1e9);
        System.out.printf("Phase 2 - per-cell density:     %.3f s%n", (t4 - t3) / 1e9);
        System.out.printf("Phase 3 - cell clustering:      %.3f s%n", (t6 - t5) / 1e9);
        System.out.printf("Phase 4 - cluster accumulation: %.3f s%n", (t8 - t7) / 1e9);
        System.out.printf("Final export:                   %.3f s%n", (t9 - t8) / 1e9);
        System.out.printf("Total time:                     %.3f s%n", (t9 - t0) / 1e9);

        System.out.println("\nFiles generated:");
        System.out.println(" - " + ARCHIVO_DENSIDAD);
        System.out.println(" - " + ARCHIVO_CELL_TO_CLUSTER);
        System.out.println(" - " + ARCHIVO_CLUSTERS);
    }

    // =========================================================
    // PHASE 1 - Consolidate events sequentially
    // =========================================================

    static TreeMap<String, AlertAgg> contarSecuencial(File[] archivos) {
        TreeMap<String, AlertAgg> global = new TreeMap<>();

        for (File archivo : archivos) {
            procesarArchivo(archivo, global);
        }

        return global;
    }

    static void procesarArchivo(File archivo, TreeMap<String, AlertAgg> global) {
        long snapshotTime = parseSnapshotTimeFromFilename(archivo.getPath());

        try (InputStream is = new FileInputStream(archivo)) {

            JSONTokener tokener = new JSONTokener(is);
            JSONObject object = new JSONObject(tokener);

            JSONArray alerts = object.optJSONArray("alerts");
            if (alerts == null) {
                return;
            }

            for (int i = 0; i < alerts.length(); i++) {
                JSONObject alert = alerts.getJSONObject(i);

                String eventId = alert.optString("uuid", "");
                if (eventId.isEmpty()) {
                    eventId = alert.optString("id", "");
                }
                if (eventId.isEmpty()) {
                    continue;
                }

                String type = alert.optString("type", null);
                String subtype = alert.optString("subtype", null);

                JSONObject loc = alert.optJSONObject("location");
                double x = (loc != null) ? loc.optDouble("x", 0.0) : 0.0;
                double y = (loc != null) ? loc.optDouble("y", 0.0) : 0.0;

                AlertAgg agg = global.get(eventId);

                if (agg == null) {
                    agg = new AlertAgg();
                    agg.setEventId(eventId);
                    agg.setType(type);
                    agg.setSubtype(subtype);
                    agg.setFirstSeen(snapshotTime);
                    agg.setLastSeen(snapshotTime);
                    agg.setCount(1);
                    agg.setSumX(x);
                    agg.setSumY(y);
                    agg.setLongitude(x);
                    agg.setLatitude(y);
                    global.put(eventId, agg);
                } else {
                    if (snapshotTime < agg.getFirstSeen()) {
                        agg.setFirstSeen(snapshotTime);
                    }
                    if (snapshotTime > agg.getLastSeen()) {
                        agg.setLastSeen(snapshotTime);
                    }

                    agg.setCount(agg.getCount() + 1);
                    agg.setSumX(agg.getSumX() + x);
                    agg.setSumY(agg.getSumY() + y);

                    if (agg.getType() == null && type != null) {
                        agg.setType(type);
                    }
                    if (agg.getSubtype() == null && subtype != null) {
                        agg.setSubtype(subtype);
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("Error processing file " + archivo.getPath() + ": " + e.getMessage());
        }
    }

    // =========================================================
    // PHASE 2 - Sequential per-cell density
    // =========================================================

    static TreeMap<String, CellAgg> densidadCeldaSecuencial(Collection<AlertAgg> eventos) {
        TreeMap<String, CellAgg> global = new TreeMap<>();

        for (AlertAgg e : eventos) {
            double lat = e.getLatitude();
            double lon = e.getLongitude();

            if (lat == 0.0 && lon == 0.0) {
                continue;
            }

            String cid = cellId(lat, lon);

            CellAgg agg = global.get(cid);
            if (agg == null) {
                global.put(cid, new CellAgg(lat, lon));
            } else {
                agg.add(lat, lon);
            }
        }

        return global;
    }

    // =========================================================
    // PHASE 3 - Cell clustering
    // =========================================================

    static TreeMap<String, Integer> construirClustersDeCeldas(TreeMap<String, CellAgg> densidadGlobal, int threshold) {
        HashSet<String> candidatas = new HashSet<>();

        for (Map.Entry<String, CellAgg> en : densidadGlobal.entrySet()) {
            if (en.getValue().getCount() >= threshold) {
                candidatas.add(en.getKey());
            }
        }

        TreeMap<String, Integer> cellToCluster = new TreeMap<>();
        HashSet<String> visited = new HashSet<>();
        int clusterId = 0;

        for (String start : candidatas) {
            if (visited.contains(start)) {
                continue;
            }

            ArrayDeque<String> q = new ArrayDeque<>();
            q.add(start);
            visited.add(start);

            while (!q.isEmpty()) {
                String cur = q.poll();
                cellToCluster.put(cur, clusterId);

                int[] xy = parseCell(cur);
                int cx = xy[0];
                int cy = xy[1];

                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }

                        String nb = (cx + dx) + "_" + (cy + dy);
                        if (!candidatas.contains(nb)) {
                            continue;
                        }
                        if (visited.add(nb)) {
                            q.add(nb);
                        }
                    }
                }
            }

            clusterId++;
        }

        return cellToCluster;
    }

    // =========================================================
    // PHASE 4 - Sequential per-cluster accumulation
    // =========================================================

    static TreeMap<Integer, ClusterAgg> acumularClustersSecuencial(
        Collection<AlertAgg> eventos,
        TreeMap<String, Integer> cellToCluster
    ) {
        TreeMap<Integer, ClusterAgg> global = new TreeMap<>();

        for (AlertAgg ev : eventos) {
            String cid = cellId(ev.getLatitude(), ev.getLongitude());
            Integer clusterId = cellToCluster.get(cid);
            if (clusterId == null) {
                clusterId = -1;
            }

            ClusterAgg agg = global.get(clusterId);
            if (agg == null) {
                agg = new ClusterAgg(clusterId);
                global.put(clusterId, agg);
            }

            agg.add(ev);
        }

        return global;
    }

    // =========================================================
    // Geographic utilities
    // =========================================================

    static String cellId(double lat, double lon) {
        int y = (int) Math.floor((lat - LAT0) / DLAT);
        int x = (int) Math.floor((lon - LON0) / DLON);
        return x + "_" + y;
    }

    static int[] parseCell(String cid) {
        int p = cid.indexOf('_');
        int x = Integer.parseInt(cid.substring(0, p));
        int y = Integer.parseInt(cid.substring(p + 1));
        return new int[]{x, y};
    }

    // =========================================================
    // File and time utilities
    // =========================================================

    static File[] listarArchivosJson(String carpeta) {
        File dir = new File(carpeta);

        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalStateException("Directory does not exist: " + carpeta);
        }

        File[] archivos = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".json"));

        if (archivos == null || archivos.length == 0) {
            throw new IllegalStateException("No JSON files found in: " + carpeta);
        }

        Arrays.sort(archivos, Comparator.comparing(File::getName));
        return archivos;
    }

    static long parseSnapshotTimeFromFilename(String path) {
        String name = Paths.get(path).getFileName().toString();

        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }

        int idx = name.indexOf('_');
        if (idx < 0 || idx == name.length() - 1) {
            throw new IllegalArgumentException("File name without the expected timestamp: " + path);
        }

        String ts = name.substring(idx + 1); // 2019-01-20-09_15

        String[] parts = ts.split("-");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid timestamp in file name: " + path);
        }

        String yyyy = parts[0];
        String mm = parts[1];
        String dd = parts[2];

        String[] hm = parts[3].split("_");
        if (hm.length != 2) {
            throw new IllegalArgumentException("Invalid hour/minute in file name: " + path);
        }

        int year = Integer.parseInt(yyyy);
        int month = Integer.parseInt(mm);
        int day = Integer.parseInt(dd);
        int hour = Integer.parseInt(hm[0]);
        int minute = Integer.parseInt(hm[1]);

        ZoneId zone = ZoneId.of("America/Mexico_City");
        LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, minute);
        return ldt.atZone(zone).toInstant().toEpochMilli();
    }

    // =========================================================
    // JSON export
    // =========================================================

    static void guardarCellToCluster(String path, TreeMap<String, Integer> cellToCluster) throws IOException {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, Integer> en : cellToCluster.entrySet()) {
            obj.put(en.getKey(), en.getValue());
        }

        try (FileWriter fw = new FileWriter(path)) {
            fw.write(obj.toString(2));
        }
    }

    static void guardarDensidadGlobal(TreeMap<String, CellAgg> densidadGlobal, String path) throws IOException {
        JSONObject root = new JSONObject();

        for (Map.Entry<String, CellAgg> en : densidadGlobal.entrySet()) {
            CellAgg c = en.getValue();

            JSONObject obj = new JSONObject();
            obj.put("count", c.getCount());
            obj.put("sumaLat", c.getSumaLat());
            obj.put("sumaLon", c.getSumaLon());
            obj.put("centroLat", c.getCentroLat());
            obj.put("centroLon", c.getCentroLon());

            root.put(en.getKey(), obj);
        }

        try (FileWriter fw = new FileWriter(path)) {
            fw.write(root.toString(2));
        }
    }

    static void guardarClustersFinales(
        TreeMap<Integer, ClusterAgg> globalClusters,
        TreeMap<String, Integer> cellToCluster,
        String path
    ) throws IOException {

        TreeMap<Integer, Integer> celdasPorCluster = new TreeMap<>();
        for (Map.Entry<String, Integer> en : cellToCluster.entrySet()) {
            int clusterId = en.getValue();
            celdasPorCluster.merge(clusterId, 1, Integer::sum);
        }

        JSONObject root = new JSONObject();

        for (Map.Entry<Integer, ClusterAgg> en : globalClusters.entrySet()) {
            int clusterId = en.getKey();

            // Group -1 is skipped: it holds events not assigned to any dense cluster.
            if (clusterId < 0) {
                continue;
            }

            ClusterAgg c = en.getValue();
            JSONObject obj = new JSONObject();

            obj.put("clusterId", clusterId);
            obj.put("eventos", c.getEventos());
            obj.put("observaciones", c.getObservaciones());
            obj.put("duracionTotalMs", c.getDuracionTotalMs());
            obj.put("minTotal", c.getDuracionTotalMs() / 60000.0);
            obj.put("numCeldas", celdasPorCluster.getOrDefault(clusterId, 0));
            obj.put("centroLat", c.getCentroLat());
            obj.put("centroLon", c.getCentroLon());

            root.put(String.valueOf(clusterId), obj);
        }

        try (FileWriter fw = new FileWriter(path)) {
            fw.write(root.toString(2));
        }
    }
}
