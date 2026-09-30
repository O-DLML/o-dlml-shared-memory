/**
 * Message between virtual processes on the thread bus.
 * Equivalent to an MPI message with a tag, a source and an integer value.
 */
class Msg {
    final int source;
    final int tag;
    final int value;

    Msg(int source, int tag, int value) {
        this.source = source;
        this.tag    = tag;
        this.value  = value;
    }
}
