package io.twba.search.toolkit.opensearch.obs;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * A real meter registry plus a real observation registry that keeps every context it sees, so a test
 * can ask what the toolkit published rather than trusting that it published nothing wrong.
 *
 * <p>The "nothing sensitive in telemetry" rule is only checkable if the whole published surface is
 * enumerable, which is why this collects meter names, every tag on every meter, and every key value
 * on every observation — low and high cardinality alike. A test then asserts a sentinel appears
 * nowhere in it. Asserting on the tags a test happens to expect would pass while a new one leaked.
 */
public final class RecordingTelemetry {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private final List<Observation.Context> contexts = new ArrayList<>();

    public RecordingTelemetry() {
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

    public SimpleMeterRegistry meters() {
        return meters;
    }

    public List<Observation.Context> contexts() {
        return List.copyOf(contexts);
    }

    public List<String> meterNames() {
        return meters.getMeters().stream().map(meter -> meter.getId().getName()).toList();
    }

    /** Every tag value published on every meter — the full time-series label surface. */
    public List<String> everyTagValue() {
        return meters.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .map(Tag::getValue)
                .toList();
    }

    /** Every key value on every observation, low and high cardinality, plus the observation names. */
    public List<String> everyKeyValue() {
        List<String> values = new ArrayList<>();
        for (Observation.Context context : contexts) {
            values.add(context.getName());
            values.add(String.valueOf(context.getContextualName()));
            context.getAllKeyValues().forEach(keyValue -> values.add(keyValue.getValue()));
        }
        return values;
    }

    /** The key values of one observation by name, for asserting what a span actually carries. */
    public List<KeyValue> keyValuesOf(String observationName) {
        return contexts.stream()
                .filter(context -> observationName.equals(context.getName()))
                .flatMap(context -> context.getAllKeyValues().stream())
                .toList();
    }
}
