import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a batch of images for distributed classification with DLML.
 *
 * Each object holds a list of image file paths that will be
 * processed by a worker.
 */
public class Data implements DataLike {

    /** Batch identifier. */
    private int idLote;

    /** List of absolute or relative paths to the images. */
    private List<String> rutas = new ArrayList<>();

    /** Default constructor required by Jackson. */
    public Data() {
    }

    /**
     * Constructor with an identifier and a list of paths.
     *
     * @param idLote batch identifier
     * @param rutas list of image paths
     */
    public Data(int idLote, List<String> rutas) {
        this.idLote = idLote;
        this.rutas = (rutas != null) ? rutas : new ArrayList<>();
    }

    public int getIdLote() {
        return idLote;
    }

    public void setIdLote(int idLote) {
        this.idLote = idLote;
    }

    public List<String> getRutas() {
        return rutas;
    }

    public void setRutas(List<String> rutas) {
        this.rutas = (rutas != null) ? rutas : new ArrayList<>();
    }

    @Override
    public String toString() {
        return "Data{idLote=" + idLote + ", rutas=" + rutas.size() + " images}";
    }

    @Override
    public int hashCode() {
        return Objects.hash(idLote, rutas);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Data)) return false;
        Data other = (Data) obj;
        return idLote == other.idLote && Objects.equals(rutas, other.rutas);
    }
}
