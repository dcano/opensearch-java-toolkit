package io.twba.search.toolkit;

/** A search could not be completed. The cause carries the engine-level detail; this does not. */
public class ErrorSearchingDataException extends RuntimeException {

    public ErrorSearchingDataException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * For a search the toolkit itself refuses to complete — a response it cannot turn into results
     * — where there is no engine-level exception underneath. Same type as the transport failure on
     * purpose: a caller's handling is identical either way, and the distinction matters only to
     * whoever reads the message.
     */
    public ErrorSearchingDataException(String message) {
        super(message);
    }
}
