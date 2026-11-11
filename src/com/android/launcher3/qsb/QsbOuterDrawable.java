/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.qsb;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import com.android.launcher3.util.Themes;

public class QsbOuterDrawable extends Drawable {
    private final Paint mPaint;
    private final RectF mRect;
    private final Context mContext;
    private int mOpacity = 70;
    private boolean mBlurActive = false;

    public QsbOuterDrawable(Context context) {
        mContext = context;
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mRect = new RectF();
    }

    public void setBlurActive(boolean blurActive) {
        if (mBlurActive != blurActive) {
            mBlurActive = blurActive;
            invalidateSelf();
        }
    }

    public boolean isBlurActive() {
        return mBlurActive;
    }

    public void setOpacity(int opacity) {
        if (mOpacity != opacity) {
            mOpacity = opacity;
            invalidateSelf();
        }
    }

    public int getOpacityPercent() {
        return mOpacity;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;

        // Get the themed color for outer shape
        int themedColor = Themes.getAttrColor(mContext, com.android.launcher3.R.attr.qsbOuterColor);

        // Apply opacity to outer shape only (slightly reduced when blur is active for frosted glass look)
        int effectiveOpacity = mBlurActive ? Math.min(mOpacity, 60) : mOpacity;
        int alpha = (int) (effectiveOpacity * 255 / 100);
        int outerColorWithAlpha = (themedColor & 0x00FFFFFF) | (alpha << 24);

        mPaint.setColor(outerColorWithAlpha);

        // Draw outer shape
        float radius = mContext.getResources().getDimensionPixelSize(
                com.android.launcher3.R.dimen.qsb_widget_height);
        mRect.set(bounds);
        canvas.drawRoundRect(mRect, radius, radius, mPaint);

        // Draw inner shape (existing search bar) with full opacity
        int padding = mContext.getResources().getDimensionPixelSize(
                com.android.launcher3.R.dimen.qsb_widget_height) / 8; // 8dp equivalent

        // Calculate right padding based on actual layout dimensions
        int aiModeButtonSize = mContext.getResources().getDimensionPixelSize(
                com.android.launcher3.R.dimen.qsb_ai_mode_button_size);
        int aiModeButtonMargin = mContext.getResources().getDimensionPixelSize(
                com.android.launcher3.R.dimen.qsb_ai_mode_button_margin);
        int marginEnd = mContext.getResources().getDimensionPixelSize(
                com.android.launcher3.R.dimen.qsb_marginEnd);

        int rightPadding = aiModeButtonSize + aiModeButtonMargin + marginEnd - padding; // Reduce padding to extend inner shape further to the right

        Rect innerBounds = new Rect(
                bounds.left + padding,
                bounds.top + padding,
                bounds.right - rightPadding,
                bounds.bottom - padding
        );

        int fillColor = Themes.getAttrColor(mContext, com.android.launcher3.R.attr.qsbFillColor);
        mPaint.setColor(fillColor);

        RectF innerRect = new RectF(innerBounds);
        canvas.drawRoundRect(innerRect, radius, radius, mPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        // Not used
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // Not used
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
