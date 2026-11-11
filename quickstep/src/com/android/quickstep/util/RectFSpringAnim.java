/*
 * Copyright (C) 2019 The Android Open Source Project
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


import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Choreographer;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation.OnAnimationEndListener;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.util.DynamicResource;
import com.android.quickstep.RemoteAnimationTargets.ReleaseCheck;
import com.android.quickstep.cos.CosAppAnimation;
import com.android.systemui.plugins.ResourceProvider;

import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.List;


/**
 * Applies spring forces to animate from a starting rect to a target rect,
 * while providing update callbacks to the caller.
 */
public class RectFSpringAnim extends ReleaseCheck {

    private static final FloatPropertyCompat<RectFSpringAnim> RECT_CENTER_X =
            new FloatPropertyCompat<RectFSpringAnim>("rectCenterXSpring") {
                @Override
                public float getValue(RectFSpringAnim anim) {
                    return anim.mCurrentCenterX;
                }

                @Override
                public void setValue(RectFSpringAnim anim, float currentCenterX) {
                    anim.mCurrentCenterX = currentCenterX;
                    anim.onUpdate();
                }
            };

    private static final FloatPropertyCompat<RectFSpringAnim> RECT_Y =
            new FloatPropertyCompat<RectFSpringAnim>("rectYSpring") {
                @Override
                public float getValue(RectFSpringAnim anim) {
                    return anim.mCurrentY;
                }

                @Override
                public void setValue(RectFSpringAnim anim, float y) {
                    anim.mCurrentY = y;
                    anim.onUpdate();
                }
            };

    private static final FloatPropertyCompat<RectFSpringAnim> RECT_SCALE_PROGRESS =
            new FloatPropertyCompat<RectFSpringAnim>("rectScaleProgress") {
                @Override
                public float getValue(RectFSpringAnim object) {
                    return object.mCurrentScaleProgress;
                }

                @Override
                public void setValue(RectFSpringAnim object, float value) {
                    object.mCurrentScaleProgress = value;
                    object.onUpdate();
                }
            };

    // Cached spring constants to avoid per-start resource lookups
    private static volatile boolean sConstantsLoaded;
    private static long sCachedMinVelocityXPxPerS;
    private static long sCachedMaxVelocityXPxPerS;
    private static long sCachedMinVelocityYPxPerS;
    private static long sCachedMaxVelocityYPxPerS;
    private static float sCachedFallOffFactor;
    private static float sCachedStiffnessX;
    private static float sCachedDampingX;
    private static float sCachedStiffnessY;
    private static float sCachedDampingY;
    private static float sCachedStiffnessZ;
    private static float sCachedDampingZ;

    private final RectF mStartRect;
    private final RectF mTargetRect;
    private final RectF mCurrentRect = new RectF();
    private final List<OnUpdateListener> mOnUpdateListeners = new ArrayList<>();
    private final List<Animator.AnimatorListener> mAnimatorListeners = new ArrayList<>();

    private float mCurrentCenterX;
    private float mCurrentY;
    // If true, tracking the bottom of the rects, else tracking the top.
    private float mCurrentScaleProgress;
    private SpringAnimation mRectXSpring;
    private SpringAnimation mRectYSpring;
    private SpringAnimation mRectScaleAnim;
    private boolean mAnimsStarted;
    private boolean mRectXAnimEnded;
    private boolean mRectYAnimEnded;
    private boolean mRectScaleAnimEnded;

    /**
     * Indicates which part of the start & target rects we are interpolating between.
     */
    public static final int TRACKING_TOP = 0;
    public static final int TRACKING_CENTER = 1;
    public static final int TRACKING_BOTTOM = 2;

    @Retention(SOURCE)
    @IntDef(value = {TRACKING_TOP,
                    TRACKING_CENTER,
                    TRACKING_BOTTOM})
    public @interface Tracking{}

