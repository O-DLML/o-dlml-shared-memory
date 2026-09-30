public class CellAgg implements DataLike {

    private int count;
    private double sumaLat;
    private double sumaLon;

    public CellAgg() {
        this.count = 0;
    }

    public CellAgg(double lat, double lon) {
        this.count = 1;
        this.sumaLat = lat;
        this.sumaLon = lon;
    }

    // Used by TrafficDLMLLocal: increments the counter without coordinates
    public void inc() {
        this.count++;
    }

    // Used by TrafficLocalSeq: increments the counter and accumulates coordinates
    public void add(double lat, double lon) {
        this.count++;
        this.sumaLat += lat;
        this.sumaLon += lon;
    }

    public void merge(CellAgg other) {
        this.count += other.count;
        this.sumaLat += other.sumaLat;
        this.sumaLon += other.sumaLon;
    }

    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }

    public double getSumaLat() { return sumaLat; }
    public void setSumaLat(double sumaLat) { this.sumaLat = sumaLat; }

    public double getSumaLon() { return sumaLon; }
    public void setSumaLon(double sumaLon) { this.sumaLon = sumaLon; }

    public double getCentroLat() {
        return count == 0 ? 0.0 : sumaLat / count;
    }

    public double getCentroLon() {
        return count == 0 ? 0.0 : sumaLon / count;
    }
}
