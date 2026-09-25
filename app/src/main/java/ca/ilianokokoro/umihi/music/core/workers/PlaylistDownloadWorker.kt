package ca.ilianokokoro.umihi.music.core.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ca.ilianokokoro.umihi.music.core.ApiResult
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.helpers.DownloadHelper
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printd
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe
import ca.ilianokokoro.umihi.music.core.managers.NotificationManager
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import ca.ilianokokoro.umihi.music.data.repositories.SongRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.cancellation.CancellationException

class PlaylistDownloadWorker(
    private val appContext: Context,
    private val params: WorkerParameters
) :
    CoroutineWorker(appContext, params) {

    private val playlistRepository = AppDatabase.getInstance(appContext).playlistRepository()
    private val localSongRepository = AppDatabase.getInstance(appContext).songRepository()
    private val songRepository = SongRepository()

    private val progressUpdate = Mutex()
    private var lastProgressNotifyAt = 0L

    @OptIn(ExperimentalAtomicApi::class)
    override suspend fun doWork(): Result {
        val playlistId = params.inputData.getString(PLAYLIST_KEY)
            ?: return Result.failure()

        val playlist = playlistRepository.getPlaylistById(playlistId)
            ?: return Result.failure()

        return try {
            val totalSongs = playlist.songs.size
            val downloadedSongs = AtomicInt(0)

            NotificationManager.showPlaylistDownloadProgress(
                appContext,
                playlist,
                0,
                totalSongs
            )

            val playlistImage = DownloadHelper.downloadImage(
                appContext,
                playlist.info.coverHref,
                playlist.info.id
            )

            playlistRepository.insertPlaylist(
                playlist.info.copy(
                    coverPath = playlistImage?.path
                )
            )

            val semaphore = Semaphore(Constants.Downloads.MAX_CONCURRENT_DOWNLOADS)
            coroutineScope {
                playlist.songs.map { song ->
                    async {
                        semaphore.withPermit {
                            try {
                                val fullSongData = songRepository
                                    .getSongInfo(song.youtubeId)
                                    .first { it is ApiResult.Success }

                                val fullSong = (fullSongData as ApiResult.Success).data

                                val audioPath = DownloadHelper.downloadAudio(appContext, song)

                                val thumbnailPath = DownloadHelper.downloadImage(
                                    appContext,
                                    fullSong.thumbnailHref,
                                    song.youtubeId
                                )

                                val updatedSong = song.copy(
                                    thumbnailPath = thumbnailPath?.path,
                                    audioFilePath = audioPath,
                                )

                                localSongRepository.create(updatedSong)
                            } catch (e: CancellationException) {
                                printd("Song download canceled ${song.title}")
                                throw e
                            } catch (e: Exception) {
                                NotificationManager.showSongDownloadFailed(appContext, song)
                                printe(
                                    message = "Error downloading song: ${song.title}",
                                    exception = e
                                )
                            } finally {
                                progressUpdate.withLock {
                                    val downloaded = downloadedSongs.incrementAndFetch()
                                    val now = System.currentTimeMillis()
                                    // Songs that were already downloaded (or small
                                    // playlists) can all finish within milliseconds of each
                                    // other. Posting a notify() for every single one floods
                                    // the same notification id, and Android can silently
                                    // drop updates fired in that fast a burst - including
                                    // the terminal "download complete" one posted right
                                    // after this loop finishes. Throttle intermediate
                                    // updates so the final notify() call has a clear slot.
                                    if (now - lastProgressNotifyAt >= PROGRESS_NOTIFY_MIN_INTERVAL_MS) {
                                        lastProgressNotifyAt = now
                                        NotificationManager.showPlaylistDownloadProgress(
                                            appContext, playlist, downloaded, totalSongs
                                        )
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }

            // Wrapped in NonCancellable: if WorkManager decides to stop this worker
            // (e.g. a constraint like the network type briefly stops being met)
            // right as the last song finishes, the coroutine's Job can already be
            // cancelled by the time we get here. Without this, the notify() call
            // below would be skipped silently and this whole, fully finished
            // download would fall into the catch block and get treated as
            // interrupted/retried instead of successful.
            withContext(NonCancellable) {
                playlistRepository.insertPlaylist(
                    playlist.info.copy(shouldBeDownloaded = false)
                )
                NotificationManager.showPlaylistDownloadSuccess(appContext, playlist)
            }
            printd("Playlist download complete")

            Result.success()
        } catch (_: CancellationException) {
            // The whole handling must run under NonCancellable: doWork()'s coroutine
            // is already cancelled at this point, so any suspending call made outside
            // of this context (including the notification post below) would be
            // cancelled immediately, silently skipping it and leaving the ongoing
            // progress notification stuck on screen.
            withContext(NonCancellable) {
                val isUserCancelled = try {
                    WorkManager.getInstance(appContext)
                        .getWorkInfoById(params.id)
                        .get()
                        ?.state == WorkInfo.State.CANCELLED
                } catch (e: Exception) {
                    false
                }

                if (isUserCancelled) {
                    NotificationManager.showPlaylistDownloadCanceled(appContext, playlist)
                    printd("Playlist download canceled ${playlist.info.title}")
                    Result.failure()
                } else {
                    printd("Playlist download interrupted, retrying ${playlist.info.title}")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            withContext(NonCancellable) {
                NotificationManager.showPlaylistDownloadFailure(appContext, playlist)
            }
            printe(message = e.toString(), exception = e)
            Result.failure()
        }
    }


    companion object {
        const val PLAYLIST_KEY = "playlist"
        private const val PROGRESS_NOTIFY_MIN_INTERVAL_MS = 500L
    }
}