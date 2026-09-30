// DataLike.java
/**
 * Marker interface for the data types exchanged by DLML/Protocol.
 *
 * Recommendations for classes implementing this interface:
 * - They should be POJOs compatible with Jackson serialization/deserialization.
 * - Declare a no-argument constructor.
 * - Include getters and setters for all fields.
 * - Optional: override toString(), equals() and hashCode() where applicable.
 */
public interface DataLike {
    // Marker interface with no methods
}

