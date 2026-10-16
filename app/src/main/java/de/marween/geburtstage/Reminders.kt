package de.marween.geburtstage

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Tägliche Prüfung und Benachrichtigungen. */
object Reminders {
    const val CHANNEL = "geburtstage"
    const val ACTION_CHECK = "de.marween.geburtstage.CHECK"
    const val ACTION_MUTE = "de.marween.geburtstage.MUTE"
    const val EXTRA_ID = "id"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Geburtstags-Erinnerungen", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "Erinnert dich vor Geburtstagen"
            nm.createNotificationChannel(ch)
        }
    }

    /** Nächste Prüfung zur eingestellten Uhrzeit planen. */
    fun schedule(ctx: Context) {
        val store = Store(ctx)
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var at = now.toLocalDate().atTime(store.hour, 0).atZone(zone)
        if (!at.isAfter(now)) at = at.plusDays(1)
        val intent = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_CHECK)
        val pi = PendingIntent.getBroadcast(
            ctx, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), pi)
    }

    /** Prüft alle Geburtstage und verschickt fällige Erinnerungen (jede nur einmal). */
    fun check(ctx: Context) {
        val store = Store(ctx)
        val list = store.load()
        val today = LocalDate.now()
        val daysBefore = store.daysBefore

        // Alte Einträge im Protokoll aufräumen
        val log = store.notified.filter { entry ->
            try {
                !LocalDate.parse(entry.substringAfterLast('|')).isBefore(today)
            } catch (e: Exception) {
                false
            }
        }.toMutableSet()

        for (b in list) {
            if (b.muted) continue
            val d = b.daysUntil(today)
            val next = b.nextDate(today)
            if (d == 0) {
                if (store.notifyOnDay && log.add("${b.id}|day|$next")) notify(ctx, b, 0)
            } else if (d <= daysBefore) {
                if (log.add("${b.id}|pre|$next")) notify(ctx, b, d)
            }
        }
        store.notified = log
    }

    private fun notify(ctx: Context, b: Birthday, days: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(ctx)

        val title = when (days) {
            0 -> "🎂 ${b.name} hat heute Geburtstag!"
            1 -> "🎂 ${b.name} hat morgen Geburtstag"
            else -> "🎂 ${b.name} hat in $days Tagen Geburtstag"
        }
        val age = b.turningAge()
        val next = b.nextDate()
        val text = Fmt.date(next.monthValue, next.dayOfMonth, null) + (if (age != null) " · wird $age" else "")

        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val muteIntent = Intent(ctx, ReminderReceiver::class.java)
            .setAction(ACTION_MUTE)
            .putExtra(EXTRA_ID, b.id)
        val mutePi = PendingIntent.getBroadcast(
            ctx, b.id.hashCode(), muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val muteAction = Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_mute), "Stummschalten", mutePi
        ).build()

        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_cake)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(muteAction)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(b.id.hashCode(), n)
    }
}

/** Wird täglich vom Wecker-Dienst aufgerufen, und vom "Stummschalten"-Knopf in der Benachrichtigung. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val result = goAsync()
        SyncEngine.submit {
            try {
                if (intent.action == Reminders.ACTION_MUTE) {
                    val id = intent.getStringExtra(Reminders.EXTRA_ID)
                    if (id != null) {
                        SyncEngine.setMuted(ctx, id, true)
                        ctx.getSystemService(android.app.NotificationManager::class.java).cancel(id.hashCode())
                    }
                } else {
                    dailyRun(ctx)
                }
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        fun dailyRun(ctx: Context) {
            try {
                SyncEngine.syncNow(ctx)
            } catch (e: Exception) {
                android.util.Log.w("Geburtstage", "Sync", e)
            }
            try {
                Reminders.check(ctx)
            } finally {
                Reminders.schedule(ctx)
            }
        }
    }
}

/** Nach Neustart oder Update den Wecker neu stellen. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val result = goAsync()
        SyncEngine.submit {
            try {
                ReminderReceiver.dailyRun(ctx)
            } finally {
                result.finish()
            }
        }
    }
}
