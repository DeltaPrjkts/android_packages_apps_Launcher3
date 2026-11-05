/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.os.Trace.TRACE_TAG_APP;

import static com.android.launcher3.Flags.enableOverviewBackgroundWallpaperBlur;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.WallpaperManager;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.gui.EarlyWakeupInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Trace;
import android.util.FloatProperty;
import android.util.Log;
import android.view.CrossWindowBlurListeners;
import android.view.SurfaceControl;
import android.view.View;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.app.animation.Interpolators;
import com.android.launcher3.Flags;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherPrefChangeListener;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.LauncherState;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.statemanager.StateManager;
import com.android.launcher3.statemanager.StatefulActivity;
import com.android.launcher3.uioverrides.QuickstepLauncher;
import com.android.launcher3.util.MultiPropertyFactory;
import com.android.launcher3.util.MultiPropertyFactory.MultiProperty;
import com.android.quickstep.views.RecentsViewContainer;
import com.android.systemui.shared.system.BlurUtils;

/**
 * Utility class for applying depth effect
 */
public class BaseDepthController {
    public static final float DEPTH_0_PERCENT = 0f;
    public static final float DEPTH_60_PERCENT = 0.6f;
    public static final float DEPTH_70_PERCENT = 0.7f;

    private static final FloatProperty<BaseDepthController> DEPTH =
            new FloatProperty<BaseDepthController>("depth") {
                @Override
                public void setValue(BaseDepthController depthController, float depth) {
                    depthController.setDepth(depth);
                }

                @Override
                public Float get(BaseDepthController depthController) {
                    return depthController.mDepth;
                }
            };

    private static final int DEPTH_INDEX_STATE_TRANSITION = 0;
    private static final int DEPTH_INDEX_WIDGET = 1;
    private static final int DEPTH_INDEX_COUNT = 2;

    // b/291401432
    private static final String TAG = "BaseDepthController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final long WALLPAPER_ZOOM_HANDOFF_DURATION_MS = 700;

    @Nullable protected final QuickstepLauncher mLauncher;
    private final StatefulActivity<?> mActivity;
    private final RecentsViewContainer mContainer;
    /** Property to set the depth for state transition. */
    public final MultiProperty stateDepth;
    /** Property to set the depth for widget picker. */
    public final MultiProperty widgetDepth;

    /**
     * Blur radius when completely zoomed out, in pixels.
     */
    protected final int mMaxBlurRadius;
    protected final WallpaperManager mWallpaperManager;
    protected boolean mCrossWindowBlursEnabled;

    private float mLastWallpaperZoom = -1f;
    @Nullable private IBinder mWallpaperWindowToken;
    private boolean mDisposed;
    private boolean mWallpaperZoomSuppressed;
    @Nullable private ValueAnimator mWallpaperZoomHandoffAnimator;
    private float mWallpaperZoomHandoffTarget;
    private float mAppAnimationBlur = -1f;
    private float mAppWallpaperDepth = Float.NaN;
    @Nullable private ValueAnimator mAppWallpaperAnimator;
    private final boolean mCosWallpaperMotion;

    private final LauncherPrefChangeListener mPreferenceChangeListener =
            this::onPreferenceChanged;

    /**
     * Ratio from 0 to 1, where 0 is fully zoomed out, and 1 is zoomed in.
     *
     * @see android.service.wallpaper.WallpaperService.Engine#onZoomChanged(float)
     */
    private float mDepth;

    protected SurfaceControl mBaseSurface;
    protected SurfaceControl mBaseSurfaceOverride;
    // May be temporarily null while the Launcher is being created, in which case all blur
    // requests will be applied immediately rather than synced to the RenderThread. This shouldn't
    // really happen in practice since we won't apply blur until the Launcher is interactive.
    @Nullable protected SurfaceTransactionApplier mSurfaceTransactionApplier;

