import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
public class AlertAgg implements DataLike {

    // =============================
    // Campos principales del evento
    // =============================

    // Identificador estable del evento (uuid o id)
    private String eventId;

    // Informacion descriptiva
    private String type;
    private String subtype;

    // =============================
    // Metricas temporales
    // =============================

    // Primer snapshot del dia en que aparece
    private long firstSeen;

    // Ultimo snapshot del dia en que aparece
    private long lastSeen;

    // Numero de observaciones (cuantos snapshots lo reportaron)
    private int count;

    // =============================
    // Para tener las coordinadas
    // =============================

    private double latitude;
    private double longitude;


    // =============================
    // Para calcular posicion promedio
    // =============================

    private double sumX;
    private double sumY;

    // =============================
    // Constructor vacio (obligatorio para Jackson)
    // =============================

    // =============================
    // Para tener la alcaldia
    // =============================

    private String alcaldia;


    public AlertAgg() {
    }

    // =============================
    // Constructor conveniente
    // =============================

    public AlertAgg(String eventId, long snapshotTime) {
        this.eventId = eventId;
        this.firstSeen = snapshotTime;
        this.lastSeen = snapshotTime;
        this.count = 1;
        this.sumX = 0.0;
        this.sumY = 0.0;
    }

    // =============================
    // Metodo de actualizacion
    // =============================

    public void update(long snapshotTime, double x, double y) {

        if (snapshotTime < this.firstSeen) {
            this.firstSeen = snapshotTime;
        }

        if (snapshotTime > this.lastSeen) {
            this.lastSeen = snapshotTime;
        }

        this.count++;
        this.sumX += x;
        this.sumY += y;
    }

    // =============================
    // Metodo de fusion (para Reduce)
    // =============================

    public void merge(AlertAgg other) {

        if (other.firstSeen < this.firstSeen) {
            this.firstSeen = other.firstSeen;
        }

        if (other.lastSeen > this.lastSeen) {
            this.lastSeen = other.lastSeen;
        }

        this.count += other.count;
        this.sumX += other.sumX;
        this.sumY += other.sumY;

        // Si no se habia definido type/subtype, tomar del otro
        if (this.type == null && other.type != null) {
            this.type = other.type;
        }

        if (this.subtype == null && other.subtype != null) {
            this.subtype = other.subtype;
        }
    }

    // =============================
    // Derivados utiles
    // =============================

    public long getDuration() {
        return lastSeen - firstSeen;
    }

    public double getAvgX() {
        return (count > 0) ? sumX / count : 0.0;
    }

    public double getAvgY() {
        return (count > 0) ? sumY / count : 0.0;
    }

    // =============================
    // Getters y Setters
    // =============================

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSubtype() {
        return subtype;
    }

    public void setSubtype(String subtype) {
        this.subtype = subtype;
    }

    public long getFirstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(long firstSeen) {
        this.firstSeen = firstSeen;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public double getSumX() {
        return sumX;
    }

    public void setSumX(double sumX) {
        this.sumX = sumX;
    }

    public double getSumY() {
        return sumY;
    }
    public void setSumY(double sumY) {
        this.sumY = sumY;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }
    public double getLatitude() {
        return latitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setAlcaldia(String alcaldia) {
        this.alcaldia = alcaldia;
    }

    public String getAlcaldia() {
        return alcaldia;
    }
}

