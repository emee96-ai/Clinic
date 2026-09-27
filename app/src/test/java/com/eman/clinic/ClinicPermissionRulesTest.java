package com.eman.clinic;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClinicPermissionRulesTest {
    @Test public void receptionistCannotReceiveClinicalReportingOrTeamPermissions() {
        assertTrue(ClinicPermissionRules.allows("receptionist", "register_visits"));
        assertTrue(ClinicPermissionRules.allows("receptionist", "record_payments"));
        assertFalse(ClinicPermissionRules.allows("receptionist", "view_clinical"));
        assertFalse(ClinicPermissionRules.allows("receptionist", "edit_clinical"));
        assertFalse(ClinicPermissionRules.allows("receptionist", "view_finance"));
        assertFalse(ClinicPermissionRules.allows("receptionist", "close_day"));
        assertFalse(ClinicPermissionRules.allows("receptionist", "manage_staff"));
    }

    @Test public void substituteDoctorCannotReceiveFinanceOrTeamPermissions() {
        assertTrue(ClinicPermissionRules.allows("substitute_doctor", "view_clinical"));
        assertTrue(ClinicPermissionRules.allows("substitute_doctor", "edit_clinical"));
        assertFalse(ClinicPermissionRules.allows("substitute_doctor", "record_payments"));
        assertFalse(ClinicPermissionRules.allows("substitute_doctor", "view_finance"));
        assertFalse(ClinicPermissionRules.allows("substitute_doctor", "manage_invites"));
    }

    @Test public void unknownRolesAndPermissionsAreDenied() {
        assertFalse(ClinicPermissionRules.isKnownRole("admin"));
        assertFalse(ClinicPermissionRules.allows("admin", "manage_staff"));
        assertFalse(ClinicPermissionRules.allows("owner_doctor", "invented_permission"));
    }

    @Test public void ownerHasEveryKnownManagementPermission() {
        assertTrue(ClinicPermissionRules.allows("owner_doctor", "manage_staff"));
        assertTrue(ClinicPermissionRules.allows("owner_doctor", "manage_invites"));
        assertTrue(ClinicPermissionRules.allows("owner_doctor", "view_finance"));
        assertTrue(ClinicPermissionRules.allows("owner_doctor", "edit_clinical"));
    }
}
