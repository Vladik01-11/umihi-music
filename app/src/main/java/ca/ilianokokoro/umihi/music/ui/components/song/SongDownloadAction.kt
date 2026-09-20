package ca.ilianokokoro.umihi.music.ui.components.song

import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.FileDownloadOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.ilianokokoro.umihi.music.R
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import ca.ilianokokoro.umihi.music.data.repositories.DownloadRepository
import ca.ilianokokoro.umihi.music.models.Song
import ca.ilianokokoro.umihi.music.ui.components.dialog.ConfirmDialog
import ca.ilianokokoro.umihi.music.ui.components.materialu.dropdown.MaterialUDropdownItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SongDownloadAction(song: Song, inMenu: Boolean = false) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { DownloadRepository(context) }
    val songFlow = remember(context, song.youtubeId) {
        AppDatabase.getInstance(context).songRepository().observeSong(song.youtubeId)
    }
    val workFlow = remember(repository, song.youtubeId) {
        repository.observeSongWork(song.youtubeId)
    }
    val saved by songFlow.collectAsStateWithLifecycle(initialValue = null)
    val work by workFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val downloading = work.any { !it.state.isFinished }
    val downloaded = saved?.downloaded == true
    var confirming by remember(song.youtubeId) { mutableStateOf(false) }
    var busy by remember(song.youtubeId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val errorText = stringResource(R.string.song_download_action_failed)
    val label = stringResource(
        when {
            downloading -> R.string.cancel_download
            downloaded -> R.string.remove_download
            else -> R.string.download
        }
    )
    val icon = when {
        downloading -> Icons.Rounded.Cancel
        downloaded && inMenu -> Icons.Rounded.FileDownloadOff
        downloaded -> Icons.Rounded.DownloadDone
        else -> Icons.Rounded.Download
    }
    fun execute(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(context, errorText, Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }
    val onClick: () -> Unit = {
        if (!busy) {
            when {
                downloading -> execute { repository.cancelSongDownload(song.youtubeId) }
                downloaded -> confirming = true
                else -> execute { repository.downloadSong(song) }
            }
        }
    }
    if (inMenu) {
        MaterialUDropdownItem(leadingIcon = icon, text = label, onClick = onClick)
    } else {
        IconButton(onClick = onClick, enabled = !busy) {
            if (downloading || busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(imageVector = icon, contentDescription = label)
            }
        }
    }
    if (confirming) {
        ConfirmDialog(
            title = stringResource(R.string.remove_download),
            text = stringResource(R.string.remove_song_download_confirm_text, song.title),
            onConfirm = {
                confirming = false
                execute { repository.deleteSongDownload(song.youtubeId) }
            },
            onDismiss = { confirming = false }
        )
    }
}
