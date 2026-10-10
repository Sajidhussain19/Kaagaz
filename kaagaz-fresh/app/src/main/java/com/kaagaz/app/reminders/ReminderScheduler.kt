package com.kaagaz.app.reminders

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.kaagaz.app.core.DeadlineReminder
import com.kaagaz.app.core.ReminderState
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class ReminderScheduler(context: Context) {
    private val manager = WorkManager.getInstance(context)

    fun schedule(reminder: DeadlineReminder, nowMillis: Long = System.currentTimeMillis()): Boolean = try {
        cancel(reminder.documentId)
        scheduleWithoutCancel(reminder, nowMillis)
        true
    } catch (_: Exception) { false }

    fun cancel(documentId: String) = manager.cancelAllWorkByTag(tag(documentId))

    /**
     * Re-enqueues every scheduled reminder once per app version, so pending work always points at the current
     * worker class (the worker moved packages in 0.3.0) and survives upgrades.
     */
    fun rescheduleAllOnUpgrade(context: Context, reminders: List<DeadlineReminder>, versionCode: Long) {
        val prefs = context.getSharedPreferences("kaagaz_reminders", Context.MODE_PRIVATE)
        if (prefs.getLong("scheduled_for_version", -1) == versionCode) return
        val byDocument = reminders.filter { it.state == ReminderState.SCHEDULED }.groupBy { it.documentId }
        byDocument.forEach { (documentId, list) ->
            cancel(documentId)
            list.forEach { reminder -> scheduleWithoutCancel(reminder) }
        }
        prefs.edit().putLong("scheduled_for_version", versionCode).apply()
    }

    private fun scheduleWithoutCancel(reminder: DeadlineReminder, nowMillis: Long = System.currentTimeMillis()) {
        reminder.offsetsDays.forEach { offset ->
            val trigger = LocalDate.ofEpochDay(reminder.deadlineEpochDay).minusDays(offset.toLong())
                .atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (trigger > nowMillis) {
                val data = Data.Builder().putString(ReminderWorker.KEY_DOCUMENT_ID, reminder.documentId)
                    .putInt(ReminderWorker.KEY_OFFSET, offset).putString(ReminderWorker.KEY_DEADLINE_TYPE, reminder.deadlineType).build()
                manager.enqueueUniqueWork(ReminderPolicy.workName(reminder.documentId, reminder.deadlineType, offset), ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(trigger - nowMillis, TimeUnit.MILLISECONDS)
                        .setInputData(data).addTag(tag(reminder.documentId)).build())
            }
        }
    }

    companion object { fun tag(documentId: String) = "document_reminders_$documentId" }
}
