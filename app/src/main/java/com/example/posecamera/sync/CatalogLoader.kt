package com.example.posecamera.sync

import com.example.posecamera.data.CatalogExercise
import com.example.posecamera.data.CatalogFood
import com.example.posecamera.data.CatalogMachine
import com.example.posecamera.data.CatalogMuscle
import com.example.posecamera.data.CatalogPlan
import com.example.posecamera.data.CatalogServing
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.IOException
import java.io.Reader

const val CATALOG_PAGE = 500
const val CATALOG_CHUNK = 100 // items per Room transaction; memory stays ~one chunk, not one page

data class CatalogProgress(val version: Long, val offset: Int, val total: Int)

/** Result of one export request. Reader is owned (closed) by the loader. */
sealed interface PageResponse {
    data object NotModified : PageResponse
    class Body(val reader: Reader, val etag: String?) : PageResponse
}

interface CatalogFetcher {
    @Throws(IOException::class)
    fun fetch(entity: String, limit: Int, offset: Int, etag: String?): PageResponse
}

interface CatalogSink {
    fun progress(entity: String): CatalogProgress?
    fun etag(entity: String, offset: Int, limit: Int): String?

    /** One chunk of raw server records = one transaction. Upserts, so replays are harmless. */
    fun insert(entity: String, items: List<JsonObject>)

    /** Atomic: progress + the page's ETag. Called after all of the page's chunks are in. */
    fun pageDone(entity: String, progress: CatalogProgress, limit: Int, etag: String?)

    /** Download complete: clears progress and ETags for the entity. The caller records the version. */
    fun finish(entity: String)
}

/** Callback for UI progress (entity, rows so far, total). */
fun interface CatalogProgressListener {
    fun onProgress(entity: String, done: Int, total: Int)
}

/**
 * Streams /api/catalog/export pages with Gson's JsonReader: one record is materialised at a time and written in
 * [CATALOG_CHUNK]-sized transactions. The server sorts JSON keys, so `items` arrives BEFORE `version`/`total`;
 * the version is therefore only known at the end of a page and checked then. Resumable per entity and page via
 * [CatalogSink.progress]; a version change between pages restarts the entity from offset 0.
 */
class CatalogLoader(
    private val fetcher: CatalogFetcher,
    private val sink: CatalogSink,
    private val listener: CatalogProgressListener = CatalogProgressListener { _, _, _ -> },
    private val limit: Int = CATALOG_PAGE,
    private val chunk: Int = CATALOG_CHUNK,
) {
    @Throws(IOException::class)
    fun download(entity: String) {
        var progress = sink.progress(entity)
        var offset = progress?.offset ?: 0
        var restarts = 0
        while (true) {
            val etag = sink.etag(entity, offset, limit)
            val response = fetcher.fetch(entity, limit, offset, etag)
            val known = progress
            val page: PageMeta = when (response) {
                PageResponse.NotModified -> {
                    // 304 = this exact page (same version) is already stored; only possible while resuming.
                    if (known == null || etag == null) throw IOException("unexpected 304 for $entity@$offset")
                    PageMeta(known.version, known.total)
                }
                is PageResponse.Body -> response.reader.use { readPage(entity, it) }
            }
            if (known != null && known.version != page.version) { // catalog moved under us: start over
                if (++restarts > 2) throw IOException("catalog $entity keeps changing")
                progress = null
                offset = 0
                sink.finish(entity)
                continue
            }
            val next = offset + limit
            val saved = CatalogProgress(page.version, minOf(next, page.total), page.total)
            val newEtag = (response as? PageResponse.Body)?.etag
            if (next >= page.total) {
                sink.finish(entity)
                listener.onProgress(entity, page.total, page.total)
                return
            }
            sink.pageDone(entity, saved.copy(offset = next), limit, newEtag)
            listener.onProgress(entity, next, page.total)
            progress = saved.copy(offset = next)
            offset = next
        }
    }

    private class PageMeta(val version: Long, val total: Int)

    private fun readPage(entity: String, reader: Reader): PageMeta {
        val jr = JsonReader(reader)
        var version = -1L
        var total = -1
        val buffer = ArrayList<JsonObject>(chunk)
        jr.beginObject()
        while (jr.hasNext()) {
            when (jr.nextName()) {
                "version" -> version = jr.nextLong()
                "total" -> total = jr.nextInt()
                "items" -> {
                    jr.beginArray()
                    while (jr.hasNext()) {
                        buffer += JsonParser.parseReader(jr).asJsonObject
                        if (buffer.size >= chunk) {
                            sink.insert(entity, buffer.toList())
                            buffer.clear()
                        }
                    }
                    jr.endArray()
                }
                else -> jr.skipValue()
            }
        }
        jr.endObject()
        if (buffer.isNotEmpty()) sink.insert(entity, buffer.toList())
        if (version < 0 || total < 0) throw IOException("catalog page for $entity lacks version/total")
        return PageMeta(version, total)
    }
}

// ---- server record -> Room rows (pure) ----

private fun JsonObject.s(k: String) = get(k)?.takeUnless { it.isJsonNull }?.asString
private fun JsonObject.d(k: String) = get(k)?.takeUnless { it.isJsonNull }?.asDouble ?: 0.0
private fun JsonObject.b(k: String) = get(k)?.takeUnless { it.isJsonNull }?.asBoolean ?: false

/** Lower-case search key: names joined by a space so " word" LIKE matching finds any word start. */
fun searchKey(vararg names: String?): String = names.filterNotNull().joinToString(" ") { it.trim() }.lowercase()

fun exerciseRow(o: JsonObject) = CatalogExercise(
    slug = o.s("slug")!!, name = o.s("name")!!, search = searchKey(o.s("name")),
    primaryMuscles = o.getAsJsonArray("primary_muscles")?.joinToString(",") { it.asString } ?: "",
    machine = o.s("machine"), movementPattern = o.s("movement_pattern"), difficulty = o.s("difficulty"),
    hasPoseTracking = o.b("has_pose_tracking"), verified = o.b("verified"), json = o.toString(),
)

fun foodRow(o: JsonObject) = CatalogFood(
    slug = o.s("slug")!!, name = o.s("name")!!, nameLocal = o.s("name_local"),
    search = searchKey(o.s("name"), o.s("name_local")), category = o.s("category"), region = o.s("region"),
    kcal = o.d("kcal"), proteinG = o.d("protein_g"), carbsG = o.d("carbs_g"), fatG = o.d("fat_g"),
    verified = o.b("verified"), json = o.toString(),
)

fun servingRows(o: JsonObject): List<CatalogServing> = o.getAsJsonArray("servings")?.map {
    val s = it.asJsonObject
    CatalogServing(o.s("slug")!!, s.s("label")!!, s.get("id")?.takeUnless { x -> x.isJsonNull }?.asLong, s.d("grams"))
} ?: emptyList()

fun machineRow(o: JsonObject) = CatalogMachine(o.s("name")!!, o.s("type"), o.toString())

fun muscleRow(o: JsonObject) = CatalogMuscle(o.s("name")!!)

fun planRow(o: JsonObject) = CatalogPlan(
    o.s("slug")!!, o.s("name")!!, o.s("level"), o.s("goal"), o.d("days_per_week").toInt(), o.s("split"), o.toString(),
)

/** Strips LIKE wildcards/escape so user text is matched literally; then lower-cases. */
fun sanitizeQuery(q: String): String = q.trim().lowercase().filter { it != '%' && it != '_' && it != '\\' }

/** [lo, hi) range that selects every key starting with [q] using the index on `search`. */
fun prefixRange(q: String): Pair<String, String> = q to q + Char.MAX_VALUE

