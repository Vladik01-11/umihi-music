package ca.ilianokokoro.umihi.music.core.managers

import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import ca.ilianokokoro.umihi.music.R
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe
import ca.ilianokokoro.umihi.music.models.Playlist
import ca.ilianokokoro.umihi.music.models.Song
import ca.ilianokokoro.umihi.music.core.workers.SongDownloadWorker
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import android.app.NotificationManager as AndroidNotificationManager

object NotificationManager {
    private const val SONG_DOWNLOAD_SUMMARY_ID = 2
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadsJob: Job? = null
    private lateinit var androidNotificationManager: AndroidNotificationManager
    private lateinit var pendingIntent: PendingIntent

    @Synchronized
    fun init(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        androidNotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannels.entries.forEach {
                val notificationChannel = NotificationChannel(
                    it.channelId,
                    context.getString(it.nameRes),
                    it.importance
                ).apply {
                    description = context.getString(it.descriptionRes)
                }
                androidNotificationManager.createNotificationChannel(notificationChannel)
            }
        } else {
            printe("Could not start the notification channels because the android version is too old")
        }
    }

    @Synchronized
    fun observeSongDownloads(context: Context) {
        if (downloadsJob?.isActive == true) return
        val appContext = context.applicationContext
        init(appContext)
        downloadsJob = scope.launch {
            WorkManager.getInstance(appContext).getWorkInfosByTagFlow(SongDownloadWorker.DOWNLOAD_TAG)
                .map { infos -> infos.count { !it.state.isFinished } }
                .distinctUntilChanged()
                .collect { remaining -> showSongDownloadsRemaining(appContext, remaining) }
        }
    }

    fun showPlaylistDownloadWaitingForWifi(
        context: Context,
        playlist: Playlist,
    ) {
        val notification = getBaseNotification(context, NotificationChannels.PLAYLIST_DOWNLOAD)
            .setContentTitle(playlist.info.title)
            .setContentText(
                context.getString(
                    R.string.waiting_for_wifi_to_download,
                    playlist.info.title
                )
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setGroup(NotificationChannels.PLAYLIST_DOWNLOAD.group)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        androidNotificationManager.notify(getNotificationID(playlist.info.id), notification)
        updateGroupSummary(context)
    }

    fun showPlaylistDownloadProgress(
        context: Context,
        playlist: Playlist,
        currentSong: Int,
        totalSongs: Int
    ) {

        val notification = getBaseNotification(context, NotificationChannels.PLAYLIST_DOWNLOAD)
            .setContentTitle(playlist.info.title)
            .setContentText(
                context.getString(
                    R.string.number_of_songs_downloaded,
                    currentSong,
                    totalSongs
                )
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(totalSongs, currentSong, false)
            .setOngoing(true)
            .setGroup(NotificationChannels.PLAYLIST_DOWNLOAD.group)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        androidNotificationManager.notify(getNotificationID(playlist.info.id), notification)
        updateGroupSummary(context)
    }

    fun showPlaylistDownloadSuccess(
        context: Context,
        playlist: Playlist,
    ) {
        val notification = getBaseNotification(context, NotificationChannels.PLAYLIST_DOWNLOAD)
            .setContentTitle(playlist.info.title)
            .setContentText(context.getString(R.string.playlist_downloaded))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setGroup(NotificationChannels.PLAYLIST_DOWNLOAD.group)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        androidNotificationManager.notify(getNotificationID(playlist.info.id), notification)
        updateGroupSummary(context)
    }

    fun showPlaylistDownloadFailure(
        context: Context,
        playlist: Playlist,
    ) {
        val notification = getBaseNotification(context, NotificationChannels.PLAYLIST_DOWNLOAD)
            .setContentTitle(context.getString(R.string.download_failed))
            .setContentText(
                context.getString(
                    R.string.failed_to_download_playlist,
                    playlist.info.title
                )
            )
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setGroup(NotificationChannels.PLAYLIST_DOWNLOAD.group)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        androidNotificationManager.notify(getNotificationID(playlist.info.id), notification)
        updateGroupSummary(context)
    }

    fun showPlaylistDownloadCanceled(
        context: Context,
        playlist: Playlist
    ) {
        val notification = getBaseNotification(context, NotificationChannels.PLAYLIST_DOWNLOAD)
            .setContentTitle(playlist.info.title)
            .setContentText(context.getString(R.string.download_canceled))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setGroup(NotificationChannels.PLAYLIST_DOWNLOAD.group)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        androidNotificationManager.notify(getNotificationID(playlist.info.id), notification)
        updateGroupSummary(context)
    }

    private fun updateGroupSummary(context: Context) {
        val summaryNotification =
            getBaseNotification(context, NotificationChannels.PLAYLIST_DOWNLOAD)
                .setContentTitle(context.getString(R.string.download_finished))
                .setContentText(String())
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setGroup(NotificationChannels.PLAYLIST_DOWNLOAD.group)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        androidNotificationManager.notify(0, summaryNotification)
    }


    fun showSongDownloadWaitingForWifi(
        context: Context,
        song: Song,
    ) {
        val notification = getBaseNotification(context, NotificationChannels.SONG_DOWNLOAD)
            .setContentTitle(song.title)
            .setContentText(context.getString(R.string.waiting_for_wifi_to_download, song.title))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setGroup(NotificationChannels.SONG_DOWNLOAD.group)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        androidNotificationManager.notify(getNotificationID(song.youtubeId), notification)
    }

    fun showSongDownloadFailed(
        context: Context,
        song: Song,
    ) {
        val notification = getBaseNotification(context, NotificationChannels.SONG_DOWNLOAD)
            .setContentTitle(context.getString(R.string.download_failed))
            .setContentText(
                context.getString(
                    R.string.failed_to_download_song,
                    song.title,
                    song.artist
                )
            )
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setGroup(NotificationChannels.SONG_DOWNLOAD.group)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        androidNotificationManager.notify(getNotificationID(song.youtubeId), notification)
    }

    suspend fun showSongDownloadSuccess(
        context: Context,
        song: Song,
    ) {
        val notification = getBaseNotification(context, NotificationChannels.SONG_DOWNLOAD)
            .setContentTitle(song.title)
            .setContentText(context.getString(R.string.song_downloaded))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setLargeIcon(song.getThumbnailBitmap())
            .setAutoCancel(true)
            .setGroup(NotificationChannels.SONG_DOWNLOAD.group)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        androidNotificationManager.notify(getNotificationID(song.youtubeId), notification)
    }

    fun showSongDownloadProgress(context: Context, song: Song, bytes: Long, total: Long) {
        val percent = if (total > 0) (100.0 * bytes / total).toInt().coerceIn(0, 100) else 0
        val notification = getBaseNotification(context, NotificationChannels.SONG_DOWNLOAD)
            .setContentTitle(song.title)
            .setContentText(context.getString(R.string.song_download_progress, percent))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(NotificationChannels.SONG_DOWNLOAD.group)
            .build()
        androidNotificationManager.notify(getNotificationID(song.youtubeId), notification)
    }

    fun showSongDownloadsRemaining(context: Context, remaining: Int) {
        if (remaining == 0) {
            androidNotificationManager.cancel(SONG_DOWNLOAD_SUMMARY_ID)
            return
        }
        val notification = getBaseNotification(context, NotificationChannels.SONG_DOWNLOAD)
            .setContentTitle(context.getString(R.string.download))
            .setContentText(context.getString(R.string.song_downloads_remaining, remaining))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(NotificationChannels.SONG_DOWNLOAD.group)
            .setGroupSummary(true)
            .build()
        androidNotificationManager.notify(SONG_DOWNLOAD_SUMMARY_ID, notification)
    }

    fun cancelSongDownloadNotification(songId: String) {
        androidNotificationManager.cancel(getNotificationID(songId))
    }

    private fun getBaseNotification(
        context: Context,
        channel: NotificationChannels
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(
            context,
            channel.channelId
        )
            .setContentIntent(pendingIntent)
    }

    private fun getNotificationID(id: String): Int {
        return 1000 + abs(id.hashCode() and 0x7fffffff)
    }


    private enum class NotificationChannels(
        val channelId: String,
        @param:StringRes val nameRes: Int,
        @param:StringRes val descriptionRes: Int,
        val importance: Int,
        val group: String
    ) {

        PLAYLIST_DOWNLOAD(
            channelId = "playlist_progress",
            nameRes = R.string.playlist_progress_name,
            descriptionRes = R.string.playlist_progress_description,
            importance = AndroidNotificationManager.IMPORTANCE_LOW,
            group = "PLAYLIST_GROUP"
        ),

        SONG_DOWNLOAD(
            channelId = "song_alerts",
            nameRes = (R.string.song_alerts_name),
            descriptionRes = (R.string.song_alerts_description),
            importance = AndroidNotificationManager.IMPORTANCE_DEFAULT,
            group = "SONG_GROUP"
        );
    }
}
