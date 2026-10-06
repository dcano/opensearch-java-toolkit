package io.twba.search.toolkit;

/** The outcome of one bulk write: how many documents landed, how many did not, how long it took. */
public record BulkStats(int succeeded, int failed, long tookMillis) {
}
