package com.eman.clinic;

/** Pure workflow rules shared by the database layer and unit tests. */
public final class ClinicWorkflowRules {
    private ClinicWorkflowRules() {}

    public static boolean isValidVisitType(String type) {
        return ClinicDb.NEW.equals(type)
                || ClinicDb.FREE_FOLLOWUP.equals(type)
                || ClinicDb.PAID_FOLLOWUP.equals(type)
                || ClinicDb.LAB_RESULT.equals(type);
    }

    public static boolean canSendToDoctor(String status) {
        return ClinicDb.REGISTERED.equals(status);
    }

    public static boolean canStartVisit(String status) {
        return ClinicDb.WAITING.equals(status);
    }

    public static boolean canEditClinical(String status) {
        return ClinicDb.IN_CONSULT.equals(status);
    }

    public static boolean canCancel(String status) {
        return ClinicDb.REGISTERED.equals(status) || ClinicDb.WAITING.equals(status);
    }

    public static boolean canReopen(String status) { return ClinicDb.CANCELLED.equals(status); }
    public static boolean canTransfer(String status) { return ClinicDb.IN_CONSULT.equals(status); }

    public static boolean canRecordPayment(int requestedAmount, int remaining, boolean dayClosed) {
        return !dayClosed && requestedAmount > 0 && remaining > 0 && requestedAmount <= remaining;
    }

    public static boolean canCloseDay(int openQueueCount) {
        return openQueueCount == 0;
    }

    public static boolean isFreeFollowup(int daysSinceLastCompletedVisit, int freeWindowDays) {
        return daysSinceLastCompletedVisit >= 0
                && daysSinceLastCompletedVisit != 9999
                && daysSinceLastCompletedVisit <= Math.max(0, freeWindowDays);
    }

    public static boolean canCompleteClinical(String visitType, String complaint,
                                              String diagnosis, String labs) {
        if (ClinicDb.LAB_RESULT.equals(visitType)) return present(labs);
        return present(complaint) && present(diagnosis);
    }

    public static String normalizePhone(String phone) {
        if (phone == null) return "";
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.startsWith("00249")) digits = digits.substring(2);
        if (digits.startsWith("249") && digits.length() > 9) return digits;
        if (digits.startsWith("0") && digits.length() == 10) return "249" + digits.substring(1);
        return digits;
    }

    private static boolean present(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
