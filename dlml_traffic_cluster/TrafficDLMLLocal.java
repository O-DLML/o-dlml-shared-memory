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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;

/**
 * Version of Traffic.java (see legacy_mpi/) that uses DLMLLocal (Java threads) instead of MPI.
 *
 * Changes with respect to Traffic.java:
 *  - No MPI imports; no MPIException.
 *  - DLML.id / DLML.total → DLMLLocal.id() / DLMLLocal.total()
 *  - DLML.Init + DLML.Finalize → DLMLLocal.RunBody(np, () -> { ... })
 *  - MPI.wtime() → System.nanoTime()
 *  - The per-process local maps (reportesLocales, localCells, localClusters)
 *    move from static fields to local variables inside the lambda,
 *    since with threads they all share the same JVM.
 *  - The contar/densidad_celda/acumulador_cluster methods receive the map
 *    as a parameter instead of reading a static field.
 */
public class TrafficDLMLLocal {

    static final String CARPETA = "/home/local/2019/";

    static final double LAT0 = 19.194289;
    static final double LON0 = -99.321468;
    static final double DLAT = 0.0018;
    static final double DLON = 0.0021;

    // -----------------------------------------------------------------------
    // Geographic and file utilities (no shared state)
    // -----------------------------------------------------------------------

    static String cellId(double lat, double lon) {
        int y = (int) Math.floor((lat - LAT0) / DLAT);
        int x = (int) Math.floor((lon - LON0) / DLON);
        return x + "_" + y;
    }

    static int[] parseCell(String cid) {
        int p = cid.indexOf('_');
        return new int[]{
            Integer.parseInt(cid.substring(0, p)),
            Integer.parseInt(cid.substring(p + 1))
        };
    }

    static long parseSnapshotTimeFromFilename(String path) {
        String name = Paths.get(path).getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        int idx = name.indexOf('_');
        if (idx < 0 || idx == name.length() - 1)
            throw new IllegalArgumentException("File name without a timestamp: " + path);
        String ts = name.substring(idx + 1);
        String[] parts = ts.split("-");
        if (parts.length != 4)
            throw new IllegalArgumentException("Invalid timestamp in: " + path);
        String[] hm = parts[3].split("_");
        if (hm.length != 2)
            throw new IllegalArgumentException("Invalid hour/minute in: " + path);
        LocalDateTime ldt = LocalDateTime.of(
            Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
            Integer.parseInt(parts[2]), Integer.parseInt(hm[0]), Integer.parseInt(hm[1]));
        return ldt.atZone(ZoneId.of("America/Mexico_City")).toInstant().toEpochMilli();
    }

