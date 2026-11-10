/*
 * Copyright (C) 2026 Lunaris AOSP
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

package com.android.quickstep.views;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.CrossWindowBlurListeners;
import android.widget.ImageButton;

import androidx.annotation.Nullable;

import com.android.axion.blur.ui.view.AxViewBackdropBlur;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.Utilities;

import java.util.function.Consumer;

public class OverviewActionImageButton extends ImageButton
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private final AxViewBackdropBlur mBackdropBlur = new AxViewBackdropBlur(this, false);

    private boolean mBlurEnabled = true;
    private boolean mCrossWindowBlurEnabled;
    private int mBlurDepth = 45;

    private final Consumer<Boolean> mCrossWindowBlurListener =
            this::onCrossWindowBlurEnabledChanged;

    public OverviewActionImageButton(Context context) {
        this(context, null);
    }

    public OverviewActionImageButton(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public OverviewActionImageButton(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public OverviewActionImageButton(Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private void init() {
        setScaleType(ScaleType.FIT_CENTER);
        mBackdropBlur.setOverlayColor(Color.TRANSPARENT);
        updateBlurPreferences();
        updateBackground();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mBackdropBlur.onAttachedToWindow();
        mCrossWindowBlurEnabled = CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled();
        CrossWindowBlurListeners.getInstance().addListener(
                getContext().getMainExecutor(), mCrossWindowBlurListener);
        LauncherPrefs.getPrefs(getContext()).registerOnSharedPreferenceChangeListener(this);
        updateBlurPreferences();
        updateBackground();
        postInvalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        mBackdropBlur.onDetachedFromWindow();
        CrossWindowBlurListeners.getInstance().removeListener(mCrossWindowBlurListener);
        LauncherPrefs.getPrefs(getContext()).unregisterOnSharedPreferenceChangeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        mBackdropBlur.onVisibilityAggregated(isVisible);
        if (isVisible) {
            updateBackground();
            postInvalidate();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            updateBlurPreferences();
            updateBackground();
            postInvalidate();
        } else {
            mBackdropBlur.setEnabled(false);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateBackground();
        postInvalidate();
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who)
                || (mBackdropBlur != null && mBackdropBlur.verifyDrawable(who));
    }

    private void onCrossWindowBlurEnabledChanged(boolean enabled) {
        mCrossWindowBlurEnabled = enabled;
        updateBlurState(null);
        updateBackground();
        postInvalidate();
    }

    private void updateBlurPreferences() {
        mBlurEnabled = LauncherPrefs.RECENTS_ACTIONS_BLUR.get(getContext());
        mBlurDepth = Math.max(0, LauncherPrefs.BLUR_DEPTH.get(getContext()));
        mBackdropBlur.setBlurRadiusPx(mBlurDepth);
        updateBlurState(null);
    }

    private boolean isBlurAvailable() {
        return mBlurEnabled && mCrossWindowBlurEnabled && mBlurDepth > 0;
    }

    private boolean canDrawBlur(@Nullable Canvas canvas) {
        boolean hw = canvas != null ? canvas.isHardwareAccelerated() : isHardwareAccelerated();
        return isBlurAvailable() && isAttachedToWindow() && hw
                && getWidth() > 0 && getHeight() > 0
                && getWindowVisibility() == VISIBLE;
    }

    private void updateBlurState(@Nullable Canvas canvas) {
        mBackdropBlur.setEnabled(canDrawBlur(canvas));
        mBackdropBlur.setSurfaceAlpha(Math.round(255f
                * Utilities.boundToRange(getAlpha(), 0f, 1f)));
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (LauncherPrefs.BLUR_DEPTH.getSharedPrefKey().equals(key)
                || LauncherPrefs.RECENTS_ACTIONS_BLUR.getSharedPrefKey().equals(key)) {
            updateBlurPreferences();
            updateBackground();
            postInvalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (h > 0 && (h != oldh || w != oldw)) {
            updateBackground();
        }
    }

    private void updateBackground() {
        int w = getWidth();
        int h = getHeight();
        float cornerRadius = (w > 0 && h > 0)
                ? (Math.min(w, h) / 2f)
                : Utilities.dpToPx(24f);
        boolean isDark = Utilities.isDarkTheme(getContext());
        boolean blurActive = isBlurAvailable();

        mBackdropBlur.setOverlayColor(Color.TRANSPARENT);

        int topColor;
        int bottomColor;
        if (isDark) {
            topColor = blurActive
                    ? Color.argb(28, 255, 255, 255)
                    : Color.argb(160, 45, 48, 55);
            bottomColor = blurActive
                    ? Color.argb(50, 255, 255, 255)
                    : Color.argb(200, 32, 34, 40);
        } else {
            topColor = blurActive
                    ? Color.argb(120, 255, 255, 255)
                    : Color.argb(210, 250, 252, 255);
            bottomColor = blurActive
                    ? Color.argb(165, 240, 242, 248)
                    : Color.argb(235, 235, 238, 245);
        }

        int strokeWidth = Math.max(1, Utilities.dpToPx(1f));
        int strokeColor = isDark
                ? Color.argb(65, 255, 255, 255)
                : Color.argb(35, 0, 0, 0);

        GradientDrawable contentDrawable = new GradientDrawable();
        contentDrawable.setShape(GradientDrawable.RECTANGLE);
        contentDrawable.setCornerRadius(cornerRadius);
        contentDrawable.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        contentDrawable.setColors(new int[] { topColor, bottomColor });
        contentDrawable.setStroke(strokeWidth, strokeColor);

        GradientDrawable maskDrawable = new GradientDrawable();
        maskDrawable.setShape(GradientDrawable.RECTANGLE);
        maskDrawable.setCornerRadius(cornerRadius);
        maskDrawable.setColor(Color.WHITE);

        int rippleColor = isDark
                ? Color.argb(40, 255, 255, 255)
                : Color.argb(25, 0, 0, 0);

        RippleDrawable rippleDrawable = new RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                contentDrawable,
                maskDrawable
        );

        setBackground(rippleDrawable);
        setClipToOutline(false);
    }

    @Override
    public void draw(Canvas canvas) {
        if (canvas.isHardwareAccelerated()) {
            updateBlurState(canvas);
            if (mBackdropBlur.isEnabled()) {
                float cornerRadius = Math.min(getWidth(), getHeight()) / 2f;
                mBackdropBlur.draw(canvas, 0, 0, getWidth(), getHeight(), cornerRadius);
            }
        }
        super.draw(canvas);
    }
}
