package ca.ilianokokoro.umihi.music.core.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubeSearchLikeStatusTest {
    @Test
    fun extractsLikedStateFromSearchMenuToggle() {
        val result = YoutubeDataExtractor.extractSearchResults(
            """
            {
              "contents": {
                "tabbedSearchResultsRenderer": {
                  "tabs": [{
                    "tabRenderer": {
                      "selected": true,
                      "content": {
                        "sectionListRenderer": {
                          "contents": [{
                            "musicShelfRenderer": {
                              "contents": [{
                                "musicResponsiveListItemRenderer": {
                                  "thumbnail": {
                                    "thumbnails": [{"url": "https://example.com/art.jpg"}]
                                  },
                                  "playlistItemData": {"videoId": "video"},
                                  "flexColumns": [
                                    {"musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {"runs": [{"text": "Title"}]}
                                    }},
                                    {"musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {"runs": [{"text": "Artist"}, {"text": " - "}, {"text": "3:00"}]}
                                    }}
                                  ],
                                  "menu": {"menuRenderer": {"items": [{
                                    "toggleMenuServiceItemRenderer": {
                                      "defaultIcon": {"iconType": "FAVORITE"},
                                      "toggledIcon": {"iconType": "UNFAVORITE"},
                                      "isToggled": true
                                    }
                                  }]}}
                                }
                              }]
                            }
                          }]
                        }
                      }
                    }
                  }]
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(true, result.single().isLiked)
    }
}
