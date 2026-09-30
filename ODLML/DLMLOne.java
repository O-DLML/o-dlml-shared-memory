// DLMLOne.java
/**
 * Action to run exactly once on the root process.
 * Used with DLML.OnlyOne(...).
 */
@FunctionalInterface
interface DLMLOne {

    /**
     * Runs the user-defined action on the root process.
     */
    void run();
}

