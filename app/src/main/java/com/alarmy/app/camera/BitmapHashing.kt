package com.alarmy.app.camera

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.alarmy.core.mission.PhotoFingerprint
import com.alarmy.core.mission.PhotoHasher

/**
 * Bridges an Android [Bitmap] to the platform-free hasher in the core module.
 *
 * This is deliberately the *only* image code in the app. Everything that
 * decides whether two photos match is arithmetic in `PhotoHasher`, where it is
 * unit-tested; this file just reduces a bitmap to a grid of grey values.
 */
object BitmapHashing {

    fun fingerprint(bitmap: Bitmap): PhotoFingerprint {
        val width = PhotoFingerprint.DEFAULT_WIDTH
        val height = PhotoFingerprint.DEFAULT_HEIGHT

        // filter = true matters: nearest-neighbour sampling of a large photo
        // down to nine pixels across would latch onto whichever pixels happened
        // to land on the grid, making the hash depend on tiny hand movements.
        val scaled = bitmap.scale(width, height, filter = true)

        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()

        val grey = IntArray(pixels.size) { index ->
            val pixel = pixels[index]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // Rec. 601 luma. Perceptual weighting matters here: a green wall
            // and a red wall of equal measured intensity look very different to
            // a person and should hash differently.
            (r * 299 + g * 587 + b * 114) / 1000
        }
        return PhotoHasher.hash(grey, width, height)
    }
}
