/**
 * Minimal data class for testing DLML.
 *
 * Implements DataLike so it can be exchanged between DLML virtual processes.
 */
public class Data implements DataLike {

    /** Message or content of the item. */
    private String mensaje = "";

    /** Default constructor (required by Jackson). */
    public Data() {
    }

    /**
     * Convenience constructor.
     *
     * @param mensaje text to store
     */
    public Data(String mensaje) {
        this.mensaje = mensaje;
    }

    /**
     * Returns the current message.
     *
     * @return content of the item
     */
    public String getMensaje() {
        return mensaje;
    }

    /**
     * Sets a new message.
     *
     * @param mensaje text to store; if null, an empty string is stored
     */
    public void setMensaje(String mensaje) {
        this.mensaje = (mensaje != null) ? mensaje : "";
    }

    @Override
    public String toString() {
        return "Data{mensaje='" + mensaje + "'}";
    }
}
