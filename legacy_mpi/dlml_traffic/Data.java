// Data.java
import java.util.Objects;

/**
 * POJO que representa una entrada de trabajo (archivo) para DLML/Protocol.
 * Debe ser simple para permitir su serializacion a JSON.
 */
public class Data implements DataLike {

    /** Ruta o nombre del archivo a procesar. */
    private String archivo = "";

    /** Constructor por defecto requerido por Jackson. */
    public Data() {
    }

    /**
     * Constructor de conveniencia.
     *
     * @param archivo ruta o nombre del archivo
     */
    public Data(String archivo) {
        this.archivo = archivo;
    }

    /**
     * Obtiene el nombre/ruta del archivo.
     *
     * @return cadena con el archivo
     */
    public String getArchivo() {
        return archivo;
    }

    /**
     * Establece el nombre/ruta del archivo.
     *
     * @param archivo cadena con el archivo; si es null se asigna cadena vacia
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

