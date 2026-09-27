package com.eman.clinic;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class Phase3SyncPolicyTest {
    @Test public void equalTimestampRowsUseUuidAsStableTieBreaker() {
        String at = "2026-09-27T11:30:00.000000+00:00";
        assertFalse(SyncCursorPolicy.isAfter(at, "00000000-0000-0000-0000-000000000001", at,
                "00000000-0000-0000-0000-000000000001"));
        assertTrue(SyncCursorPolicy.isAfter(at, "00000000-0000-0000-0000-000000000002", at,
                "00000000-0000-0000-0000-000000000001"));
        assertTrue(SyncCursorPolicy.isAfter("2026-09-27T11:30:01+00:00", "a", at, "z"));
    }

    @Test public void weakNetworkRetriesBackOffWithoutGrowingForever() {
        assertEquals(15, SyncRetryPolicy.delaySeconds(1));
        assertEquals(30, SyncRetryPolicy.delaySeconds(2));
        assertEquals(60, SyncRetryPolicy.delaySeconds(3));
        assertEquals(1800, SyncRetryPolicy.delaySeconds(99));
    }

    @Test public void registrationPaymentAndClinicalChangesRemainIdempotentAfterLostResponse() {
        for (String ignored : new String[]{"patient", "payment", "visit"}) {
            assertEquals(SyncConflictPolicy.Decision.PUSH,
                    SyncConflictPolicy.decide(true, "device-a:change-1", "", "device-a:change-1"));
        }
    }

    @Test public void staleDeviceCannotOverwriteAnyRecordType() {
        for (String ignored : new String[]{"patient", "visit", "payment", "day_closure"}) {
            assertEquals(SyncConflictPolicy.Decision.CONFLICT,
                    SyncConflictPolicy.decide(true, "device-b:2", "device-a:1", "device-a:3"));
        }
    }
}
