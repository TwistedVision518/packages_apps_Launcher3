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
package com.android.quickstep.views

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.util.FloatProperty
import android.view.CrossWindowBlurListeners
import android.widget.Button
import com.android.axion.blur.ui.view.AxViewBackdropBlur
import com.android.launcher3.Flags.enableFocusOutline
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.util.KFloatProperty
import com.android.launcher3.util.MultiPropertyDelegate
import com.android.launcher3.util.MultiValueAlpha
import com.android.quickstep.util.BorderAnimator
import com.android.quickstep.util.BorderAnimator.Companion.createSimpleBorderAnimator
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class ClearAllButton
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : Button(context, attrs, defStyleAttr, defStyleRes) {

    private val backdropBlur = AxViewBackdropBlur(this, false)
    private var blurEnabled = true
    private var crossWindowBlurEnabled = false
    private var blurDepth = 45
    private val crossWindowBlurListener = Consumer<Boolean> { onCrossWindowBlurEnabledChanged(it) }
    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (LauncherPrefs.BLUR_DEPTH.sharedPrefKey == key
                || LauncherPrefs.RECENTS_CLEAR_ALL_BLUR.sharedPrefKey == key) {
            updateBlurPreferences()
            updateBackground()
            postInvalidate()
        }
    }

    init {
        backdropBlur.setOverlayColor(Color.TRANSPARENT)
        updateBlurPreferences()
        updateBackground()
    }

    private val clearAllButtonAlpha =
        object : MultiValueAlpha(this, Alpha.entries.size) {
            override fun apply(value: Float) {
                super.apply(value)
                isClickable = value >= 1f
            }
        }
    var scrollAlpha by MultiPropertyDelegate(clearAllButtonAlpha, Alpha.SCROLL)
    var contentAlpha by MultiPropertyDelegate(clearAllButtonAlpha, Alpha.CONTENT)
    var visibilityAlpha by MultiPropertyDelegate(clearAllButtonAlpha, Alpha.VISIBILITY)
    var dismissAlpha by MultiPropertyDelegate(clearAllButtonAlpha, Alpha.DISMISS)

    var fullscreenProgress = 1f
        set(value) {
            if (field == value) {
                return
            }
            field = value
            applyPrimaryTranslation()
        }

    private var isPerformingMemoryBoost = false
    fun setMemoryBoostInProgress(inProgress: Boolean) {
        if (isPerformingMemoryBoost == inProgress) return
        
        isPerformingMemoryBoost = inProgress
        isSelected = inProgress
        
        animate()
            .scaleX(if (inProgress) 0.95f else 1f)
            .scaleY(if (inProgress) 0.95f else 1f)
            .alpha(if (inProgress) 0.8f else 1f)
            .setDuration(100)
            .start()
    }

    /**
     * Moves ClearAllButton between carousel and 2 row grid.
     *
     * 0 = carousel; 1 = 2 row grid.
     */
    var gridProgress = 1f
        set(value) {
            if (field == value) {
                return
            }
            field = value
            applyPrimaryTranslation()
        }

    private var normalTranslationPrimary = 0f
    var fullscreenTranslationPrimary = 0f
        set(value) {
            if (field == value) {
                return
            }
            field = value
            applyPrimaryTranslation()
        }

    var gridTranslationPrimary = 0f
        set(value) {
            if (field == value) {
                return
            }
            field = value
            applyPrimaryTranslation()
        }

    /** Used to put the button at the middle in the secondary coordinate. */
    var taskAlignmentTranslationY = 0f
        set(value) {
            if (field == value) {
                return
            }
            field = value
            applySecondaryTranslation()
        }

    var gridScrollOffset = 0f
    var scrollOffsetPrimary = 0f

    private var sidePadding = 0
    var borderEnabled = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            focusBorderAnimator?.setBorderVisibility(visible = field && isFocused, animated = true)
        }

    private val focusBorderAnimator: BorderAnimator? =
        if (enableFocusOutline())
            createSimpleBorderAnimator(
                context.resources.getDimensionPixelSize(R.dimen.recents_clear_all_outline_radius),
                context.resources.getDimensionPixelSize(R.dimen.keyboard_quick_switch_border_width),
                this::getBorderBounds,
                this,
                context
                    .obtainStyledAttributes(attrs, R.styleable.ClearAllButton)
                    .getColor(
                        R.styleable.ClearAllButton_focusBorderColor,
                        BorderAnimator.DEFAULT_BORDER_COLOR,
                    ),
            )
        else null

    private fun getBorderBounds(bounds: Rect) {
        bounds.set(0, 0, width, height)
        val outlinePadding =
            context.resources.getDimensionPixelSize(R.dimen.recents_clear_all_outline_padding)
        // Make the value negative to form a padding between button and outline
        bounds.inset(-outlinePadding, -outlinePadding)
    }

    public override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (borderEnabled) {
            focusBorderAnimator?.setBorderVisibility(gainFocus, /* animated= */ true)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        backdropBlur.onAttachedToWindow()
        crossWindowBlurEnabled = CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled
        CrossWindowBlurListeners.getInstance().addListener(
            context.mainExecutor, crossWindowBlurListener
        )
        LauncherPrefs.getPrefs(context).registerOnSharedPreferenceChangeListener(prefChangeListener)
        updateBlurPreferences()
        updateBackground()
        postInvalidate()
    }

    override fun onDetachedFromWindow() {
        backdropBlur.onDetachedFromWindow()
        CrossWindowBlurListeners.getInstance().removeListener(crossWindowBlurListener)
        LauncherPrefs.getPrefs(context).unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        backdropBlur.onVisibilityAggregated(isVisible)
        if (isVisible) {
            updateBackground()
            postInvalidate()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            updateBlurPreferences()
            updateBackground()
            postInvalidate()
        } else {
            backdropBlur.setEnabled(false)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateBackground()
        postInvalidate()
    }

    override fun verifyDrawable(who: Drawable): Boolean {
        return super.verifyDrawable(who) || backdropBlur.verifyDrawable(who)
    }

    private fun onCrossWindowBlurEnabledChanged(enabled: Boolean) {
        crossWindowBlurEnabled = enabled
        updateBlurState(null)
        updateBackground()
        postInvalidate()
    }

    private fun updateBlurPreferences() {
        blurEnabled = LauncherPrefs.RECENTS_CLEAR_ALL_BLUR.get(context)
        blurDepth = maxOf(0, LauncherPrefs.BLUR_DEPTH.get(context))
        backdropBlur.setBlurRadiusPx(blurDepth.toFloat())
        updateBlurState(null)
    }

    private fun isBlurAvailable(): Boolean {
        return blurEnabled && crossWindowBlurEnabled && blurDepth > 0
    }

    private fun canDrawBlur(canvas: Canvas?): Boolean {
        val hw = canvas?.isHardwareAccelerated ?: isHardwareAccelerated
        return isBlurAvailable() && isAttachedToWindow && hw && width > 0 && height > 0 && windowVisibility == VISIBLE
    }

    private fun updateBlurState(canvas: Canvas? = null) {
        backdropBlur.setEnabled(canDrawBlur(canvas))
        backdropBlur.setSurfaceAlpha((255f * Utilities.boundToRange(alpha, 0f, 1f)).roundToInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h > 0 && h != oldh) {
            updateBackground()
        }
    }

    private fun updateBackground() {
        val cornerRadius = if (height > 0) height / 2f else Utilities.dpToPx(26f).toFloat()
        val isDark = Utilities.isDarkTheme(context)
        val blurActive = isBlurAvailable()

        backdropBlur.setOverlayColor(Color.TRANSPARENT)

        val topColor: Int
        val bottomColor: Int
        if (isDark) {
            topColor = if (blurActive) {
                Color.argb(28, 255, 255, 255)
            } else {
                Color.argb(160, 45, 48, 55)
            }
            bottomColor = if (blurActive) {
                Color.argb(50, 255, 255, 255)
            } else {
                Color.argb(200, 32, 34, 40)
            }
        } else {
            topColor = if (blurActive) {
                Color.argb(120, 255, 255, 255)
            } else {
                Color.argb(210, 250, 252, 255)
            }
            bottomColor = if (blurActive) {
                Color.argb(165, 240, 242, 248)
            } else {
                Color.argb(235, 235, 238, 245)
            }
        }

        val strokeWidth = maxOf(1, Utilities.dpToPx(1f))
        val strokeColor = if (isDark) {
            Color.argb(65, 255, 255, 255)
        } else {
            Color.argb(35, 0, 0, 0)
        }

        val contentDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(topColor, bottomColor)
            setStroke(strokeWidth, strokeColor)
        }

        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            setColor(Color.WHITE)
        }

        val rippleColor = if (isDark) Color.argb(40, 255, 255, 255) else Color.argb(25, 0, 0, 0)
        val rippleDrawable = RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            contentDrawable,
            mask
        )

        background = rippleDrawable
        clipToOutline = false
    }

    override fun draw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) {
            updateBlurState(canvas)
            if (backdropBlur.isEnabled()) {
                val cornerRadius = height / 2f
                backdropBlur.draw(canvas, 0, 0, width, height, cornerRadius)
            }
        }
        super.draw(canvas)
        focusBorderAnimator?.drawBorder(canvas)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        sidePadding =
            recentsView?.let { it.pagedOrientationHandler?.getClearAllSidePadding(it, isLayoutRtl) }
                ?: 0
    }

    private val recentsView: RecentsView<*, *>?
        get() = parent as? RecentsView<*, *>?

    override fun hasOverlappingRendering() = false

    fun onRecentsViewScroll(scroll: Int, gridEnabled: Boolean) {
        val recentsView = recentsView ?: return

        val orientationSize =
            recentsView.pagedOrientationHandler.getPrimaryValue(width, height).toFloat()
        if (orientationSize == 0f) {
            return
        }

        val clearAllScroll = recentsView.clearAllScroll
        val adjustedScrollFromEdge = abs((scroll - clearAllScroll)).toFloat()
        val shift = min(adjustedScrollFromEdge, orientationSize)
        normalTranslationPrimary = if (isLayoutRtl) -shift else shift
        if (!gridEnabled) {
            normalTranslationPrimary += sidePadding.toFloat()
        }
        applyPrimaryTranslation()
        applySecondaryTranslation()
        var clearAllSpacing = recentsView.pageSpacing + recentsView.clearAllExtraPageSpacing
        clearAllSpacing = if (isLayoutRtl) -clearAllSpacing else clearAllSpacing
        scrollAlpha =
            ((clearAllScroll + clearAllSpacing - scroll) / clearAllSpacing.toFloat()).coerceAtLeast(
                0f
            )
    }

    fun getScrollAdjustment(fullscreenEnabled: Boolean, gridEnabled: Boolean): Float {
        var scrollAdjustment = 0f
        if (fullscreenEnabled) {
            scrollAdjustment += fullscreenTranslationPrimary
        }
        if (gridEnabled) {
            scrollAdjustment += gridTranslationPrimary + gridScrollOffset
        }
        scrollAdjustment += scrollOffsetPrimary
        return scrollAdjustment
    }

    fun getOffsetAdjustment(fullscreenEnabled: Boolean, gridEnabled: Boolean) =
        getScrollAdjustment(fullscreenEnabled, gridEnabled)

    private fun applyPrimaryTranslation() {
        val recentsView = recentsView ?: return
        val orientationHandler = recentsView.pagedOrientationHandler
        orientationHandler.primaryViewTranslate.set(
            this,
            (orientationHandler.getPrimaryValue(0f, taskAlignmentTranslationY) +
                normalTranslationPrimary +
                getFullscreenTrans(fullscreenTranslationPrimary) +
                getGridTrans(gridTranslationPrimary)),
        )
    }

    private fun applySecondaryTranslation() {
        val recentsView = recentsView ?: return
        val orientationHandler = recentsView.pagedOrientationHandler
        orientationHandler.secondaryViewTranslate.set(
            this,
            orientationHandler.getSecondaryValue(0f, taskAlignmentTranslationY),
        )
    }

    private fun getFullscreenTrans(endTranslation: Float) =
        if (fullscreenProgress > 0) endTranslation else 0f

    private fun getGridTrans(endTranslation: Float) = if (gridProgress > 0) endTranslation else 0f

    companion object {
        private enum class Alpha {
            SCROLL,
            CONTENT,
            VISIBILITY,
            DISMISS,
        }

        @JvmField
        val VISIBILITY_ALPHA: FloatProperty<ClearAllButton> =
            KFloatProperty(ClearAllButton::visibilityAlpha)

        @JvmField
        val DISMISS_ALPHA: FloatProperty<ClearAllButton> =
            KFloatProperty(ClearAllButton::dismissAlpha)
    }
}
