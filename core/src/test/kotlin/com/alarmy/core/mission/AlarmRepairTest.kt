package com.alarmy.core.mission

import com.alarmy.core.model.MissionConfig
import com.alarmy.core.model.MissionType
import com.alarmy.core.model.SoundLibrary
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlarmRepairTest {
    @Test
    fun `unregistered camera children are replaced with a completable mission`() {
        val requested = MissionConfig(
            type = MissionType.COMBINATION, targetCount = 2,
            children = listOf(MissionConfig(type = MissionType.PHOTO), MissionConfig(type = MissionType.QR))
        )
        val resolved = MissionLibrary.resolve(requested, MissionType.entries, { false }, MissionType.NONE)
        assertEquals(2, resolved.children.size)
        assertTrue(resolved.children.all { it.type == MissionType.TYPING })
    }

    @Test
    fun `valid target and explicit safe fallback are honored independently`() {
        val photo = MissionConfig(type = MissionType.PHOTO, targetCount = 1, referenceAssetIds = listOf("sink"))
        assertEquals(photo, MissionLibrary.resolve(photo, MissionType.entries, { true }))
        val missing = MissionLibrary.resolve(photo, MissionType.entries, { false }, MissionType.MEMORY)
        assertEquals(MissionType.MEMORY, missing.type)
        assertTrue(missing.referenceAssetIds.isEmpty())
    }

    @Test
    fun `all selectable tones are valid distinct nonsilent PCM`() {
        val tones = SoundLibrary.all.map { AlarmToneSynthesizer.wav(it.id) }
        assertEquals(tones.size, tones.map { it.contentHashCode() }.toSet().size)
        tones.forEach { wav ->
            assertEquals("RIFF", String(wav, 0, 4))
            val data = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(wav.size - 8, data.getInt(4))
            assertEquals(16_000, data.getInt(24))
            assertEquals(wav.size - 44, data.getInt(40))
            assertTrue((44 until wav.size step 2).count { data.getShort(it).toInt() != 0 } > 20_000)
        }
    }

    @Test
    fun `custom typing sentence must actually be typed`() {
        val challenge = TypingChallenge("Open the green curtains.", true)
        assertEquals(MissionAttemptResult.Correct, challenge.check("Open the green curtains."))
        assertFalse(challenge.check("open the green curtains.") is MissionAttemptResult.Correct)
        assertFalse(challenge.check("") is MissionAttemptResult.Correct)
    }
}
