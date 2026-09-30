public class ClusterAgg implements DataLike {

    private int clusterId;
    private int eventos;
    private int observaciones;
    private long duracionTotalMs;
    private double sumLat;
    private double sumLon;

    public ClusterAgg() {
    }

    public ClusterAgg(int clusterId) {
        this.clusterId = clusterId;
        this.eventos = 0;
        this.observaciones = 0;
        this.duracionTotalMs = 0;
    }

    public void add(AlertAgg e) {
        eventos++;
        observaciones += e.getCount();

        long dur = e.getLastSeen() - e.getFirstSeen();
        if (dur < 0) {
            dur = 0;
        }
        duracionTotalMs += dur;

        sumLat += e.getLatitude();
        sumLon += e.getLongitude();
    }

    public double getSumLat() {
        return sumLat;
    }

    public void setSumLat(double sumLat) {
        this.sumLat = sumLat;
    }

    public double getSumLon() {
        return sumLon;
    }

    public void setSumLon(double sumLon) {
        this.sumLon = sumLon;
    }

    public void merge(ClusterAgg other) {
        this.eventos += other.eventos;
        this.observaciones += other.observaciones;
        this.duracionTotalMs += other.duracionTotalMs;
        this.sumLat += other.sumLat;
        this.sumLon += other.sumLon;
    }

    public double getCentroLat() {
        return (eventos > 0) ? (sumLat / eventos) : 0.0;
    }

    public double getCentroLon() {
        return (eventos > 0) ? (sumLon / eventos) : 0.0;
    }

    public int getClusterId() {
        return clusterId;
    }

    public int getEventos() {
        return eventos;
    }

    public int getObservaciones() {
        return observaciones;
    }

    public long getDuracionTotalMs() {
        return duracionTotalMs;
    }
}