    @Tracking
    public final int mTracking;
    protected final float mStiffnessX;
    protected final float mStiffnessY;
    protected final float mDampingX;
    protected final float mDampingY;
    protected final float mRectStiffness;
    private final float mRectDamping;
    private final boolean mUseConfiguredSpring;
    private final boolean mCos;
    private final boolean mNothing;
    private final boolean mQsb;
    private long mCosStartTime;
    private float mCosDurationScale;
    private float mCosFadeProgress;
    private Choreographer mChoreographer;
    private boolean mUpdatePending;
    private final Runnable mFrameUpdate;
    private boolean mEnding;
    private boolean mWindowDetached;

    public RectFSpringAnim(SpringConfig config) {
        mStartRect = config.startRect;
        mTargetRect = config.targetRect;
        mCurrentCenterX = mStartRect.centerX();

        setCanRelease(true);

        mTracking = config.tracking;
        mStiffnessX = config.stiffnessX;
        mStiffnessY = config.stiffnessY;
        mDampingX = config.dampingX;
        mDampingY = config.dampingY;
        mRectStiffness = config.rectStiffness;
        mRectDamping = config.rectDamping;
        mCos = config instanceof CosSpringConfig;
        mNothing = config instanceof NothingSpringConfig;
        mQsb = config instanceof QsbSpringConfig;
        mFrameUpdate = mCos ? this::dispatchFrameUpdate : null;
        mUseConfiguredSpring = mCos || mNothing
                || config instanceof WidgetSpringConfig;

        mCurrentY = getTrackedYFromRect(mStartRect);
    }

    public RectF getTargetRect() {
        return mTargetRect;
    }

    public boolean isCos() {
        return mCos;
    }

    public boolean isNothing() {
        return mNothing;
    }

    public boolean isRunning() {
        return mAnimsStarted && !isEnded();
    }

    public void detachWindow() {
        if (mCos) {
            mWindowDetached = true;
            setCanRelease(true);
        }
    }

    public boolean isWindowDetached() {
        return mWindowDetached;
    }

    public float getCosFadeProgress() {
        return mCosFadeProgress;
    }

    private float getTrackedYFromRect(RectF rect) {
        switch (mTracking) {
            case TRACKING_TOP:
                return rect.top;
            case TRACKING_BOTTOM:
                return rect.bottom;
            case TRACKING_CENTER:
            default:
                return rect.centerY();
        }
    }

    public void onTargetPositionChanged() {
        if (isEnded()) {
            return;
        }

        if (mRectXSpring != null) {
            mRectXSpring.animateToFinalPosition(mTargetRect.centerX());
            mRectXAnimEnded = false;
        }

        if (mRectYSpring != null) {
            switch (mTracking) {
                case TRACKING_TOP:
                    mRectYSpring.animateToFinalPosition(mTargetRect.top);
                    break;
                case TRACKING_BOTTOM:
                    mRectYSpring.animateToFinalPosition(mTargetRect.bottom);
                    break;
                case TRACKING_CENTER:
                    mRectYSpring.animateToFinalPosition(mTargetRect.centerY());
                    break;
            }
            mRectYAnimEnded = false;
        }
    }

    public void addOnUpdateListener(OnUpdateListener onUpdateListener) {
        mOnUpdateListeners.add(onUpdateListener);
    }

    public void addAnimatorListener(Animator.AnimatorListener animatorListener) {
        mAnimatorListeners.add(animatorListener);
    }

