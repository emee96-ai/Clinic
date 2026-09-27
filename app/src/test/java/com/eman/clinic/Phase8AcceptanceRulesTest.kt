package com.eman.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase8AcceptanceRulesTest {
    @Test fun receptionistCannotEnterClinicalOrAdministrativeAreas() {
        assertFalse(ClinicPermissionRules.allows("receptionist", "view_clinical"))
        assertFalse(ClinicPermissionRules.allows("receptionist", "edit_clinical"))
        assertFalse(ClinicPermissionRules.allows("receptionist", "close_day"))
        assertFalse(ClinicPermissionRules.allows("receptionist", "manage_staff"))
        assertTrue(ClinicPermissionRules.allows("receptionist", "register_visits"))
        assertTrue(ClinicPermissionRules.allows("receptionist", "record_payments"))
    }

    @Test fun substituteDoctorCanOnlyEditTheirActiveVisit() {
        assertTrue(ClinicClinicalAccessRules.canEditVisit(
            "substitute_doctor", "doctor-a", "doctor-a", ClinicDb.IN_CONSULT))
        assertFalse(ClinicClinicalAccessRules.canEditVisit(
            "substitute_doctor", "doctor-a", "doctor-b", ClinicDb.IN_CONSULT))
        assertFalse(ClinicClinicalAccessRules.canEditVisit(
            "substitute_doctor", "doctor-a", "doctor-a", ClinicDb.COMPLETED))
        assertFalse(ClinicClinicalAccessRules.canEditVisit(
            "receptionist", "reception-a", "reception-a", ClinicDb.IN_CONSULT))
    }

    @Test fun ownerMustTransferAnotherDoctorsVisitBeforeEditingIt() {
        assertFalse(ClinicClinicalAccessRules.canEditVisit(
            "owner_doctor", "owner", "doctor-a", ClinicDb.IN_CONSULT))
        assertTrue(ClinicClinicalAccessRules.canTransferVisit(
            "owner_doctor", "owner", "doctor-a", ClinicDb.IN_CONSULT))
        assertTrue(ClinicClinicalAccessRules.canEditVisit(
            "owner_doctor", "owner", "owner", ClinicDb.IN_CONSULT))
        assertFalse(ClinicClinicalAccessRules.canEditVisit(
            "owner_doctor", "owner", "owner", ClinicDb.COMPLETED))
    }

    @Test fun crashRetryOfSameLogicalChangeIsIdempotent() {
        assertEquals(SyncConflictPolicy.Decision.PUSH,
            SyncConflictPolicy.decide(true, "change-7", "change-6", "change-7"))
        assertEquals(SyncConflictPolicy.Decision.CONFLICT,
            SyncConflictPolicy.decide(true, "other-device", "change-6", "change-7"))
    }

    @Test fun retryBackoffIsBounded() {
        assertEquals(15, SyncRetryPolicy.delaySeconds(1))
        assertEquals(30, SyncRetryPolicy.delaySeconds(2))
        assertEquals(1_800, SyncRetryPolicy.delaySeconds(50))
    }
}
