/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import com.android.launcher3.Launcher
import com.android.launcher3.Reorderable
import com.android.launcher3.dragndrop.DraggableView
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.model.data.WidgetStackInfo
import com.android.launcher3.popup.Poppable
import com.android.launcher3.popup.PoppableType
import com.android.launcher3.util.ItemInflater
import com.android.launcher3.util.MultiTranslateDelegate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * View that displays a stack of widgets (up to [WidgetStackInfo.MAX_STACK_SIZE]) of the same size,
 * allowing the user to swipe horizontally between them with a smooth animation and page indicator dots.
 */
class WidgetStackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr),
    DraggableView,
    Reorderable,
    Poppable,
    WidgetStackInfo.OnStackChangeListener {

    var stackInfo: WidgetStackInfo? = null
        private set

    val childViews = mutableListOf<View>()
    var currentPage = 0
        private set

    private var currentScrollX = 0f
    private var lastTouchX = 0f
    private var downX = 0f
    private var downY = 0f
    private var isSwiping = false
    private var snapAnimator: ValueAnimator? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocityTracker: VelocityTracker? = null

    private val translateDelegate = MultiTranslateDelegate(this)
    private var reorderBounceScale = 1f

    // Paints for page indicator dots
    private val dotsBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x59000000 // 35% translucent black capsule background
        style = Paint.Style.FILL
    }
    private val activeDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt() // Solid white active pill
        style = Paint.Style.FILL
    }
    private val inactiveDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF // 40% translucent white inactive dot
        style = Paint.Style.FILL
    }

    val widgetCount: Int
        get() = childViews.size

    init {
        setWillNotDraw(false)
        clipChildren = true
        val enforcedRadius = RoundedCornerEnforcement.computeEnforcedRadius(context)
        if (enforcedRadius > 0) {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, enforcedRadius)
                }
            }
            clipToOutline = true
        }
    }

    fun bindStack(info: WidgetStackInfo, itemInflater: ItemInflater<*>) {
        this.stackInfo?.removeListener(this)
        this.stackInfo = info
        info.addListener(this)

        removeAllViews()
        childViews.clear()

        val widgets = info.getWidgets()
        for (wInfo in widgets) {
            val hostView = itemInflater.inflateAppWidgetView(wInfo)
            if (hostView != null) {
                childViews.add(hostView)
                addView(hostView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                attachChildLongClickListener(hostView)
            }
        }

        currentPage = max(0, min(info.currentIndex, childViews.size - 1))
        val w = width.toFloat()
        currentScrollX = if (w > 0f) currentPage * w else 0f
        updateViewsForScroll(currentScrollX)
        invalidate()

        if (childViews.isEmpty()) {
            visibility = GONE
            post {
                val launcher = Launcher.getLauncher(context)
                launcher.removeItem(this, info, true, "Empty widget stack removed")
            }
        }
    }

    private fun attachChildLongClickListener(child: View) {
        child.setOnLongClickListener {
            // Forward long clicks to the stack view so Launcher popup menu triggers
            performLongClick()
        }
    }

    fun getActiveWidgetInfo(): LauncherAppWidgetInfo? {
        val widgets = stackInfo?.getWidgets() ?: return null
        if (widgets.isEmpty()) return null
        val idx = max(0, min(currentPage, widgets.size - 1))
        return widgets.getOrNull(idx)
    }

    fun getActiveWidgetView(): View? = childViews.getOrNull(currentPage)

    fun addWidgetView(info: LauncherAppWidgetInfo, view: View) {
        val stack = stackInfo ?: return
        if (!stack.getWidgets().contains(info)) {
            stack.add(info)
        }
        if (!childViews.contains(view)) {
            childViews.add(view)
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            attachChildLongClickListener(view)
        }
        snapToPage(childViews.size - 1, true)
    }

    fun removeCurrentWidget(deleteFromDb: Boolean) {
        val stack = stackInfo ?: return
        val currentInfo = getActiveWidgetInfo() ?: return
        val currentView = getActiveWidgetView()
        val launcher = Launcher.getLauncher(context)

        if (childViews.size <= 1) {
            // Removing the last widget removes the entire stack
            launcher.removeItem(this, stack, deleteFromDb, "User removed last widget from stack")
            return
        }

        // Remove active widget from stack
        if (currentView != null) {
            removeView(currentView)
            childViews.remove(currentView)
        }
        stack.removeWidget(currentInfo)

        if (deleteFromDb) {
            launcher.modelWriter.deleteWidgetInfo(
                currentInfo,
                launcher.appWidgetHolder,
                "User removed widget from stack"
            )
        }

        currentPage = max(0, min(currentPage, childViews.size - 1))
        stack.currentIndex = currentPage
        val w = width.toFloat()
        currentScrollX = if (w > 0f) currentPage * w else 0f
        snapToPage(currentPage, false)
    }

    override fun onWidgetAdded(info: LauncherAppWidgetInfo) {
        post {
            val w = width.toFloat()
            currentScrollX = if (w > 0f) currentPage * w else 0f
            updateViewsForScroll(currentScrollX)
            invalidate()
        }
    }

    override fun onWidgetRemoved(info: LauncherAppWidgetInfo) {
        post {
            currentPage = max(0, min(currentPage, childViews.size - 1))
            val w = width.toFloat()
            currentScrollX = if (w > 0f) currentPage * w else 0f
            updateViewsForScroll(currentScrollX)
            invalidate()
        }
    }

    override fun onCurrentIndexChanged(index: Int) {
        if (index != currentPage && index in 0 until childViews.size) {
            snapToPage(index, true)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)

        val childWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)

        for (i in 0 until childCount) {
            getChildAt(i).measure(childWidthSpec, childHeightSpec)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        for (i in 0 until childCount) {
            getChildAt(i).layout(0, 0, width, height)
        }
        if (!isSwiping && snapAnimator?.isRunning != true) {
            currentScrollX = currentPage * width.toFloat()
        }
        updateViewsForScroll(currentScrollX)
    }

    private fun updateViewsForScroll(scrollX: Float) {
        val w = width.toFloat()
        if (w <= 0f) return

        for (i in childViews.indices) {
            val child = childViews[i]
            val pageOffset = (i * w) - scrollX
            child.translationX = pageOffset

            if (abs(pageOffset) < w) {
                child.visibility = VISIBLE
                val progress = abs(pageOffset) / w
                // Subtle scale and alpha for depth and physical realism
                val scale = 1f - 0.04f * progress
                val alpha = 1f - 0.15f * progress
                child.scaleX = scale
                child.scaleY = scale
                child.alpha = alpha
            } else {
                child.visibility = INVISIBLE
                child.alpha = 0f
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (childViews.size <= 1) {
            return super.dispatchTouchEvent(ev)
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastTouchX = ev.x
                val wasAnimating = snapAnimator?.isRunning == true
                snapAnimator?.cancel()
                isSwiping = wasAnimating
                // Disallow workspace page scrolling while touching down on stack
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!isSwiping) {
                    // If vertical drag dominates, allow workspace/drawer/shade to handle it
                    if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }

        val handled = super.dispatchTouchEvent(ev)
        // If child widget didn't consume ACTION_DOWN, consume it so we continue to receive MOVE events
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && !handled) {
            return true
        }
        return handled
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (childViews.size <= 1) {
            return false
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastTouchX = ev.x
                val wasAnimating = snapAnimator?.isRunning == true
                snapAnimator?.cancel()
                isSwiping = wasAnimating
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                velocityTracker?.addMovement(ev)
                if (!isSwiping && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    val canScrollLeft = dx < 0 && currentPage < childViews.size - 1
                    val canScrollRight = dx > 0 && currentPage > 0
                    if (canScrollLeft || canScrollRight) {
                        isSwiping = true
                        lastTouchX = ev.x
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    } else {
                        // At the edge of the stack swiping towards next homescreen page
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isSwiping = false
            }
        }
        return isSwiping
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (childViews.size <= 1) {
            return super.onTouchEvent(ev)
        }

        velocityTracker?.addMovement(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastTouchX = ev.x
                snapAnimator?.cancel()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!isSwiping && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    val canScrollLeft = dx < 0 && currentPage < childViews.size - 1
                    val canScrollRight = dx > 0 && currentPage > 0
                    if (canScrollLeft || canScrollRight) {
                        isSwiping = true
                        lastTouchX = ev.x
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                if (isSwiping) {
                    val deltaX = lastTouchX - ev.x
                    lastTouchX = ev.x
                    val w = width.toFloat()
                    val maxScroll = ((childViews.size - 1) * w).coerceAtLeast(0f)
                    val dampedDelta = if ((currentScrollX <= 0f && deltaX < 0f) || (currentScrollX >= maxScroll && deltaX > 0f)) {
                        deltaX * 0.3f
                    } else {
                        deltaX
                    }
                    currentScrollX += dampedDelta
                    updateViewsForScroll(currentScrollX)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isSwiping) {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vx = velocityTracker?.xVelocity ?: 0f
                    val w = width.toFloat()
                    val progress = if (w > 0f) currentScrollX / w else currentPage.toFloat()

                    val deltaProgress = progress - currentPage
                    val targetPage = when {
                        vx < -500f -> currentPage + 1
                        vx > 500f -> currentPage - 1
                        deltaProgress > 0.20f -> currentPage + kotlin.math.ceil(deltaProgress).toInt()
                        deltaProgress < -0.20f -> currentPage + kotlin.math.floor(deltaProgress).toInt()
                        else -> currentPage
                    }.coerceIn(0, childViews.size - 1)

                    snapToPage(targetPage, animated = true, velocity = vx)
                    isSwiping = false
                    velocityTracker?.recycle()
                    velocityTracker = null
                    return true
                }
                isSwiping = false
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return super.onTouchEvent(ev)
    }

    fun snapToPage(targetPage: Int, animated: Boolean = true, velocity: Float = 0f) {
        snapAnimator?.cancel()
        val clampedPage = targetPage.coerceIn(0, max(0, childViews.size - 1))
        val w = width.toFloat()

        if (!animated || w <= 0f) {
            currentPage = clampedPage
            currentScrollX = clampedPage * w
            stackInfo?.currentIndex = currentPage
            updateViewsForScroll(currentScrollX)
            invalidate()
            return
        }

        val targetScrollX = clampedPage * w
        val startScrollX = currentScrollX
        val distance = abs(targetScrollX - startScrollX)

        if (distance < 1f) {
            currentPage = clampedPage
            currentScrollX = targetScrollX
            stackInfo?.currentIndex = currentPage
            updateViewsForScroll(currentScrollX)
            invalidate()
            return
        }

        val baseDuration = 240L + (distance / w * 100L).toLong()
        val durationMs = if (abs(velocity) > 1200f) {
            (baseDuration * 0.8f).toLong()
        } else {
            baseDuration
        }.coerceIn(200L, 340L)

        // Apple / One UI fluid spring-like deceleration curve:
        // High initial responsiveness, zero jarring bounce, smooth settling
        val fluidInterpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)

        snapAnimator = ValueAnimator.ofFloat(startScrollX, targetScrollX).apply {
            duration = durationMs
            interpolator = fluidInterpolator
            var isCancelled = false
            addUpdateListener { anim ->
                currentScrollX = anim.animatedValue as Float
                updateViewsForScroll(currentScrollX)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    isCancelled = true
                }
                override fun onAnimationEnd(animation: Animator) {
                    if (!isCancelled) {
                        currentPage = clampedPage
                        stackInfo?.currentIndex = currentPage
                        currentScrollX = targetScrollX
                        updateViewsForScroll(currentScrollX)
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        val count = childViews.size
        if (count <= 1) return

        val w = width.toFloat()
        if (w <= 0f) return

        val density = resources.displayMetrics.density
        val dotRadius = 3f * density
        val slotSpacing = 14f * density
        val activeWidth = 14f * density
        val bgHPad = 8f * density
        val bgVPad = 4f * density

        val totalTrackWidth = (count - 1) * slotSpacing
        val trackStartX = (width - totalTrackWidth) / 2f
        val centerY = height - (14f * density)

        // Progress of current scroll (clamped between 0 and count - 1)
        val progress = (currentScrollX / w).coerceIn(0f, (count - 1).toFloat())

        // 1. Draw capsule background pill
        val bgRect = RectF(
            trackStartX - dotRadius - bgHPad,
            centerY - dotRadius - bgVPad,
            trackStartX + totalTrackWidth + dotRadius + bgHPad,
            centerY + dotRadius + bgVPad
        )
        canvas.drawRoundRect(bgRect, dotRadius + bgVPad, dotRadius + bgVPad, dotsBgPaint)

        // 2. Draw inactive dots
        for (i in 0 until count) {
            val cx = trackStartX + i * slotSpacing
            canvas.drawCircle(cx, centerY, dotRadius, inactiveDotPaint)
        }

        // 3. Draw active sliding pill smoothly tracking scroll progress
        val activeCenterX = trackStartX + progress * slotSpacing
        val halfPill = activeWidth / 2f
        val activeRect = RectF(
            activeCenterX - halfPill,
            centerY - dotRadius,
            activeCenterX + halfPill,
            centerY + dotRadius
        )
        canvas.drawRoundRect(activeRect, dotRadius, dotRadius, activeDotPaint)
    }

    override fun getWorkspaceVisualDragBounds(bounds: Rect) {
        bounds.set(0, 0, width, height)
    }

    override fun getViewType(): Int = DraggableView.DRAGGABLE_WIDGET

    override fun getTranslateDelegate(): MultiTranslateDelegate = translateDelegate

    override fun setReorderBounceScale(scale: Float) {
        reorderBounceScale = scale
        scaleX = scale
        scaleY = scale
    }

    override fun getReorderBounceScale(): Float = reorderBounceScale

    override fun getPoppableType(): PoppableType = PoppableType.WIDGET

    companion object {
        @JvmStatic
        fun inflate(
            context: Context,
            parent: ViewGroup?,
            info: WidgetStackInfo,
            itemInflater: ItemInflater<*>,
        ): WidgetStackView {
            val view = WidgetStackView(context)
            view.tag = info
            view.bindStack(info, itemInflater)
            return view
        }
    }
}
