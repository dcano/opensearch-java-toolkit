package io.twba.search.toolkit.opensearch.testlog;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A test-only SLF4J backend that keeps every event instead of printing it.
 *
 * <p>Registered through {@code META-INF/services/org.slf4j.spi.SLF4JServiceProvider} in the test
 * resources, so it is the provider for the module's test JVM and for nothing else. The toolkit
 * depends on {@code slf4j-api} alone — binding a backend is the application's decision — which
 * would otherwise leave every log line going to the no-op provider and make "this must not be
 * logged" an unverifiable claim.
 *
 * <p>Every level is enabled, deliberately. The line {@code OpenSearchObservations} writes per search
 * is at {@code debug}, and a secrecy test that ran with debug switched off would pass by not
 * looking.
 */
public final class RecordingSlf4jProvider implements SLF4JServiceProvider {

    /** The API version this provider is built against; SLF4J warns if it drifts too far. */
    private static final String REQUESTED_API_VERSION = "2.0.99";

    private final Map<String, Logger> loggers = new ConcurrentHashMap<>();
    private final IMarkerFactory markerFactory = new BasicMarkerFactory();
    private final MDCAdapter mdcAdapter = new BasicMDCAdapter();

    @Override
    public ILoggerFactory getLoggerFactory() {
        return name -> loggers.computeIfAbsent(name, RecordingLogger::new);
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return REQUESTED_API_VERSION;
    }

    @Override
    public void initialize() {
        // nothing to set up: the sink is a list
    }

    /** Formats the event exactly as a file appender would, then keeps the resulting string. */
    private static final class RecordingLogger extends LegacyAbstractLogger {

        private RecordingLogger(String name) {
            this.name = name;
        }

        @Override
        protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern,
                                                   Object[] arguments, Throwable throwable) {
            String message = MessageFormatter.basicArrayFormat(messagePattern, arguments);
            RecordedLogs.record(new RecordedLogs.Event(getName(), level, message, throwable));
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return null;   // no caller-location lookup: nothing here writes a stack frame
        }

        @Override
        public boolean isTraceEnabled() {
            return true;
        }

        @Override
        public boolean isDebugEnabled() {
            return true;
        }

        @Override
        public boolean isInfoEnabled() {
            return true;
        }

        @Override
        public boolean isWarnEnabled() {
            return true;
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }
    }
}
