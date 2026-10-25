package pl.f650gs.forum

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.CookieManager

/**
 * Periodic check (Android's minimum is about 15 minutes) of the forum header counters.
 * Posts a system notification when the count of private messages, forum notifications
 * or unread topics goes up, depending on what is enabled in the app settings.
 */
class ForumCheckJob : JobService() {
    companion object {
        private const val JOB_ID = 6501
        private const val CHANNEL = "forum"
        const val PREFS = "forum_client"
        const val NOTIFY_MESSAGES = "notify_messages"
        const val NOTIFY_FORUM = "notify_forum"
        const val NOTIFY_TOPICS = "notify_topics"
        private const val LAST_INBOX = "last_inbox"
        private const val LAST_NOTIFY = "last_notify"
        private const val LAST_TOPICS = "last_topics"
        private const val ID_MESSAGES = 1
        private const val ID_FORUM = 2
        private const val ID_TOPICS = 3

        fun anyEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return listOf(NOTIFY_MESSAGES, NOTIFY_FORUM, NOTIFY_TOPICS).any { prefs.getBoolean(it, false) }
        }

        /** Schedules or cancels the job to match the settings. */
        fun sync(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (!anyEnabled(context)) {
                scheduler.cancel(JOB_ID)
                return
            }
            if (scheduler.getPendingJob(JOB_ID) != null) return
            // Never take the app down over background checks; worst case there are no notifications.
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, ForumCheckJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build()
            runCatching { scheduler.schedule(job) }
        }

        /** Takes this job's notifications off the status bar once the member is in the app: [ids] or, without any, all of them. */
        fun dismiss(context: Context, vararg ids: Int) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            (if (ids.isEmpty()) intArrayOf(ID_MESSAGES, ID_FORUM, ID_TOPICS) else ids).forEach(manager::cancel)
        }

        /** The notification for [screen]'s list, which the member is looking at now. */
        internal fun dismissFor(context: Context, screen: Screen) = when (screen) {
            Screen.Messages -> dismiss(context, ID_MESSAGES)
            Screen.Notifications -> dismiss(context, ID_FORUM)
            Screen.Unread -> dismiss(context, ID_TOPICS)
            else -> Unit
        }

        /** Remembers counts the user has already seen in the app, so they do not trigger notifications. */
        fun markSeen(context: Context, counters: Counters) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(LAST_INBOX, counters.inbox)
                .putInt(LAST_NOTIFY, counters.notifications)
                .putInt(LAST_TOPICS, counters.unreadTopics)
                .apply()
        }
    }

    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread {
            runCatching { check() }
            jobFinished(params, false)
        }.apply { start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true
    }

    private fun check() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val cookies = CookieManager.getInstance().getCookie("http://f650gs.pl/") ?: return
        val counters = ForumRepository().apply { updateSessionCookies(cookies) }.counters()
        val lastInbox = prefs.getInt(LAST_INBOX, 0)
        val lastNotify = prefs.getInt(LAST_NOTIFY, 0)
        val lastTopics = prefs.getInt(LAST_TOPICS, 0)
        if (prefs.getBoolean(NOTIFY_MESSAGES, false) && counters.inbox > lastInbox) {
            notify(ID_MESSAGES, "Nowa wiadomość prywatna", "Nieprzeczytane rozmowy: ${counters.inbox}", "messages")
        }
        if (prefs.getBoolean(NOTIFY_FORUM, false) && counters.notifications > lastNotify) {
            notify(ID_FORUM, "Nowe powiadomienie na forum", "Odpowiedzi, cytaty lub polubienia: ${counters.notifications}", "notifications")
        }
        if (prefs.getBoolean(NOTIFY_TOPICS, false) && counters.unreadTopics > lastTopics) {
            notify(ID_TOPICS, "Nowe wpisy na forum", "Nieprzeczytane tematy: ${counters.unreadTopics}", "unread")
        }
        markSeen(this, counters)
    }

    private fun notify(id: Int, title: String, text: String, tab: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "f650gs.pl", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            this, id,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SELECT_TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) android.app.Notification.Builder(this, CHANNEL)
            else android.app.Notification.Builder(this)
        manager.notify(
            id,
            builder.setSmallIcon(R.drawable.ic_nav_chat)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}
