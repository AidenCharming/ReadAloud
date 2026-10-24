package com.readaloud.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.readaloud.MainActivity
import com.readaloud.R

/**
 * Keeps the process alive and shows media controls while the [Narrator] reads.
 * The Narrator itself is owned by the app's ViewModel; this service only mirrors its state.
 */
class PlaybackService : Service() {
    companion object {
        var narrator: Narrator? = null
        var title: String = ""
        private var running = false

        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 1
        private const val ACTION_TOGGLE = "toggle"
        private const val ACTION_PREV = "prev"
        private const val ACTION_NEXT = "next"
        private const val ACTION_REFRESH = "refresh"
        private const val ACTION_QUIT = "quit"

        /** Called by the Narrator on every state change. Starts the service when playback begins. */
        fun sync(ctx: Context) {
            val n = narrator ?: return
            val intent = Intent(ctx, PlaybackService::class.java).setAction(ACTION_REFRESH)
            if (n.playing) ContextCompat.startForegroundService(ctx, intent)
            else if (running) ctx.startService(intent)
        }

        fun quit(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, PlaybackService::class.java).setAction(ACTION_QUIT))
        }
    }

    private lateinit var session: MediaSession

    override fun onCreate() {
        super.onCreate()
        running = true
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "ReadAloud").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { narrator?.play() }
                override fun onPause() { narrator?.pause() }
                override fun onSkipToNext() { narrator?.let { it.seek(it.index + 1) } }
                override fun onSkipToPrevious() { narrator?.let { it.seek(it.index - 1) } }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = narrator
        if (n == null || intent?.action == ACTION_QUIT) {
            // startForegroundService() obliges us to call startForeground even if we're about to stop
            if (n == null) startForeground(NOTIF_ID, build(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_TOGGLE -> if (n.playing) n.pause() else n.play()
            ACTION_PREV -> n.seek(n.index - 1)
            ACTION_NEXT -> n.seek(n.index + 1)
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(if (n.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, n.index.toLong(), 1f)
                .build()
        )
        val notification = build(n)
        if (n.playing) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            // Paused: keep the notification so it can be resumed, but let the system reclaim the service
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            stopForeground(STOP_FOREGROUND_DETACH)
        }
        return START_NOT_STICKY
    }

    private fun build(n: Narrator?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        fun action(icon: Int, label: String, act: String) = Notification.Action.Builder(
            Icon.createWithResource(this, icon), label,
            PendingIntent.getService(
                this, act.hashCode(), Intent(this, PlaybackService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE
            )
        ).build()

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_read)
            .setContentTitle(title.ifEmpty { "ReadAloud" })
            .setContentText(n?.sentence?.take(120) ?: "")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREV))
            .addAction(
                if (n?.playing == true) action(android.R.drawable.ic_media_pause, "Pause", ACTION_TOGGLE)
                else action(android.R.drawable.ic_media_play, "Play", ACTION_TOGGLE)
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onDestroy() {
        running = false
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
