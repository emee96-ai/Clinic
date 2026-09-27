package com.eman.clinic

/** High-risk finance rules kept independent from Android and persistence code. */
object ClinicFinanceRules {
    @JvmStatic fun canBeginReversal(
        isOwner: Boolean,
        dayClosed: Boolean,
        reason: String?,
        eventType: String?
    ): Boolean = isOwner && !dayClosed && !reason.isNullOrBlank() && reason.trim().length >= 3 &&
        eventType in setOf("VOID", "REFUND", "CORRECTION")

    @JvmStatic fun correctedMaximum(fee: Int, currentPaid: Int, originalAmount: Int): Int =
        (fee - (currentPaid - originalAmount)).coerceAtLeast(0)

    @JvmStatic fun canReplacePayment(
        fee: Int,
        currentPaid: Int,
        originalAmount: Int,
        replacementAmount: Int
    ): Boolean = replacementAmount in 1..correctedMaximum(fee, currentPaid, originalAmount)
}
