/*
 * Copyright (C) 2026 The PenguinOS Project
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.quickstep.cos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;

/** Keeps the closing window visible after its recents transition finishes for a new launch. */
public final class CosHomeSnapshot extends View {
    private final Bitmap mBitmap;
    private final RectF mSourceCrop;
    private final RectF mDrawRect = new RectF();
    private final Matrix mShaderMatrix = new Matrix();
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final BitmapShader mShader;
    private ViewGroup mRoot;
    private float mDrawRadius;

    public CosHomeSnapshot(Context context, Bitmap bitmap, RectF sourceCrop, Rect taskBounds) {
        super(context);
        mBitmap = bitmap;
        mSourceCrop = new RectF(sourceCrop);
        mShader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        mPaint.setShader(mShader);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The rect is in launcher coordinates; the radius is in the window's crop coordinates. */
    public void update(RectF rect, float radius, float alpha) {
        if (mSourceCrop.isEmpty() || rect.isEmpty()) return;
        float scale = rect.width() / mSourceCrop.width();
        float targetRadius = Math.max(0f, radius * scale);
        int targetAlpha = Math.round(255 * Math.max(0f, Math.min(1f, alpha)));

        if (rect.equals(mDrawRect) && mDrawRadius == targetRadius && mPaint.getAlpha() == targetAlpha) {
            return;
        }

        mDrawRect.set(rect);
        mDrawRadius = targetRadius;
        mShaderMatrix.setRectToRect(mSourceCrop, rect, Matrix.ScaleToFit.FILL);
        mShader.setLocalMatrix(mShaderMatrix);
        mPaint.setShader(mShader);
        mPaint.setAlpha(targetAlpha);
        invalidate();
    }

    /** An overlay stays above home content and below the floating icon, without taking touches. */
    public void attachTo(ViewGroup root) {
        remove();
        mRoot = root;
        layout(0, 0, root.getWidth(), root.getHeight());
        root.getOverlay().add(this);
    }

    public void remove() {
        if (mRoot != null) {
            mRoot.getOverlay().remove(this);
            mRoot = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mBitmap == null || mBitmap.isRecycled() || mDrawRect.isEmpty()) return;
        canvas.drawRoundRect(mDrawRect, mDrawRadius, mDrawRadius, mPaint);
    }
}
