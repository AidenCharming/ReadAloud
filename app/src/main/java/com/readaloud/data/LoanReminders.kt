package com.readaloud.data

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.readaloud.MainActivity
import com.readaloud.R
import java.util.Calendar

/** Reminds the user on the morning a lent book is due back. Alarms are inexact, so it may arrive a little late. */
object LoanReminders {
    private const val CHANNEL = "loans"
    const val EXTRA_BOOK = "bookId"

    private fun pending(ctx: Context, bookId: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, bookId.hashCode(),
        Intent(ctx, LoanReminderReceiver::class.java).putExtra(EXTRA_BOOK, bookId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Sets one alarm per open loan with a due date and cancels the alarms of books that are back or gone. */
    fun reschedule(ctx: Context, shelf: List<ShelfBook>) {
        val alarms = ctx.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        shelf.forEach { book ->
            val loan = book.currentLoan
            val pi = pending(ctx, book.id)
            if (loan != null && loan.dueAt > now) {
                val at = Calendar.getInstance().apply {
                    timeInMillis = loan.dueAt
                    set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                }.timeInMillis.coerceAtLeast(now + 60_000)
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else alarms.cancel(pi)
        }
    }

    fun notify(ctx: Context, book: ShelfBook, friend: String) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lent books", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            book.id.hashCode(),
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_read)
                .setContentTitle("\"${book.title}\" is due back today")
                .setContentText("You lent it to $friend.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }
}

class LoanReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val shelf = Shelf(context).load()
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) { LoanReminders.reschedule(context, shelf); return }
        val book = shelf.firstOrNull { it.id == intent.getStringExtra(LoanReminders.EXTRA_BOOK) } ?: return
        val loan = book.currentLoan ?: return // already returned
        LoanReminders.notify(context, book, loan.friend)
    }
}
