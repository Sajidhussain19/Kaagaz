package com.kaagaz.app.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** A personal note with an optional reminder time; stored only on this device. */
data class Note(val id: Long, val text: String, val remindAt: Long?, val done: Boolean, val createdAt: Long)

/** Notes table access and reminder scheduling (WorkManager → local notification). */
class NoteBook(private val context: Context, private val db: SQLiteDatabase) {
    fun add(text: String, remindAt: Long?): Long {
        val id = db.insertOrThrow("notes", null, ContentValues().apply {
            put("text", text.trim()); remindAt?.let { put("remind_at", it) }; put("done", 0); put("created_at", System.currentTimeMillis())
        })
        remindAt?.let { schedule(id, text, it) }
        return id
    }

    fun list(): List<Note> = db.rawQuery("SELECT id,text,remind_at,done,created_at FROM notes ORDER BY done, COALESCE(remind_at, 9e18), created_at DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(Note(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getLong(2), c.getInt(3) == 1, c.getLong(4))) }
    }

    fun setDone(note: Note, done: Boolean) {
        db.update("notes", ContentValues().apply { put("done", if (done) 1 else 0) }, "id=?", arrayOf(note.id.toString()))
        if (done) cancel(note.id) else note.remindAt?.takeIf { it > System.currentTimeMillis() }?.let { schedule(note.id, note.text, it) }
    }

    fun delete(note: Note) { cancel(note.id); db.delete("notes", "id=?", arrayOf(note.id.toString())) }

    private fun schedule(id: Long, text: String, at: Long) {
        val delay = (at - System.currentTimeMillis()).coerceAtLeast(0)
        WorkManager.getInstance(context).enqueueUniqueWork("note_$id", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<NoteReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(Data.Builder().putLong("id", id).putString("text", text.take(200)).build()).build())
    }

    private fun cancel(id: Long) = WorkManager.getInstance(context).cancelUniqueWork("note_$id")

    companion object {
        fun createTable(db: SQLiteDatabase) = db.execSQL("""CREATE TABLE IF NOT EXISTS notes (
            id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, remind_at INTEGER, done INTEGER NOT NULL, created_at INTEGER NOT NULL)""")
    }
}

class NoteReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val text = inputData.getString("text") ?: return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("kaagaz_notes", "Notes & reminders", NotificationManager.IMPORTANCE_HIGH))
        NotificationManagerCompat.from(applicationContext).notify(("note" + inputData.getLong("id", 0)).hashCode(),
            NotificationCompat.Builder(applicationContext, "kaagaz_notes").setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Kaagaz reminder").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true).build())
        return Result.success()
    }
}
