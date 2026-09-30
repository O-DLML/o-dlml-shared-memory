import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

public class AlcaldiaResolver {

    private static final String PROPERTY_NAME = "NOMGEO"; // ajusta si es necesario

    private static class PolygonData {
        String nombre;
        List<List<double[]>> polygons;  // lista de anillos (outer + holes)
        double minLat, maxLat, minLon, maxLon;
    }

    private final List<PolygonData> alcaldias = new ArrayList<>();

    public AlcaldiaResolver(String geojsonPath) {
        cargarGeoJSON(geojsonPath);
    }

    private void cargarGeoJSON(String path) {
        try (InputStream is = new FileInputStream(path)) {

            JSONObject root = new JSONObject(new JSONTokener(is));
            JSONArray features = root.getJSONArray("features");

            for (int i = 0; i < features.length(); i++) {

                JSONObject feature = features.getJSONObject(i);
                JSONObject properties = feature.getJSONObject("properties");
                JSONObject geometry = feature.getJSONObject("geometry");

                String nombre = properties.optString(PROPERTY_NAME, "UNKNOWN");
                String type = geometry.getString("type");

                PolygonData data = new PolygonData();
                data.nombre = nombre;
                data.polygons = new ArrayList<>();
                data.minLat = Double.MAX_VALUE;
                data.maxLat = -Double.MAX_VALUE;
                data.minLon = Double.MAX_VALUE;
                data.maxLon = -Double.MAX_VALUE;

                if (type.equals("Polygon")) {
                    procesarPolygon(geometry.getJSONArray("coordinates"), data);
                }
                else if (type.equals("MultiPolygon")) {
                    JSONArray multi = geometry.getJSONArray("coordinates");
                    for (int j = 0; j < multi.length(); j++) {
                        procesarPolygon(multi.getJSONArray(j), data);
                    }
                }

                alcaldias.add(data);
            }

        } catch (Exception e) {
            throw new RuntimeException("Error cargando GeoJSON de alcaldías: " + e.getMessage(), e);
        }
    }

    private void procesarPolygon(JSONArray coords, PolygonData data) {

        List<double[]> ring = new ArrayList<>();

        for (int i = 0; i < coords.length(); i++) {
            JSONArray ringCoords = coords.getJSONArray(i);

            List<double[]> anillo = new ArrayList<>();

            for (int j = 0; j < ringCoords.length(); j++) {
                JSONArray point = ringCoords.getJSONArray(j);

                double lon = point.getDouble(0);
                double lat = point.getDouble(1);

                anillo.add(new double[]{lat, lon});

                // actualizar bounding box
                data.minLat = Math.min(data.minLat, lat);
                data.maxLat = Math.max(data.maxLat, lat);
                data.minLon = Math.min(data.minLon, lon);
                data.maxLon = Math.max(data.maxLon, lon);
            }

            data.polygons.add(anillo);
        }
    }

    public String getAlcaldia(double lat, double lon) {


        for (PolygonData data : alcaldias) {

            // filtro rápido bounding box
            if (lat < data.minLat || lat > data.maxLat ||
                lon < data.minLon || lon > data.maxLon) {
                continue;
            }

            for (List<double[]> ring : data.polygons) {
                if (pointInPolygon(lat, lon, ring)) {
                    return data.nombre;
                }
            }
        }

        return null;
    }

    // Ray casting algorithm
    private boolean pointInPolygon(double lat, double lon, List<double[]> polygon) {

        boolean inside = false;

        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {

            double latI = polygon.get(i)[0];
            double lonI = polygon.get(i)[1];
            double latJ = polygon.get(j)[0];
            double lonJ = polygon.get(j)[1];

            boolean intersect =
                    ((lonI > lon) != (lonJ > lon)) &&
                    (lat < (latJ - latI) * (lon - lonI) / (lonJ - lonI) + latI);

            if (intersect)
                inside = !inside;
        }

        return inside;
    }
}
