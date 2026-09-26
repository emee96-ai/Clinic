package com.eman.clinic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Pure scope rules used to isolate every clinic's local database and key. */
final class ClinicDatabaseScope {
    private ClinicDatabaseScope() {}

    static String scopeId(String clinicId, String userId) {
        String clinic = clean(clinicId);
        if (!clinic.isEmpty()) return "clinic:" + clinic;
        String user = clean(userId);
        if (!user.isEmpty()) return "unlinked-user:" + user;
        return "local-device";
    }

    static String databaseName(String scopeId) {
        return "clinic_secure_" + sha256(scopeId).substring(0, 24) + ".db";
    }

    static String keyName(String scopeId) {
        return "db-key-" + sha256(scopeId);
    }

    static String boundClinicId(String scopeId) {
        return scopeId.startsWith("clinic:") ? scopeId.substring("clinic:".length()) : "";
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
