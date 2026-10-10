package com.kaagaz.app

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class ReminderScheduler(context: Context) {
    private val manager = WorkManager.getInstance(context)

    fun schedule(reminder: DeadlineReminder, nowMillis: Long = System.currentTimeMillis()): Boolean = try {
        cancel(reminder.documentId)
        reminder.offsetsDays.forEach { offset ->
            val trigger = LocalDate.ofEpochDay(reminder.deadlineEpochDay).minusDays(offset.toLong())
                .atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (trigger > nowMillis) {
                val data = Data.Builder().putString(ReminderWorker.KEY_DOCUMENT_ID, reminder.documentId)
                    .putInt(ReminderWorker.KEY_OFFSET, offset).build()
                val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                    .setInitialDelay(trigger - nowMillis, TimeUnit.MILLISECONDS)
                    .setInputData(data).addTag(tag(reminder.documentId)).build()
                manager.enqueueUniqueWork(ReminderPolicy.workName(reminder.documentId, reminder.deadlineType, offset), ExistingWorkPolicy.REPLACE, request)
            }
        }
        true
    } catch (_: Exception) { false }

    fun cancel(documentId: String) = manager.cancelAllWorkByTag(tag(documentId))

    companion object { fun tag(documentId: String) = "document_reminders_$documentId" }
}
