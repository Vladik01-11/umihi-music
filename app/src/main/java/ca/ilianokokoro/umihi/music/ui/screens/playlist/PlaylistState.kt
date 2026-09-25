package ca.ilianokokoro.umihi.music.ui.screens.playlist

import ca.ilianokokoro.umihi.music.models.Playlist
import ca.ilianokokoro.umihi.music.models.PlaylistInfo


data class PlaylistState(
    val screenState: ScreenState,
    val isRefreshing: Boolean = false,
    // True while WorkManager reports an actual ENQUEUED/RUNNING/BLOCKED job.
    val isWorkManagerActive: Boolean = false,
    // True as soon as the locally stored playlist is flagged to be downloaded
    // and isn't fully downloaded yet. Available instantly from the local DB,
    // so the UI doesn't have to wait for WorkManager to catch up (e.g. right
    // after the app was force-stopped and WorkManager needs a moment to
    // reschedule the interrupted job) before it stops offering "Download".
    val isDownloadPending: Boolean = false,
    val loadedSongsCount: Int = 0,
    val searchQuery: String = "",
    val showingSearch: Boolean = false,
    val isLoggedIn: Boolean = false,
    val optionsExtended: Boolean = false
) {
    val isDownloading: Boolean
        get() = isWorkManagerActive || isDownloadPending
}

sealed class ScreenState {
    data class Success(
        val playlist: Playlist
    ) : ScreenState()

    data class Loading(
        val playlistInfo: PlaylistInfo
    ) : ScreenState()

    data class Error(val exception: Exception) : ScreenState()
}