    private static synchronized void ensureConstantsLoaded(Context context) {
        if (sConstantsLoaded) return;
        ResourceProvider rp = DynamicResource.provider(context);
        sCachedMinVelocityXPxPerS = rp.getInt(R.dimen.swipe_up_min_velocity_x_px_per_s);
        sCachedMaxVelocityXPxPerS = rp.getInt(R.dimen.swipe_up_max_velocity_x_px_per_s);
        sCachedMinVelocityYPxPerS = rp.getInt(R.dimen.swipe_up_min_velocity_y_px_per_s);
        sCachedMaxVelocityYPxPerS = rp.getInt(R.dimen.swipe_up_max_velocity_y_px_per_s);
        sCachedFallOffFactor = rp.getFloat(R.dimen.swipe_up_max_velocity_fall_off_factor);
        sCachedStiffnessX = rp.getFloat(R.dimen.swipe_up_rect_x_stiffness);
        sCachedDampingX = rp.getFloat(R.dimen.swipe_up_rect_x_damping_ratio);
        sCachedStiffnessY = rp.getFloat(R.dimen.swipe_up_rect_y_stiffness);
        sCachedDampingY = rp.getFloat(R.dimen.swipe_up_rect_y_damping_ratio);
        sCachedStiffnessZ = rp.getFloat(R.dimen.swipe_up_rect_scale_stiffness_v2);
        sCachedDampingZ = rp.getFloat(R.dimen.swipe_up_rect_scale_damping_ratio_v2);
        sConstantsLoaded = true;
    }

    /**
     * Starts the fling/spring animation.
     * @param context The activity context.
     * @param velocityPxPerMs Velocity of swipe in px/ms.
     */
    public void start(Context context, @Nullable DeviceProfile profile, PointF velocityPxPerMs) {
        ensureConstantsLoaded(context);

        // Only tell caller that we ended if both x and y animations have ended.
        OnAnimationEndListener onXEndListener = ((animation, canceled, centerX, velocityX) -> {
            mRectXAnimEnded = true;
            maybeOnEnd();
        });
        OnAnimationEndListener onYEndListener = ((animation, canceled, centerY, velocityY) -> {
            mRectYAnimEnded = true;
            maybeOnEnd();
        });

        float xVelocityPxPerS = velocityPxPerMs.x * 1000;
        float yVelocityPxPerS = velocityPxPerMs.y * 1000;
        float startX = mCurrentCenterX;
        float endX = mTargetRect.centerX();
        float startY = mCurrentY;
        float endY = getTrackedYFromRect(mTargetRect);
        float minVisibleChange = Math.abs(1f / mStartRect.height());

        // We want the actual initial velocity to never dip below the minimum, and to taper off
        // once it's above the soft cap so that we can prevent the window from flying off
        // screen, while maintaining a natural feel.
        xVelocityPxPerS = adjustVelocity(
                xVelocityPxPerS, sCachedMinVelocityXPxPerS, sCachedMaxVelocityXPxPerS,
                sCachedFallOffFactor);
        yVelocityPxPerS = adjustVelocity(
                yVelocityPxPerS, mCos ? 500 : sCachedMinVelocityYPxPerS,
                mCos ? 2500 : sCachedMaxVelocityYPxPerS,
                sCachedFallOffFactor);

        if (mQsb) {
            xVelocityPxPerS *= 0.4f;
            yVelocityPxPerS *= 0.4f;
        }

        float stiffnessScale = mNothing && ValueAnimator.areAnimatorsEnabled()
                ? 1f / ValueAnimator.getDurationScale() : 1f;

        if (mCos) {
            if ((endX - startX) * xVelocityPxPerS < 0) xVelocityPxPerS *= 0.25f;
            if ((endY - startY) * yVelocityPxPerS < 0) yVelocityPxPerS = 0f;
            mChoreographer = Choreographer.getInstance();
            mCosStartTime = 0;
            mCosFadeProgress = 0f;
            mCosDurationScale = ValueAnimator.getDurationScale();
        }

        mRectXSpring =
                new SpringAnimation(this, RECT_CENTER_X)
                        .setSpring(
                                new SpringForce(endX)
                                        .setStiffness(mUseConfiguredSpring
                                                ? mStiffnessX * stiffnessScale : sCachedStiffnessX)
                                        .setDampingRatio(mUseConfiguredSpring
                                                ? mDampingX : sCachedDampingX)
                        ).setStartValue(startX)
                        .setStartVelocity(xVelocityPxPerS)
                        .addEndListener(onXEndListener);

        mRectYSpring =
                new SpringAnimation(this, RECT_Y)
                        .setSpring(
                                new SpringForce(endY)
                                        .setStiffness(mUseConfiguredSpring
                                                ? mStiffnessY * stiffnessScale : sCachedStiffnessY)
                                        .setDampingRatio(mUseConfiguredSpring
                                                ? mDampingY : sCachedDampingY)
                        )
                        .setStartValue(startY)
                        .setStartVelocity(yVelocityPxPerS)
                        .addEndListener(onYEndListener);

        float scaleVelocity = velocityPxPerMs.y * minVisibleChange;
        if (mCos) {
            float span = Math.max(1f, mStartRect.width() - mTargetRect.width());
            float flight = 2.8f * context.getResources().getDisplayMetrics().widthPixels / span;
            scaleVelocity = Utilities.boundToRange(Math.max(flight,
                    -velocityPxPerMs.y * 1000 / Math.max(1f, mStartRect.height())), 0f, 12f);
        }

        mRectScaleAnim =
                new SpringAnimation(this, RECT_SCALE_PROGRESS)
                        .setSpring(
                                new SpringForce(1f)
                                        .setStiffness(mUseConfiguredSpring
                                                ? mRectStiffness * stiffnessScale : sCachedStiffnessZ)
                                        .setDampingRatio(mUseConfiguredSpring
                                                ? mRectDamping : sCachedDampingZ))
                        .setStartVelocity(scaleVelocity)
                        .setMaxValue(mCos ? 1.25f : 1f)
                        .setMinimumVisibleChange(minVisibleChange)
                        .addEndListener((animation, canceled, value, velocity) -> {
                            mRectScaleAnimEnded = true;
                            maybeOnEnd();
                        });

        setCanRelease(false);
        mAnimsStarted = true;

        mRectXSpring.start();
        mRectYSpring.start();

        mRectScaleAnim.start();
        for (Animator.AnimatorListener animatorListener : mAnimatorListeners) {
            animatorListener.onAnimationStart(null);
        }
        if (mNothing && !ValueAnimator.areAnimatorsEnabled()) end();
        if (mCos && !ValueAnimator.areAnimatorsEnabled()) end();
    }

