package io.twba.search.toolkit.testkit;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.twba.search.toolkit.opensearch.obs.OpenSearchObservations;

import java.util.ArrayList;
import java.util.List;

/**
 * A real meter registry and a real observation registry that keep every context they see, so a
 * conformance run can ask what the toolkit published rather than trust that it published nothing
 * wrong.
 *
 * <p>"Nothing sensitive in telemetry" is only checkable if the whole published surface is
 * enumerable, which is why this collects meter names, every tag on every meter, and every key value
 * on every observation — low and high cardinality alike. The assertion is then that a sentinel
 * appears nowhere in that surface. Asserting instead on the tags a test happened to expect would
 * pass happily while a newly added one leaked.
 */
public final class RecordedTelemetry {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private final List<Observation.Context> contexts = new ArrayList<>();

    public RecordedTelemetry() {
        observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(Observation.Context context) {
                contexts.add(context);
            }
        });
    }

    public OpenSearchObservations observations() {
        return new OpenSearchObservations(observations, meters);
    }

    /**
     * Everything this telemetry published, as strings: meter names, every tag key and value, every
     * observation name and key value. One list, because the question a conformance run asks is
     * "does this string appear anywhere", and a surface split across several accessors is a surface
     * with somewhere to hide.
     */
    public List<String> everythingPublished() {
        List<String> published = new ArrayList<>();
        for (Meter meter : meters.getMeters()) {
            published.add(meter.getId().getName());
            for (Tag tag : meter.getId().getTags()) {
                published.add(tag.getKey());
                published.add(tag.getValue());
            }
        }
        for (Observation.Context context : contexts) {
            published.add(context.getName());
            published.add(String.valueOf(context.getContextualName()));
            context.getAllKeyValues().forEach(keyValue -> {
                published.add(keyValue.getKey());
                published.add(keyValue.getValue());
            });
        }
        return List.copyOf(published);
    }

    /** The key values of one observation by name, for asserting what a span does carry. */
    public List<String> keyValuesOf(String observationName) {
        return contexts.stream()
                .filter(context -> observationName.equals(context.getName()))
                .flatMap(context -> context.getAllKeyValues().stream())
                .map(io.micrometer.common.KeyValue::getValue)
                .toList();
    }

    /** Every tag value on every meter — the time-series label surface, which must hold no tenant id. */
    public List<String> everyMeterTagValue() {
        return meters.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .map(Tag::getValue)
                .toList();
    }
}
