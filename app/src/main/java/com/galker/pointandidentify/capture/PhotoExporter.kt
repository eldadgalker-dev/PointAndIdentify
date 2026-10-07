// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.5
package com.galker.pointandidentify.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import com.galker.pointandidentify.config.AppConfig
import com.galker.pointandidentify.ui.OverlayContent
import com.galker.pointandidentify.ui.OverlayRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Metadata written to EXIF of the saved photo; dest* describe the selected target (GPSDest* tags). */
data class PhotoMeta(
    val lat: Double?,
    val lon: Double?,
    val altitudeM: Double?,
    val trueAzimuthDeg: Double?,
    val destLat: Double? = null,
    val destLon: Double? = null,
    val destBearingDeg: Double? = null,
    val destDistanceM: Double? = null
)

/**
 * Burns the overlay into the captured JPEG and saves it with EXIF metadata.
 * Memory: exactly one full-resolution bitmap. Pixels are not rotated; the overlay is drawn in the
 * upright frame through a canvas transform and the EXIF orientation tag carries the rotation.
 */
class PhotoExporter(private val context: Context) {

    private val renderer = OverlayRenderer()

    /**
     * Must be called on a background thread; closes the ImageProxy.
     * extraZoom > 1 applies the extra calculated zoom: the centre 1/extraZoom of the frame is kept (no upscaling).
     * viewAspect (width / height of the on-screen preview, 0 = none): the photo is cut to the shape of the screen view,
     * so the saved picture shows what the user saw, with the crosshair at the same place in the picture.
     */
    suspend fun export(
        image: ImageProxy, content: OverlayContent, meta: PhotoMeta, extraZoom: Double = 1.0, viewAspect: Double = 0.0
    ): Uri =
        withContext(Dispatchers.Default) {
            val rotation = image.imageInfo.rotationDegrees
            val bitmap = image.use { cropToView(decodeMutable(it), rotation, viewAspect, extraZoom) }
            val tmp = File.createTempFile("capture_", ".jpg", context.cacheDir) // unique: two quick shots must not share a file
            try {
                burnOverlay(bitmap, rotation, content)
                tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, AppConfig.JPEG_QUALITY, it) }
                writeExif(tmp, rotation, meta)
                withContext(Dispatchers.IO) { publish(tmp) }
            } finally {
                bitmap.recycle()
                tmp.delete()
            }
        }

    /**
     * Centre crop to the screen shape and the calculated zoom, done in the upright frame and mapped to the raw
     * sensor pixels (width and height swap for 90 / 270 degrees). Returns the source itself when no crop is needed.
     */
    private fun cropToView(src: Bitmap, rotation: Int, viewAspect: Double, extraZoom: Double): Bitmap {
        val quarter = rotation % 180 != 0
        val uprightW = (if (quarter) src.height else src.width).toDouble()
        val uprightH = (if (quarter) src.width else src.height).toDouble()
        var cropW = uprightW
        var cropH = uprightH
        if (viewAspect > 0.0) {
            if (viewAspect < cropW / cropH) cropW = cropH * viewAspect else cropH = cropW / viewAspect
        }
        val zoom = maxOf(extraZoom, 1.0)
        cropW /= zoom
        cropH /= zoom
        val cw = (if (quarter) cropH else cropW).toInt().coerceIn(1, src.width)
        val ch = (if (quarter) cropW else cropH).toInt().coerceIn(1, src.height)
        if (cw == src.width && ch == src.height) return src
        val cropped = Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw, ch)
        if (cropped !== src) src.recycle()
        // The overlay is drawn onto this bitmap, so it must be mutable.
        return if (cropped.isMutable) cropped else cropped.copy(Bitmap.Config.ARGB_8888, true).also { cropped.recycle() }
    }

    private fun decodeMutable(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IOException("JPEG decode failed")
    }

    /**
     * Canvas transform maps upright (display) coordinates to raw sensor pixels:
     *   90  : raw = (y', H - x')      -> translate(0, H) then rotate(-90)
     *   180 : raw = (W - x', H - y')  -> translate(W, H) then rotate(180)
     *   270 : raw = (W - y', x')      -> translate(W, 0) then rotate(90)
     */
    private fun burnOverlay(bitmap: Bitmap, rotation: Int, content: OverlayContent) {
        val w = bitmap.width
        val h = bitmap.height
        val canvas = Canvas(bitmap)
        canvas.save()
        val (uprightW, uprightH) = when (rotation) {
            90 -> { canvas.translate(0f, h.toFloat()); canvas.rotate(-90f); h to w }
            180 -> { canvas.translate(w.toFloat(), h.toFloat()); canvas.rotate(180f); w to h }
            270 -> { canvas.translate(w.toFloat(), 0f); canvas.rotate(90f); h to w }
            else -> w to h
        }
        renderer.draw(canvas, uprightW, uprightH, content)
        canvas.restore()
    }

    private fun writeExif(file: File, rotation: Int, meta: PhotoMeta) {
        val exif = ExifInterface(file.absolutePath)
        val orientation = when (rotation) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        if (meta.lat != null && meta.lon != null) exif.setLatLong(meta.lat, meta.lon)
        meta.altitudeM?.let { exif.setAltitude(it) }
        meta.trueAzimuthDeg?.let {
            // GPSImgDirection as a rational with 1/100 degree resolution; "T" = true north reference.
            exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, "${(it * 100).toLong()}/100")
            exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, "T")
        }
        if (meta.destLat != null && meta.destLon != null) {
            exif.setAttribute(ExifInterface.TAG_GPS_DEST_LATITUDE, toDmsRational(meta.destLat))
            exif.setAttribute(ExifInterface.TAG_GPS_DEST_LATITUDE_REF, if (meta.destLat >= 0) "N" else "S")
            exif.setAttribute(ExifInterface.TAG_GPS_DEST_LONGITUDE, toDmsRational(meta.destLon))
            exif.setAttribute(ExifInterface.TAG_GPS_DEST_LONGITUDE_REF, if (meta.destLon >= 0) "E" else "W")
        }
        meta.destBearingDeg?.let {
            exif.setAttribute(ExifInterface.TAG_GPS_DEST_BEARING, "${(it * 100).toLong()}/100")
            exif.setAttribute(ExifInterface.TAG_GPS_DEST_BEARING_REF, "T")
        }
        meta.destDistanceM?.let {
            // GPSDestDistance in kilometres ("K"), 1 m resolution.
            exif.setAttribute(ExifInterface.TAG_GPS_DEST_DISTANCE, "${it.toLong()}/1000")
            exif.setAttribute(ExifInterface.TAG_GPS_DEST_DISTANCE_REF, "K")
        }
        exif.setAttribute(
            ExifInterface.TAG_DATETIME_ORIGINAL,
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
        )
        exif.saveAttributes()
    }

    /** EXIF rational DMS "d/1,m/1,s*1000/1000" for an absolute decimal-degree value. */
    private fun toDmsRational(value: Double): String {
        val abs = kotlin.math.abs(value)
        val deg = abs.toInt()
        val minFull = (abs - deg) * 60.0
        val min = minFull.toInt()
        val secMilli = ((minFull - min) * 60.0 * 1000.0).toLong()
        return "$deg/1,$min/1,$secMilli/1000"
    }

    private fun publish(file: File): Uri {
        val name = "PointAndIdentify_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, AppConfig.PHOTO_RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1) // hidden from gallery until fully written
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Cannot open output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