    public void end() {
        mEnding = true;
        if (mUpdatePending) {
            mChoreographer.removeCallbacks(Choreographer.CALLBACK_INSETS_ANIMATION,
                    mFrameUpdate, null);
            mUpdatePending = false;
        }
        if (mAnimsStarted) {
            if (mRectXSpring.canSkipToEnd()) {
                mRectXSpring.skipToEnd();
            }
            if (mRectYSpring.canSkipToEnd()) {
                mRectYSpring.skipToEnd();
            }
            if (mRectScaleAnim.canSkipToEnd()) {
                mRectScaleAnim.skipToEnd();
            }
            mCurrentScaleProgress = mRectScaleAnim.getSpring().getFinalPosition();
            if (mCos || mNothing) {
                mCurrentCenterX = mTargetRect.centerX();
                mCurrentY = getTrackedYFromRect(mTargetRect);
            }

            // Ensures that we end the animation with the final values.
            mRectXAnimEnded = false;
            mRectYAnimEnded = false;
            mRectScaleAnimEnded = false;
            onUpdate();
        }

        mRectXAnimEnded = true;
        mRectYAnimEnded = true;
        mRectScaleAnimEnded = true;
        maybeOnEnd();
    }

    private boolean isEnded() {
        return mRectXAnimEnded && mRectYAnimEnded && mRectScaleAnimEnded;
    }

    private void onUpdate() {
        if (isEnded()) {
            // Prevent further updates from being called. This can happen between callbacks for
            // ending the x/y/scale animations.
            return;
        }

        if (mCos && !mEnding) {
            if (!mUpdatePending) {
                mUpdatePending = true;
                mChoreographer.postCallback(Choreographer.CALLBACK_INSETS_ANIMATION,
                        mFrameUpdate, null);
            }
        } else {
            dispatchUpdate();
        }
    }

