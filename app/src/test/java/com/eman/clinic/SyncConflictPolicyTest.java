package com.eman.clinic;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SyncConflictPolicyTest {
    @Test public void newRemoteRowCanBeCreated() {
        assertEquals(SyncConflictPolicy.Decision.PUSH,
                SyncConflictPolicy.decide(false, "", "", "dev-a:1"));
    }

    @Test public void retryAfterLostResponseIsIdempotent() {
        assertEquals(SyncConflictPolicy.Decision.PUSH,
                SyncConflictPolicy.decide(true, "dev-a:1", "", "dev-a:1"));
    }

    @Test public void legacyRowsAllowOneMigrationWrite() {
        assertEquals(SyncConflictPolicy.Decision.PUSH,
                SyncConflictPolicy.decide(true, "legacy:abc", "", "dev-a:2"));
    }

    @Test public void normalEditPushesWhenBaseIsStillCurrent() {
        assertEquals(SyncConflictPolicy.Decision.PUSH,
                SyncConflictPolicy.decide(true, "dev-a:1", "dev-a:1", "dev-a:2"));
    }

    @Test public void concurrentDeviceEditBecomesConflict() {
        assertEquals(SyncConflictPolicy.Decision.CONFLICT,
                SyncConflictPolicy.decide(true, "dev-b:7", "dev-a:1", "dev-a:2"));
    }

    @Test public void unknownModernRemoteVersionIsNotOverwritten() {
        assertEquals(SyncConflictPolicy.Decision.CONFLICT,
                SyncConflictPolicy.decide(true, "dev-b:7", "", "dev-a:2"));
    }
}
