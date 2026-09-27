package com.eman.clinic

import android.app.Application
import androidx.lifecycle.AndroidViewModel

data class ClinicSyncUiState(
    val pending: Int,
    val failed: Int,
    val conflicts: Int,
    val lastCloudSync: String
)

class ClinicRepository(private val app: Application) {
    val database = ClinicDb(app)
    private val auth = AuthStore(app)
    private val roomDelegate = lazy { ClinicRoomDatabase.open(app, auth) }
    private val room: ClinicRoomDatabase by roomDelegate
    private var roomDisabled = false

    init {
        // The legacy helper remains the schema owner during the incremental Room migration.
        // Opening it first prevents Room from creating a partial database on a fresh install.
        database.writableDatabase
    }

    fun syncState(): ClinicSyncUiState {
        if (!roomDisabled) {
            try {
                val dao = room.syncStatusDao()
                return ClinicSyncUiState(
                    dao.pendingCount(), dao.failedCount(), database.syncCount("conflict"),
                    dao.meta("last_cloud_sync_at").orEmpty()
                )
            } catch (_: Exception) {
                // Keep the clinic usable if Room cannot validate an older installation.
                roomDisabled = true
            }
        }
        return ClinicSyncUiState(
            database.syncCount("pending"), database.syncCount("failed"),
            database.syncCount("conflict"), database.syncMeta("last_cloud_sync_at")
        )
    }

    fun todayStats() = database.todayStats()
    fun openQueue() = database.openQueue()
    fun doctorQueue() = database.doctorQueue()
    fun searchPatients(query: String?) = database.searchPatients(query)
    fun patient(id: Long) = database.getPatient(id)
    fun visit(id: Long) = database.getVisit(id)
    fun visitsForPatient(id: Long) = database.visitsForPatient(id)
    fun unpaidToday() = database.unpaidToday()
    fun olderDebts() = database.olderDebts()
    fun recentPaymentsToday() = database.recentPaymentsToday()

    fun close() {
        if (roomDelegate.isInitialized() && room.isOpen) room.close()
        database.close()
    }
}

class ClinicViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ClinicRepository(application)

    fun database(): ClinicDb = repository.database
    fun syncState() = repository.syncState()
    fun todayStats() = repository.todayStats()
    fun openQueue() = repository.openQueue()
    fun doctorQueue() = repository.doctorQueue()
    fun searchPatients(query: String?) = repository.searchPatients(query)
    fun patient(id: Long) = repository.patient(id)
    fun visit(id: Long) = repository.visit(id)
    fun visitsForPatient(id: Long) = repository.visitsForPatient(id)
    fun unpaidToday() = repository.unpaidToday()
    fun olderDebts() = repository.olderDebts()
    fun recentPaymentsToday() = repository.recentPaymentsToday()

    override fun onCleared() { repository.close() }
}
