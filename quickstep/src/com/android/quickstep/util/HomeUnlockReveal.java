/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.quickstep.util;

import android.util.ArrayMap;
import android.util.ArraySet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.android.launcher3.CellLayout;
import com.android.launcher3.Launcher;
import com.android.launcher3.ShortcutAndWidgetContainer;
import com.android.launcher3.Utilities;
import com.android.launcher3.Workspace;
import com.android.launcher3.widget.NavigableAppWidgetHostView;

public final class HomeUnlockReveal implements ViewTreeObserver.OnPreDrawListener {
    private static final float START_SCALE = 0.85f;
    private static final int START_TRANSLATION_DP = 20;

    private final Launcher mLauncher;
    private final ArrayMap<View, ViewState> mViews = new ArrayMap<>();
    private final ArraySet<View> mExcludedViews = new ArraySet<>();
    private final ArrayMap<ViewGroup, boolean[]> mClipping = new ArrayMap<>();
    private final float mStartTranslation;
    private ViewTreeObserver mObserver;
    private float mUnlockAmount;

    public HomeUnlockReveal(Launcher launcher) {
        mLauncher = launcher;
        mStartTranslation = launcher.getResources().getDisplayMetrics().density
                * START_TRANSLATION_DP;
    }

    public void prepareForUnlock() {
        reset();
        mUnlockAmount = 0f;
        collectViews();
        mObserver = mLauncher.getDragLayer().getViewTreeObserver();
        mObserver.addOnPreDrawListener(this);
        setUnlockAmount(0f);
    }

    private void collectViews() {
        Workspace<?> workspace = mLauncher.getWorkspace();
        float[] center = {workspace.getWidth() / 2f, workspace.getHeight() / 2f};
        Utilities.getDescendantCoordRelativeToAncestor(
                workspace, mLauncher.getDragLayer(), center, false);
        disableClipping(workspace);
        workspace.forEachVisiblePage(page -> {
            CellLayout layout = (CellLayout) page;
            ShortcutAndWidgetContainer container = layout.getShortcutsAndWidgets();
            disableClipping(layout);
            disableClipping(container);
            for (int i = 0; i < container.getChildCount(); i++) {
                addView(container.getChildAt(i), center);
            }
        });
        addView(mLauncher.getHotseat(), center);
    }

    private void addView(View view, float[] center) {
        if (mViews.containsKey(view) || mExcludedViews.contains(view)
                || view.getVisibility() != View.VISIBLE || !view.isLaidOut()) {
            return;
        }
        if (view.getAlpha() != 1f || view.getScaleX() <= 0f || view.getScaleY() <= 0f
                || (!(view instanceof NavigableAppWidgetHostView)
                && (view.getScaleX() != 1f || view.getScaleY() != 1f))) {
            mExcludedViews.add(view);
            return;
        }
        float[] localCenter = {center[0], center[1]};
        Utilities.mapCoordInSelfToDescendant(view, mLauncher.getDragLayer(), localCenter);
        mViews.put(view, new ViewState(view, localCenter));
    }

    private void disableClipping(ViewGroup view) {
        if (!mClipping.containsKey(view)) {
            mClipping.put(view, new boolean[]{view.getClipChildren(), view.getClipToPadding()});
            view.setClipChildren(false);
            view.setClipToPadding(false);
        }
    }

    @Override
    public boolean onPreDraw() {
        collectViews();
        setUnlockAmount(mUnlockAmount);
        return true;
    }

    public float getUnlockAmount() {
        return mUnlockAmount;
    }

    public void setUnlockAmount(float amount) {
        mUnlockAmount = Utilities.boundToRange(amount, 0f, 1f);
        float scale = START_SCALE + (1f - START_SCALE) * mUnlockAmount;
        float translation = mStartTranslation * (1f - mUnlockAmount);
        for (int i = 0; i < mViews.size(); i++) {
            mViews.valueAt(i).apply(mUnlockAmount, scale, translation);
        }
    }

    public void reset() {
        if (mObserver != null) {
            ViewTreeObserver observer = mObserver.isAlive()
                    ? mObserver : mLauncher.getDragLayer().getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnPreDrawListener(this);
            }
        }
        mObserver = null;
        for (int i = 0; i < mViews.size(); i++) {
            mViews.valueAt(i).restore();
        }
        mViews.clear();
        mExcludedViews.clear();
        for (int i = 0; i < mClipping.size(); i++) {
            boolean[] clipping = mClipping.valueAt(i);
            mClipping.keyAt(i).setClipChildren(clipping[0]);
            mClipping.keyAt(i).setClipToPadding(clipping[1]);
        }
        mClipping.clear();
        mUnlockAmount = 1f;
        if (mLauncher != null && mLauncher.getHotseat() != null) {
            mLauncher.getHotseat().postInvalidate();
        }
    }

    private static final class ViewState {
        private final View mView;
        private final float mAlpha;
        private final float mScaleX;
        private final float mScaleY;
        private final float mTranslationX;
        private final float mTranslationY;
        private final float mPivotOffsetX;
        private final float mPivotOffsetY;

        ViewState(View view, float[] center) {
            mView = view;
            mAlpha = view.getAlpha();
            mScaleX = view.getScaleX();
            mScaleY = view.getScaleY();
            mTranslationX = view.getTranslationX();
            mTranslationY = view.getTranslationY();
            mPivotOffsetX = mScaleX
                    * (Utilities.boundToRange(center[0], 0f, view.getWidth()) - view.getPivotX());
            mPivotOffsetY = mScaleY
                    * (Utilities.boundToRange(center[1], 0f, view.getHeight()) - view.getPivotY());
        }

        void apply(float amount, float scale, float translation) {
            mView.setAlpha(mAlpha * amount);
            mView.setScaleX(mScaleX * scale);
            mView.setScaleY(mScaleY * scale);
            mView.setTranslationX(mTranslationX + mPivotOffsetX * (1f - scale));
            mView.setTranslationY(mTranslationY + mPivotOffsetY * (1f - scale) + translation);
        }

        void restore() {
            mView.setAlpha(mAlpha);
            mView.setScaleX(mScaleX);
            mView.setScaleY(mScaleY);
            mView.setTranslationX(mTranslationX);
            mView.setTranslationY(mTranslationY);
        }
    }
}
