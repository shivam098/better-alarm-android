package com.alarmy.app.data

import com.alarmy.core.mission.CodeTarget
import com.alarmy.core.mission.PhotoTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * Registered photo and code targets for the camera missions.
 *
 * Kept out of [Alarm] so the alarm record stays small and cheap to rewrite --
 * it is saved on every toggle. Alarms reference targets by id through
 * `MissionConfig.referenceAssetIds`.
 *
 * Note that only the *fingerprint* of a photo is stored, never the photo
 * itself. A 64-bit hash cannot be turned back into an image, so registering the
 * inside of your home does not leave a picture of it on disk.
 */
@Serializable
private data class TargetBundle(
    val photos: List<PhotoTarget> = emptyList(),
    val codes: List<CodeTarget> = emptyList()
)

class MissionTargetStore(directory: File) {

    private val store = JsonStore(directory)

    private val _bundle = MutableStateFlow(
        store.read(FILE, TargetBundle.serializer(), TargetBundle())
    )

    val photoTargets: StateFlow<TargetBundle>
        get() = _bundle.asStateFlow()

    fun photos(): List<PhotoTarget> = _bundle.value.photos
    fun codes(): List<CodeTarget> = _bundle.value.codes

    fun photo(id: String): PhotoTarget? = _bundle.value.photos.firstOrNull { it.id == id }
    fun code(id: String): CodeTarget? = _bundle.value.codes.firstOrNull { it.id == id }

    fun addPhoto(label: String, fingerprint: com.alarmy.core.mission.PhotoFingerprint): PhotoTarget {
        val target = PhotoTarget(UUID.randomUUID().toString(), label, fingerprint)
        update { it.copy(photos = it.photos + target) }
        return target
    }

    fun addCode(label: String, payload: String): CodeTarget {
        val target = CodeTarget(UUID.randomUUID().toString(), label, payload)
        update { it.copy(codes = it.codes + target) }
        return target
    }

    fun removePhoto(id: String) = update { it.copy(photos = it.photos.filterNot { p -> p.id == id }) }

    fun removeCode(id: String) = update { it.copy(codes = it.codes.filterNot { c -> c.id == id }) }

    private fun update(transform: (TargetBundle) -> TargetBundle) {
        val updated = transform(_bundle.value)
        _bundle.value = updated
        store.write(FILE, TargetBundle.serializer(), updated)
    }

    private companion object {
        const val FILE = "targets.json"
    }
}
