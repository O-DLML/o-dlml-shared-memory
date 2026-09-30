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

import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

class Traffic {

	/** Total global de archivos procesados (reduce de enteros). */
	static int totalProcesados = 0;

	/** Reportes únicos locales (id -> "AlertAgg"). */
	static final TreeMap<String, AlertAgg> reportesLocales = new TreeMap<>();

	static final TreeMap<String, TypeAgg> localPorTipo = new TreeMap<>();

	static final TreeMap<String, AlertAgg> localConAlcaldia = new TreeMap<>();

	static final TreeMap<String, AlcaldiaAgg> localPorAlcaldia = new TreeMap<>();

	private static long parseSnapshotTimeFromFilename(String path) {
		// Ejemplo: CDMX_2019-01-20-09_15.json
		String name = Paths.get(path).getFileName().toString();

		// Quitar extension
		int dot = name.lastIndexOf('.');
		if (dot > 0) {
			name = name.substring(0, dot);

		}

		// Tomar la ultima parte con fecha-hora: "2019-01-20-09_15"
		int idx = name.indexOf('_');

		if (idx < 0 || idx == name.length() - 1) {
			throw new IllegalArgumentException("Nombre de archivo sin timestamp esperado: " + path);
		}

		String ts = name.substring(idx + 1); // "2019-01-20-09_15"

		// Separar: yyyy-mm-dd-hh_mm
		// yyyy-mm-dd-hh_mm -> [yyyy, mm, dd, hh_mm]
		String[] parts = ts.split("-");
		if (parts.length != 4) {
			throw new IllegalArgumentException("Timestamp invalido en nombre de archivo: " + path + " length "
					+ parts.length + " ts " + ts + " name " + name);
		}

		String yyyy = parts[0];
		String mm = parts[1];
		String dd = parts[2];

		String[] hm = parts[3].split("_");
		if (hm.length != 2) {
			throw new IllegalArgumentException("Hora/minuto invalidos en nombre de archivo: " + path);
		}

		int year = Integer.parseInt(yyyy);
		int month = Integer.parseInt(mm);
		int day = Integer.parseInt(dd);
		int hour = Integer.parseInt(hm[0]);
		int minute = Integer.parseInt(hm[1]);

		// Ajusta zona si quieres; por default Mexico_City:
		ZoneId zone = ZoneId.of("America/Mexico_City");

		LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, minute);
		return ldt.atZone(zone).toInstant().toEpochMilli();
	}

	/** Procesa los archivos asignados y llena reportesLocales con IDs únicos. */
	static int contar() throws MPIException {
		int procesados = 0;
		Data elem;

		System.out.println(DLML.id + ": Iniciando conteo...");

		while ((elem = DLML.Get(Data.class)) != null) {
			String archivo = elem.getArchivo();

			// snapshotTime basado en el nombre: CDMX_2019-01-20-09_15.json -> epochMillis
			long snapshotTime = parseSnapshotTimeFromFilename(archivo);

			try (InputStream is = new FileInputStream(archivo)) {
				if (is == null) {
					throw new NullPointerException("No se puede abrir el archivo " + archivo);
				}

				JSONTokener tokener = new JSONTokener(is);
				JSONObject object = new JSONObject(tokener);

				JSONArray alerts = object.optJSONArray("alerts");
				if (alerts == null) {
					procesados++;
					continue;
				}

				for (int i = 0; i < alerts.length(); i++) {
					JSONObject alert = alerts.getJSONObject(i);

					// 1) id estable (ideal: uuid; fallback: id)
					String eventId = alert.optString("uuid", "");
					if (eventId.isEmpty()) {
						eventId = alert.optString("id", "");
					}
					if (eventId.isEmpty()) {
						continue;
					}

					// 2) Datos opcionales (si vienen)
					String type = alert.optString("type", null);
					String subtype = alert.optString("subtype", null);

					JSONObject loc = alert.optJSONObject("location");
					double x = (loc != null) ? loc.optDouble("x", 0.0) : 0.0;
					double y = (loc != null) ? loc.optDouble("y", 0.0) : 0.0;

					// 3) Actualizar acumulador local
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
						// actualizacion incremental
						if (snapshotTime < agg.getFirstSeen())
							agg.setFirstSeen(snapshotTime);
						if (snapshotTime > agg.getLastSeen())
							agg.setLastSeen(snapshotTime);
						agg.setCount(agg.getCount() + 1);
						agg.setSumX(agg.getSumX() + x);
						agg.setSumY(agg.getSumY() + y);

						// si quieres conservar type/subtype cuando vengan nulos:
						if (agg.getType() == null && type != null)
							agg.setType(type);
						if (agg.getSubtype() == null && subtype != null)
							agg.setSubtype(subtype);
					}
				}

				procesados++;

			} catch (IOException e) {
				System.err.println("Error de lectura en " + archivo + ": " + e.getMessage());
			}
		}

		return procesados;
	}

	/** Procesa los archivos asignados y llena reportesLocales con IDs únicos. */
	static int clasificar() throws MPIException {
		int procesados = 0;
		AlertAgg e;

		System.out.println(DLML.id + ": Iniciando conteo...");

		while ((e = DLML.Get(AlertAgg.class)) != null) {
			String type = e.getType();
			if (type == null || type.isEmpty())
				type = "UNKNOWN";
			TypeAgg agg = localPorTipo.get(type);
			if (agg == null) {
				agg = new TypeAgg(type);
				localPorTipo.put(type, agg);
			}
			agg.add(e);
			procesados++;
		}

		return procesados;
	}

	static int agregar_alcaldia() throws MPIException {
		int procesados = 0;
		AlertAgg e;
		AlcaldiaResolver resolver = new AlcaldiaResolver("waze/alcaldias.geojson");

		System.out.println(DLML.id + ": Iniciando conteo...");

		while ((e = DLML.Get(AlertAgg.class)) != null) {
			String alc = resolver.getAlcaldia(e.getLatitude(), e.getLongitude());
			e.setAlcaldia(alc == null ? "FUERA_CDMX" : alc);
			localConAlcaldia.put(e.getEventId(), e);
			procesados++;
		}
		return procesados;
	}

	static int clasificar_alcaldia() throws MPIException {
		int procesados = 0;

		AlertAgg e3;

		System.out.println(DLML.id + ": Iniciando conteo...");

		while ((e3 = DLML.Get(AlertAgg.class)) != null) {

			String alc = e3.getAlcaldia();
			if (alc == null || alc.isEmpty())
				alc = "FUERA_CDMX";

			AlcaldiaAgg agg = localPorAlcaldia.get(alc);
			if (agg == null) {
				agg = new AlcaldiaAgg(alc);
				localPorAlcaldia.put(alc, agg);
			}

			agg.add(e3);
			procesados++;
		}
		return procesados;
	}

	public static void main(String[] args) throws MPIException, IOException {
		DLML.setDataClass(Data.class); // Indica a la lib qué Data concreta se usa
		DLML.Init(args);

		int id = DLML.id;
		int total = DLML.total;
		double t0 = MPI.wtime();

		System.out.println(id + ": Iniciando carga de archivos...");

		final String carpeta = "waze/";

		File dir = new File(carpeta);

		if (!dir.exists() || !dir.isDirectory()) {
			throw new IllegalStateException("No existe el directorio: " + carpeta);
		}

		// Obtener todos los .json
		File[] archivos = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".json"));

		if (archivos == null || archivos.length == 0) {
			throw new IllegalStateException("No se encontraron archivos JSON en: " + carpeta);
		}

		// Ordenarlos por nombre (importante para mantener orden temporal)
		Arrays.sort(archivos, Comparator.comparing(File::getName));

		// Distribucion round-robin real
		for (int i = id; i < archivos.length; i += total) {
			DLML.Insert(new Data(archivos[i].getPath()));
		}

		// Procesamiento local
		/*
		 * Agrupar todas las repeticiones del mismo evento
		 * Antes
		 * Choque A
		 * Choque A
		 * Choque A
		 * Choque A
		 * Despues
		 * Choque A
		 * - empezo a las 09:00
		 * - termino a las 09:25
		 * - aparecio 6 veces
		 */
		int locales = contar();
		System.out.println(id + ": Archivos procesados localmente: " + locales);

		// Reduce global del conteo (entero)
		totalProcesados = DLML.Reduce_Add(locales);

		TreeMap<String, AlertAgg> all = DLML.ReduceMap(
				reportesLocales,
				TreeMap.class,
				String.class,
				AlertAgg.class,
				(a, b) -> {
					b.forEach((k, v) -> {
						AlertAgg cur = a.get(k);
						if (cur == null) {
							a.put(k, v);
						} else {
							cur.merge(v); // <- aqui esta la consolidacion real
						}
					});
					return a;
				});

		if (id == 0) {

			System.out.println("\n==============================");
			System.out.println(" FASE 1 - EVENTOS CONSOLIDADOS");
			System.out.println("==============================");
			System.out.println("Total de archivos procesados (global): " + totalProcesados);
			System.out.println("Total eventos unicos: " + all.size());
			System.out.println("---------------------------------------------------------------------");

			System.out.printf("Obs = cuántos snapshots lo reportaron.");
			System.out.printf("Duracion = cuánto tiempo estuvo activo.");
			System.out.printf("%-20s %-15s %10s %12s%n", "EventId", "Tipo", "Obs", "Duracion(min)");
			System.out.println(
					"---------------------------------------------------------------------");

			int max = 10;
			int count = 0;
			for (AlertAgg e : all.values()) {
				if (count++ >= max)
					break;
				String tipo = (e.getType() == null) ? "UNKNOWN" : e.getType();
				double durMin = (e.getLastSeen() - e.getFirstSeen()) / 60000.0;

				System.out.printf("%-20s %-15s %10d %12.2f%n", e.getEventId(), tipo, e.getCount(), durMin);
			}

			System.out.println(
					"---------------------------------------------------------------------");
		}

		DLML.setDataClass(AlertAgg.class); // Indica a la lib qué Data concreta se usa

		if (id == 0) {

			for (AlertAgg e : all.values()) {
				DLML.Insert(e); // ahora la cola distribuida contiene AlertAgg
			}

		}

		/*
		 * Despues de fase 1
		 * Evento 1 - ACCIDENT (20 min)
		 * Evento 2 - JAM (15 min)
		 * Evento 3 - ACCIDENT (10 min)
		 * Evento 4 - JAM (30 min)
		 * Después de Fase 2:
		 * ACCIDENT:
		 * 2 eventos
		 * 30 min total
		 * 15 min promedio
		 * JAM:
		 * 2 eventos
		 * 45 min total
		 * 22.5 min promedio
		 * 
		 */

		// Procesamiento local
		locales = agregar_alcaldia();
		System.out.println(id + ": Datos procesados localmente agregando alcaldia: " + locales);

		totalProcesados = DLML.Reduce_Add(locales);

		TreeMap<String, AlertAgg> allConAlcaldia = DLML.ReduceMap(
				localConAlcaldia,
				TreeMap.class,
				String.class,
				AlertAgg.class,
				(a, b) -> {
					a.putAll(b);
					return a;
				});

		if (id == 0) {
			System.out.println("\n==============================");
			System.out.println(" FASE 2 - ALCALDIAS");
			System.out.println("==============================");
			System.out.println("Total eventos con alcaldia: " + allConAlcaldia.size());
			System.out.println("---------------------------------------------------------------------");

		}

		if (id == 0) {
			for (AlertAgg e : allConAlcaldia.values()) {
				DLML.Insert(e);
			}
		}

		locales = clasificar_alcaldia();
		System.out.println(id + ": Datos procesados localmente: " + locales);

		TreeMap<String, AlcaldiaAgg> globalPorAlcaldia = DLML.ReduceMap(
				localPorAlcaldia,
				TreeMap.class,
				String.class,
				AlcaldiaAgg.class,
				(a, b) -> {
					b.forEach((k, v) -> {
						AlcaldiaAgg cur = a.get(k);
						if (cur == null)
							a.put(k, v);
						else
							cur.merge(v);
					});
					return a;
				});

		if (id == 0) {
			System.out.println("\n==============================");
			System.out.println(" FASE 3 - RESUMEN POR ALCALDIA");
			System.out.println("==============================");
			System.out.printf("%-25s %10s %10s %15s %15s%n",
					"Alcaldia", "Eventos", "Obs", "Min total", "Min promedio");
			System.out.println("---------------------------------------------------------------------");

			globalPorAlcaldia.entrySet().stream()
					.sorted((e1, e2) -> Long.compare(
							e2.getValue().getDuracionTotalMs(),
							e1.getValue().getDuracionTotalMs()))
					.forEach(en -> {
						AlcaldiaAgg agg = en.getValue();
						double minTotal = agg.getDuracionTotalMs() / 60000.0;
						double minProm = agg.getDuracionPromedioMs() / 60000.0;

						System.out.printf("%-25s %10d %10d %15.2f %15.2f%n",
								en.getKey(),
								agg.getEventos(),
								agg.getObservaciones(),
								minTotal,
								minProm);
					});

			System.out.println("---------------------------------------------------------------------");
		}

		/*
		 * TreeMap<String, TypeAgg> globalPorTipo = DLML.ReduceMap(
		 * localPorTipo, // TreeMap<String, TypeAgg> local de cada proceso
		 * TreeMap.class,
		 * String.class,
		 * TypeAgg.class,
		 * (a, b) -> {
		 * b.forEach((k, v) -> {
		 * TypeAgg cur = a.get(k);
		 * if (cur == null) {
		 * a.put(k, v);
		 * } else {
		 * cur.merge(v); // consolidacion real por tipo
		 * }
		 * });
		 * return a;
		 * });
		 * 
		 * if (id == 0) {
		 * System.out.println("Total de entradas: " + globalPorTipo.size());
		 * System.out.println("Total de datos procesados (global): " + totalProcesados);
		 * System.out.println("\n==============================");
		 * System.out.println(" FASE 2 - RESUMEN POR TIPO");
		 * System.out.println("==============================");
		 * System.out.printf("%-25s %10s %10s %15s %15s%n",
		 * "Tipo", "Eventos", "Obs", "Min total", "Min promedio");
		 * System.out.println(
		 * "---------------------------------------------------------------------");
		 * 
		 * // Ordenar por minutos totales (descendente)
		 * globalPorTipo.entrySet().stream()
		 * .sorted((e1, e2) -> Long.compare(e2.getValue().getTotalDurationMs(),
		 * e1.getValue().getTotalDurationMs()))
		 * .forEach(e -> {
		 * String tipo = e.getKey();
		 * TypeAgg agg = e.getValue();
		 * 
		 * double minTotal = agg.getTotalDurationMs() / 60000.0;
		 * double minProm = agg.avgDurationMs() / 60000.0;
		 * 
		 * System.out.printf("%-25s %10d %10d %15.2f %15.2f%n",
		 * tipo,
		 * agg.getEvents(),
		 * agg.getObservations(),
		 * minTotal,
		 * minProm);
		 * });
		 * 
		 * System.out.println(
		 * "---------------------------------------------------------------------");
		 * }
		 */
		double t1 = MPI.wtime();
		System.out.printf("Tiempo total: %.3f segundos%n", (t1 - t0));

		DLML.Finalize();
	}
}
