package ca.ilianokokoro.umihi.music.ui.screens.playlist

import ca.ilianokokoro.umihi.music.models.Playlist
import ca.ilianokokoro.umihi.music.models.PlaylistInfo


data class PlaylistState(
    val screenState: ScreenState,
    val isRefreshing: Boolean = false,
    // True while the playlist download worker is actually running.
    val isWorkManagerActive: Boolean = false,
    // True after a download is requested in the current ViewModel before the
    // worker starts running. This state is intentionally not restored from
    // the local database after an app restart.
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