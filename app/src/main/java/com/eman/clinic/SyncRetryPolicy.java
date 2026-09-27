package com.eman.clinic;

/** Pure exponential-backoff rules shared by durable sync and unit tests. */
final class SyncRetryPolicy {
    private SyncRetryPolicy() {}

    static int delaySeconds(int attempt) {
        int safe = Math.max(1, attempt);
        return Math.min(1800, 15 * (1 << Math.min(7, safe - 1)));
    }
}
