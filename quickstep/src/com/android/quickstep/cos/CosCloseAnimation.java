/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.quickstep.cos;

import static com.android.launcher3.BaseActivity.EVENT_DESTROYED;
import static com.android.launcher3.LauncherState.NORMAL;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;

import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.statemanager.StateManager.StateListener;

public final class CosCloseAnimation extends AnimatorListenerAdapter
        implements StateListener<LauncherState> {
    private final Launcher mLauncher;
    private final Runnable mFinishAnimation;
    private final Runnable mOnDestroy = this::finish;
    private boolean mListening;

    public CosCloseAnimation(Launcher launcher, Runnable finishAnimation) {
        mLauncher = launcher;
        mFinishAnimation = finishAnimation;
    }

    @Override
    public void onAnimationStart(Animator animation) {
        if (mListening) return;
        mListening = true;
        mLauncher.getStateManager().addStateListener(this);
        mLauncher.addEventCallback(EVENT_DESTROYED, mOnDestroy);
    }

    @Override
    public void onStateTransitionStart(LauncherState state) {
        if (state != NORMAL) finish();
    }

    private void finish() {
        if (!mListening) return;
        onAnimationEnd(null);
        mFinishAnimation.run();
    }

    @Override
    public void onAnimationEnd(Animator animation) {
        if (!mListening) return;
        mListening = false;
        mLauncher.getStateManager().removeStateListener(this);
        mLauncher.removeEventCallback(EVENT_DESTROYED, mOnDestroy);
    }
}