    private void dispatchFrameUpdate() {
        if (!mUpdatePending) return;
        mUpdatePending = false;
        dispatchUpdate();
        maybeOnEnd();
    }

    private void dispatchUpdate() {
        if (mCos) {
            if (mCosDurationScale <= 0 || isEnded() || mEnding) {
                mCosFadeProgress = 1f;
            } else {
                long frameTime = mChoreographer.getFrameTime();
                if (mCosStartTime == 0) mCosStartTime = frameTime;
                mCosFadeProgress = CosAppAnimation.iconFade(
                        (frameTime - mCosStartTime) / mCosDurationScale);
            }
        }
        if (!mOnUpdateListeners.isEmpty()) {
            float startWidth = mStartRect.width();
            float startHeight = mStartRect.height();
            float sizeProgress = mNothing
                    ? NothingAppAnimation.getSizeProgress(mCurrentScaleProgress)
                    : mCurrentScaleProgress;
            float currentWidth = startWidth + sizeProgress * (mTargetRect.width() - startWidth);
            float currentHeight = mNothing
                    ? NothingAppAnimation.getHeight(sizeProgress, startWidth, startHeight,
                            mTargetRect.width(), mTargetRect.height())
                    : startHeight + sizeProgress * (mTargetRect.height() - startHeight);
            if (mCos && startWidth > 0 && mTargetRect.width() > 0) {
                currentWidth = Math.max(mTargetRect.width() * 0.95f, currentWidth);
                currentHeight = currentWidth * Utilities.mapRange(
                        Utilities.boundToRange(mCurrentScaleProgress, 0f, 1f),
                        startHeight / startWidth, mTargetRect.height() / mTargetRect.width());
            }
            float halfWidth = currentWidth / 2;
            switch (mTracking) {
                case TRACKING_TOP:
                    mCurrentRect.set(mCurrentCenterX - halfWidth,
                            mCurrentY,
                            mCurrentCenterX + halfWidth,
                            mCurrentY + currentHeight);
                    break;
                case TRACKING_BOTTOM:
                    mCurrentRect.set(mCurrentCenterX - halfWidth,
                            mCurrentY - currentHeight,
                            mCurrentCenterX + halfWidth,
                            mCurrentY);
                    break;
                case TRACKING_CENTER:
                    mCurrentRect.set(mCurrentCenterX - halfWidth,
                            mCurrentY - currentHeight / 2,
                            mCurrentCenterX + halfWidth,
                            mCurrentY + currentHeight / 2);
                    break;
            }
            for (OnUpdateListener onUpdateListener : mOnUpdateListeners) {
                onUpdateListener.onUpdate(mCurrentRect, mCos
                        ? Utilities.boundToRange(mCurrentScaleProgress, 0f, 1f)
                        : mCurrentScaleProgress);
            }
        }
    }

    private void maybeOnEnd() {
        if (mAnimsStarted && isEnded() && !mUpdatePending) {
            mAnimsStarted = false;
            setCanRelease(true);
            for (Animator.AnimatorListener animatorListener : mAnimatorListeners) {
                animatorListener.onAnimationEnd(null);
            }
        }
    }

    public void cancel() {
        if (mAnimsStarted) {
            for (OnUpdateListener onUpdateListener : mOnUpdateListeners) {
                onUpdateListener.onCancel();
            }
        }
        end();
    }

