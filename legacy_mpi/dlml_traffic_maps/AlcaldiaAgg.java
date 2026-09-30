import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)

public class AlcaldiaAgg implements DataLike {

    // Nombre o codigo de la alcaldia
    private String alcaldia;

    // Numero de eventos unicos
    private int eventos;

    // Numero total de observaciones (snapshots)
    private int observaciones;

    // Duracion acumulada en milisegundos
    private long duracionTotalMs;

    // =============================
    // Constructor vacio (Jackson)
    // =============================
    public AlcaldiaAgg() {
    }

    // =============================
    // Constructor conveniente
    // =============================
    public AlcaldiaAgg(String alcaldia) {
        this.alcaldia = alcaldia;
        this.eventos = 0;
        this.observaciones = 0;
        this.duracionTotalMs = 0;
    }

    // =============================
    // Metodo para agregar un evento
    // =============================
    public void add(AlertAgg e) {

        long duracion = e.getLastSeen() - e.getFirstSeen();
        if (duracion < 0) duracion = 0;

        eventos += 1;
        observaciones += e.getCount();
        duracionTotalMs += duracion;
    }

    // =============================
    // Metodo de fusion (para Reduce)
    // =============================
    public void merge(AlcaldiaAgg other) {

        if (this.alcaldia == null) {
            this.alcaldia = other.alcaldia;
        }

        this.eventos += other.eventos;
        this.observaciones += other.observaciones;
        this.duracionTotalMs += other.duracionTotalMs;
    }

    // =============================
    // Derivados utiles
    // =============================
    public double getDuracionPromedioMs() {
        return (eventos > 0) ? ((double) duracionTotalMs / eventos) : 0.0;
    }

    public double getDuracionTotalMin() {
        return duracionTotalMs / 60000.0;
    }

    public double getDuracionPromedioMin() {
        return getDuracionPromedioMs() / 60000.0;
    }

    // =============================
    // Getters y Setters
    // =============================

    public String getAlcaldia() {
        return alcaldia;
    }

    public void setAlcaldia(String alcaldia) {
        this.alcaldia = alcaldia;
    }

    public int getEventos() {
        return eventos;
    }

    public void setEventos(int eventos) {
        this.eventos = eventos;
    }

    public int getObservaciones() {
        return observaciones;
    }

    public void setObservaciones(int observaciones) {
        this.observaciones = observaciones;
    }

    public long getDuracionTotalMs() {
        return duracionTotalMs;
    }

    public void setDuracionTotalMs(long duracionTotalMs) {
        this.duracionTotalMs = duracionTotalMs;
    }
}

