package com.alarmy.core.mission

import com.alarmy.core.model.SoundLibrary
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Original three-second PCM tones. No recordings or external assets. */
object AlarmToneSynthesizer {
    fun wav(soundId: String): ByteArray {
        val index = SoundLibrary.all.indexOf(SoundLibrary.soundFor(soundId))
        val sampleRate = 16_000
        val samples = sampleRate * 3
        val output = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        output.put("RIFF".toByteArray()).putInt(36 + samples * 2)
        output.put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        output.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        output.put("data".toByteArray()).putInt(samples * 2)
        var phase = 0.0
        repeat(samples) { n ->
            val seconds = n.toDouble() / sampleRate
            val beat = seconds % 0.5
            val envelope = (beat / 0.015).coerceAtMost(1.0) *
                ((0.38 - beat) / 0.06).coerceIn(0.0, 1.0)
            val base = 330.0 + index * 85.0
            val frequency = when (soundId) {
                "ascend", "sunrise" -> base + seconds * 120
                "siren" -> base + 250 * sin(2 * PI * seconds)
                "chimes" -> base * (1 + (seconds * 4).toInt() % 3 * 0.25)
                "insistent" -> base + if ((seconds * 8).toInt() % 2 == 0) 0 else 300
                else -> base
            }
            phase += 2 * PI * frequency / sampleRate
            val value = (sin(phase) + 0.25 * sin(phase * 2)) * envelope * 16_000
            output.putShort(value.toInt().coerceIn(-32767, 32767).toShort())
        }
        return output.array()
    }
}
