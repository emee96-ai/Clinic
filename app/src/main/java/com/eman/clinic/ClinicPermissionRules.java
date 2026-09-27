package com.eman.clinic;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Client-side deny-by-default mirror of the server role ceiling. */
final class ClinicPermissionRules {
    private static final Set<String> OWNER = set(
            "view_patients", "edit_patients", "register_visits", "manage_queue",
            "view_clinical", "edit_clinical", "view_finance", "record_payments",
            "close_day", "manage_staff", "manage_invites");
    private static final Set<String> RECEPTIONIST = set(
            "view_patients", "edit_patients", "register_visits", "manage_queue",
            "record_payments");
    private static final Set<String> SUBSTITUTE_DOCTOR = set(
            "view_patients", "manage_queue", "view_clinical", "edit_clinical");

    private ClinicPermissionRules() {}

    static boolean isKnownRole(String role) {
        return "owner_doctor".equals(role) || "receptionist".equals(role)
                || "substitute_doctor".equals(role);
    }

    static boolean allows(String role, String permission) {
        if (permission == null || permission.isEmpty()) return false;
        if ("owner_doctor".equals(role)) return OWNER.contains(permission);
        if ("receptionist".equals(role)) return RECEPTIONIST.contains(permission);
        if ("substitute_doctor".equals(role)) return SUBSTITUTE_DOCTOR.contains(permission);
        return false;
    }

    static Set<String> allowedFor(String role) {
        if ("owner_doctor".equals(role)) return OWNER;
        if ("receptionist".equals(role)) return RECEPTIONIST;
        if ("substitute_doctor".equals(role)) return SUBSTITUTE_DOCTOR;
        return Collections.emptySet();
    }

    private static Set<String> set(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
}
