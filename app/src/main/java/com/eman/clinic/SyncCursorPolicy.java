package com.eman.clinic;

/** Keyset cursor ordering that never loses rows sharing the same server timestamp. */
final class SyncCursorPolicy {
    private SyncCursorPolicy() {}

    static boolean isAfter(String updatedAt, String syncKey, String cursorTime, String cursorKey) {
        if (cursorTime == null || cursorTime.isEmpty()) return true;
        int time = safe(updatedAt).compareTo(cursorTime);
        return time > 0 || time == 0 && safe(syncKey).compareTo(safe(cursorKey)) > 0;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