    // Hints that there is potentially content behind Launcher and that we shouldn't optimize by
    // marking the launcher surface as opaque.  Only used in certain Launcher states.
    private boolean mHasContentBehindLauncher;

    /** Pause blur but allow transparent, can be used when launch something behind the Launcher. */
    protected boolean mPauseBlurs;

    /**
     * Last blur value, in pixels, that was applied.
     */
    protected int mCurrentBlur;
    /**
     * If we requested early wake-up offsets to SurfaceFlinger.
     */
    protected boolean mInEarlyWakeUp;

    protected boolean mWaitingOnSurfaceValidity;

    private boolean mWorkspaceBlurActive = false;

    private SurfaceControl mBlurSurface = null;
    /**
     * Info for early wakeup requests to SurfaceFlinger.
     */
    private EarlyWakeupInfo mEarlyWakeupInfo = new EarlyWakeupInfo();

    public <T extends StatefulActivity<?> & RecentsViewContainer> BaseDepthController(T activity) {
        mActivity = activity;
        mContainer = activity;
        mLauncher = activity instanceof QuickstepLauncher launcher ? launcher : null;
        mCosWallpaperMotion = mLauncher != null && LauncherPrefs.COS_APP_ANIMATIONS.get(activity);
        if (Flags.allAppsBlur() || enableOverviewBackgroundWallpaperBlur()) {
            mCrossWindowBlursEnabled =
                    CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled();
        }
        mMaxBlurRadius = LauncherPrefs.BLUR_DEPTH.get(mActivity);
        mWallpaperManager = activity.getSystemService(WallpaperManager.class);

        MultiPropertyFactory<BaseDepthController> depthProperty =
                new MultiPropertyFactory<>(this, DEPTH, DEPTH_INDEX_COUNT, Float::max);
        stateDepth = depthProperty.get(DEPTH_INDEX_STATE_TRANSITION);
        widgetDepth = depthProperty.get(DEPTH_INDEX_WIDGET);
        mEarlyWakeupInfo.token = new Binder();
        mEarlyWakeupInfo.trace = BaseDepthController.class.getName();
        LauncherPrefs.get(mActivity).addListener(
                mPreferenceChangeListener,
                LauncherPrefs.ALLOW_WALLPAPER_ZOOMING,
                LauncherPrefs.ALTERNATIVE_WALLPAPER_ZOOMING);
    }

    /**
     * Sets the applier to use for syncing surface transactions to the RenderThread.
     *
     * @param rootView The root view of the surface to apply the surface transactions to.
     */
    public void setSurfaceTransactionApplier(View rootView) {
        mSurfaceTransactionApplier = new SurfaceTransactionApplier(rootView);
    }

    /**
     * Returns if cross window blurs are enabled. In other words, whether launcher should use blurs
     * style UI or fallback style UI.
     */
    public boolean isCrossWindowBlursEnabled() {
        return mCrossWindowBlursEnabled;
    }

    protected void setCrossWindowBlursEnabled(boolean isEnabled) {
        if (mDisposed || mCrossWindowBlursEnabled == isEnabled) {
            return;
        }
        mCrossWindowBlursEnabled = isEnabled;
        if (mLauncher != null) {
            mLauncher.updateBlurStyle();
        }
        applyDepthAndBlur();
    }

    public void setHasContentBehindLauncher(boolean hasContentBehindLauncher) {
        mHasContentBehindLauncher = hasContentBehindLauncher;
    }

    public void pauseBlursOnWindows(boolean pause) {
        if (mDisposed || mPauseBlurs == pause) {
            return;
        }
        mPauseBlurs = pause;
        applyDepthAndBlur();
        if (mLauncher != null && mLauncher.getHotseat() != null) {
            mLauncher.getHotseat().postInvalidate();
        }
    }

    public boolean isBlursPaused() {
        return mPauseBlurs;
    }

