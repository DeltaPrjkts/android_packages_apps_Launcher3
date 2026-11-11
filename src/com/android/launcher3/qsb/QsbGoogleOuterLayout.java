/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.qsb;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.CrossWindowBlurListeners;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.axion.blur.ui.view.AxViewBackdropBlur;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.Reorderable;
import com.android.launcher3.graphics.ThemeManager;
import com.android.launcher3.util.MultiTranslateDelegate;
import com.android.launcher3.views.FloatingIconViewCompanion;

import java.util.function.Consumer;

public class QsbGoogleOuterLayout extends FrameLayout
        implements Reorderable, FloatingIconViewCompanion,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private final MultiTranslateDelegate mTranslateDelegate = new MultiTranslateDelegate(this);
    private float mScaleForReorderBounce = 1f;

    private final AxViewBackdropBlur mBackdropBlur = new AxViewBackdropBlur(this, false);
    private boolean mBlurDrawn;
    private boolean mCrossWindowBlurEnabled;
    private boolean mBlurEnabled = true;
    private int mBlurDepth = 45;

    private final Consumer<Boolean> mCrossWindowBlurListener =
            this::onCrossWindowBlurEnabledChanged;
    private final ThemeManager.ThemeChangeListener mThemeChangeListener = this::postInvalidate;

    public QsbGoogleOuterLayout(@NonNull Context context) {
        this(context, null);
    }

    public QsbGoogleOuterLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public QsbGoogleOuterLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        setBackground(new QsbOuterDrawable(context));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getBackground() == null) {
            setBackground(new QsbOuterDrawable(getContext()));
        }
        updateBlurPreferences();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mBackdropBlur.onAttachedToWindow();
        mCrossWindowBlurEnabled = CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled();
        CrossWindowBlurListeners.getInstance().addListener(
                getContext().getMainExecutor(), mCrossWindowBlurListener);
        LauncherPrefs.getPrefs(getContext()).registerOnSharedPreferenceChangeListener(this);
        ThemeManager.INSTANCE.get(getContext()).addChangeListener(mThemeChangeListener);
        updateBlurPreferences();
        postInvalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        hideBlur();
        mBackdropBlur.onDetachedFromWindow();
        CrossWindowBlurListeners.getInstance().removeListener(mCrossWindowBlurListener);
        LauncherPrefs.getPrefs(getContext()).unregisterOnSharedPreferenceChangeListener(this);
        ThemeManager.INSTANCE.get(getContext()).removeChangeListener(mThemeChangeListener);
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        mBackdropBlur.onVisibilityAggregated(isVisible);
        if (!isVisible) {
            hideBlur();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            updateBlurPreferences();
            postInvalidate();
        } else {
            hideBlur();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            postInvalidate();
        }
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return super.verifyDrawable(who)
                || (mBackdropBlur != null && mBackdropBlur.verifyDrawable(who));
    }

    private void onCrossWindowBlurEnabledChanged(boolean enabled) {
        mCrossWindowBlurEnabled = enabled;
        updateBlurState();
        postInvalidate();
    }

    private void updateBlurPreferences() {
        Context context = getContext();
        mBlurEnabled = LauncherPrefs.SEARCH_BAR_BLUR.get(context);
        mBlurDepth = Math.max(0, LauncherPrefs.BLUR_DEPTH.get(context));
        mBackdropBlur.setBlurRadiusPx(mBlurDepth);
        updateBlurState();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (LauncherPrefs.SEARCH_BAR_BLUR.getSharedPrefKey().equals(key)
                || LauncherPrefs.BLUR_DEPTH.getSharedPrefKey().equals(key)
                || LauncherPrefs.DOCK_THEME.getSharedPrefKey().equals(key)) {
            updateBlurPreferences();
            postInvalidate();
        }
    }

    private boolean isBlurAvailable() {
        return mBlurEnabled && mCrossWindowBlurEnabled && mBlurDepth > 0
                && isHardwareAccelerated();
    }

    private boolean canDrawBlur() {
        return isBlurAvailable() && isAttachedToWindow() && isShown()
                && getWidth() > 0 && getHeight() > 0
                && getWindowVisibility() == VISIBLE;
    }

    private void updateBlurState() {
        // The blur renderer already applies this view's and its ancestors' alpha.
        mBackdropBlur.setEnabled(canDrawBlur());
    }

    private void hideBlur() {
        mBackdropBlur.setEnabled(false);
        if (mBlurDrawn) {
            mBlurDrawn = false;
            if (getBackground() instanceof QsbOuterDrawable outerDrawable) {
                outerDrawable.setBlurActive(false);
            }
            invalidate();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        drawBlur(canvas);
        super.draw(canvas);
    }

    private void drawBlur(Canvas canvas) {
        if (!canvas.isHardwareAccelerated() || !canDrawBlur()) {
            hideBlur();
            return;
        }

        updateBlurState();
        float cornerRadius = getHeight() / 2f;
        boolean blurDrawn = mBackdropBlur.draw(canvas, 0, 0, getWidth(), getHeight(), cornerRadius);
        if (mBlurDrawn != blurDrawn) {
            mBlurDrawn = blurDrawn;
            if (getBackground() instanceof QsbOuterDrawable outerDrawable) {
                outerDrawable.setBlurActive(blurDrawn);
            }
            invalidate();
        }
    }

    public void playSquishAnimation() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof QsbLayoutGoogle qsbGoogle) {
                qsbGoogle.playLandingSquishAnimation();
                return;
            }
        }
    }

    @Override
    public void setIconVisible(boolean visible) {
        // Keep search bar visible during window transitions so it does not flicker or pop
    }

    @Override
    public void setForceHideDot(boolean hide) {}

    @Override
    public MultiTranslateDelegate getTranslateDelegate() {
        return mTranslateDelegate;
    }

    @Override
    public void setReorderBounceScale(float scale) {
        mScaleForReorderBounce = scale;
        super.setScaleX(scale);
        super.setScaleY(scale);
    }

    @Override
    public float getReorderBounceScale() {
        return mScaleForReorderBounce;
    }
}