    /**
     * Modify the given velocity so that it's never below the minimum value, and falls off by the
     * given factor once it goes above the maximum value.
     * In order for the max soft cap to be enforced, the fall-off factor must be >1.
     */
    private static float adjustVelocity(float velocity, long min, long max, float factor) {
        float sign = Math.signum(velocity);
        float magnitude = Math.abs(velocity);

        // If the absolute velocity is less than the min, bump it up.
        if (magnitude < min) {
            return min * sign;
        }

        // If the absolute velocity falls between min and max, or the fall-off factor is invalid,
        // do nothing.
        if (magnitude <= max || factor <= 1) {
            return velocity;
        }

        // Scale the excess velocity by the fall-off factor.
        float excess = magnitude - max;
        float scaled = (float) Math.pow(excess, 1f / factor);
        return (max + scaled) * sign;
    }

    public interface OnUpdateListener {
        /**
         * Called when an update is made to the animation.
         * @param currentRect The rect of the window.
         * @param progress [0, 1] The progress of the rect scale animation.
         */
        void onUpdate(RectF currentRect, float progress);

        default void onCancel() { }
    }

    private abstract static class SpringConfig {
        protected RectF startRect;
        protected RectF targetRect;
        protected @Tracking int tracking;
        protected float stiffnessX;
        protected float stiffnessY;
        protected float dampingX;
        protected float dampingY;
        protected float rectStiffness;
        protected float rectDamping;
        protected float minVisChange;
        protected int maxVelocityPxPerS;

        private SpringConfig(Context context, RectF start, RectF target) {
            startRect = start;
            targetRect = target;

            ResourceProvider rp = DynamicResource.provider(context);
            minVisChange = rp.getDimension(R.dimen.swipe_up_fling_min_visible_change);
            maxVelocityPxPerS = (int) rp.getDimension(R.dimen.swipe_up_max_velocity);
        }
    }

    /**
     * Standard spring configuration parameters.
     */
    public static class DefaultSpringConfig extends SpringConfig {

        public DefaultSpringConfig(Context context, DeviceProfile deviceProfile,
                RectF startRect, RectF targetRect) {
            super(context, startRect, targetRect);

            ResourceProvider rp = DynamicResource.provider(context);
            tracking = getDefaultTracking(deviceProfile);
            stiffnessX = rp.getFloat(R.dimen.swipe_up_rect_xy_stiffness);
            stiffnessY = rp.getFloat(R.dimen.swipe_up_rect_xy_stiffness);
            dampingX = rp.getFloat(R.dimen.swipe_up_rect_xy_damping_ratio);
            dampingY = rp.getFloat(R.dimen.swipe_up_rect_xy_damping_ratio);

            this.startRect = startRect;
            this.targetRect = targetRect;

            // Increase the stiffness for devices where we want the window size to transform
            // quicker.
            boolean shouldUseHigherStiffness = deviceProfile != null
                    && (deviceProfile.getDeviceProperties().isLandscape() || deviceProfile.getDeviceProperties().isTablet());
            rectStiffness = shouldUseHigherStiffness
                    ? rp.getFloat(R.dimen.swipe_up_rect_scale_higher_stiffness)
                    : rp.getFloat(R.dimen.swipe_up_rect_scale_stiffness);
        }

        private @Tracking int getDefaultTracking(@Nullable DeviceProfile deviceProfile) {
            @Tracking int tracking;
            if (deviceProfile == null) {
                tracking = startRect.bottom < targetRect.bottom
                        ? TRACKING_BOTTOM
                        : TRACKING_TOP;
            } else {
                int heightPx = deviceProfile.getDeviceProperties().getHeightPx();
                Rect padding = deviceProfile.mWorkspaceProfile.getWorkspacePadding();

                final float topThreshold = heightPx / 3f;
                final float bottomThreshold = deviceProfile.getDeviceProperties().getHeightPx() - padding.bottom;

                if (targetRect.bottom > bottomThreshold) {
                    tracking = TRACKING_CENTER;
                } else if (targetRect.top < topThreshold) {
                    tracking = TRACKING_TOP;
                } else {
                    tracking = TRACKING_CENTER;
                }
            }
            return tracking;
        }
    }

