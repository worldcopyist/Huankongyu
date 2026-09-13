package com.huankongyu.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Circular avatar cropper: drag to position and pinch to zoom inside the visible frame. */
class AvatarCropView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = android.graphics.Color.WHITE
    }
    private val frameOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x66FFFFFF
    }
    private val shadePaint = Paint().apply { color = 0xB3000000.toInt() }
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var pendingReset = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val source = bitmap ?: return false
            val old = scale
            val min = minScale()
            // Allow free zoom-in up to 8x of the fit-to-frame scale.
            scale = (scale * detector.scaleFactor).coerceIn(min, min * 8f)
            val factor = scale / old
            offsetX = detector.focusX - (detector.focusX - offsetX) * factor
            offsetY = detector.focusY - (detector.focusY - offsetY) * factor
            clamp()
            invalidate()
            return true
        }
    })

    init {
        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val sizeChanged = (right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)
            if (sizeChanged && (pendingReset || bitmap != null)) {
                pendingReset = false
                resetCrop()
            }
        }
    }

    fun setImage(bytes: ByteArray) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
        pendingReset = true
        if (width > 0 && height > 0) {
            pendingReset = false
            resetCrop()
        }
    }

    fun croppedBitmap(): Bitmap? {
        val source = bitmap ?: return null
        val frame = cropFrame()
        val output = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val outputMatrix = Matrix(matrix).apply {
            postTranslate(-frame.left, -frame.top)
            postScale(512f / frame.width(), 512f / frame.height())
        }
        // Keep the saved PNG circular as well: the transparent corners won't show up
        // when the avatar is used anywhere else in the app.
        Canvas(output).apply {
            save()
            clipPath(Path().apply { addCircle(256f, 256f, 256f, Path.Direction.CW) })
            drawBitmap(source, outputMatrix, paint)
            restore()
        }
        return output
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF101827.toInt())
        bitmap?.let { canvas.drawBitmap(it, matrix, paint) }
        val frame = cropFrame()
        val radius = frame.width() / 2f
        canvas.save()
        canvas.clipOutPath(Path().apply { addCircle(frame.centerX(), frame.centerY(), radius, Path.Direction.CW) })
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
        canvas.restore()
        canvas.drawCircle(frame.centerX(), frame.centerY(), radius, framePaint)
        canvas.drawCircle(frame.centerX(), frame.centerY(), radius + 10f, frameOuterPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress) {
                offsetX += event.x - lastX
                offsetY += event.y - lastY
                lastX = event.x
                lastY = event.y
                clamp()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun resetCrop() {
        val source = bitmap ?: return
        if (width <= 0 || height <= 0) return
        val frame = cropFrame()
        scale = max(frame.width() / source.width, frame.height() / source.height)
        offsetX = frame.centerX() - source.width * scale / 2f
        offsetY = frame.centerY() - source.height * scale / 2f
        updateMatrix()
        invalidate()
    }

    private fun minScale(): Float {
        val source = bitmap ?: return 1f
        val frame = cropFrame()
        return max(frame.width() / source.width, frame.height() / source.height)
    }

    private fun clamp() {
        val source = bitmap ?: return
        val frame = cropFrame()
        // When the image is smaller than the frame on an axis, center it on that axis.
        val scaledW = source.width * scale
        val scaledH = source.height * scale
        offsetX = if (scaledW <= frame.width()) {
            frame.centerX() - scaledW / 2f
        } else {
            offsetX.coerceIn(frame.right - scaledW, frame.left)
        }
        offsetY = if (scaledH <= frame.height()) {
            frame.centerY() - scaledH / 2f
        } else {
            offsetY.coerceIn(frame.bottom - scaledH, frame.top)
        }
        updateMatrix()
    }

    private fun updateMatrix() {
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(offsetX, offsetY)
    }

    private fun cropFrame(): RectF {
        val side = min(width.toFloat() - 48f, height.toFloat() - 112f).coerceAtLeast(1f)
        return RectF((width - side) / 2f, (height - side) / 2f, (width + side) / 2f, (height + side) / 2f)
    }
}
