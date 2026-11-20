package io.github.jimthefatjam.polaris.core.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

data class CachedPayload(val raw: String, val fetchedAt: Instant)

interface WeatherCache {
    suspend fun read(key: String): CachedPayload?
    suspend fun write(key: String, payload: CachedPayload)
}

/**
 * Stores the raw provider JSON on disk (one small file per rounded location). Raw JSON is cached
 * rather than parsed objects so that parser improvements apply to cached data, and so a corrupt
 * file can simply be discarded.
 */
class FileWeatherCache(private val dir: File) : WeatherCache {
    override suspend fun read(key: String): CachedPayload? = withContext(Dispatchers.IO) {
        val f = File(dir, "$key.json")
        val meta = File(dir, "$key.meta")
        if (!f.exists() || !meta.exists()) return@withContext null
        runCatching { CachedPayload(f.readText(), Instant.ofEpochMilli(meta.readText().trim().toLong())) }.getOrNull()
    }

    override suspend fun write(key: String, payload: CachedPayload) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, "$key.json.tmp")
        tmp.writeText(payload.raw)
        tmp.renameTo(File(dir, "$key.json"))
        File(dir, "$key.meta").writeText(payload.fetchedAt.toEpochMilli().toString())
    }
}

class InMemoryWeatherCache : WeatherCache {
    private val map = mutableMapOf<String, CachedPayload>()
    override suspend fun read(key: String) = synchronized(map) { map[key] }
    override suspend fun write(key: String, payload: CachedPayload) { synchronized(map) { map[key] = payload } }
}