    public static class NothingSpringConfig extends DefaultSpringConfig {
        public NothingSpringConfig(Context context, DeviceProfile deviceProfile,
                RectF startRect, RectF targetRect) {
            super(context, deviceProfile, startRect, targetRect);
            stiffnessX = NothingAppAnimation.STIFFNESS_X;
            stiffnessY = NothingAppAnimation.STIFFNESS_Y;
            rectStiffness = NothingAppAnimation.STIFFNESS_SCALE;
            dampingX = dampingY = rectDamping = NothingAppAnimation.DAMPING_RATIO;
        }

        public NothingSpringConfig(Context context, DeviceProfile deviceProfile,
                RectF startRect, RectF targetRect, @Tracking int tracking) {
            this(context, deviceProfile, startRect, targetRect);
            this.tracking = tracking;
        }
    }

    /**
     * Softer, slightly under-damped spring used when the app closes into the search bar, so the
     * window glides into the pill instead of snapping to it.
     */
    public static final class QsbSpringConfig extends NothingSpringConfig {
        public QsbSpringConfig(Context context, DeviceProfile deviceProfile,
                RectF startRect, RectF targetRect) {
            super(context, deviceProfile, startRect, targetRect, TRACKING_CENTER);
            stiffnessX = 120f;
            stiffnessY = 105f;
            rectStiffness = 130f;
            dampingX = 0.95f;
            dampingY = 0.92f;
            rectDamping = 1f;
        }
    }

    public static final class WidgetSpringConfig extends DefaultSpringConfig {

        public WidgetSpringConfig(Context context, DeviceProfile deviceProfile,
                RectF startRect, RectF targetRect) {
            super(context, deviceProfile, startRect, targetRect);
            ResourceProvider rp = DynamicResource.provider(context);
            stiffnessX = rp.getFloat(R.dimen.widget_x_stiffness);
            stiffnessY = rp.getFloat(R.dimen.widget_y_stiffness);
            rectStiffness = rp.getFloat(R.dimen.widget_rect_scale_stiffness);
            dampingX = rp.getFloat(R.dimen.swipe_up_rect_x_damping_ratio);
            dampingY = rp.getFloat(R.dimen.swipe_up_rect_y_damping_ratio);
            rectDamping = rp.getFloat(R.dimen.swipe_up_rect_scale_damping_ratio_v2);
        }
    }

    public static final class CosSpringConfig extends DefaultSpringConfig {
        public CosSpringConfig(Context context, DeviceProfile deviceProfile,
                RectF startRect, RectF targetRect) {
            super(context, deviceProfile, startRect, targetRect);
            tracking = TRACKING_CENTER;
            stiffnessX = 330f;
            stiffnessY = 250f;
            rectStiffness = 330f;
            dampingX = 1f;
            dampingY = CosAppAnimation.bounceDamping(
                    6f * context.getResources().getDisplayMetrics().density,
                    Math.abs(targetRect.centerY() - startRect.centerY()));
            rectDamping = CosAppAnimation.bounceDamping(
                    0.05f * targetRect.width(), startRect.width() - targetRect.width());
        }
    }

    /**
     * Spring configuration parameters for Taskbar/Hotseat items on devices that have a taskbar.
     */
    public static class TaskbarHotseatSpringConfig extends SpringConfig {

        public TaskbarHotseatSpringConfig(Context context, RectF start, RectF target) {
            super(context, start, target);

            ResourceProvider rp = DynamicResource.provider(context);
            tracking = TRACKING_CENTER;
            stiffnessX = rp.getFloat(R.dimen.taskbar_swipe_up_rect_x_stiffness);
            stiffnessY = rp.getFloat(R.dimen.taskbar_swipe_up_rect_y_stiffness);
            dampingX = rp.getFloat(R.dimen.taskbar_swipe_up_rect_x_damping);
            dampingY = rp.getFloat(R.dimen.taskbar_swipe_up_rect_y_damping);
            rectStiffness = rp.getFloat(R.dimen.taskbar_swipe_up_rect_scale_stiffness);
        }
    }

}
