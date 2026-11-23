package dev.hybrid.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

internal data class Geometry(val xyz: FloatArray, val colors: IntArray, val triangles: IntArray) {
    companion object {
        fun parse(json: JSONObject, mesh: Boolean): Geometry {
            val rows = json.getJSONArray(if (mesh) "vertices" else "points")
            val xyz = FloatArray(rows.length() * 3)
            val colors = IntArray(if (mesh) 0 else rows.length())
            for (i in 0 until rows.length()) {
                val row = rows.getJSONArray(i)
                for (axis in 0..2) xyz[3*i+axis] = row.getDouble(axis).toFloat()
                if (!mesh) colors[i] = Color.rgb(row.getInt(3), row.getInt(4), row.getInt(5))
            }
            val faces = if (mesh) json.getJSONArray("faces") else null
            val stride = ((faces?.length() ?: 0) + 5999).div(6000).coerceAtLeast(1)
            val triangles = if (faces == null) IntArray(0) else IntArray((faces.length() + stride-1)/stride * 3)
            if (faces != null) for (i in 0 until triangles.size/3) {
                val row = faces.getJSONArray(i * stride)
                for (j in 0..2) triangles[3*i+j] = row.getInt(j)
            }
            return Geometry(xyz, colors, triangles)
        }
    }
}

internal class GeometryView(context: Context) : View(context) {
    var geometry: Geometry? = null
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var yaw = .3f
    private var pitch = .35f
    private var zoom = 1f
    private var touchX = 0f
    private var touchY = 0f
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(.2f, 8f)
            invalidate()
            return true
        }
    })

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(12, 21, 32))
        val data = geometry ?: return empty(canvas)
        if (data.xyz.isEmpty()) return empty(canvas)
        val count = data.xyz.size / 3
        val min = FloatArray(3) { Float.POSITIVE_INFINITY }
        val max = FloatArray(3) { Float.NEGATIVE_INFINITY }
        for (i in data.xyz.indices) {
            val a = i % 3
            min[a] = minOf(min[a], data.xyz[i])
            max[a] = maxOf(max[a], data.xyz[i])
        }
        val center = FloatArray(3) { (min[it] + max[it]) * .5f }
        val extent = (0..2).maxOf { max[it]-min[it] }.coerceAtLeast(.05f)
        val scale = zoom * minOf(width, height) * .7f / extent
        val x = FloatArray(count)
        val y = FloatArray(count)
        val z = FloatArray(count)
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        for (i in 0 until count) {
            val a = data.xyz[3*i]-center[0]
            val b = data.xyz[3*i+1]-center[1]
            val c = data.xyz[3*i+2]-center[2]
            val horizontal = b*cy-a*sy
            x[i] = width*.5f + (a*cy+b*sy)*scale
            y[i] = height*.5f - (c*cp+horizontal*sp)*scale
            z[i] = horizontal*cp-c*sp
        }
        if (data.triangles.isEmpty()) {
            paint.style = Paint.Style.FILL
            for (i in x.indices.sortedBy { z[it] }) {
                paint.color = data.colors.getOrElse(i) { 0xff45ccb5.toInt() }
                canvas.drawCircle(x[i], y[i], 2.2f, paint)
            }
        } else {
            paint.color = 0xff57d4bb.toInt()
            paint.strokeWidth = 1.1f
            paint.style = Paint.Style.STROKE
            val tris = data.triangles
            for (i in tris.indices step 3) {
                val a = tris[i]
                val b = tris[i+1]
                val c = tris[i+2]
                path.rewind()
                path.moveTo(x[a], y[a])
                path.lineTo(x[b], y[b])
                path.lineTo(x[c], y[c])
                path.close()
                canvas.drawPath(path, paint)
            }
        }
    }

    private fun empty(canvas: Canvas) {
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        paint.textSize = 16 * resources.displayMetrics.scaledDensity
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Ждём геометрию с ПК", width * .5f, height * .5f, paint)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE && !scaler.isInProgress && event.pointerCount == 1) {
            yaw += (event.x-touchX)*.009f
            pitch = (pitch+(event.y-touchY)*.009f).coerceIn(-1.45f, 1.45f)
            invalidate()
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        touchX = event.x
        touchY = event.y
        return true
    }
}
