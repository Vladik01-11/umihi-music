package ca.ilianokokoro.umihi.music.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongDownloadMetadataTest {
    private fun song() = Song(
        youtubeId = "video",
        uid = "row",
        title = "Playlist title",
        artist = "Playlist artist",
        duration = "3:12",
        isExplicit = true,
        isLiked = true,
    )

    @Test
    fun preservesPlaylistMetadataWhenPlayerResponseLacksFlags() {
        val original = song().also { it.setVideoId = "playlist-entry" }
        val details = Song(
            youtubeId = "video",
            uid = "details",
            title = "Video title",
            artist = "Channel",
            thumbnailHref = "https://example.com/cover.jpg",
        )

        val merged = original.withDownloadMetadata(details)

        assertTrue(merged.isExplicit)
        assertEquals(true, merged.isLiked)
        assertEquals(original.title, merged.title)
        assertEquals(original.artist, merged.artist)
        assertEquals(original.duration, merged.duration)
        assertEquals(original.uid, merged.uid)
        assertEquals(original.youtubeId, merged.youtubeId)
        assertEquals(original.setVideoId, merged.setVideoId)
        assertEquals(details.thumbnailHref, merged.thumbnailHref)
    }

    @Test
    fun fillsMissingLinkMetadataAndKeepsExistingArtwork() {
        val original = Song(youtubeId = "video", uid = "row", duration = "0:00", thumbnailHref = "cover")
        val merged = original.withDownloadMetadata(song())

        assertEquals("Playlist title", merged.title)
        assertEquals("Playlist artist", merged.artist)
        assertEquals("3:12", merged.duration)
        assertEquals("cover", merged.thumbnailHref)
        assertTrue(merged.isExplicit)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMetadataForAnotherSong() {
        song().withDownloadMetadata(Song(youtubeId = "other", uid = "other-row"))
    }
}
