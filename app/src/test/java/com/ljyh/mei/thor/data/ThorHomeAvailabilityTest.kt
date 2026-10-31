package com.ljyh.mei.thor.data

import com.google.gson.JsonParser
import com.ljyh.mei.data.model.SongPlaybackAvailability
import com.ljyh.mei.data.model.isExplicitlyUnavailable
import org.junit.Assert.*
import org.junit.Test

class ThorHomeAvailabilityTest {
    @Test fun actualHomeSongDataCarriesItsPrivilegeWithoutAnyPlaybackUrlProbe() {
        val resource = JsonParser.parseString("""{
          "resourceId":"7","resourceType":"song","title":"Fixture",
          "resourceExtInfo":{"songData":{"id":7,"name":"Fixture","dt":1000,"ar":[],
            "al":{"id":2,"name":"Album","picUrl":"https://example.invalid/cover"},"privilege":{"pl":0}}}
        }""")
        val metadata = parseHomeResources(listOf(resource)).single().tracks.single()
        assertTrue(metadata.isExplicitlyUnavailable)
    }

    @Test fun incompleteHomeCardsStayUnresolvedRatherThanInventingSongMetadataOrPermissions() {
        val resource = JsonParser.parseString("""{
          "resourceId":"7","resourceType":"song","title":"Fixture","privilege":{"pl":0},
          "resourceExtInfo":{"songData":{"id":7,"name":"Fixture"}}
        }""")
        assertTrue(parseHomeResources(listOf(resource)).single().tracks.isEmpty())
    }

    @Test fun unrelatedSiblingPrivilegeNeverOverridesTheActualSongId() {
        val resource = JsonParser.parseString("""{
          "resourceId":"7","resourceType":"song","title":"Fixture",
          "resourceExtInfo":{"songData":{"id":8,"name":"Other","dt":1000,"ar":[],
            "al":{"id":2,"name":"Album","picUrl":"https://example.invalid/cover"},"privilege":{"st":-200}}}
        }""")
        assertTrue(parseHomeResources(listOf(resource)).single().tracks.isEmpty())
    }
}
