/*
 * Copyright (C) 2026 The PenguinOS Project
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.quickstep.cos;

import android.content.Context;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import com.android.internal.util.lunaris.SpringInterpolator;
import com.android.launcher3.LauncherPrefs;

/** Motion measured from ColorOS, kept independent of the default launcher animations. */
public final class CosAppAnimation {
    public static final long LAUNCH_DURATION = 420;
    public static final long ICON_FADE_DURATION = 95;
    public static final long REVEAL_DURATION = 520;
    public static final long WALLPAPER_OPEN_DURATION = 420;
    public static final long WALLPAPER_CLOSE_DURATION = 900;
    public static final float HOME_SCALE = 0.92f;
    public static final Interpolator OPEN = new PathInterpolator(0.25f, 0f, 0.15f, 1f);
    public static final Interpolator REVEAL = spring(300f, 1f, REVEAL_DURATION);
    public static final Interpolator WALLPAPER_OPEN = new PathInterpolator(0.25f, 0f, 0.15f, 1f);
    public static final Interpolator WALLPAPER_CLOSE = spring(49f, 1f, WALLPAPER_CLOSE_DURATION);

    private static final int LUT_SIZE = 512;

    private CosAppAnimation() { }

    public static boolean isEnabled(Context context) {
        return LauncherPrefs.COS_APP_ANIMATIONS.get(context);
    }

    /** Fast table-based spring interpolator that evaluates in O(1) without transcendentals. */
    private static final class FastSpringInterpolator implements Interpolator {
        private final float[] mValues;

        FastSpringInterpolator(SpringInterpolator spring, double seconds, float end) {
            mValues = new float[LUT_SIZE];
            float invEnd = end != 0f ? 1f / end : 1f;
            for (int i = 0; i < LUT_SIZE; i++) {
                double t = (i / (double) (LUT_SIZE - 1)) * seconds;
                mValues[i] = spring.positionAt(t) * invEnd;
            }
            mValues[LUT_SIZE - 1] = 1f;
        }

        @Override
        public float getInterpolation(float input) {
            if (input <= 0f) return 0f;
            if (input >= 1f) return 1f;
            float val = input * (LUT_SIZE - 1);
            int index = (int) val;
            if (index >= LUT_SIZE - 1) return mValues[LUT_SIZE - 1];
            float frac = val - index;
            return mValues[index] + frac * (mValues[index + 1] - mValues[index]);
        }
    }

    /** Use the framework Luminous spring solver, normalized to Pengu's measured duration. */
    public static Interpolator spring(float stiffness, float damping, long duration) {
        SpringInterpolator spring = new SpringInterpolator(stiffness, damping, 0f);
        double seconds = duration / 1000.0;
        float end = spring.positionAt(seconds);
        return new FastSpringInterpolator(spring, seconds, end);
    }

    public static float bounceDamping(float overshoot, float distance) {
        if (overshoot <= 0f || distance <= overshoot) return 1f;
        double log = Math.log(overshoot / distance);
        return Math.max(0.5f, Math.min(1f,
                (float) (-log / Math.sqrt(Math.PI * Math.PI + log * log))));
    }

    public static float iconFade(float elapsedMs) {
        if (elapsedMs <= 20f) return 0f;
        if (elapsedMs >= 340f) return 1f;
        float wt = 20f * (elapsedMs - 20f) / 1000f;
        return 1f - (1f + wt) * (float) Math.exp(-wt);
    }
}
