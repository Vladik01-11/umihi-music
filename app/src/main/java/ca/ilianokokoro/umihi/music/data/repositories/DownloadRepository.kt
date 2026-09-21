package ca.ilianokokoro.umihi.music.data.repositories

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import ca.ilianokokoro.umihi.music.core.helpers.ConnectivityHelper
import ca.ilianokokoro.umihi.music.core.helpers.FileHelper
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printd
import ca.ilianokokoro.umihi.music.core.managers.NotificationManager
import ca.ilianokokoro.umihi.music.core.workers.PlaylistDownloadWorker
import ca.ilianokokoro.umihi.music.core.workers.SongDownloadWorker
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import ca.ilianokokoro.umihi.music.models.Playlist
import ca.ilianokokoro.umihi.music.models.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class DownloadRepository(appContext: Context) {
    private val _appContext = appContext.applicationContext
    private val workManager: WorkManager = WorkManager.getInstance(_appContext)
    private val localPlaylistRepository = AppDatabase.getInstance(_appContext).playlistRepository()
    private val localSongRepository = AppDatabase.getInstance(_appContext).songRepository()

    private fun songWorkName(songId: String) = "standalone-song:$songId"

    fun observeSongWork(songId: String): Flow<List<WorkInfo>> =
        workManager.getWorkInfosForUniqueWorkFlow(songWorkName(songId))

    suspend fun downloadSong(song: Song) = withContext(Dispatchers.IO) {
        NotificationManager.observeSongDownloads(_appContext)
        val saved = localSongRepository.getSong(song.youtubeId)
        if (saved?.downloaded == true) return@withContext
        if (getExistingJobs(songWorkName(song.youtubeId)).isNotEmpty()) return@withContext
        localSongRepository.create(saved ?: song.copy(audioFilePath = null, thumbnailPath = null))
        val settings = DatastoreRepository(_appContext).getSettings()
        val request = OneTimeWorkRequestBuilder<SongDownloadWorker>()
            .addTag(SongDownloadWorker.DOWNLOAD_TAG)
            .setInputData(workDataOf(SongDownloadWorker.SONG_KEY to song.youtubeId))
            .setConstraints(
                Constraints(
                    requiredNetworkType = if (settings.downloadOnMetered) NetworkType.CONNECTED else NetworkType.UNMETERED,
                    requiresStorageNotLow = true
                )
            ).build()
        workManager.enqueueUniqueWork(songWorkName(song.youtubeId), ExistingWorkPolicy.KEEP, request)
            .result.get()
        if (!settings.downloadOnMetered && ConnectivityHelper.isMeteredNetwork(_appContext)) {
            NotificationManager.showSongDownloadWaitingForWifi(_appContext, song)
        }
    }

    suspend fun cancelSongDownload(songId: String) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(songWorkName(songId)).result.get()
        NotificationManager.cancelSongDownloadNotification(songId)
    }


    suspend fun deleteSongDownload(songId: String) = withContext(Dispatchers.IO) {
        val song = localSongRepository.getSong(songId) ?: return@withContext
        deleteDownloadFile(song.audioFilePath)
        deleteDownloadFile(song.thumbnailPath)
        localSongRepository.clearDownload(songId)
    }

    private fun deleteDownloadFile(path: String?) {
        if (path.isNullOrBlank()) return
        val uri = path.toUri()
        val deleted = if (uri.scheme == "content") {
            val document = DocumentFile.fromSingleUri(_appContext, uri)
                ?: throw IOException("Cannot access downloaded file")
            !document.exists() || document.delete()
        } else {
            val file = File(if (uri.scheme == "file") requireNotNull(uri.path) else path)
            !file.exists() || file.delete()
        }
        if (!deleted) throw IOException("Cannot delete downloaded file")
    }

    suspend fun downloadPlaylist(playlist: Playlist, useMetered: Boolean = false) {
        val existingWork = getExistingJobs(playlist.info.id)
        if (existingWork.isNotEmpty()) {
            printd("Download is already ongoing for playlist ${playlist.info.title}")
            return
        }
        localPlaylistRepository.insertPlaylistWithSongs(playlist)
        val request = OneTimeWorkRequestBuilder<PlaylistDownloadWorker>().setInputData(
            workDataOf(
                PlaylistDownloadWorker.PLAYLIST_KEY to playlist.info.id
            )
        ).setConstraints(
            Constraints(
                requiredNetworkType = if (useMetered) NetworkType.CONNECTED else NetworkType.UNMETERED,
                requiresStorageNotLow = true
            )
        ).build()


        workManager.enqueueUniqueWork(playlist.info.id, ExistingWorkPolicy.KEEP, request)

        if (!useMetered && ConnectivityHelper.isMeteredNetwork(_appContext)) {
            NotificationManager.showPlaylistDownloadWaitingForWifi(_appContext, playlist)
        }
    }

    suspend fun deletePlaylist(context: Context, playlist: Playlist) = withContext(Dispatchers.IO) {
        val localPlaylist = localPlaylistRepository.getPlaylistById(playlist.info.id) ?: playlist

        FileHelper.deleteStoredFile(context, localPlaylist.info.coverPath)
        localPlaylistRepository.deleteFullPlaylist(localPlaylist.info.id)

        val stillLinked = localPlaylistRepository
            .getSongIdsWithPlaylist(localPlaylist.songs.map { it.youtubeId })
            .toSet()

        val orphaned = localPlaylist.songs.filter { it.youtubeId !in stillLinked }

        orphaned.forEach { song ->
            FileHelper.deleteStoredFile(context, song.audioFilePath)
            FileHelper.deleteStoredFile(context, song.thumbnailPath)
        }

        if (orphaned.isNotEmpty()) {
            localSongRepository.deleteByIds(orphaned.map { it.youtubeId })
        }
    }

    suspend fun downloadSong(playlist: Playlist, song: Song, useMetered: Boolean = false) {
        val id = "${playlist.info.id}${song.youtubeId}"
        val existingWork = getExistingJobs(id)
        if (existingWork.isNotEmpty()) {
            printd("Download is already ongoing for song ${playlist.info.title}")
            return
        }

        localPlaylistRepository.insertPlaylistWithSongs(playlist)
        val request = OneTimeWorkRequestBuilder<SongDownloadWorker>().setInputData(
            workDataOf(
                SongDownloadWorker.PLAYLIST_KEY to playlist.info.id,
                SongDownloadWorker.SONG_KEY to song.youtubeId
            )
        ).setConstraints(
            Constraints(
                requiredNetworkType = if (useMetered) NetworkType.CONNECTED else NetworkType.UNMETERED,
                requiresStorageNotLow = true
            )
        ).build()


        workManager.enqueueUniqueWork(
            id,
            ExistingWorkPolicy.KEEP,
            request
        )

        if (!useMetered && ConnectivityHelper.isMeteredNetwork(_appContext)) {
            NotificationManager.showSongDownloadWaitingForWifi(_appContext, song)
        }
    }

    fun cancelPlaylistDownload(playlist: Playlist) {
        printd("stopping work ${playlist.info.title}")
        workManager.cancelUniqueWork(playlist.info.id)
    }

    fun cancelAllWorks() {
        workManager.cancelAllWork()
    }

    fun getExistingJobFlow(playlist: Playlist): Flow<List<WorkInfo>> {
        return workManager.getWorkInfosForUniqueWorkFlow(playlist.info.id)

    }

    private fun getExistingJobs(id: String): List<WorkInfo> {
        return workManager.getWorkInfosForUniqueWork(id).get().filter {
            it.state == WorkInfo.State.ENQUEUED ||
                    it.state == WorkInfo.State.RUNNING ||
                    it.state == WorkInfo.State.BLOCKED
        }
    }
}
