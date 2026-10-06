package io.twba.search.toolkit.opensearch.testlog;

import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the code under test logged, in order.
 *
 * <p>The toolkit's rule that no query text, canonical value, hash, key or opened value reaches a log
 * line is only a rule if something checks it, and a log line is the one output surface that is
 * invisible to ordinary assertions: it leaves the process without passing through a return value.
 * The module has no logging backend on its test classpath — by design, since the toolkit must not
 * impose one — so instead of adding a dependency, the tests register their own SLF4J provider (see
 * {@link RecordingSlf4jProvider}) and read the events back from here.
 *
 * <p>Static because SLF4J resolves its provider once per JVM. Tests that assert on log output
 * {@link #clear()} first; every other test in the module logs into the same list and ignores it.
 */
public final class RecordedLogs {

    /** One log event, already formatted — the string that would have been written to a file. */
    public record Event(String logger, Level level, String message, Throwable thrown) {
    }

    private static final List<Event> EVENTS = new ArrayList<>();

    private RecordedLogs() {
    }

    static void record(Event event) {
        synchronized (EVENTS) {
            EVENTS.add(event);
        }
    }

    public static void clear() {
        synchronized (EVENTS) {
            EVENTS.clear();
        }
    }

    public static List<Event> events() {
        synchronized (EVENTS) {
            return List.copyOf(EVENTS);
        }
    }

    /** Events from one logger name — the audit trail is routed by name, so tests select by it. */
    public static List<Event> from(String logger) {
        return events().stream().filter(event -> logger.equals(event.logger())).toList();
    }

    /** Every formatted message, whichever logger produced it: the whole log surface to search. */
    public static List<String> messages() {
        return events().stream().map(Event::message).toList();
    }
}
