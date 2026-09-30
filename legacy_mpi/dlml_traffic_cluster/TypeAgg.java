import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
public class TypeAgg implements DataLike {

    private String type;
    private int events;            // #eventos únicos
    private int observations;      // suma de count (snapshots)
    private long totalDurationMs;  // suma (last-first)

    public TypeAgg() {}

    public TypeAgg(String type) {
        this.type = type;
    }

    public void add(AlertAgg e) {
        long d = e.getLastSeen() - e.getFirstSeen();
        if (d < 0) d = 0;

        events += 1;
        observations += e.getCount();
        totalDurationMs += d;
    }

    public void merge(TypeAgg o) {
        if (this.type == null) this.type = o.type;
        this.events += o.events;
        this.observations += o.observations;
        this.totalDurationMs += o.totalDurationMs;
    }

    public double avgDurationMs() {
        return (events > 0) ? ((double) totalDurationMs / events) : 0.0;
    }

    // getters/setters
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public int getEvents() { return events; }
    public void setEvents(int events) { this.events = events; }
    public int getObservations() { return observations; }
    public void setObservations(int observations) { this.observations = observations; }
    public long getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(long totalDurationMs) { this.totalDurationMs = totalDurationMs; }
}

