package com.eman.clinic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase7ArchitectureRulesTest {
    @Test fun navigationNeverExposesUnauthorizedScreens() {
        assertFalse(ClinicAccessPolicy.canOpenQueue(false, false, false))
        assertTrue(ClinicAccessPolicy.canOpenQueue(true, false, false))
        assertTrue(ClinicAccessPolicy.canOpenQueue(false, true, false))
        assertTrue(ClinicAccessPolicy.canOpenQueue(false, false, true))
        assertFalse(ClinicAccessPolicy.canOpenPatients(false))
        assertFalse(ClinicAccessPolicy.canOpenDoctor(false))
        assertFalse(ClinicAccessPolicy.canOpenFinance(false, false, false))
        assertTrue(ClinicAccessPolicy.canOpenFinance(false, true, false))
    }

    @Test fun paymentReversalRequiresOwnerOpenDayAndReason() {
        assertTrue(ClinicFinanceRules.canBeginReversal(true, false, "خطأ إدخال", "VOID"))
        assertFalse(ClinicFinanceRules.canBeginReversal(false, false, "خطأ إدخال", "VOID"))
        assertFalse(ClinicFinanceRules.canBeginReversal(true, true, "خطأ إدخال", "REFUND"))
        assertFalse(ClinicFinanceRules.canBeginReversal(true, false, "لا", "CORRECTION"))
        assertFalse(ClinicFinanceRules.canBeginReversal(true, false, "سبب واضح", "DELETE"))
    }

    @Test fun correctedPaymentCannotCreateOverpayment() {
        assertEquals(6_000, ClinicFinanceRules.correctedMaximum(10_000, 8_000, 4_000))
        assertTrue(ClinicFinanceRules.canReplacePayment(10_000, 8_000, 4_000, 6_000))
        assertFalse(ClinicFinanceRules.canReplacePayment(10_000, 8_000, 4_000, 6_001))
        assertFalse(ClinicFinanceRules.canReplacePayment(10_000, 8_000, 4_000, 0))
    }
}
