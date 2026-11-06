package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.PointerIcon
import android.view.View
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.TooltipCompat
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.BaseProgressIndicator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.ktx.dp2pxf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.*
import kotlin.math.min

class ServiceButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) :
    FloatingActionButton(context, attrs, defStyleAttr), DynamicAnimation.OnAnimationEndListener {
    companion object {
        private const val GLOBAL_MODE_ICON_SCALE = 20f / 24f
        private const val GLOBAL_MODE_ICON_OFFSET_X_DP = 1

        private const val DEFAULT_FILL_COLOR = 0xFF6A4BFF.toInt()
        private const val RIM_STROKE_DP = 1.5f
        private const val RING_STROKE_DP = 1.2f

        /** Radii of the decorative rings, measured outward from the button edge. */
        private val RING_OFFSETS_DP = floatArrayOf(13f, 26f, 42f)

        /** Opacity of each ring, fading out with distance. */
        private val RING_ALPHAS = floatArrayOf(0.42f, 0.26f, 0.13f)
    }

    private val globalModeOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp2pxf(4)
    }
    private val globalModeOutlineBounds = RectF()

    // Flat look: the button is defined by a rim and fading rings instead of a drop shadow.
    private val contentBounds = Rect()
    private val ringBounds = RectF()
    private var circleCenterX = 0f
    private var circleCenterY = 0f
    private var circleRadius = 0f
    private var decorationsReady = false
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp2pxf(RING_STROKE_DP)
    }
    private val ringFadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp2pxf(RIM_STROKE_DP)
    }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        // The redesign defines the button with a rim and fading rings instead of a cast shadow,
        // so every source of elevation (which Material uses to draw that shadow) is disabled.
        // useCompatPadding stays at its default (false), so the button's measured size, the
        // progress ring and the StatsBar cradle are all left exactly as they were.
        stateListAnimator = null
        translationZ = 0f
        elevation = 0f
    }

    private val callback = object : Animatable2Compat.AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable) {
            super.onAnimationEnd(drawable)
            var next = animationQueue.peek() ?: return
            if (next.icon.current == drawable) {
                animationQueue.pop()
                next = animationQueue.peek() ?: return
            }
            next.start()
        }
    }

    private inner class AnimatedState(
        @DrawableRes resId: Int,
        private val onStart: BaseProgressIndicator<*>.() -> Unit = { hideProgress() }
    ) {
        val icon: AnimatedVectorDrawableCompat =
            AnimatedVectorDrawableCompat.create(context, resId)!!.apply {
                registerAnimationCallback(this@ServiceButton.callback)
            }

        fun start() {
            setImageDrawable(icon)
            icon.start()
            progress.onStart()
        }

        fun stop() = icon.stop()
    }

    private val iconStopped by lazy { AnimatedState(R.drawable.ic_service_stopped) }
    private val iconConnecting by lazy {
        AnimatedState(R.drawable.ic_service_connecting) {
            hideProgress()
            delayedAnimation = (context as LifecycleOwner).lifecycleScope.launchWhenStarted {
                delay(context.resources.getInteger(android.R.integer.config_mediumAnimTime) + 1000L)
                isIndeterminate = true
                show()
            }
        }
    }
    private val iconConnected by lazy {
        AnimatedState(R.drawable.ic_service_connected) {
            delayedAnimation?.cancel()
            setProgressCompat(1, true)
        }
    }
    private val iconStopping by lazy { AnimatedState(R.drawable.ic_service_stopping) }
    private val animationQueue = ArrayDeque<AnimatedState>()

    private var checked = false
    private var globalModeStyle = false
    private var globalModeOutline = false
    private var delayedAnimation: Job? = null
    private lateinit var progress: BaseProgressIndicator<*>
    fun initProgress(progress: BaseProgressIndicator<*>) {
        this.progress = progress
        progress.progressDrawable?.addSpringAnimationEndListener(this)
    }

    override fun onAnimationEnd(
        animation: DynamicAnimation<out DynamicAnimation<*>>?, canceled: Boolean, value: Float,
        velocity: Float
    ) {
        if (!canceled) progress.hide()
    }

    fun hideProgress() {
        delayedAnimation?.cancel()
        progress.hide()
    }

    override fun onDraw(canvas: Canvas) {
        if (decorationsReady) {
            drawDecorativeRings(canvas)
            drawSheen(canvas)
        }

        if (globalModeStyle) {
            canvas.save()
            canvas.scale(GLOBAL_MODE_ICON_SCALE, GLOBAL_MODE_ICON_SCALE, width / 2f, height / 2f)
            super.onDraw(canvas)
            canvas.restore()
        } else {
            canvas.save()
            canvas.translate(dp2pxf(GLOBAL_MODE_ICON_OFFSET_X_DP), 0f)
            super.onDraw(canvas)
            canvas.restore()
        }

        if (decorationsReady) {
            drawRim(canvas)
        }
        if (!globalModeOutline) return

        val strokeWidth = globalModeOutlinePaint.strokeWidth
        val outlineSize = min(width, height).toFloat()
        val inset = strokeWidth / 2f + dp2pxf(1)
        val left = (width - outlineSize) / 2f + inset
        val top = (height - outlineSize) / 2f + inset
        globalModeOutlineBounds.set(
            left,
            top,
            left + outlineSize - inset * 2f,
            top + outlineSize - inset * 2f,
        )
        canvas.drawOval(globalModeOutlineBounds, globalModeOutlinePaint)
    }

    /** Thin concentric circles around the button, fading into transparency toward the content. */
    private fun drawDecorativeRings(canvas: Canvas) {
        val fill = currentFillColor()
        val layer = canvas.saveLayer(ringBounds, null)
        for (i in RING_OFFSETS_DP.indices) {
            ringPaint.color = ColorUtils.setAlphaComponent(
                fill,
                (255 * RING_ALPHAS[i]).toInt().coerceIn(0, 255),
            )
            canvas.drawCircle(circleCenterX, circleCenterY, circleRadius + dp2pxf(RING_OFFSETS_DP[i]), ringPaint)
        }
        canvas.drawRect(ringBounds, ringFadePaint)
        canvas.restoreToCount(layer)
    }

    /** Soft top highlight that gives the flat circle a bit of volume without any shadow. */
    private fun drawSheen(canvas: Canvas) {
        canvas.drawCircle(circleCenterX, circleCenterY, circleRadius - dp2pxf(1f), sheenPaint)
    }

    /** Hairline rim that keeps the circle readable on any background. */
    private fun drawRim(canvas: Canvas) {
        rimPaint.color = ColorUtils.blendARGB(currentFillColor(), Color.WHITE, 0.32f)
        canvas.drawCircle(circleCenterX, circleCenterY, circleRadius - rimPaint.strokeWidth / 2f, rimPaint)
    }

    private fun currentFillColor(): Int {
        val color = backgroundTintList?.getColorForState(drawableState, DEFAULT_FILL_COLOR)
            ?: DEFAULT_FILL_COLOR
        return if (Color.alpha(color) > 0) color else DEFAULT_FILL_COLOR
    }

    /** Builds the cached geometry and gradients used by the flat button decoration. */
    private fun prepareDecorations() {
        getMeasuredContentRect(contentBounds)
        if (contentBounds.width() <= 0 || contentBounds.height() <= 0) {
            decorationsReady = false
            return
        }
        circleCenterX = contentBounds.exactCenterX()
        circleCenterY = contentBounds.exactCenterY()
        circleRadius = min(contentBounds.width(), contentBounds.height()) / 2f

        val outermost = circleRadius + dp2pxf(RING_OFFSETS_DP.last()) + ringPaint.strokeWidth
        ringBounds.set(
            circleCenterX - outermost,
            circleCenterY - outermost,
            circleCenterX + outermost,
            circleCenterY + outermost,
        )
        // The rings dissolve toward the bottom, where the bottom bar content begins.
        ringFadePaint.shader = LinearGradient(
            0f,
            ringBounds.top,
            0f,
            ringBounds.bottom,
            intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
            floatArrayOf(0f, 0.46f, 0.94f),
            Shader.TileMode.CLAMP,
        )
        sheenPaint.shader = RadialGradient(
            circleCenterX,
            circleCenterY - circleRadius * 0.42f,
            circleRadius * 1.15f,
            intArrayOf(Color.argb(30, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        decorationsReady = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        globalModeOutlinePaint.shader = LinearGradient(
            0f,
            0f,
            w.toFloat(),
            h.toFloat(),
            intArrayOf(
                Color.rgb(124, 92, 255),
                Color.rgb(78, 168, 255),
                Color.rgb(124, 92, 255),
            ),
            floatArrayOf(0f, 0.54f, 1f),
            Shader.TileMode.CLAMP,
        )
        prepareDecorations()
    }

    fun setGlobalModeStyle(enabled: Boolean, outline: Boolean) {
        var changed = false
        if (globalModeStyle != enabled) {
            globalModeStyle = enabled
            changed = true
        }
        if (globalModeOutline != outline) {
            globalModeOutline = outline
            changed = true
        }
        if (changed) invalidate()
    }

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val drawableState = super.onCreateDrawableState(extraSpace + 1)
        if (checked) View.mergeDrawableStates(
            drawableState,
            intArrayOf(android.R.attr.state_checked)
        )
        return drawableState
    }

    fun changeState(state: BaseService.State, previousState: BaseService.State, animate: Boolean) {
        when (state) {
            BaseService.State.Connecting -> changeState(iconConnecting, animate)
            BaseService.State.Connected -> changeState(iconConnected, animate)
            BaseService.State.Stopping -> {
                changeState(iconStopping, animate && previousState == BaseService.State.Connected)
            }
            else -> changeState(iconStopped, animate)
        }
        checked = state == BaseService.State.Connected
        refreshDrawableState()
        val description = context.getText(if (state.canStop) R.string.stop else R.string.connect)
        contentDescription = description
        TooltipCompat.setTooltipText(this, description)
        val enabled = state.canStop || state == BaseService.State.Stopped
        isEnabled = enabled
        if (Build.VERSION.SDK_INT >= 24) pointerIcon = PointerIcon.getSystemIcon(
            context,
            if (enabled) PointerIcon.TYPE_HAND else PointerIcon.TYPE_WAIT
        )
    }

    private fun changeState(icon: AnimatedState, animate: Boolean) {
        fun counters(a: AnimatedState, b: AnimatedState): Boolean =
            a == iconStopped && b == iconConnecting ||
                    a == iconConnecting && b == iconStopped ||
                    a == iconConnected && b == iconStopping ||
                    a == iconStopping && b == iconConnected
        if (animate) {
            if (animationQueue.size < 2 || !counters(animationQueue.last, icon)) {
                animationQueue.add(icon)
                if (animationQueue.size == 1) icon.start()
            } else animationQueue.removeLast()
        } else {
            animationQueue.peekFirst()?.stop()
            animationQueue.clear()
            icon.start()    // force ensureAnimatorSet to be called so that stop() will work
            icon.stop()
        }
    }
}
