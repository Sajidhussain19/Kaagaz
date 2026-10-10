package com.kaagaz.app.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.kaagaz.app.core.DeadlineReminder
import com.kaagaz.app.core.ReminderState
import com.kaagaz.app.data.KaagazStore
import java.io.File

class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val documentId = inputData.getString(KEY_DOCUMENT_ID) ?: return Result.failure()
        val offset = inputData.getInt(KEY_OFFSET, -1)
        val deadlineType = inputData.getString(KEY_DEADLINE_TYPE) ?: "POLICY_EXPIRY"
        val store = KaagazStore(applicationContext)
        val reminder: DeadlineReminder?
        val documentExists: Boolean
        try {
            reminder = store.getReminder(documentId, deadlineType)
            documentExists = store.load(documentId)?.document?.uri?.path?.let { File(it).isFile } == true
            if (!documentExists) store.deleteReminder(documentId)
        } finally { store.close() }
        if (!documentExists) return Result.success()
        if (reminder == null || offset !in reminder.offsetsDays || reminder.state != ReminderState.SCHEDULED) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            KaagazStore(applicationContext).use { it.saveReminder(reminder.copy(state = ReminderState.SAVED_PERMISSION_REQUIRED, updatedAt = System.currentTimeMillis())) }
            return Result.success()
        }
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Deadline reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val timing = when (offset) { 0 -> "today"; 1 -> "in 1 day"; else -> "in $offset days" }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Kaagaz deadline reminder")
            .setContentText(if (deadlineType == "BILL_DUE") "A bill (${reminder.sourceValue}) is due $timing." else "Your policy expires $timing.")
            .setAutoCancel(true).build()
        NotificationManagerCompat.from(applicationContext).notify(ReminderPolicy.workName(documentId, reminder.deadlineType, offset).hashCode(), notification)
        return Result.success()
    }

    companion object {
        const val KEY_DOCUMENT_ID = "document_id"
        const val KEY_OFFSET = "offset"
        const val KEY_DEADLINE_TYPE = "deadline_type"
        private const val CHANNEL_ID = "kaagaz_deadlines"
    }
}
