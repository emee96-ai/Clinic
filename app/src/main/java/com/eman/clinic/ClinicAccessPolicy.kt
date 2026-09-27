package com.eman.clinic

/** Pure navigation policy shared by the activity and regression tests. */
object ClinicAccessPolicy {
    @JvmStatic fun canOpenQueue(manageQueue: Boolean, viewClinical: Boolean, registerVisits: Boolean) =
        manageQueue || viewClinical || registerVisits

    @JvmStatic fun canOpenPatients(viewPatients: Boolean) = viewPatients

    @JvmStatic fun canOpenDoctor(viewClinical: Boolean) = viewClinical

    @JvmStatic fun canOpenFinance(viewFinance: Boolean, recordPayments: Boolean, closeDay: Boolean) =
        viewFinance || recordPayments || closeDay
}
