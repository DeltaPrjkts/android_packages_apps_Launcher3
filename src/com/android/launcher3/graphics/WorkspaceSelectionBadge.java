/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.graphics;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

import com.android.launcher3.BubbleTextView;
import com.android.launcher3.R;
import com.android.launcher3.util.Themes;

public final class WorkspaceSelectionBadge extends Drawable {
    private final BubbleTextView mView;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mIconBounds = new Rect();
    private final Path mCheck = new Path();
    private final float mRadius;
    private final int mColor;
    private final CharSequence mOriginalStateDescription;
    private final View.OnLayoutChangeListener mLayoutListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    setBounds(0, 0, right - left, bottom - top);
    private boolean mSelected;

    public WorkspaceSelectionBadge(BubbleTextView view) {
        mView = view;
        mRadius = 10 * view.getResources().getDisplayMetrics().density;
        mColor = Themes.getAttrColor(view.getContext(), android.R.attr.colorAccent);
        mOriginalStateDescription = view.getStateDescription();
        setBounds(0, 0, view.getWidth(), view.getHeight());
        view.addOnLayoutChangeListener(mLayoutListener);
    }

    public void setSelected(boolean selected) {
        mSelected = selected;
        setBounds(0, 0, mView.getWidth(), mView.getHeight());
        mView.setStateDescription(mView.getContext().getString(selected
                ? R.string.batch_edit_selected : R.string.batch_edit_not_selected));
        invalidateSelf();
    }

    public void remove() {
        mView.removeOnLayoutChangeListener(mLayoutListener);
        mView.getOverlay().remove(this);
        mView.setStateDescription(mOriginalStateDescription);
    }

    @Override
    public void draw(Canvas canvas) {
        mView.getIconBounds(mIconBounds);
        float cx = Math.max(mRadius + 1, mIconBounds.left);
        float cy = Math.max(mRadius + 1, mIconBounds.top);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mSelected ? mColor : 0xCC424242);
        canvas.drawCircle(cx, cy, mRadius, mPaint);
        mPaint.setColor(mSelected && Color.luminance(mColor) > 0.5f ? Color.BLACK : Color.WHITE);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(mRadius * 0.18f);
        canvas.drawCircle(cx, cy, mRadius - mPaint.getStrokeWidth() / 2, mPaint);
        if (mSelected) {
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            mCheck.reset();
            mCheck.moveTo(cx - mRadius * 0.45f, cy);
            mCheck.lineTo(cx - mRadius * 0.1f, cy + mRadius * 0.35f);
            mCheck.lineTo(cx + mRadius * 0.45f, cy - mRadius * 0.35f);
            canvas.drawPath(mCheck, mPaint);
        }
    }

    @Override
    public void setAlpha(int alpha) { mPaint.setAlpha(alpha); }

    @Override
    public void setColorFilter(ColorFilter filter) { mPaint.setColorFilter(filter); }

    @Override
    public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
