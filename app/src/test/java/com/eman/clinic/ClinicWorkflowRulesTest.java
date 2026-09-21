package com.eman.clinic;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClinicWorkflowRulesTest {
    @Test public void visitMovesOnlyForwardThroughQueue() {
        assertTrue(ClinicWorkflowRules.canSendToDoctor(ClinicDb.REGISTERED));
        assertFalse(ClinicWorkflowRules.canSendToDoctor(ClinicDb.WAITING));
        assertFalse(ClinicWorkflowRules.canSendToDoctor(ClinicDb.COMPLETED));

        assertTrue(ClinicWorkflowRules.canStartVisit(ClinicDb.WAITING));
        assertFalse(ClinicWorkflowRules.canStartVisit(ClinicDb.REGISTERED));
        assertFalse(ClinicWorkflowRules.canStartVisit(ClinicDb.COMPLETED));

        assertTrue(ClinicWorkflowRules.canEditClinical(ClinicDb.IN_CONSULT));
        assertFalse(ClinicWorkflowRules.canEditClinical(ClinicDb.WAITING));
        assertFalse(ClinicWorkflowRules.canEditClinical(ClinicDb.COMPLETED));
    }

    @Test public void paymentCannotExceedRemainingOrHappenAfterDayClose() {
        assertTrue(ClinicWorkflowRules.canRecordPayment(4_000, 10_000, false));
        assertTrue(ClinicWorkflowRules.canRecordPayment(10_000, 10_000, false));
        assertFalse(ClinicWorkflowRules.canRecordPayment(10_001, 10_000, false));
        assertFalse(ClinicWorkflowRules.canRecordPayment(0, 10_000, false));
        assertFalse(ClinicWorkflowRules.canRecordPayment(1_000, 10_000, true));
    }

    @Test public void dayCannotCloseWithOpenPatients() {
        assertTrue(ClinicWorkflowRules.canCloseDay(0));
        assertFalse(ClinicWorkflowRules.canCloseDay(1));
        assertFalse(ClinicWorkflowRules.canCloseDay(4));
    }

    @Test public void freeFollowupOnlyInsideConfiguredWindow() {
        assertTrue(ClinicWorkflowRules.isFreeFollowup(0, 7));
        assertTrue(ClinicWorkflowRules.isFreeFollowup(7, 7));
        assertFalse(ClinicWorkflowRules.isFreeFollowup(8, 7));
        assertFalse(ClinicWorkflowRules.isFreeFollowup(9999, 7));
    }

    @Test public void acceptedVisitTypesAreExplicit() {
        assertTrue(ClinicWorkflowRules.isValidVisitType(ClinicDb.NEW));
        assertTrue(ClinicWorkflowRules.isValidVisitType(ClinicDb.FREE_FOLLOWUP));
        assertTrue(ClinicWorkflowRules.isValidVisitType(ClinicDb.PAID_FOLLOWUP));
        assertTrue(ClinicWorkflowRules.isValidVisitType(ClinicDb.LAB_RESULT));
        assertFalse(ClinicWorkflowRules.isValidVisitType("FREE_MANUAL"));
    }
}
