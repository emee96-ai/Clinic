package com.eman.clinic;

/** Pure decision logic for optimistic cloud synchronization. */
public final class SyncConflictPolicy {
    public enum Decision { PUSH, CONFLICT }

    private SyncConflictPolicy() {}

    public static Decision decide(boolean remoteExists, String remoteChangeId,
                                  String baseChangeId, String localChangeId) {
        if (!remoteExists) return Decision.PUSH;
        String remote = safe(remoteChangeId);
        String base = safe(baseChangeId);
        String local = safe(localChangeId);

        // Old server rows created before conflict IDs existed are a one-time baseline.
        if (remote.isEmpty() || (remote.startsWith("legacy:") && base.isEmpty())) return Decision.PUSH;

        // Same logical change reaching the server again after a timeout/crash is idempotent.
        if (!local.isEmpty() && remote.equals(local)) return Decision.PUSH;

        // Normal optimistic update: the remote row is still the version we edited from.
        if (!base.isEmpty() && remote.equals(base)) return Decision.PUSH;

        return Decision.CONFLICT;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