    public void setAppAnimationBlur(float radius) {
        mAppAnimationBlur = radius;
        if (radius < 0f && mLauncher != null) mLauncher.getWorkspace().setRenderEffect(null);
        blurWorkspaceDepthTargets();
    }

    public Animator animateAppWallpaperDepth(float from, float to, long duration,
            Interpolator interpolator) {
        float startDepth = Float.isNaN(mAppWallpaperDepth) ? from : mAppWallpaperDepth;
        ValueAnimator animator = ValueAnimator.ofFloat(startDepth, to);
        animator.setDuration(duration);
        animator.setInterpolator(interpolator);
        animator.addUpdateListener(value -> {
            if (mAppWallpaperAnimator != animator) return;
            mAppWallpaperDepth = (Float) value.getAnimatedValue();
            applyWallpaperZoom(false);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                float currentDepth = Float.isNaN(mAppWallpaperDepth)
                        ? startDepth : mAppWallpaperDepth;
                ValueAnimator previous = mAppWallpaperAnimator;
                mAppWallpaperAnimator = animator;
                mAppWallpaperDepth = currentDepth;
                if (previous != null && previous != animator) previous.cancel();
                animator.setFloatValues(currentDepth, to);
                applyWallpaperZoom(false);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mAppWallpaperAnimator == animation) {
                    mAppWallpaperAnimator = null;
                    mAppWallpaperDepth = Float.NaN;
                    applyWallpaperZoom(false);
                }
            }
        });
        return animator;
    }

    protected void onInvalidSurface() { }

    private void onPreferenceChanged(String key) {
        if (LauncherPrefs.ALLOW_WALLPAPER_ZOOMING.getSharedPrefKey().equals(key)
                || LauncherPrefs.ALTERNATIVE_WALLPAPER_ZOOMING.getSharedPrefKey().equals(key)) {
            mActivity.getMainExecutor().execute(() -> applyWallpaperZoom(/* force */ true));
        }
    }

    public void setWallpaperZoomSuppressed(boolean suppressed) {
        if (mDisposed || mWallpaperZoomSuppressed == suppressed) {
            return;
        }
        mWallpaperZoomSuppressed = suppressed;
        animateWallpaperZoomHandoff();
    }

    protected void applyDepthAndBlur() {
        applyDepthAndBlur(null, /* applyImmediately */ false, /* skipSimilarBlur */ true);
    }

    /**
     * Applies depth and blur to the launcher.
     *
     * @param surfaceTransaction optional SurfaceTransaction to apply the blur to.
     * @param applyImmediately whether to apply the blur immediately or defer to the next frame.
     * @param skipSimilarBlur whether to skip applying blur if the change is minimal.
     */
    private void applyDepthAndBlur(@Nullable SurfaceTransaction surfaceTransaction,
            boolean applyImmediately, boolean skipSimilarBlur) {
        if (mDisposed) {
            return;
        }
        float depth = mDepth;
        applyWallpaperZoom(/* force */ false);
        applyDepthBlur(surfaceTransaction, applyImmediately, skipSimilarBlur, depth);
    }

    private void applyWallpaperZoom(boolean force) {
        if (mDisposed || mWallpaperZoomSuppressed) {
            return;
        }
        float zoom = getWallpaperZoom();
        if (!force && mWallpaperZoomHandoffAnimator != null
                && Float.compare(zoom, mWallpaperZoomHandoffTarget) == 0) {
            return;
        }
        cancelWallpaperZoomHandoff();
        submitWallpaperZoom(zoom, force);
    }

    private float getWallpaperZoom() {
        float depth = Float.isNaN(mAppWallpaperDepth) ? mDepth : mAppWallpaperDepth;
        if (mCosWallpaperMotion) {
            depth = Utilities.boundToRange(depth / Math.max(0.01f,
                    LauncherState.BACKGROUND_APP.getDepth(mLauncher)), 0f, 1f);
        }
        return mWallpaperZoomSuppressed
                ? 0f
                : mapDepthToWallpaperZoom(
                        depth,
                        LauncherPrefs.ALLOW_WALLPAPER_ZOOMING.get(mActivity),
                        LauncherPrefs.ALTERNATIVE_WALLPAPER_ZOOMING.get(mActivity));
    }

    private void submitWallpaperZoom(float zoom, boolean force) {
        if (mDisposed) {
            return;
        }
        View rootView = mActivity.getRootView();
        if (rootView == null) {
            return;
        }
        IBinder windowToken = rootView.getWindowToken();
        if (windowToken == null) {
            return;
        }

        if (force || windowToken != mWallpaperWindowToken
                || (!Float.isNaN(mAppWallpaperDepth) && mLastWallpaperZoom != zoom)
                || Math.abs(mLastWallpaperZoom - zoom) >= 0.015f
                || (zoom == 0f && mLastWallpaperZoom != 0f)
                || (zoom == 1f && mLastWallpaperZoom != 1f)) {
            mWallpaperManager.setWallpaperZoomOut(windowToken, zoom);

            mLastWallpaperZoom = zoom;
            mWallpaperWindowToken = windowToken;
        }
    }

    protected void onWallpaperWindowDetached() {
        if (mAppWallpaperAnimator != null) mAppWallpaperAnimator.cancel();
        cancelWallpaperZoomHandoff();
        mWallpaperWindowToken = null;
        mLastWallpaperZoom = -1f;
    }

    private void animateWallpaperZoomHandoff() {
        cancelWallpaperZoomHandoff();

        float targetZoom = getWallpaperZoom();
        float startZoom = mLastWallpaperZoom < 0f ? targetZoom : mLastWallpaperZoom;
        if (Float.compare(startZoom, targetZoom) == 0) {
            submitWallpaperZoom(targetZoom, /* force */ true);
            return;
        }

        ValueAnimator animator = ValueAnimator.ofFloat(startZoom, targetZoom);
        mWallpaperZoomHandoffTarget = targetZoom;
        mWallpaperZoomHandoffAnimator = animator;
        animator.setDuration(WALLPAPER_ZOOM_HANDOFF_DURATION_MS);
        animator.setInterpolator(Interpolators.EMPHASIZED_DECELERATE);
        animator.addUpdateListener(animation -> submitWallpaperZoom(
                (Float) animation.getAnimatedValue(), /* force */ false));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mWallpaperZoomHandoffAnimator == animation) {
                    mWallpaperZoomHandoffAnimator = null;
                }
            }
        });
        animator.start();
    }

    private void cancelWallpaperZoomHandoff() {
        if (mWallpaperZoomHandoffAnimator != null) {
            mWallpaperZoomHandoffAnimator.cancel();
            mWallpaperZoomHandoffAnimator = null;
        }
    }

    private void applyDepthBlur(@Nullable SurfaceTransaction surfaceTransaction,
            boolean applyImmediately, boolean skipSimilarBlur, float depth) {

        if (!BlurUtils.supportsBlursOnWindows()) {
            return;
        }
        if (mBaseSurface == null) {
            if (surfaceTransaction != null) {
                surfaceTransaction.getTransaction().apply();
            }
            if (DEBUG) {
                Log.d(TAG, "mSurface is null and mCurrentBlur is: " + mCurrentBlur);
            }
            return;
        }
        if (!mBaseSurface.isValid()) {
            if (DEBUG) Log.d(TAG, "mSurface is not valid");
            mWaitingOnSurfaceValidity = true;
            onInvalidSurface();
            return;
        }
        mWaitingOnSurfaceValidity = false;
        boolean hasOpaqueBg = mContainer.getScrimView().isFullyOpaque();
        boolean isSurfaceOpaque = !mHasContentBehindLauncher && hasOpaqueBg && !mPauseBlurs;

        float blurAmount = mapDepthToBlur(depth);
        SurfaceControl blurSurface =
                enableOverviewBackgroundWallpaperBlur() && mBlurSurface != null ? mBlurSurface
                        : mBaseSurface;

        int previousBlur = mCurrentBlur;
        int newBlur = mCrossWindowBlursEnabled && !hasOpaqueBg && !mPauseBlurs ? (int) (blurAmount
                * mMaxBlurRadius) : 0;
        int delta = Math.abs(newBlur - previousBlur);
        if (skipSimilarBlur && delta < Utilities.dpToPx(2) && newBlur != 0 && previousBlur != 0
                && blurAmount != 1f) {
            if (DEBUG) {
                Log.d(TAG, "Skipping small blur delta. newBlur: " + newBlur + " previousBlur: "
                        + previousBlur + " delta: " + delta + " surface: " + blurSurface);
            }
            if (Flags.allAppsBlur()
                    && (shouldBlurWorkspaceNow() && mCurrentBlur > 0) != mWorkspaceBlurActive) {
                blurWorkspaceDepthTargets();
            }
            return;
        }
        mCurrentBlur = newBlur;
        if (DEBUG) {
            Log.v(TAG, "Applying blur: " + mCurrentBlur + " to " + blurSurface + " applyImmediately: "
                    + applyImmediately);
        }

        if (surfaceTransaction == null) {
            surfaceTransaction = new SurfaceTransaction();
        }

        surfaceTransaction.forSurface(blurSurface)
                .setBackgroundBlurRadius(mCurrentBlur)
                .setOpaque(isSurfaceOpaque);
        // Set early wake-up flags when we know we're executing an expensive operation, this way
        // SurfaceFlinger will adjust its internal offsets to avoid jank.
        boolean wantsEarlyWakeUp = blurAmount > 0 && blurAmount < 1;
        if (wantsEarlyWakeUp && !mInEarlyWakeUp) {
            setEarlyWakeup(surfaceTransaction.getTransaction(), true);
        } else if (!wantsEarlyWakeUp && mInEarlyWakeUp) {
            setEarlyWakeup(surfaceTransaction.getTransaction(), false);
        }

        if (applyImmediately || mSurfaceTransactionApplier == null) {
            if (DEBUG) {
                Log.d(TAG, "Applying blur immediately, mSurfaceTransactionApplier is null? "
                        + (mSurfaceTransactionApplier == null));
            }
            surfaceTransaction.getTransaction().apply();
        } else {
            mSurfaceTransactionApplier.scheduleApply(surfaceTransaction);
        }

        blurWorkspaceDepthTargets();
    }

    /**
     * Sets the early wakeup state.
     *
     * @param inEarlyWakeUp whether SurfaceFlinger's early wakeup timing should be active.
     */
    public void setEarlyWakeup(boolean inEarlyWakeUp) {
        if (mInEarlyWakeUp == inEarlyWakeUp) {
            return;
        }
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            setEarlyWakeup(transaction, inEarlyWakeUp);
            transaction.apply();
        }
    }

    /**
     * Sets the early wakeup state.
     *
     * @param transaction transaction to apply to.
     * @param start whether to start or end the early wakeup.
     */
    protected void setEarlyWakeup(@NonNull SurfaceControl.Transaction transaction, boolean start) {
        if (mInEarlyWakeUp == start) {
            return;
        }
        if (DEBUG) Log.d(TAG, "setEarlyWakeup: " + start);
        if (start) {
            Trace.instantForTrack(TRACE_TAG_APP, TAG, "notifyRendererForGpuLoadUp");
            mActivity.getRootView().getViewRootImpl().notifyRendererForGpuLoadUp("applyBlur");
            mActivity.getRootView().getViewRootImpl().notifyRendererOfExpensiveFrame("applyBlur");
            transaction.setEarlyWakeupStart(mEarlyWakeupInfo);
        } else {
            transaction.setEarlyWakeupEnd(mEarlyWakeupInfo);
        }
        mInEarlyWakeUp = start;
    }

    private boolean shouldBlurWorkspaceNow() {
        if (mLauncher == null) {
            return false;
        }
        StateManager<LauncherState, Launcher> stateManager = mLauncher.getStateManager();
        LauncherState targetState = stateManager.getTargetState() != null
                ? stateManager.getTargetState() : stateManager.getState();
        return stateManager.getCurrentStableState().shouldBlurWorkspace(targetState);
    }

    /** @return {@code true} if the workspace should be blurred. */
    @VisibleForTesting
    public boolean blurWorkspaceDepthTargets() {
        if (mLauncher != null && mAppAnimationBlur >= 0f) {
            RenderEffect effect = mCrossWindowBlursEnabled && mAppAnimationBlur > 0f
                    ? RenderEffect.createBlurEffect(mAppAnimationBlur, mAppAnimationBlur,
                            Shader.TileMode.CLAMP) : null;
            mLauncher.getWorkspace().setRenderEffect(effect);
            return effect != null;
        }
        if (mLauncher == null || !Flags.allAppsBlur()) {
            return false;
        }
        StateManager<LauncherState, Launcher> stateManager = mLauncher.getStateManager();
        LauncherState targetState = stateManager.getTargetState() != null
                ? stateManager.getTargetState() : stateManager.getState();
        boolean shouldBlurWorkspace = shouldBlurWorkspaceNow();

        RenderEffect blurEffect = shouldBlurWorkspace && mCurrentBlur > 0
                ? RenderEffect.createBlurEffect(mCurrentBlur, mCurrentBlur, Shader.TileMode.DECAL)
                // If blur is not desired, clear the blur effect from the depth targets.
                : null;
        if (DEBUG) {
            Log.d(TAG, "shouldBlurWorkspace: " + shouldBlurWorkspace
                    + " targetState: " + targetState
                    + " currentStableState: " + stateManager.getCurrentStableState()
                    + " mCurrentBlur: " + mCurrentBlur
                    + " mLauncher.getDepthBlurTargets(): " + mLauncher.getDepthBlurTargets());
        }
        mWorkspaceBlurActive = blurEffect != null;
        mLauncher.getDepthBlurTargets().forEach(target -> target.setRenderEffect(blurEffect));
        return shouldBlurWorkspace;
    }

    private void setDepth(float depth) {
        depth = Utilities.boundToRange(depth, 0, 1);
        // Depth of the Launcher state we are in or transitioning to.
        float targetStateDepth = getTargetStateDepth();

        float depthF;
        if (depth == targetStateDepth) {
            // Always apply the target state depth.
            depthF = depth;
        } else {
            // Round out the depth to dedupe frequent, non-perceptable updates
            int depthI = (int) (depth * 128);
            depthF = depthI / 128f;
        }
        if (Float.compare(mDepth, depthF) == 0) {
            return;
        }
        mDepth = depthF;
        applyDepthAndBlur();
    }

    /**
     * Sets the lowest surface that should not be blurred.
     * <p>
     * Blur is applied to below {@link #mBaseSurfaceOverride}. When set to {@code null}, blur is
     * applied to below {@link #mBaseSurface}.
     * </p>
     */
    public void setBaseSurfaceOverride(@Nullable SurfaceControl baseSurfaceOverride,
            boolean applyOnDraw) {
        if (mBaseSurfaceOverride != baseSurfaceOverride) {
            boolean applyImmediately = mBaseSurfaceOverride != null && baseSurfaceOverride == null
                    && !applyOnDraw;
            mBaseSurfaceOverride = baseSurfaceOverride;
            if (DEBUG) {
                Log.d(TAG, "setBaseSurfaceOverride: applying blur behind leash " + baseSurfaceOverride);
            }
            SurfaceTransaction transaction = setupBlurSurface();
            applyDepthAndBlur(transaction, applyImmediately, /* skipSimilarBlur */ false);
        }
    }

    protected float getTargetStateDepth() {
        return mLauncher == null ? 0f
                : mLauncher.getStateManager().getState().getDepth(mLauncher);
    }

    public float getBackgroundAppDepth() {
        return mLauncher == null ? DEPTH_70_PERCENT
                : LauncherState.BACKGROUND_APP.getDepth(mLauncher);
    }

    public void dispose() {
        mDisposed = true;
        onWallpaperWindowDetached();
        LauncherPrefs.get(mActivity).removeListener(
                mPreferenceChangeListener,
                LauncherPrefs.ALLOW_WALLPAPER_ZOOMING,
                LauncherPrefs.ALTERNATIVE_WALLPAPER_ZOOMING);
    }

    protected final boolean isDisposed() {
        return mDisposed;
    }

    private @Nullable SurfaceTransaction setupBlurSurface() {
        SurfaceTransaction surfaceTransaction = null;

        if (mBaseSurface != null && mBaseSurface.isValid() && mBaseSurfaceOverride != null) {
            surfaceTransaction = new SurfaceTransaction();
            surfaceTransaction.forSurface(mBaseSurface).setBackgroundBlurRadius(0).setOpaque(false);
            if (mBlurSurface == null) {
                mBlurSurface = new SurfaceControl.Builder()
                        .setName("Overview Blur")
                        .setHidden(false)
                        .build();
                if (DEBUG) {
                    Log.d(TAG, "setupBlurSurface: creating Overview Blur surface " + mBlurSurface);
                }
                surfaceTransaction.forSurface(mBlurSurface).reparent(mBaseSurface);
                if (DEBUG) {
                    Log.d(TAG, "setupBlurSurface: reparenting " + mBlurSurface + " to " + mBaseSurface);
                }
            }
            surfaceTransaction.forSurface(mBlurSurface).setRelativeLayer(mBaseSurfaceOverride, -1);
            if (DEBUG) Log.d(TAG, "setupBlurSurface: relayering to leash " + mBaseSurfaceOverride);
        } else if (mBlurSurface != null) {
            if (DEBUG) Log.d(TAG, "setupBlurSurface: removing blur surface " + mBlurSurface);
            surfaceTransaction = new SurfaceTransaction();
            surfaceTransaction.forSurface(mBlurSurface).setRemove();
            mBlurSurface = null;
        }
        return surfaceTransaction;
    }

    /**
     * Sets the specified app target surface to apply the blur to.
     */
    protected void setBaseSurface(SurfaceControl baseSurface) {
        if (mBaseSurface != baseSurface || mWaitingOnSurfaceValidity) {
            mBaseSurface = baseSurface;
            if (DEBUG) {
                Log.d(TAG, "setSurface:\n\tmWaitingOnSurfaceValidity: " + mWaitingOnSurfaceValidity
                        + "\n\tmBaseSurface: " + mBaseSurface);
            }
            SurfaceTransaction transaction = null;
            if (enableOverviewBackgroundWallpaperBlur()) {
                transaction = setupBlurSurface();
            }
            applyDepthAndBlur(transaction, /* applyImmediately */ false,
                    /* skipSimilarBlur */ false);
        }
    }

    /**
     * Maps depth values to blur amounts as a percentage of the max blur.
     * The blur percentage grows linearly with depth, and maxes out at 30% depth.
     */
    private static float mapDepthToBlur(float depth) {
        return Interpolators.STANDARD.getInterpolation(
                Interpolators.clampToProgress(depth, 0, 0.4f));
    }

    @VisibleForTesting
    static float mapDepthToWallpaperZoom(float depth, boolean wallpaperZoomingEnabled,
            boolean alternativeWallpaperZooming) {
        if (!wallpaperZoomingEnabled) {
            return 0f;
        }
        return alternativeWallpaperZooming ? 1f - depth : depth;
    }
}
