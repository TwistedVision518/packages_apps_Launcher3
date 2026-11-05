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
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import com.android.launcher3.views.ActivityContext
import com.android.launcher3.widget.util.WidgetSizeHandler
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
    private var itemInflater: ItemInflater<*>? = null
    private val activityContext: ActivityContext = ActivityContext.lookupContext(context)
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
        isFocusable = true
        accessibilityDelegate = activityContext.accessibilityDelegate
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
        this.itemInflater = itemInflater

        removeAllViews()
        childViews.clear()

        val widgets = info.getWidgets().toList()
        for (wInfo in widgets) {
            val hostView = itemInflater.inflateAppWidgetView(wInfo)
            if (hostView != null) {
                childViews.add(hostView)
                addView(hostView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                attachChildLongClickListener(hostView)
            } else {
                info.removeWidget(wInfo)
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
                if (stackInfo === info && childViews.isEmpty()) {
                    (activityContext as? Launcher)?.removeItem(
                        this, info, true, "Empty widget stack removed")
                }
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
        return getActiveWidgetView()?.tag as? LauncherAppWidgetInfo
    }

    fun getActiveWidgetView(): LauncherAppWidgetHostView? =
        childViews.getOrNull(currentPage) as? LauncherAppWidgetHostView

    fun findWidgetViewById(appWidgetId: Int): LauncherAppWidgetHostView? {
        for (view in childViews) {
            if (view is LauncherAppWidgetHostView && view.appWidgetId == appWidgetId) {
                return view
            }
        }
        return null
    }

    fun getActiveWidgetBounds(): Rect {
        val widgetBounds = Rect(0, 0, width, height)
        val activeView = getActiveWidgetView()
        if (activeView != null) {
            val bg = RoundedCornerEnforcement.findBackground(activeView)
            if (bg != null && bg.width > 0 && bg.height > 0) {
                RoundedCornerEnforcement.computeRoundedRectangle(activeView, bg, widgetBounds)
            } else if (activeView.width > 0 && activeView.height > 0) {
                widgetBounds.set(0, 0, activeView.width, activeView.height)
            }
        }
        return widgetBounds
    }

    fun launchActiveWidgetApp() {
        val activeInfo = getActiveWidgetInfo() ?: return
        val activeView = getActiveWidgetView() ?: return
        val packageName = activeInfo.providerName?.packageName ?: return
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val launcher = Launcher.getLauncher(context)
        launcher.startActivitySafely(activeView, intent, activeInfo)
    }

    fun addWidgetView(info: LauncherAppWidgetInfo, view: View) {
        val stack = stackInfo ?: return
        if (!WidgetStackInfo.hasMatchingSize(info, stack.spanX, stack.spanY)) return
        if (!stack.getWidgets().contains(info)) {
            if (!stack.canAdd(info)) return
            stack.add(info)
        }
        val previousView = childViews.firstOrNull { it.tag == info }
        if (previousView == view) return
        val replacing = previousView != null
        previousView?.let {
            removeView(it)
            childViews.remove(it)
        }
        (view.parent as? ViewGroup)?.removeView(view)
        val index = stack.getWidgets().indexOf(info).coerceAtMost(childViews.size)
        childViews.add(index, view)
        addView(view, index, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        attachChildLongClickListener(view)
        if (view is LauncherAppWidgetHostView) {
            WidgetSizeHandler.updateSizeRanges(view, info.spanX, info.spanY)
        }
        visibility = VISIBLE
        snapToPage(if (replacing) currentPage else index, !replacing)
    }

    fun removeCurrentWidget(deleteFromDb: Boolean) {
        getActiveWidgetInfo()?.let { removeWidget(it, deleteFromDb) }
    }

    fun removeWidget(info: LauncherAppWidgetInfo, deleteFromDb: Boolean) {
        val stack = stackInfo ?: return
        if (!stack.getWidgets().contains(info)) return
        val currentView = childViews.firstOrNull { it.tag == info }
        val launcher = activityContext as? Launcher ?: return
        snapAnimator?.cancel()

        if (stack.getWidgets().size <= 1) {
            // Removing the last widget removes the entire stack
            launcher.removeItem(this, stack, deleteFromDb, "User removed last widget from stack")
            return
        }

        // Remove widget from stack
        if (currentView != null) {
            removeView(currentView)
            childViews.remove(currentView)
        }
        stack.removeWidget(info)

        if (deleteFromDb) {
            launcher.modelWriter.deleteWidgetInfo(
                info,
                launcher.appWidgetHolder,
                "User removed widget from stack"
            )
            stack.getWidgets().forEach { launcher.modelWriter.updateItemInDatabase(it) }
            launcher.modelWriter.updateItemInDatabase(stack)
        }

        currentPage = stack.currentIndex
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
        val launcher = activityContext as? Launcher
        stackInfo?.let { launcher?.modelWriter?.updateItemInDatabase(it) }
        if (index != currentPage && index in 0 until childViews.size) {
            snapToPage(index, true)
        }
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val holder = activityContext.appWidgetHolder
        if (holder != null) {
            childViews.toList().forEach { child ->
                val info = child.tag as? LauncherAppWidgetInfo ?: return@forEach
                if (child is LauncherAppWidgetHostView && !info.isCustomWidget) {
                    val attached = holder.attachViewToHostAndGetAttachedView(child)
                    itemInflater?.prepareAppWidget(attached, info)
                    if (attached !== child) addWidgetView(info, attached)
                }
            }
        }
        updateWidgetSizes()
        stackInfo?.addListener(this)
    }

    fun updateWidgetSizes() {
        childViews.forEach { child ->
            val info = child.tag as? LauncherAppWidgetInfo ?: return@forEach
            if (child is LauncherAppWidgetHostView) {
                WidgetSizeHandler.updateSizeRanges(child, info.spanX, info.spanY)
            }
        }
    }

    fun updateWidgetStates(updates: Set<ItemInfo>) {
        childViews.toList().forEach { child ->
            if (child is PendingAppWidgetHostView && child.tag in updates) {
                child.applyState()
                child.postProviderAvailabilityCheck()
            }
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = childViews.size > 1
        if (currentPage > 0) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
        if (currentPage < childViews.lastIndex) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val targetPage = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> currentPage + 1
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> currentPage - 1
            else -> return super.performAccessibilityAction(action, arguments)
        }
        if (targetPage !in childViews.indices) return false
        snapToPage(targetPage, true)
        return true
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (childViews.size > 1) {
            parent?.requestDisallowInterceptTouchEvent(disallowIntercept || isSwiping)
        } else {
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
    }

    override fun onDetachedFromWindow() {
        snapAnimator?.cancel()
        velocityTracker?.recycle()
        velocityTracker = null
        isSwiping = false
        stackInfo?.removeListener(this)
        super.onDetachedFromWindow()
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
                if (childViews.size > 1) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!isSwiping) {
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
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            velocityTracker?.recycle()
            velocityTracker = null
        }
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
                if (childViews.size > 1 && !isSwiping && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
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
            MotionEvent.ACTION_UP -> {
                velocityTracker?.computeCurrentVelocity(1000)
                val vx = velocityTracker?.xVelocity ?: 0f

                if (isSwiping) {
                    isSwiping = false
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
                    velocityTracker?.recycle()
                    velocityTracker = null
                    return true
                } else {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (hypot(dx.toDouble(), dy.toDouble()) < touchSlop) {
                        val count = childViews.size
                        if (count > 1) {
                            val density = resources.displayMetrics.density
                            val dotRadius = 3f * density
                            val slotSpacing = 14f * density
                            val bgHPad = 8f * density
                            val bgVPad = 4f * density
                            val totalTrackWidth = (count - 1) * slotSpacing

                            val trackStartX = (width - totalTrackWidth) / 2f
                            val centerY = height - (14f * density)

                            val pillLeft = trackStartX - dotRadius - bgHPad - (8f * density)
                            val pillRight = trackStartX + totalTrackWidth + dotRadius + bgHPad + (8f * density)
                            val pillTop = centerY - dotRadius - bgVPad - (8f * density)
                            val pillBottom = centerY + dotRadius + bgVPad + (8f * density)

                            if (ev.x in pillLeft..pillRight && ev.y in pillTop..pillBottom) {
                                val relX = ev.x - trackStartX
                                val tappedDot = (relX / slotSpacing).roundToInt().coerceIn(0, count - 1)
                                snapToPage(tappedDot, animated = true)
                                velocityTracker?.recycle()
                                velocityTracker = null
                                return true
                            }
                        }
                        launchActiveWidgetApp()
                        velocityTracker?.recycle()
                        velocityTracker = null
                        return true
                    }
                }
                isSwiping = false
                velocityTracker?.recycle()
                velocityTracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isSwiping) {
                    isSwiping = false
                    val w = width.toFloat()
                    val progress = if (w > 0f) currentScrollX / w else currentPage.toFloat()
                    val targetPage = progress.roundToInt().coerceIn(0, childViews.size - 1)
                    snapToPage(targetPage, animated = true)
                }
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

        val activeView = getActiveWidgetView()
        // If the active widget is currently animating (hidden or transitioning in FloatingWidgetView), hide dots
        if (activeView != null && (activeView.visibility != VISIBLE || activeView.alpha == 0f)) {
            return
        }

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
