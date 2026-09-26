package com.eman.clinic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ClinicDatabaseScopeTest {
    @Test public void clinicIdentityAlwaysWinsOverUserIdentity() {
        assertEquals("clinic:clinic-a", ClinicDatabaseScope.scopeId(" clinic-a ", "user-b"));
    }

    @Test public void signedInUserWithoutClinicIsIsolatedFromAnonymousData() {
        assertEquals("unlinked-user:user-a", ClinicDatabaseScope.scopeId("", "user-a"));
        assertEquals("local-device", ClinicDatabaseScope.scopeId("", ""));
    }

    @Test public void clinicsReceiveDifferentOpaqueDatabaseNamesAndKeys() {
        String a = ClinicDatabaseScope.scopeId("clinic-a", "user");
        String b = ClinicDatabaseScope.scopeId("clinic-b", "user");
        assertNotEquals(ClinicDatabaseScope.databaseName(a), ClinicDatabaseScope.databaseName(b));
        assertNotEquals(ClinicDatabaseScope.keyName(a), ClinicDatabaseScope.keyName(b));
        assertTrue(ClinicDatabaseScope.databaseName(a).startsWith("clinic_secure_"));
        assertTrue(ClinicDatabaseScope.databaseName(a).endsWith(".db"));
        assertFalse(ClinicDatabaseScope.databaseName(a).contains("clinic-a"));
    }

    @Test public void onlyClinicScopesCanBindRemoteSync() {
        assertEquals("clinic-a", ClinicDatabaseScope.boundClinicId("clinic:clinic-a"));
        assertEquals("", ClinicDatabaseScope.boundClinicId("unlinked-user:user-a"));
        assertEquals("", ClinicDatabaseScope.boundClinicId("local-device"));
    }
}
