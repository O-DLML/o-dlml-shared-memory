// Data.java
import java.util.Objects;

/**
 * POJO representing a work item (a file) for DLML/Protocol.
 * It must stay simple so that it can be serialized to JSON.
 */
public class Data implements DataLike {

    /** Path or name of the file to process. */
    private String archivo = "";

    /** Default constructor required by Jackson. */
    public Data() {
    }

    /**
     * Convenience constructor.
     *
     * @param archivo path or name of the file
     */
    public Data(String archivo) {
        this.archivo = archivo;
    }

    /**
     * Returns the file name/path.
     *
     * @return string with the file
     */
    public String getArchivo() {
        return archivo;
    }

    /**
     * Sets the file name/path.
     *
     * @param archivo string with the file; if null, an empty string is stored
     */
    public void setArchivo(String archivo) {
        this.archivo = (archivo != null) ? archivo : "";
    }

    @Override
    public String toString() {
        return "Data{archivo='" + archivo + "'}";
    }

    @Override
    public int hashCode() {
        return Objects.hash(archivo);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Data)) return false;
        Data other = (Data) obj;
        return Objects.equals(this.archivo, other.archivo);
    }
}

