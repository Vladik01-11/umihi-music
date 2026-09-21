package ca.ilianokokoro.umihi.music.data.repositories

import android.content.Context
import ca.ilianokokoro.umihi.music.core.ApiResult
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import ca.ilianokokoro.umihi.music.data.datasources.SongDataSource
import ca.ilianokokoro.umihi.music.extensions.toException
import ca.ilianokokoro.umihi.music.models.Song
import ca.ilianokokoro.umihi.music.models.UmihiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class SongRepository(private val context: Context? = null) {
    private val songDataSource = SongDataSource()

    fun search(query: String, settings: UmihiSettings? = null): Flow<ApiResult<List<Song>>> {
        return flow {
            emit(ApiResult.Loading)
            val remoteSongs = songDataSource.search(query, settings)
            val localSongs = context?.let {
                remoteSongs
                    .map { song -> song.youtubeId }
                    .takeIf { songIds -> songIds.isNotEmpty() }
                    ?.let { songIds ->
                        AppDatabase.getInstance(it).songRepository()
                            .getSongsByYoutubeIds(songIds)
                            .associateBy { song -> song.youtubeId }
                    }
            }.orEmpty()
            emit(
                ApiResult.Success(
                    remoteSongs.map { song ->
                        localSongs[song.youtubeId]?.let { localSong ->
                            song.copy(
                                thumbnailPath = localSong.thumbnailPath,
                                audioFilePath = localSong.audioFilePath,
                                streamUrl = localSong.streamUrl,
                                isExplicit = localSong.isExplicit || song.isExplicit,
                                isLiked = localSong.isLiked ?: song.isLiked,
                            )
                        } ?: song
                    }
                )
            )
        }.catch { e ->
            emit(ApiResult.Error(e.toException()))
        }.flowOn(Dispatchers.IO)
    }

    fun getSongInfo(songId: String): Flow<ApiResult<Song>> {
        return flow {
            emit(ApiResult.Loading)
            emit(ApiResult.Success(songDataSource.getSongInfo(songId)))
        }.catch { e ->
            emit(ApiResult.Error(e.toException()))
        }.flowOn(Dispatchers.IO)
    }
}
