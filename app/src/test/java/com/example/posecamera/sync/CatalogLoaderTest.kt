package com.example.posecamera.sync

import com.google.gson.JsonObject
import java.io.IOException
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class MemSink : CatalogSink {
    var progress: CatalogProgress? = null
    val etags = mutableMapOf<String, String>()
    val chunks = mutableListOf<List<String>>() // slugs per insert call
    var failAtChunk = -1
    var finished = 0
    override fun progress(entity: String) = progress
    override fun etag(entity: String, offset: Int, limit: Int) = etags["$entity:$offset:$limit"]
    override fun insert(entity: String, items: List<JsonObject>) {
        if (chunks.size == failAtChunk) throw IOException("boom")
        chunks += items.map { it["slug"].asString }
    }
    override fun pageDone(entity: String, progress: CatalogProgress, limit: Int, etag: String?) {
        this.progress = progress
        if (etag != null) etags["$entity:${progress.offset - limit}:$limit"] = etag
    }
    override fun finish(entity: String) { progress = null; etags.clear(); finished++ }
}

/** Serves `total` foods in pages. Keys are in the order the real server emits them (sorted: items first). */
private class FakeServer(val total: Int, var version: Int = 1) : CatalogFetcher {
    val requests = mutableListOf<Triple<Int, Int, String?>>() // offset, limit, if-none-match
    var onFetch: (Int) -> Unit = {}
    override fun fetch(entity: String, limit: Int, offset: Int, etag: String?): PageResponse {
        requests += Triple(offset, limit, etag)
        onFetch(requests.size)
        val tag = "W/\"$entity-$version-$limit-$offset\""
        if (etag == tag) return PageResponse.NotModified
        val items = (offset until minOf(total, offset + limit)).joinToString(",") {
            """{"slug":"food-$it","name":"Food $it","kcal":${it + 1},"protein_g":1,"carbs_g":1,"fat_g":1,
               "servings":[{"id":$it,"label":"1 piece","grams":50}],"sources":[]}"""
        }
        val body = """{"entity":"$entity","items":[$items],"limit":$limit,"offset":$offset,"total":$total,"version":$version}"""
        return PageResponse.Body(StringReader(body), tag)
    }
}

class CatalogLoaderTest {
    @Test fun pagesAreStreamedInChunksAndFinishOnce() {
        val sink = MemSink()
        val server = FakeServer(total = 25)
        val seen = mutableListOf<Pair<Int, Int>>()
        CatalogLoader(server, sink, { _, done, total -> seen += done to total }, limit = 10, chunk = 4).download("foods")
        assertEquals(listOf(0, 10, 20), server.requests.map { it.first })
        assertEquals(listOf(4, 4, 2, 4, 4, 2, 4, 1), sink.chunks.map { it.size }) // never more than one chunk in memory
        assertEquals((0 until 25).map { "food-$it" }, sink.chunks.flatten())
        assertEquals(1, sink.finished)
        assertNull(sink.progress)
        assertEquals(25 to 25, seen.last())
    }

    @Test fun resumesFromTheLastCompletedPage() {
        val sink = MemSink()
        val server = FakeServer(total = 25)
        server.onFetch = { n -> if (n == 2) throw IOException("connection lost") } // page 2 never arrives
        try {
            CatalogLoader(server, sink, limit = 10, chunk = 4).download("foods")
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals(CatalogProgress(1, 10, 25), sink.progress) // page 1 is stored and recorded
        }
        server.onFetch = {}
        server.requests.clear()
        sink.chunks.clear()
        CatalogLoader(server, sink, limit = 10, chunk = 4).download("foods")
        assertEquals(listOf(10, 20), server.requests.map { it.first }) // did not re-download page 1
        assertEquals((10 until 25).map { "food-$it" }, sink.chunks.flatten())
        assertEquals(1, sink.finished)
    }

    @Test fun crashMidPageReplaysThatPageIdempotently() {
        val sink = MemSink()
        sink.failAtChunk = 1 // second chunk of page 1 fails
        try {
            CatalogLoader(FakeServer(10), sink, limit = 10, chunk = 4).download("foods")
            fail("expected IOException")
        } catch (e: IOException) {
            assertNull(sink.progress) // page not recorded as done
        }
        sink.failAtChunk = -1
        sink.chunks.clear()
        CatalogLoader(FakeServer(10), sink, limit = 10, chunk = 4).download("foods")
        assertEquals((0 until 10).map { "food-$it" }, sink.chunks.flatten()) // whole page again; upserts make it harmless
    }

    @Test fun etagTurnsAnAlreadyStoredPageInto304() {
        val sink = MemSink()
        val server = FakeServer(total = 25)
        // page 0 was stored under version 1, then the run died before page 1
        sink.progress = CatalogProgress(1, 0, 25)
        sink.etags["foods:0:10"] = "W/\"foods-1-10-0\""
        CatalogLoader(server, sink, limit = 10, chunk = 4).download("foods")
        assertEquals("W/\"foods-1-10-0\"", server.requests[0].third) // If-None-Match sent
        assertEquals((10 until 25).map { "food-$it" }, sink.chunks.flatten()) // page 0 body was NOT re-read
        assertEquals(listOf(0, 10, 20), server.requests.map { it.first })
    }

    @Test fun versionChangeBetweenPagesRestartsFromZero() {
        val sink = MemSink()
        val server = FakeServer(total = 15)
        server.onFetch = { n -> if (n == 2) server.version = 2 } // catalog moves after page 1
        CatalogLoader(server, sink, limit = 10, chunk = 100).download("foods")
        assertEquals(listOf(0, 10, 0, 10), server.requests.map { it.first })
        assertEquals(2, sink.finished) // once for the restart, once on completion
        assertTrue(sink.progress == null)
    }

    @Test fun unexpected304WithoutProgressFails() {
        val sink = MemSink()
        sink.etags["foods:0:10"] = "W/\"foods-1-10-0\""
        try {
            CatalogLoader(FakeServer(5), sink, limit = 10).download("foods")
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("304"))
        }
    }
}

class CatalogMapperTest {
    private fun obj(s: String) = com.google.gson.JsonParser.parseString(s).asJsonObject

    @Test fun foodRowAndServings() {
        val o = obj("""{"slug":"roti","name":"Roti","name_local":"Chapati","category":"cereal","region":null,"kcal":297,
            "protein_g":9.5,"carbs_g":46,"fat_g":7,"verified":true,"servings":[{"id":4,"label":"1 roti","grams":40}],"sources":[]}""")
        val f = foodRow(o)
        assertEquals("roti chapati", f.search)
        assertEquals(297.0, f.kcal, 0.0)
        assertEquals(listOf("1 roti"), servingRows(o).map { it.label })
        assertEquals(4L, servingRows(o)[0].serverId)
    }

    @Test fun exerciseRow() {
        val e = exerciseRow(obj("""{"slug":"bodyweight-squat","name":"Bodyweight Squat","primary_muscles":["quadriceps","glutes"],
            "machine":null,"movement_pattern":"squat","difficulty":"beginner","has_pose_tracking":true,"verified":false}"""))
        assertEquals("quadriceps,glutes", e.primaryMuscles)
        assertTrue(e.hasPoseTracking)
    }

    @Test fun searchSanitizingAndRange() {
        assertEquals("ro ti", sanitizeQuery("  Ro%_ T\\i "))
        val (lo, hi) = prefixRange("ro")
        assertTrue("roti" >= lo && "roti" < hi && "rice" < lo && "sroti" >= hi)
    }
}
