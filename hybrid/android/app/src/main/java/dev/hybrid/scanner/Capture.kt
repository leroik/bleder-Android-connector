package dev.hybrid.scanner

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun plane(image: Image, index: Int, width: Int, height: Int, bytes: Int = 1): ByteArray {
    val p = image.planes[index]
    val buf = p.buffer.duplicate()
    val base = buf.position()
    val result = ByteArray(width * height * bytes)
    for (y in 0 until height) {
        val row = base + y * p.rowStride
        if (p.pixelStride == bytes) {
            buf.position(row)
            buf.get(result, y * width * bytes, width * bytes)
        } else {
            for (x in 0 until width) {
                for (b in 0 until bytes) {
                    result[(y * width + x) * bytes + b] = buf.get(row + x * p.pixelStride + b)
                }
            }
        }
    }
    return result
}

internal data class Measurement(
    val metadata: JSONObject, val depth: ByteArray, val confidence: ByteArray,
    val y: ByteArray, val u: ByteArray, val v: ByteArray,
    val rgbWidth: Int, val rgbHeight: Int
) {
    fun write(out: OutputStream) {
        val nv21 = ByteArray(y.size + 2 * u.size)
        y.copyInto(nv21)
        for (i in u.indices) {
            nv21[y.size + 2 * i] = v[i]
            nv21[y.size + 2 * i + 1] = u[i]
        }
        val jpeg = ByteArrayOutputStream()
        check(YuvImage(nv21, ImageFormat.NV21, rgbWidth, rgbHeight, null)
            .compressToJpeg(Rect(0, 0, rgbWidth, rgbHeight), 84, jpeg))
        val header = metadata.toString().toByteArray(Charsets.UTF_8)
        check(8 + header.size + depth.size + confidence.size + jpeg.size() <= 8 * 1024 * 1024)
        out.write(byteArrayOf(72, 51, 83, 49))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(header.size).array())
        out.write(header)
        out.write(depth)
        out.write(confidence)
        jpeg.writeTo(out)
    }
}

internal fun capture(frame: Frame, session: String, id: Int, previous: Long): Measurement? {
    frame.acquireRawDepthImage16Bits().use { depth ->
        val timestamp = frame.timestamp
        if (depth.timestamp != timestamp || timestamp <= previous) return null
        frame.acquireRawDepthConfidenceImage().use { confidence ->
            frame.acquireCameraImage().use { rgb ->
                if (rgb.timestamp != timestamp || confidence.timestamp != timestamp) return null
                val w = depth.width
                val h = depth.height
                val rw = rgb.width
                val rh = rgb.height
                if (confidence.width != w || confidence.height != h ||
                    rgb.format != ImageFormat.YUV_420_888 || rw % 2 != 0 || rh % 2 != 0) return null
                val texture = frame.camera.textureIntrinsics
                val tw = texture.imageDimensions[0]
                val th = texture.imageDimensions[1]
                val cpu = frame.camera.imageIntrinsics
                if (cpu.imageDimensions[0] != rw || cpu.imageDimensions[1] != rh) return null
                val corners = FloatArray(6)
                frame.transformCoordinates2d(Coordinates2d.TEXTURE_NORMALIZED,
                    floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), Coordinates2d.IMAGE_PIXELS, corners)
                val columnMajor = FloatArray(16)
                frame.camera.pose.toMatrix(columnMajor, 0)
                val matrix = JSONArray()
                for (row in 0..3) for (col in 0..3) matrix.put(columnMajor[col * 4 + row].toDouble())
                val metadata = JSONObject().apply {
                    put("type", "frame.meta")
                    put("version", 1)
                    put("session", session)
                    put("id", id)
                    put("timestamp_ns", timestamp)
                    put("rgb_timestamp_ns", rgb.timestamp)
                    put("depth_timestamp_ns", depth.timestamp)
                    put("confidence_timestamp_ns", confidence.timestamp)
                    put("tracking", "TRACKING")
                    put("coordinates", "arcore_c2w_row_major_m")
                    put("camera_to_world", matrix)
                    put("width", w)
                    put("height", h)
                    put("rgb_width", rw)
                    put("rgb_height", rh)
                    put("intrinsics", numbers(texture.focalLength[0]*w/tw, texture.focalLength[1]*h/th,
                        texture.principalPoint[0]*w/tw, texture.principalPoint[1]*h/th))
                    put("rgb_intrinsics", numbers(cpu.focalLength[0], cpu.focalLength[1],
                        cpu.principalPoint[0], cpu.principalPoint[1]))
                    put("depth_to_rgb", numbers((corners[2]-corners[0])/w,
                        (corners[4]-corners[0])/h, corners[0], (corners[3]-corners[1])/w,
                        (corners[5]-corners[1])/h, corners[1]))
                }
                return Measurement(metadata, plane(depth, 0, w, h, 2), plane(confidence, 0, w, h),
                    plane(rgb, 0, rw, rh), plane(rgb, 1, rw/2, rh/2), plane(rgb, 2, rw/2, rh/2), rw, rh)
            }
        }
    }
}

private fun numbers(vararg values: Float) = JSONArray().apply {
    for (value in values) put(value.toDouble())
}
