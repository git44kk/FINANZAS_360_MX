package mx.finanzas360.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.time.Instant
import java.time.ZoneId

object ReminderScheduler {
    const val CHANNEL_ID = "finanzas360_reminders"

    fun schedule(context: Context, reminder: ReminderItem) {
        if (reminder.done || reminder.dueAt <= System.currentTimeMillis()) return
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("id", reminder.id)
            putExtra("title", reminder.title)
            putExtra("amount", reminder.amount)
            putExtra("currency", reminder.currency)
            putExtra("repeat", reminder.repeatRule)
            putExtra("dueAt", reminder.dueAt)
        }
        val pending = PendingIntent.getBroadcast(
            context, reminder.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.dueAt, pending)
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Recordatorios financieros", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Fechas de pago, tarjetas y compromisos financieros" }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun rescheduleAll(context: Context) {
        val db = FinanceDb(context)
        db.pendingReminders().forEach { schedule(context, it) }
        db.close()
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler.createChannel(context)
        val id = intent.getLongExtra("id", 0L)
        val db = FinanceDb(context)
        val reminder = db.listReminders().firstOrNull { it.id == id }
        if (reminder == null || reminder.done) {
            db.close()
            return
        }
        val title = reminder.title
        val amount = reminder.amount
        val currency = reminder.currency
        val repeat = reminder.repeatRule
        val dueAt = reminder.dueAt

        val body = if (amount > 0) "$title · ${MoneyFormat.format(amount, currency)}" else title
        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("FINANZAS 360 MX")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id.toInt(), notification)

        if (repeat == "NONE") {
            db.markReminderDone(id)
            db.close()
            return
        }
        if (dueAt > 0L) {
            val next = when (repeat) {
                "WEEKLY" -> dueAt + 7L * 24 * 60 * 60 * 1000
                "MONTHLY" -> {
                    val date = Instant.ofEpochMilli(dueAt).atZone(ZoneId.systemDefault()).toLocalDate().plusMonths(1)
                    date.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
                "YEARLY" -> {
                    val date = Instant.ofEpochMilli(dueAt).atZone(ZoneId.systemDefault()).toLocalDate().plusYears(1)
                    date.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
                else -> 0L
            }
            if (next > System.currentTimeMillis()) {
                db.updateReminderDue(id, next)
                val updated = db.listReminders().firstOrNull { it.id == id }
                if (updated != null) ReminderScheduler.schedule(context, updated)
            }
        }
        db.close()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ReminderScheduler.rescheduleAll(context)
        }
    }
}


object BudgetNotification {
    fun show(
        context: Context,
        categoryName: String,
        actual: Double,
        budget: Double,
        currency: String,
        threshold: Int,
        isIncome: Boolean
    ) {
        ReminderScheduler.createChannel(context)
        val title = if (isIncome) "Meta de ingresos alcanzada" else "Alerta de presupuesto"
        val body = if (isIncome) {
            "$categoryName: llevas ${MoneyFormat.format(actual, currency)} de ${MoneyFormat.format(budget, currency)}."
        } else {
            "$categoryName alcanzó el $threshold%: ${MoneyFormat.format(actual, currency)} de ${MoneyFormat.format(budget, currency)}."
        }
        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()
        val notificationId = (("$categoryName|$threshold|$isIncome").hashCode() and 0x7fffffff).coerceAtLeast(1)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }
}
