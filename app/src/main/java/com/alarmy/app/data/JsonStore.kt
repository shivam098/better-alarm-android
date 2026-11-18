package com.alarmy.app.data

import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import android.util.AtomicFile
import android.util.Log

/**
 * A tiny atomic JSON file store.
 *
 * Why not Room: the domain objects in `com.alarmy.core` are already
 * `@Serializable`, there are tens of them rather than tens of thousands, and
 * every read is a whole-collection read. Room would add a KSP annotation
 * processor and a schema to migrate for no benefit. Should the history ever
 * need real querying, only this class changes.
 *
 * Why atomic: an alarm clock is killed at arbitrary moments -- by Doze, by the
 * OEM task killer, by the user force-stopping it mid-write. A half-written
 * `alarms.json` would mean waking up late, so writes always go to a temp file
 * and are renamed into place, which is atomic on every Android filesystem.
 */
class JsonStore(private val directory: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun <T> read(fileName: String, deserializer: kotlinx.serialization.DeserializationStrategy<T>, default: T): T {
        val file = File(directory, fileName)
        if (!file.exists() && !File(directory, "$fileName.bak").exists()) return default
        return try {
            val text = AtomicFile(file).openRead().bufferedReader().use { it.readText() }
            json.decodeFromString(deserializer, text)
        } catch (e: Exception) {
            // A corrupt file must never brick the app. Move it aside so the
            // user can recover it manually and carry on with defaults.
            Log.e("JsonStore", "Cannot read $fileName; retaining corrupt data for recovery", e)
            if (!file.renameTo(File(directory, "$fileName.corrupt"))) {
                Log.e("JsonStore", "Could not preserve corrupt $fileName")
            }
            default
        }
    }

    fun <T> write(fileName: String, serializer: kotlinx.serialization.SerializationStrategy<T>, value: T) {
        val target = AtomicFile(File(directory, fileName))
        val bytes = json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (e: Exception) {
            target.failWrite(stream)
            Log.e("JsonStore", "Could not persist $fileName", e)
            throw e
        }
    }
}
