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

package com.android.quickstep;

import static com.android.app.animation.Interpolators.EMPHASIZED_DECELERATE;
import static com.android.launcher3.LauncherState.NORMAL;
import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.graphics.Rect;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.Utilities;
import com.android.launcher3.uioverrides.QuickstepLauncher;
import com.android.quickstep.util.HomeUnlockReveal;
import com.android.systemui.shared.system.smartspace.ILauncherUnlockAnimationController;
import com.android.systemui.shared.system.smartspace.SmartspaceState;

import java.util.concurrent.ExecutionException;

public final class HomeUnlockRevealController
        extends ILauncherUnlockAnimationController.Stub
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private QuickstepLauncher mLauncher;
    private HomeUnlockReveal mWorkspaceAnimation;
    private ValueAnimator mAnimator;
    private boolean mPrepared;
    private final Runnable mSystemUiConnectionListener = () -> {
        if (mLauncher != null && !SystemUiProxy.INSTANCE.get(mLauncher).isActive()) {
            cancel();
        }
    };

    public HomeUnlockRevealController(QuickstepLauncher launcher) {
        mLauncher = launcher;
        mWorkspaceAnimation = new HomeUnlockReveal(launcher);
        LauncherPrefs.getPrefs(launcher).registerOnSharedPreferenceChangeListener(this);
        SystemUiProxy proxy = SystemUiProxy.INSTANCE.get(launcher);
        proxy.addOnStateChangeListener(mSystemUiConnectionListener);
        proxy.setLauncherUnlockAnimationController(
                launcher.getComponentName().getClassName(), this);
    }

    @Override
    public void prepareForUnlock(boolean animateSmartspace, Rect lockscreenSmartspaceBounds,
            int selectedPage) {
        try {
            MAIN_EXECUTOR.submit(() -> {
                cancel();
                if (isEnabled() && mLauncher.isInState(NORMAL)) {
                    mWorkspaceAnimation.prepareForUnlock();
                    mPrepared = true;
                }
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while preparing workspace unlock", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Unable to prepare workspace unlock", e.getCause());
        }
    }

    @Override
    public void setUnlockAmount(float amount, boolean forceIfAnimating) {
        MAIN_EXECUTOR.execute(() -> {
            if (!isEnabled()) {
                cancel();
                return;
            }
            if (!Float.isFinite(amount) || (mAnimator != null && !forceIfAnimating)) {
                return;
            }
            stopAnimator();
            if (amount >= 1f) {
                cancel();
            } else if (mPrepared) {
                mWorkspaceAnimation.setUnlockAmount(Utilities.boundToRange(amount, 0f, 1f));
            }
        });
    }

    @Override
    public void playUnlockAnimation(boolean unlocked, long duration, long startDelay) {
        MAIN_EXECUTOR.execute(() -> {
            if (!isEnabled()) {
                cancel();
                return;
            }
            if (!mPrepared) {
                return;
            }
            stopAnimator();
            ValueAnimator animator = ValueAnimator.ofFloat(
                    mWorkspaceAnimation.getUnlockAmount(), unlocked ? 1f : 0f);
            mAnimator = animator;
            animator.setDuration(Math.max(0L, duration));
            animator.setStartDelay(Math.max(0L, startDelay));
            animator.setInterpolator(EMPHASIZED_DECELERATE);
            animator.addUpdateListener(value -> mWorkspaceAnimation.setUnlockAmount(
                    (Float) value.getAnimatedValue()));
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (mAnimator == animation) {
                        mAnimator = null;
                        cancel();
                    }
                }
            });
            animator.start();
        });
    }

    @Override
    public void playUnlockAnimationWithWallpaperDepth(boolean unlocked, long duration,
            long startDelay, boolean animateWallpaperDepth) {
        playUnlockAnimation(unlocked, duration, startDelay);
    }

    @Override
    public void setSmartspaceSelectedPage(int selectedPage) { }

    @Override
    public void setSmartspaceVisibility(int visibility) { }

    @Override
    public void dispatchSmartspaceStateToSysui() {
        MAIN_EXECUTOR.execute(() -> {
            if (mLauncher != null) {
                SystemUiProxy.INSTANCE.get(mLauncher)
                        .notifySysuiSmartspaceStateUpdated(new SmartspaceState());
            }
        });
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        if (key == null || LauncherPrefs.WORKSPACE_UNLOCK_ANIMATION.getSharedPrefKey().equals(key)) {
            MAIN_EXECUTOR.execute(() -> {
                if (!isEnabled()) {
                    cancel();
                }
            });
        }
    }

    private boolean isEnabled() {
        return mLauncher != null && LauncherPrefs.WORKSPACE_UNLOCK_ANIMATION.get(mLauncher);
    }

    private void stopAnimator() {
        if (mAnimator != null) {
            ValueAnimator animator = mAnimator;
            mAnimator = null;
            animator.cancel();
        }
    }

    public void cancel() {
        stopAnimator();
        if (mWorkspaceAnimation != null) {
            mWorkspaceAnimation.reset();
        }
        mPrepared = false;
        if (mLauncher != null && mLauncher.getHotseat() != null) {
            mLauncher.getHotseat().postInvalidate();
        }
    }

    public void destroy() {
        if (mLauncher == null) {
            return;
        }
        cancel();
        LauncherPrefs.getPrefs(mLauncher).unregisterOnSharedPreferenceChangeListener(this);
        SystemUiProxy proxy = SystemUiProxy.INSTANCE.get(mLauncher);
        proxy.removeOnStateChangeListener(mSystemUiConnectionListener);
        proxy.clearLauncherUnlockAnimationController(this);
        mWorkspaceAnimation = null;
        mLauncher = null;
    }
}