    static void saveCellToCluster(String path, TreeMap<String, Integer> map) throws IOException {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, Integer> en : map.entrySet())
            obj.put(en.getKey(), en.getValue());
        try (FileWriter fw = new FileWriter(path)) {
            fw.write(obj.toString());
        }
    }

    static TreeMap<String, Integer> loadCellToCluster(String path) throws IOException {
        try (InputStream is = new FileInputStream(path)) {
            JSONObject obj = new JSONObject(new JSONTokener(is));
            TreeMap<String, Integer> map = new TreeMap<>();
            for (String key : obj.keySet())
                map.put(key, obj.getInt(key));
            return map;
        }
    }

    // -----------------------------------------------------------------------
    // PHASE 1: read the JSON files and consolidate unique events
    // The local map is received as a parameter (not a static field).
    // -----------------------------------------------------------------------

    static int contar(TreeMap<String, AlertAgg> reportesLocales) {
        int procesados = 0;
        System.out.println(DLMLLocal.id() + ": Starting count...");

        Data elem;
        while ((elem = DLMLLocal.Get(Data.class)) != null) {
            String archivo = elem.getArchivo();
            long snapshotTime = parseSnapshotTimeFromFilename(archivo);

            try (InputStream is = new FileInputStream(archivo)) {
                JSONObject object = new JSONObject(new JSONTokener(is));
                JSONArray alerts = object.optJSONArray("alerts");
                if (alerts == null) { procesados++; continue; }

                for (int i = 0; i < alerts.length(); i++) {
                    JSONObject alert = alerts.getJSONObject(i);

                    String eventId = alert.optString("uuid", "");
                    if (eventId.isEmpty()) eventId = alert.optString("id", "");
                    if (eventId.isEmpty()) continue;

                    String type    = alert.optString("type",    null);
                    String subtype = alert.optString("subtype", null);
                    JSONObject loc = alert.optJSONObject("location");
                    double x = (loc != null) ? loc.optDouble("x", 0.0) : 0.0;
                    double y = (loc != null) ? loc.optDouble("y", 0.0) : 0.0;

                    AlertAgg agg = reportesLocales.get(eventId);
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
                        reportesLocales.put(eventId, agg);
                    } else {
                        if (snapshotTime < agg.getFirstSeen()) agg.setFirstSeen(snapshotTime);
                        if (snapshotTime > agg.getLastSeen())  agg.setLastSeen(snapshotTime);
                        agg.setCount(agg.getCount() + 1);
                        agg.setSumX(agg.getSumX() + x);
                        agg.setSumY(agg.getSumY() + y);
                        if (agg.getType()    == null && type    != null) agg.setType(type);
                        if (agg.getSubtype() == null && subtype != null) agg.setSubtype(subtype);
                    }
                }
                procesados++;
            } catch (IOException e) {
                System.err.println("Read error in " + archivo + ": " + e.getMessage());
            }
        }
        return procesados;
    }

    // -----------------------------------------------------------------------
    // PHASE 2: compute event density per geographic cell
    // -----------------------------------------------------------------------

    static int densidad_celda(TreeMap<String, CellAgg> localCells) {
        int procesados = 0;
        System.out.println(DLMLLocal.id() + ": Starting per-cell density...");

        AlertAgg e2;
        while ((e2 = DLMLLocal.Get(AlertAgg.class)) != null) {
            double lat = e2.getLatitude();
            double lon = e2.getLongitude();
            if (lat == 0.0 && lon == 0.0) continue;

            String cid = cellId(lat, lon);
            CellAgg agg = localCells.get(cid);
            if (agg == null) {
                agg = new CellAgg();
                localCells.put(cid, agg);
            }
            agg.inc();
            procesados++;
        }
        return procesados;
    }

    // -----------------------------------------------------------------------
    // PHASE 4: assign each event to its cluster and accumulate metrics
    // -----------------------------------------------------------------------

    static int acumulador_cluster(TreeMap<Integer, ClusterAgg> localClusters,
                                   TreeMap<String, Integer> cellToClusterLocal) {
        int procesados = 0;
        System.out.println(DLMLLocal.id() + ": Starting cluster accumulation...");

        AlertAgg ev;
        while ((ev = DLMLLocal.Get(AlertAgg.class)) != null) {
            String cid      = cellId(ev.getLatitude(), ev.getLongitude());
            Integer clusterId = cellToClusterLocal.get(cid);
            if (clusterId == null) clusterId = -1;

            ClusterAgg agg = localClusters.get(clusterId);
            if (agg == null) {
                agg = new ClusterAgg(clusterId);
                localClusters.put(clusterId, agg);
            }
            agg.add(ev);
            procesados++;
        }
        return procesados;
    }

    // -----------------------------------------------------------------------
    // Main
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws InterruptedException {
        int np = (args.length > 0) ? Integer.parseInt(args[0]) : 4;

        System.out.println("TrafficDLMLLocal: " + np + " threads, folder=" + CARPETA);

        DLMLLocal.setDataClass(Data.class);

        DLMLLocal.RunBody(np, () -> {
            int id    = DLMLLocal.id();
            int total = DLMLLocal.total();
            long t0   = System.nanoTime();

            // Thread-local variables: equivalent to the static fields of Traffic.java,
            // but with threads each lambda has its own instance.
            TreeMap<String, AlertAgg>   reportesLocales = new TreeMap<>();
            TreeMap<String, CellAgg>    localCells      = new TreeMap<>();
            TreeMap<Integer, ClusterAgg> localClusters  = new TreeMap<>();

            System.out.println(id + ": Starting file loading...");

            File dir = new File(CARPETA);
            if (!dir.exists() || !dir.isDirectory())
                throw new RuntimeException("Directory does not exist: " + CARPETA);
            File[] archivos = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".json"));
            if (archivos == null || archivos.length == 0)
                throw new RuntimeException("No JSON files in: " + CARPETA);
            Arrays.sort(archivos, Comparator.comparing(File::getName));

            // Round-robin distribution (same as Traffic.java)
            for (int i = id; i < archivos.length; i += total)
                DLMLLocal.Insert(new Data(archivos[i].getPath()));

            // ----------------------------------------------------------------
            // PHASE 1 - Consolidate unique events
            // ----------------------------------------------------------------
            int locales1 = contar(reportesLocales);
            System.out.println(id + ": Files processed locally: " + locales1);

            int totalProc1 = DLMLLocal.Reduce_Add(locales1);
            ArrayList<Integer> loadPhase1 = DLMLLocal.Gather(locales1);

            TreeMap<String, AlertAgg> all = DLMLLocal.ReduceMap(
                reportesLocales, TreeMap.class, String.class, AlertAgg.class,
                (a, b) -> {
                    b.forEach((k, v) -> {
                        AlertAgg cur = a.get(k);
                        if (cur == null) a.put(k, v);
                        else cur.merge(v);
                    });
                    return a;
                });

            DLMLLocal.OnlyOne(() -> {
                System.out.println("\n==============================");
                System.out.println(" PHASE 1 - CONSOLIDATED EVENTS");
                System.out.println("==============================");
                System.out.println("Total files processed (global): " + totalProc1);
                System.out.println("Total unique events: " + all.size());
                System.out.println("---------------------------------------------------------------------");
                System.out.printf("%-20s %-15s %10s %12s%n", "EventId", "Type", "Obs", "Duration(min)");
                System.out.println("---------------------------------------------------------------------");
                int[] shown = {0};
                for (AlertAgg e : all.values()) {
                    if (shown[0]++ >= 10) break;
                    String tipo   = (e.getType() == null) ? "UNKNOWN" : e.getType();
                    double durMin = (e.getLastSeen() - e.getFirstSeen()) / 60000.0;
                    System.out.printf("%-20s %-15s %10d %12.2f%n",
                        e.getEventId(), tipo, e.getCount(), durMin);
                }
                System.out.println("---------------------------------------------------------------------");

                StringBuilder sbLoad1 = new StringBuilder("items_phase_1:");
                for (int cnt : loadPhase1) sbLoad1.append(" ").append(cnt);
                System.out.println(sbLoad1);

                // Insert events for phase 2
                for (AlertAgg e : all.values())
                    DLMLLocal.Insert(e);
            });

            // ----------------------------------------------------------------
            // PHASE 2 - Density per geographic cell
            // ----------------------------------------------------------------
            int locales2 = densidad_celda(localCells);
            System.out.println(id + ": Items processed in density phase: " + locales2);

            DLMLLocal.Reduce_Add(locales2);
            ArrayList<Integer> loadPhase2 = DLMLLocal.Gather(locales2);

            TreeMap<String, CellAgg> densidadGlobal = DLMLLocal.ReduceMap(
                localCells, TreeMap.class, String.class, CellAgg.class,
                (a, b) -> {
                    b.forEach((k, v) -> {
                        CellAgg cur = a.get(k);
                        if (cur == null) a.put(k, v);
                        else cur.merge(v);
                    });
                    return a;
                });

            DLMLLocal.OnlyOne(() -> {
                System.out.println("\n==============================");
                System.out.println(" PHASE 2 - DENSITY PER CELL");
                System.out.println("==============================");
                System.out.println("Total cells with events: " + densidadGlobal.size());
                System.out.println("Top 10 cells by #events:");
                densidadGlobal.entrySet().stream()
                    .sorted((p1, p2) -> Integer.compare(p2.getValue().getCount(), p1.getValue().getCount()))
                    .limit(10)
                    .forEach(en -> System.out.println(en.getKey() + " -> " + en.getValue().getCount()));

                StringBuilder sbLoad2 = new StringBuilder("items_phase_2:");
                for (int cnt : loadPhase2) sbLoad2.append(" ").append(cnt);
                System.out.println(sbLoad2);
            });

            // ----------------------------------------------------------------
            // PHASE 3 - Clustering of dense cells (BFS, root only)
            // ----------------------------------------------------------------
            final String modelPath = "cellToCluster.json";

            DLMLLocal.OnlyOne(() -> {
                final int T = 5;

                HashSet<String> cand = new HashSet<>();
                for (Map.Entry<String, CellAgg> en : densidadGlobal.entrySet())
                    if (en.getValue().getCount() >= T)
                        cand.add(en.getKey());

                System.out.println("\n==============================");
                System.out.println(" PHASE 3 - CELL CLUSTERING");
                System.out.println("==============================");
                System.out.println("Candidate cells (count >= " + T + "): " + cand.size());

                TreeMap<String, Integer> cellToCluster = new TreeMap<>();
                HashSet<String> visited = new HashSet<>();
                int clusterId = 0;

                for (String start : cand) {
                    if (visited.contains(start)) continue;
                    ArrayDeque<String> q = new ArrayDeque<>();
                    q.add(start);
                    visited.add(start);
                    while (!q.isEmpty()) {
                        String cur = q.poll();
                        cellToCluster.put(cur, clusterId);
                        int[] xy = parseCell(cur);
                        for (int dx = -1; dx <= 1; dx++)
                            for (int dy = -1; dy <= 1; dy++) {
                                if (dx == 0 && dy == 0) continue;
                                String nb = (xy[0] + dx) + "_" + (xy[1] + dy);
                                if (cand.contains(nb) && visited.add(nb))
                                    q.add(nb);
                            }
                    }
                    clusterId++;
                }

                System.out.println("Clusters detected: " + clusterId);
                HashMap<Integer, Integer> sizes = new HashMap<>();
                for (int c : cellToCluster.values()) sizes.merge(c, 1, Integer::sum);
                System.out.println("Top 10 clusters by #cells:");
                sizes.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .limit(10)
                    .forEach(en -> System.out.println("cluster " + en.getKey() + " -> cells=" + en.getValue()));

                try {
                    saveCellToCluster(modelPath, cellToCluster);
                    System.out.println("Model saved to: " + modelPath +
                        " (cells=" + cellToCluster.size() + ")");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            // Everyone waits until root has saved the file before reading it
            DLMLLocal.Barrier();

            TreeMap<String, Integer> cellToClusterLocal;
            try {
                cellToClusterLocal = loadCellToCluster(modelPath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            // Root inserts events for phase 4
            DLMLLocal.OnlyOne(() -> {
                for (AlertAgg e : all.values())
                    DLMLLocal.Insert(e);
            });

            // ----------------------------------------------------------------
            // PHASE 4 - Per-cluster accumulation
            // ----------------------------------------------------------------
            int locales4 = acumulador_cluster(localClusters, cellToClusterLocal);
            System.out.println(id + ": Items processed in accumulation phase: " + locales4);

            DLMLLocal.Reduce_Add(locales4);
            ArrayList<Integer> loadPhase4 = DLMLLocal.Gather(locales4);

            TreeMap<Integer, ClusterAgg> globalClusters = DLMLLocal.ReduceMap(
                localClusters, TreeMap.class, Integer.class, ClusterAgg.class,
                (a, b) -> {
                    b.forEach((k, v) -> {
                        ClusterAgg cur = a.get(k);
                        if (cur == null) a.put(k, v);
                        else cur.merge(v);
                    });
                    return a;
                });

            DLMLLocal.OnlyOne(() -> {
                System.out.println("\n==============================");
                System.out.println(" PHASE 4 - FINAL CLUSTERS");
                System.out.println("==============================");
                globalClusters.entrySet().stream()
                    .sorted((e1, e2) -> Integer.compare(e2.getValue().getEventos(), e1.getValue().getEventos()))
                    .limit(10)
                    .forEach(en -> {
                        ClusterAgg c = en.getValue();
                        double minTotal = c.getDuracionTotalMs() / 60000.0;
                        System.out.printf("Cluster %d -> events=%d  obs=%d  minTotal=%.2f%n",
                            en.getKey(), c.getEventos(), c.getObservaciones(), minTotal);
                    });

                try (FileWriter fw = new FileWriter("clusters.csv")) {
                    fw.write("cluster_id,eventos,obs,min_total,centro_lat,centro_lon\n");
                    for (Map.Entry<Integer, ClusterAgg> en : globalClusters.entrySet()) {
                        ClusterAgg c = en.getValue();
                        double minTotal = c.getDuracionTotalMs() / 60000.0;
                        fw.write(en.getKey() + "," + c.getEventos() + "," + c.getObservaciones() + "," +
                            String.format(java.util.Locale.US, "%.4f", minTotal) + "," +
                            String.format(java.util.Locale.US, "%.6f", c.getCentroLat()) + "," +
                            String.format(java.util.Locale.US, "%.6f", c.getCentroLon()) + "\n");
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                System.out.println("File generated: clusters.csv");

                StringBuilder sbLoad4 = new StringBuilder("items_phase_4:");
                for (int cnt : loadPhase4) sbLoad4.append(" ").append(cnt);
                System.out.println(sbLoad4);

                double tTotal = (System.nanoTime() - t0) / 1e9;
                System.out.printf("Total time: %.3f seconds%n", tTotal);
            });
        });
    }
}
