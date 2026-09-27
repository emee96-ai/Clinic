package com.eman.clinic;

/** High-risk clinical ownership rules shared by persistence and acceptance tests. */
public final class ClinicClinicalAccessRules {
    private ClinicClinicalAccessRules() {}

    public static boolean canEditVisit(String role, String userId,
                                       String assignedDoctorUserId, String status) {
        if (!ClinicDb.IN_CONSULT.equals(status)) return false;
        boolean doctor = "owner_doctor".equals(role) || "substitute_doctor".equals(role);
        return doctor
                && userId != null && !userId.isEmpty()
                && userId.equals(assignedDoctorUserId);
    }

    public static boolean canTransferVisit(String role, String userId,
                                           String assignedDoctorUserId, String status) {
        if (!ClinicDb.IN_CONSULT.equals(status)) return false;
        if ("owner_doctor".equals(role)) return true;
        return canEditVisit(role, userId, assignedDoctorUserId, status);
    }
}
