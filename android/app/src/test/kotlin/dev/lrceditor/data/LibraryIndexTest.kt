package dev.lrceditor.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LibraryIndexTest {
    private val codec = Json { ignoreUnknownKeys = true }

    @Test fun indexFromBeforeVersioningReadsAsVersionZero() {
        val old = """{"root":"content://x","builtAt":1,"songs":[],"durationMs":5,"checks":3}"""
        val idx = codec.decodeFromString(LibraryIndex.serializer(), old)
        assertEquals(0L, idx.version)
        assertNotEquals(LibraryRepository.DATA_VERSION, idx.version) // so it gets rebuilt
    }

    @Test fun versionSurvivesRoundTripEvenWhenHuge() {
        for (v in listOf(1L, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE)) {
            val idx = LibraryIndex("r", 1, emptyList(), 0, v)
            assertEquals(v, codec.decodeFromString(LibraryIndex.serializer(), codec.encodeToString(LibraryIndex.serializer(), idx)).version)
        }
    }

    @Test fun versionIsALong() {
        assertEquals(Long::class, LibraryRepository.DATA_VERSION::class)
    }
}
