/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.launcher3;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.role.RoleManager.ROLE_HOME;
import static android.provider.Settings.Secure.LAUNCHER_TASKBAR_EDUCATION_SHOWING;
import static android.view.RemoteAnimationTarget.MODE_CLOSING;
import static android.view.RemoteAnimationTarget.MODE_OPENING;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_NONE;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_SPLASH_SCREEN;
import static android.window.TransitionFilter.CONTAINER_ORDER_TOP;

import static com.android.app.animation.Interpolators.ACCELERATE_1_5;
import static com.android.app.animation.Interpolators.AGGRESSIVE_EASE;
import static com.android.app.animation.Interpolators.DECELERATE_1_5;
import static com.android.app.animation.Interpolators.DECELERATE_1_7;
import static com.android.app.animation.Interpolators.EXAGGERATED_EASE;
import static com.android.app.animation.Interpolators.LINEAR;
import static com.android.internal.util.LatencyTracker.ACTION_DESKTOP_MODE_EXIT_MODE_ON_LAST_WINDOW_CLOSE;
import static com.android.launcher3.BaseActivity.EVENT_DESTROYED;
import static com.android.launcher3.BaseActivity.INVISIBLE_ALL;
import static com.android.launcher3.BaseActivity.INVISIBLE_BY_APP_TRANSITIONS;
import static com.android.launcher3.BaseActivity.INVISIBLE_BY_PENDING_FLAGS;
import static com.android.launcher3.BaseActivity.PENDING_INVISIBLE_BY_WALLPAPER_ANIMATION;
import static com.android.launcher3.Flags.appLaunchBlur;
import static com.android.launcher3.Flags.refactorTaskbarUiState;
import static com.android.launcher3.Flags.syncAppLaunchWithTaskbarStash;
import static com.android.launcher3.LauncherAnimUtils.SCALE_PROPERTY;
import static com.android.launcher3.LauncherPrefs.APP_LAUNCH_BLUR_ENABLED;
import static com.android.launcher3.LauncherState.ALL_APPS;
import static com.android.launcher3.LauncherState.BACKGROUND_APP;
import static com.android.launcher3.LauncherState.NORMAL;
import static com.android.launcher3.LauncherState.OVERVIEW;
import static com.android.launcher3.Utilities.mapBoundToRange;
import static com.android.launcher3.config.FeatureFlags.SEPARATE_RECENTS_ACTIVITY;
import static com.android.launcher3.testing.shared.TestProtocol.WALLPAPER_OPEN_ANIMATION_FINISHED_MESSAGE;
import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.MultiPropertyFactory.MULTI_PROPERTY_VALUE;
import static com.android.launcher3.util.window.RefreshRateTracker.getSingleFrameMs;
import static com.android.launcher3.views.FloatingIconView.SHAPE_PROGRESS_DURATION;
import static com.android.quickstep.TaskViewUtils.findTaskViewToLaunch;
import static com.android.quickstep.util.AnimUtils.clampToDuration;
import static com.android.quickstep.util.AnimUtils.completeRunnableListCallback;
import static com.android.quickstep.util.FloatingIconViewHelper.getFloatingIconView;
import static com.android.systemui.shared.system.QuickStepContract.getWindowCornerRadius;
import static com.android.systemui.shared.system.QuickStepContract.supportsRoundedCornersOnWindows;
import static com.android.wm.shell.Flags.enableDynamicInsetsForAppLaunch;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.WindowConfiguration;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.RemoteException;
import android.view.IRemoteAnimationRunner;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.TransitionInfo;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.window.DesktopModeFlags;
import android.window.IRemoteTransition;
import android.window.RemoteTransitionStub;
import android.window.RemoteTransition;
import android.window.TransitionFilter;
import android.window.WindowAnimationState;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.android.internal.util.lunaris.Luminous;
import com.android.app.animation.Animations;
import com.android.app.animation.Interpolators;
import com.android.internal.jank.Cuj;
import com.android.internal.util.LatencyTracker;
import com.android.launcher3.DeviceProfile.OnDeviceProfileChangeListener;
import com.android.launcher3.LauncherAnimationRunner.RemoteAnimationFactory;
import com.android.launcher3.anim.AnimationSuccessListener;
import com.android.launcher3.anim.AnimatorListeners;
import com.android.launcher3.compat.AccessibilityManagerCompat;
import com.android.launcher3.dragndrop.DragLayer;
import com.android.launcher3.icons.FastBitmapDrawable;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.shortcuts.DeepShortcutView;
import com.android.launcher3.statehandlers.DepthController;
import com.android.launcher3.taskbar.TaskbarInteractor;
import com.android.launcher3.taskbar.customization.TaskbarFeatureEvaluator;
import com.android.launcher3.testing.shared.ResourceUtils;
import com.android.launcher3.touch.PagedOrientationHandler;
import com.android.launcher3.uioverrides.QuickstepLauncher;
import com.android.launcher3.util.ActivityOptionsWrapper;
import com.android.launcher3.util.InteractionBoost;
import com.android.launcher3.util.RunnableList;
import com.android.launcher3.util.StableViewInfo;
import com.android.launcher3.views.FloatingIconView;
import com.android.launcher3.widget.LauncherAppWidgetHostView;
import com.android.quickstep.LauncherBackAnimationController;
import com.android.quickstep.RemoteAnimationTargets;
import com.android.quickstep.SystemUiProxy;
import com.android.quickstep.util.LunarisBarAnimation;
import com.android.quickstep.TaskViewUtils;
import com.android.quickstep.util.AlreadyStartedBackAnimState;
import com.android.quickstep.util.AnimatorBackState;
import com.android.quickstep.util.BackAnimState;
import com.android.quickstep.util.CrossDisplayMoveTransition;
import com.android.quickstep.util.MultiValueUpdateListener;
import com.android.quickstep.util.NothingAppAnimation;
import com.android.quickstep.cos.CosAppAnimation;
import com.android.quickstep.cos.CosWorkspaceRevealAnim;
import com.android.quickstep.util.RectFSpringAnim;
import com.android.quickstep.util.RectFSpringAnim.DefaultSpringConfig;
import com.android.quickstep.util.RectFSpringAnim.NothingSpringConfig;
import com.android.quickstep.util.RectFSpringAnim.TaskbarHotseatSpringConfig;
import com.android.quickstep.util.RectFSpringAnim.WidgetSpringConfig;
import com.android.quickstep.util.ScalingWorkspaceRevealAnim;
import com.android.quickstep.util.SurfaceTransaction;
import com.android.quickstep.util.SurfaceTransaction.SurfaceProperties;
import com.android.quickstep.util.SurfaceTransactionApplier;
import com.android.quickstep.util.TaskRestartedDuringLaunchListener;
import com.android.quickstep.util.WidgetAnimationUtils;
import com.android.quickstep.util.WorkspaceRevealAnim;
import com.android.quickstep.views.FloatingWidgetView;
import com.android.quickstep.views.RecentsView;
import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.animation.DelegateTransitionAnimatorController;
import com.android.systemui.animation.LaunchableView;
import com.android.systemui.animation.RemoteAnimationDelegate;
import com.android.systemui.animation.RemoteAnimationRunnerCompat;
import com.android.systemui.animation.RemoteTransitionDelegate;
import com.android.systemui.shared.system.BlurUtils;
import com.android.systemui.shared.system.InteractionJankMonitorWrapper;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus;
import com.android.wm.shell.startingsurface.IStartingWindowListener;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

/**
 * Manages the opening and closing app transitions from Launcher
 */
public class QuickstepTransitionManager implements OnDeviceProfileChangeListener {
    private static final String TAG = "QuickstepTransitionManager";

    private static final boolean ENABLE_SHELL_STARTING_SURFACE =
            SystemProperties.getBoolean("persist.debug.shell_starting_surface", true);

    /** Duration of status bar animations. */
    public static final int STATUS_BAR_TRANSITION_DURATION = 120;

    /**
     * Since our animations decelerate heavily when finishing, we want to start status bar
     * animations x ms before the ending.
     */
    public static final int STATUS_BAR_TRANSITION_PRE_DELAY = 96;

    public static final long APP_LAUNCH_DURATION = 425;

    public static final int ANIMATION_NAV_FADE_IN_DURATION = 266;
    public static final int ANIMATION_NAV_FADE_OUT_DURATION = 160;
    public static final long ANIMATION_DELAY_NAV_FADE_IN =
            APP_LAUNCH_DURATION - ANIMATION_NAV_FADE_IN_DURATION;
    public static final Interpolator NAV_FADE_IN_INTERPOLATOR =
            new PathInterpolator(0f, 0f, 0f, 1f);
    public static final Interpolator NAV_FADE_OUT_INTERPOLATOR =
            new PathInterpolator(0.2f, 0f, 1f, 1f);

    public static final int RECENTS_LAUNCH_DURATION = 336;
    private static final int LAUNCHER_RESUME_START_DELAY = 100;
    private static final int CLOSING_TRANSITION_DURATION_MS = (int) APP_LAUNCH_DURATION;
    private static final float WIDGET_CLOSE_ALPHA_END_PROGRESS = 0.4f;
    public static final int SPLIT_LAUNCH_DURATION = 370;
    public static final int SPLIT_DIVIDER_ANIM_DURATION = 100;

    public static final int CONTENT_ALPHA_DURATION = 217;
    public static final int TRANSIENT_TASKBAR_TRANSITION_DURATION = 417;
    public static final int PINNED_TASKBAR_TRANSITION_DURATION = 600;
    public static final int TASKBAR_TO_APP_DURATION = 600;
    // TODO(b/236145847): Tune TASKBAR_TO_HOME_DURATION to 383 after conflict with unlock animation
    // is solved.
    private static final int TASKBAR_TO_HOME_DURATION_FAST = 300;
    private static final int TASKBAR_TO_HOME_DURATION_SLOW = 650;
    protected static final int CONTENT_SCALE_DURATION = 350;

    private static final int MIN_BLUR_RADIUS_DELTA_PX = 2;

    private static final int MAX_NUM_TASKS = 5;

    // Cross-fade duration between App Widget and App when launching from widget.
    private static final int WIDGET_CROSSFADE_DURATION_MILLIS = 180;

    private static final float MAX_SCRIM_ALPHA_DARK = 0.8f;
    private static final float MAX_SCRIM_ALPHA_LIGHT = 0.2f;

    protected final QuickstepLauncher mLauncher;
    protected final DragLayer mDragLayer;

    protected final Handler mHandler;

    private final float mClosingWindowTransY;
    private final float mClosingFreeformWindowTransY;
    private final float mMaxShadowRadius;
    private final int mMaxBlurRadius;

    private final StartingWindowListener mStartingWindowListener =
            new StartingWindowListener(this);

    // TODO(b/397690719): Investigate the memory leak from TaskStackChangeListeners#mImpl
    // This is a temporary fix of memory leak b/397690719. We track registered
    // {@link TaskRestartedDuringLaunchListener}, and remove them on activity destroy.
    private final List<TaskRestartedDuringLaunchListener> mRegisteredTaskStackChangeListener =
            new ArrayList<>();

    private DeviceProfile mDeviceProfile;

    // Strong refs to runners which are cleared when the launcher activity is destroyed
    private RemoteAnimationFactory mWallpaperOpenRunner;
    private RemoteAnimationFactory mAppLaunchRunner;

    private RemoteAnimationFactory mWallpaperOpenTransitionRunner;
    private RemoteTransition mLauncherOpenTransition;
    private RemoteTransition mMoveDisplayTransition;

    private final RemoteAnimationCoordinateTransfer mCoordinateTransfer;
    private final LatencyTracker mLatencyTracker;

    private LauncherBackAnimationController mBackAnimationController;
    private final AnimatorListenerAdapter mForceInvisibleListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationStart(Animator animation) {
            mLauncher.addForceInvisibleFlag(INVISIBLE_BY_APP_TRANSITIONS);
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            mLauncher.clearForceInvisibleFlag(INVISIBLE_BY_APP_TRANSITIONS);
        }
    };

    // Pairs of window starting type and starting window background color for starting tasks
    // Will never be larger than MAX_NUM_TASKS
    private LinkedHashMap<Integer, Pair<Integer, Integer>> mTaskStartParams;

    private final Interpolator mCosOpeningInterpolator;
    private LaunchPreview mImmediatePreview;

    /** Give icon taps their first frame without waiting for WM's opening app surface. */
    @Nullable
    public Runnable beginImmediateAppLaunch(View source, ItemInfo item) {
        cancelImmediateAppLaunch();
        if (!mCosAppAnimations || !(source instanceof BubbleTextView icon) || item == null
                || item.itemType != LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
                || item.shouldUseBackgroundAnimation() || icon.getIcon() == null
                || !ValueAnimator.areAnimatorsEnabled()
                || !source.isAttachedToWindow() || mLauncher.isInMultiWindowMode()
                || mLauncher.shouldShowHomeBehindDesktop()
                || !mLauncher.getSystemService(android.os.PowerManager.class).isInteractive()) {
            return null;
        }
        android.view.ViewGroup root = (android.view.ViewGroup) mDragLayer.getParent();
        if (root == null || !root.isAttachedToWindow()
                || root.getWidth() == 0 || root.getHeight() == 0) return null;
        InteractionBoost.requestForAnimation(source);
        LaunchPreview preview = new LaunchPreview(root, icon);
        mImmediatePreview = preview;
        preview.start();
        return preview::remove;
    }

    public void cancelImmediateAppLaunch() {
        if (mImmediatePreview != null) mImmediatePreview.remove();
    }

    public void cancelImmediateAppLaunch(View source) {
        if (mImmediatePreview != null && mImmediatePreview.source == source) {
            mImmediatePreview.remove();
        }
    }

    /** A noninteractive overlay, using the same crop/scale path as the real opening window. */
    private final class LaunchPreview extends View {
        final BubbleTextView source;
        final android.view.ViewGroup root;
        final RectF iconBounds = new RectF();
        final RectF frame = new RectF();
        final Rect drawableBounds = new Rect();
        final Rect predictedScreenBounds = new Rect();
        final android.graphics.Paint fill = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.drawable.Drawable logo;
        final Rect targetBounds = new Rect();
        final float originalAlpha;
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        float progress;
        boolean sourceRestored;
        final Runnable timeout = this::remove;
        boolean removed;
        boolean handedOff;

        LaunchPreview(android.view.ViewGroup root, BubbleTextView source) {
            super(mLauncher);
            this.root = root;
            this.source = source;
            originalAlpha = source.getAlpha();
            Rect local = new Rect();
            source.getIconBounds(local);
            Utilities.getBoundsForViewInDragLayer(mDragLayer, source, local,
                    false, null, iconBounds);
            int[] dragLocation = new int[2];
            int[] rootLocation = new int[2];
            mDragLayer.getLocationOnScreen(dragLocation);
            root.getLocationOnScreen(rootLocation);
            iconBounds.offset(dragLocation[0] - rootLocation[0],
                    dragLocation[1] - rootLocation[1]);
            Rect target = new Rect(0, 0, root.getWidth(), root.getHeight());
            if (enableDynamicInsetsForAppLaunch() && mDeviceProfile.isTaskbarPresent
                    && mDeviceProfile.isTaskbarPresentInApps
                    && !isTransientTaskbar()) {
                int bottom = mSystemUiProxy.getHomeVisibilityState().getNavbarInsetPosition()
                        - rootLocation[1];
                if (bottom > 0) target.bottom = Math.min(target.bottom, bottom);
            }
            predictedScreenBounds.set(target);
            predictedScreenBounds.offset(rootLocation[0], rootLocation[1]);
            targetBounds.set(target);
            android.graphics.drawable.Drawable.ConstantState state =
                    source.getIcon().getConstantState();
            logo = state == null ? source.getIcon() : state.newDrawable(getResources()).mutate();
            fill.setColor(iconBackgroundColor(logo));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setClickable(false);
            animator.setDuration(CosAppAnimation.LAUNCH_DURATION);
            animator.setInterpolator(LINEAR);
            animator.addUpdateListener(value -> {
                if (mLauncher.isDestroyed() || !root.isAttachedToWindow()) {
                    remove();
                    return;
                }
                setProgress((Float) value.getAnimatedValue());
            });
        }

        private int iconBackgroundColor(Drawable icon) {
            final int size = 48;
            int color = Color.WHITE;
            Rect bounds = new Rect(icon.getBounds());
            android.graphics.Bitmap hardware = null;
            android.graphics.Bitmap bitmap = null;
            try {
                android.graphics.Picture picture = new android.graphics.Picture();
                icon.setBounds(0, 0, size, size);
                android.graphics.Canvas canvas = picture.beginRecording(size, size);
                try {
                    icon.draw(canvas);
                } finally {
                    picture.endRecording();
                }
                hardware = android.graphics.Bitmap.createBitmap(picture, size, size,
                        android.graphics.Bitmap.Config.HARDWARE);
                bitmap = hardware.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                if (bitmap != null) {
                    for (int y = 2; y < size / 2; y++) {
                        if (Color.alpha(bitmap.getPixel(size / 2, y)) == 0xff) {
                            color = bitmap.getPixel(size / 2, y + 2) | 0xff000000;
                            break;
                        }
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Launch preview: icon colour unavailable", e);
            } finally {
                if (bitmap != null) bitmap.recycle();
                if (hardware != null) hardware.recycle();
                icon.setBounds(bounds);
            }
            return color;
        }

        void start() {
            layout(0, 0, root.getWidth(), root.getHeight());
            root.getOverlay().add(this);
            source.setAlpha(0f);
            postDelayed(timeout, 5000);
            animator.start();
        }

        long elapsed() {
            return Math.round(progress * CosAppAnimation.LAUNCH_DURATION);
        }

        void setProgress(float progress) {
            if (removed) return;
            this.progress = progress;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            if (removed) return;
            float t = mCosOpeningInterpolator.getInterpolation(progress);
            cosOpenRect(iconBounds, 0, 0, targetBounds, t, frame);
            float radius = cosOpenRadius(iconBounds, t);
            fill.setAlpha(Math.round(255 * Math.min(1f, progress * 8f)));
            canvas.drawRoundRect(frame, radius, radius, fill);
            float cx = frame.centerX();
            float cy = frame.centerY();
            float size = Math.min(Math.min(frame.width(), frame.height()),
                    iconBounds.width() * 2f);
            drawableBounds.set(logo.getBounds());
            logo.setBounds(Math.round(cx - size / 2), Math.round(cy - size / 2),
                    Math.round(cx + size / 2), Math.round(cy + size / 2));
            logo.draw(canvas);
            logo.setBounds(drawableBounds);
        }

        boolean matchesWindow(RemoteAnimationTarget[] targets) {
            Rect actual = getWindowTargetBounds(targets, 0);
            for (RemoteAnimationTarget target : targets) {
                if (target.mode != MODE_OPENING) continue;
                if (shouldCropToInset(target)) {
                    actual.bottom = Math.min(actual.bottom,
                            mSystemUiProxy.getHomeVisibilityState().getNavbarInsetPosition());
                }
                return actual.equals(predictedScreenBounds);
            }
            return false;
        }

        void handOff() {
            if (removed || handedOff) return;
            handedOff = true;
            animator.cancel();
            removeCallbacks(timeout);
            postOnAnimation(() -> {
                if (!removed) animate().alpha(0f).setDuration(64).withEndAction(this::remove).start();
            });
        }

        void restoreSource() {
            if (!sourceRestored) {
                sourceRestored = true;
                source.setAlpha(originalAlpha);
            }
        }

        void remove() {
            if (removed) return;
            removed = true;
            animator.cancel();
            removeCallbacks(timeout);
            animate().cancel();
            root.getOverlay().remove(this);
            restoreSource();
            if (mImmediatePreview == this) mImmediatePreview = null;
        }
    }

    private static void cosOpenRect(RectF icon, float dx, float dy, Rect target, float t,
            RectF out) {
        out.set(Utilities.mapRange(t, icon.left + dx, target.left),
                Utilities.mapRange(t, icon.top + dy, target.top),
                Utilities.mapRange(t, icon.right + dx, target.right),
                Utilities.mapRange(t, icon.bottom + dy, target.bottom));
    }

    private float cosOpenRadius(RectF icon, float t) {
        if (!supportsRoundedCornersOnWindows(mLauncher.getResources())) return 0f;
        return Utilities.mapRange(t, Math.min(icon.width(), icon.height()) / 2f,
                getWindowCornerRadius(mLauncher));
    }

    private final boolean mCosAppAnimations;

    private final SystemUiProxy mSystemUiProxy;

    public QuickstepTransitionManager(QuickstepLauncher launcher) {
        mLauncher = launcher;
        mDragLayer = mLauncher.getDragLayer();
        mHandler = new Handler(Looper.getMainLooper());
        mDeviceProfile = mLauncher.getDeviceProfile();
        mBackAnimationController = new LauncherBackAnimationController(mLauncher, this);

        Resources res = mLauncher.getResources();
        mClosingWindowTransY = res.getDimensionPixelSize(R.dimen.closing_window_trans_y);
        mClosingFreeformWindowTransY =
                res.getDimensionPixelSize(R.dimen.closing_freeform_window_trans_y);
        mMaxShadowRadius = res.getDimensionPixelSize(R.dimen.max_shadow_radius);

        mLauncher.addOnDeviceProfileChangeListener(this);
        mSystemUiProxy = SystemUiProxy.INSTANCE.get(mLauncher);

        if (ENABLE_SHELL_STARTING_SURFACE) {
            mTaskStartParams = new LinkedHashMap<>(MAX_NUM_TASKS) {
                @Override
                protected boolean removeEldestEntry(Entry<Integer, Pair<Integer, Integer>> entry) {
                    return size() > MAX_NUM_TASKS;
                }
            };

            mSystemUiProxy.setStartingWindowListener(mStartingWindowListener);
        }

        mCosAppAnimations = CosAppAnimation.isEnabled(launcher);
        mCosOpeningInterpolator = CosAppAnimation.OPEN;
        mCoordinateTransfer = new RemoteAnimationCoordinateTransfer(mLauncher);
        mLatencyTracker = LatencyTracker.getInstance(launcher);

        mMaxBlurRadius = launcher.getResources().getDimensionPixelSize(
                R.dimen.max_depth_blur_radius_enhanced);
    }

    @Override
    public void onDeviceProfileChanged(DeviceProfile dp) {
        cancelImmediateAppLaunch();
        mDeviceProfile = dp;
    }

    private void startCrossDisplayMoveAnimation(TransitionInfo info, SurfaceControl.Transaction t,
            IRemoteTransitionFinishedCallback finishCallback) {
        mHandler.post(() -> {
            new CrossDisplayMoveTransition(mLauncher, APP_LAUNCH_DURATION,
                    CLOSING_TRANSITION_DURATION_MS)
                    .startCrossDisplayMoveAnimation(info, t, finishCallback);
        });
    }

    /**
     * A {@link RemoteTransitionStub} that handles cross display move animations.
     */
    private class MoveDisplayChangeRunner extends RemoteTransitionStub {
        @Override
        public void startAnimation(IBinder token, TransitionInfo info, SurfaceControl.Transaction t,
                IRemoteTransitionFinishedCallback finishCallback) throws RemoteException {
            startCrossDisplayMoveAnimation(info, t, finishCallback);
        }
    }

    /**
     * @return ActivityOptions with remote animations that controls how the window of the opening
     * targets are displayed.
     */
    public ActivityOptionsWrapper getActivityLaunchOptions(View v, ItemInfo itemInfo) {
        boolean fromRecents = isLaunchingFromRecents(v, null /* targets */);
        RunnableList onEndCallback = new RunnableList();
        LaunchPreview preview = mImmediatePreview != null && mImmediatePreview.source == v
                ? mImmediatePreview : null;
        if (preview != null) onEndCallback.add(preview::remove);

        // Handle the case where an already visible task is launched which results in no transition
        TaskRestartedDuringLaunchListener restartedListener =
                new TaskRestartedDuringLaunchListener();
        restartedListener.register(onEndCallback::executeAllAndDestroy);
        mRegisteredTaskStackChangeListener.add(restartedListener);
        onEndCallback.add(new Runnable() {
            @Override
            public void run() {
                restartedListener.unregister();
                mRegisteredTaskStackChangeListener.remove(restartedListener);
            }
        });

        RemoteAnimationRunnerCompat appLaunchRunner = createAppLaunchRunner(
                v, onEndCallback);
        IRemoteTransition appLaunchRemoteTransition = createAppLaunchRemoteTransition(
                appLaunchRunner);

        // Note that this duration is a guess as we do not know if the animation will be a
        // recents launch or not for sure until we know the opening app targets.
        long duration = fromRecents
                ? RECENTS_LAUNCH_DURATION
                : mCosAppAnimations ? CosAppAnimation.LAUNCH_DURATION
                        : NothingAppAnimation.LAUNCH_DURATION_MS;

        long statusBarTransitionDelay = duration - STATUS_BAR_TRANSITION_DURATION
                - STATUS_BAR_TRANSITION_PRE_DELAY;
      ActivityOptions options = ActivityOptions.makeRemoteAnimation(
              new RemoteAnimationAdapter(appLaunchRunner, duration, statusBarTransitionDelay),
              new RemoteTransition(appLaunchRemoteTransition, mLauncher.getIApplicationThread(),
                    "QuickstepLaunch"));
        IRemoteCallback endCallback = completeRunnableListCallback(onEndCallback, mLauncher);
        options.setOnAnimationAbortListener(endCallback);
        options.setOnAnimationFinishedListener(endCallback);
        if (itemInfo != null) {
            options.setLaunchCookie(StableViewInfo.toLaunchCookie(itemInfo));
        }

        // Prepare taskbar for animation synchronization. This needs to happen here before any
        // app transition is created.
        TaskbarInteractor taskbarInteractor = mLauncher.getTaskbarInteractor();
        if (syncAppLaunchWithTaskbarStash()
                && mLauncher.getStateManager().getState() == NORMAL
                && taskbarInteractor != null) {
            taskbarInteractor.setIgnoreInAppFlagForSync(true);
            mLauncher.addEventCallback(EVENT_DESTROYED, onEndCallback::executeAllAndDestroy);
            onEndCallback.add(() -> {
                taskbarInteractor.setIgnoreInAppFlagForSync(false);
            });
        }

        return new ActivityOptionsWrapper(options, onEndCallback);
    }

    /**
     * Selects the appropriate type of launch runner for the given view, builds it, and returns it.
     * {@link QuickstepTransitionManager#mAppLaunchRunner} is updated as a by-product of this
     * method.
     */
    private RemoteAnimationRunnerCompat createAppLaunchRunner(View v, RunnableList onEndCallback) {
        ItemInfo tag = (ItemInfo) v.getTag();
        ContainerAnimationRunner containerRunner = null;
        if (mCosAppAnimations && tag != null && tag.shouldUseBackgroundAnimation()) {
            containerRunner = ContainerAnimationRunner.fromView(
                    v, true /* forLaunch */, mLauncher, mStartingWindowListener, onEndCallback,
                    null /* windowState */);
        }

        mAppLaunchRunner = containerRunner != null
                ? containerRunner : new AppLaunchAnimationRunner(v, onEndCallback);
        return new LauncherAnimationRunner(
                mHandler, mAppLaunchRunner, true /* startAtFrontOfQueue */);
    }

    /**
     * Creates a remote transition for app launches.
     *
     * The app launch runner picks an animation based on the view type (e.g. icon, widget).
     *
     * However, for cross-display moves, we need to pick a different animation based on the
     * (dynamic) TransitionInfo content. This is controlled by the
     * enableCrossDisplaysAppLaunchTransition flag.
     *
     * If the flag is enabled, the returned transition will:
     * 1. Check if the transition is a cross-display move, and if so, use the cross-display
     *    animation.
     * 2. Otherwise, use the default app launch animation from the runner.
     *
     * If the flag is disabled, this will always return the default app launch animation.
     */
    private IRemoteTransition createAppLaunchRemoteTransition(
            RemoteAnimationRunnerCompat appLaunchRunner) {
        IRemoteTransition defaultAppLaunchTransition = appLaunchRunner.toRemoteTransition();
        if (!com.android.window.flags.Flags.enableCrossDisplaysAppLaunchTransition()) {
            return defaultAppLaunchTransition;
        }

        IRemoteTransition crossDisplayMoveTransition = new MoveDisplayChangeRunner();
        return new RemoteTransitionDelegate(
                (info) -> {
                    if (CrossDisplayMoveTransition.isCrossDisplayMove(info)) {
                        Log.d(TAG, "Handling launch as a cross display move transition");
                        return crossDisplayMoveTransition;
                    } else {
                        return defaultAppLaunchTransition;
                    }
                });
    }

    /**
     * Whether the launch is a recents app transition and we should do a launch animation
     * from the recents view. Note that if the remote animation targets are not provided, this
     * may not always be correct as we may resolve the opening app to a task when the animation
     * starts.
     *
     * @param v       the view to launch from
     * @param targets apps that are opening/closing
     * @return true if the app is launching from recents, false if it most likely is not
     */
    protected boolean isLaunchingFromRecents(@NonNull View v,
            @Nullable RemoteAnimationTarget[] targets) {
        return mLauncher.getStateManager().getState().isRecentsViewVisible
                && findTaskViewToLaunch(mLauncher.getOverviewPanel(), v, targets) != null;
    }

    /**
     * Composes the animations for a launch from the recents list.
     *
     * @param anim            the animator set to add to
     * @param v               the launching view
     * @param appTargets      the apps that are opening/closing
     * @param launcherClosing true if the launcher app is closing
     */
    protected void composeRecentsLaunchAnimator(@NonNull AnimatorSet anim, @NonNull View v,
            @NonNull RemoteAnimationTarget[] appTargets,
            @NonNull RemoteAnimationTarget[] wallpaperTargets,
            @NonNull RemoteAnimationTarget[] nonAppTargets, boolean launcherClosing) {
        TaskViewUtils.composeRecentsLaunchAnimator(anim, v, appTargets, wallpaperTargets,
                nonAppTargets, launcherClosing, mLauncher.getStateManager(),
                mLauncher.getOverviewPanel(), mLauncher.getDepthController(),
                /* transitionInfo= */ null, /* appearedTaskId= */ INVALID_TASK_ID);
    }

    private boolean areAllTargetsTranslucent(@NonNull RemoteAnimationTarget[] targets) {
        boolean isAllOpeningTargetTrs = true;
        for (int i = 0; i < targets.length; i++) {
            RemoteAnimationTarget target = targets[i];
            if (target.mode == MODE_OPENING) {
                isAllOpeningTargetTrs &= target.isTranslucent;
            }
            if (!isAllOpeningTargetTrs) break;
        }
        return isAllOpeningTargetTrs;
    }

    /**
     * Compose the animations for a launch from the app icon.
     *
     * @param anim            the animation to add to
     * @param v               the launching view with the icon
     * @param appTargets      the list of opening/closing apps
     * @param launcherClosing true if launcher is closing
     */
    private void composeCosIconLaunchAnimator(@NonNull AnimatorSet anim, @NonNull View v,
            @NonNull RemoteAnimationTarget[] appTargets,
            @NonNull RemoteAnimationTarget[] wallpaperTargets,
            @NonNull RemoteAnimationTarget[] nonAppTargets,
            boolean launcherClosing, @Nullable LaunchPreview preview, long previewPlayTime) {
        // Set the state animation first so that any state listeners are called
        // before our internal listeners.
        mLauncher.getStateManager().setCurrentAnimation(anim);

        // Note: the targetBounds are relative to the launcher
        int startDelay = mCosAppAnimations ? 0 : getSingleFrameMs(mLauncher);
        Animator windowAnimator = getCosOpeningWindowAnimators(
                v, appTargets, wallpaperTargets, nonAppTargets, launcherClosing, preview,
                previewPlayTime);
        windowAnimator.setStartDelay(startDelay);
        anim.play(windowAnimator);
        if (launcherClosing) {
            Pair<AnimatorSet, Runnable> launcherContentAnimator =
                    getLauncherContentAnimator(true /* isAppOpening */,
                            startDelay + (int) previewPlayTime, false);
            anim.play(launcherContentAnimator.first);
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    launcherContentAnimator.second.run();
                }
            });
        }
    }

    private void composeCosWidgetLaunchAnimator(
            @NonNull AnimatorSet anim,
            @NonNull LauncherAppWidgetHostView v,
            @NonNull RemoteAnimationTarget[] appTargets,
            @NonNull RemoteAnimationTarget[] wallpaperTargets,
            @NonNull RemoteAnimationTarget[] nonAppTargets,
            boolean launcherClosing) {
        mLauncher.getStateManager().setCurrentAnimation(anim);
        anim.play(getCosOpeningWindowAnimatorsForWidget(
                v, appTargets, wallpaperTargets, nonAppTargets, launcherClosing));
        if (mCosAppAnimations && launcherClosing && !areAllTargetsTranslucent(appTargets)) {
            Pair<AnimatorSet, Runnable> content = getLauncherContentAnimator(true, 0, false);
            anim.play(content.first);
            anim.addListener(AnimatorListeners.forEndCallback(content.second));
        }
    }

    private RectFSpringAnim composeNothingLaunchAnimator(AnimatorSet animation, View source,
            RemoteAnimationTarget[] appTargets, RemoteAnimationTarget[] wallpaperTargets,
            RemoteAnimationTarget[] nonAppTargets, boolean launcherClosing) {
        mLauncher.getStateManager().setCurrentAnimation(animation);
        RemoteAnimationTargets targets = new RemoteAnimationTargets(appTargets,
                wallpaperTargets, nonAppTargets, MODE_OPENING);
        RemoteAnimationTarget openingTarget = targets.getFirstAppTarget();
        RectF targetRect = new RectF(getWindowTargetBounds(appTargets,
                getRotationChange(appTargets)));
        if (openingTarget != null) {
            transferRectToTargetCoordinate(openingTarget, new RectF(targetRect), true, targetRect);
        }
        RectF windowBounds = new RectF(targetRect);
        boolean cropToInset = shouldCropToInset(openingTarget);
        if (cropToInset) {
            float bottom = Math.min(targetRect.bottom,
                    mSystemUiProxy.getHomeVisibilityState().getNavbarInsetPosition());
            if (bottom > targetRect.top) targetRect.bottom = bottom;
        }
        boolean translucent = areAllTargetsTranslucent(appTargets);
        Pair<Integer, Integer> startParams = openingTarget == null ? null
                : mTaskStartParams.remove(openingTarget.taskId);
        boolean hasSplash = ENABLE_SHELL_STARTING_SURFACE && startParams != null
                && startParams.first == STARTING_WINDOW_TYPE_SPLASH_SCREEN;
        float endRadius = getWindowCornerRadius(mLauncher);
        RectF startRect = new RectF();
        final FloatingIconView icon;
        final FloatingWidgetView widget;
        final float iconAlpha;
        if (source instanceof LauncherAppWidgetHostView host) {
            int background = startParams == null ? 0 : startParams.second;
            if (background == 0) {
                background = FloatingWidgetView.getDefaultBackgroundColor(mLauncher, openingTarget);
            }
            widget = FloatingWidgetView.getFloatingWidgetView(mLauncher, host, startRect,
                    new Size(Math.round(targetRect.width()), Math.round(targetRect.height())),
                    endRadius, translucent, background);
            icon = null;
            iconAlpha = 0f;
        } else {
            widget = null;
            icon = getFloatingIconView(mLauncher, source,
                    mLauncher.getTaskbarInteractor() == null || !isTransientTaskbar() ? null
                            : mLauncher.getTaskbarInteractor().findMatchingAsyncView(source),
                    null, !translucent, startRect, true);
            iconAlpha = hasSplash && !icon.isDifferentFromAppIcon() ? 0f : 1f;
        }
        if (startRect.isEmpty() || !Float.isFinite(startRect.width())
                || !Float.isFinite(startRect.height())) {
            startRect.set(getDefaultWindowTargetRect());
        }
        final float startRadius = widget == null ? startRect.width() / 2f
                : supportsRoundedCornersOnWindows(mLauncher.getResources())
                        ? widget.getInitialCornerRadius() : 0f;
        RectFSpringAnim spring = new RectFSpringAnim(
                new NothingSpringConfig(mLauncher, mDeviceProfile, startRect, targetRect,
                        RectFSpringAnim.TRACKING_CENTER));
        View floatingView = icon != null ? icon : widget;
        SurfaceTransactionApplier applier = new SurfaceTransactionApplier(floatingView);
        targets.addReleaseCheck(spring);
        targets.addReleaseCheck(applier);
        SurfaceControl scrim = launcherClosing && !translucent
                ? addScrimLayer(applier, targets) : null;
        float scrimAlpha = getScrimAlpha();
        Rect originalBounds = new Rect();
        windowBounds.round(originalBounds);
        spring.addOnUpdateListener(new SpringAnimRunner(appTargets, originalBounds, originalBounds,
                startRadius, endRadius, MODE_OPENING, applier) {
            @Override
            public void onUpdate(RectF rect, float progress) {
                if (cropToInset) {
                    float bottom = Math.min(windowBounds.bottom,
                            mSystemUiProxy.getHomeVisibilityState().getNavbarInsetPosition());
                    if (bottom > targetRect.top && bottom != targetRect.bottom) {
                        targetRect.bottom = bottom;
                        spring.onTargetPositionChanged();
                    }
                }
                if (icon != null) {
                    icon.update(iconAlpha, rect, progress, 0f, getCornerRadius(progress), true);
                } else {
                    float foreground = 1f - Utilities.boundToRange(progress / 0.125f, 0f, 1f);
                    float preview = Utilities.boundToRange((progress - 0.124f) / 0.124f, 0f, 1f);
                    widget.update(rect, translucent ? 1f - preview : 1f, foreground,
                            Utilities.boundToRange(progress / 0.15f, 0f, 1f), progress);
                }
                super.onUpdate(rect, progress);
            }

            @Override
            protected void updateParallelSurface(SurfaceTransaction transaction, float progress) {
                if (scrim != null && scrim.isValid()) {
                    transaction.forSurface(scrim).setAlpha(scrimAlpha * progress)
                            .setBackgroundBlurRadius(Math.round(mMaxBlurRadius * progress));
                }
            }
        });
        if (icon != null) {
            spring.addAnimatorListener(icon);
            icon.setFastFinishRunnable(spring::end);
        } else {
            spring.addAnimatorListener(widget);
            widget.setFastFinishRunnable(spring::end);
        }
        spring.addAnimatorListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator ignored) {
                if (source instanceof BubbleTextView textView) textView.setStayPressed(false);
                resetScrim(applier, scrim);
                targets.release();
                TaskbarInteractor taskbar = mLauncher.getTaskbarInteractor();
                if (taskbar != null) taskbar.showEduOnAppLaunch();
            }
        });
        animation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator ignored) {
                spring.start(mLauncher, mDeviceProfile, new PointF());
            }

            @Override
            public void onAnimationCancel(Animator ignored) {
                spring.cancel();
            }
        });
        animation.play(ValueAnimator.ofFloat(0f, 1f)
                .setDuration(NothingAppAnimation.LAUNCH_DURATION_MS));
        if (launcherClosing && !translucent) {
            Pair<AnimatorSet, Runnable> content = getLauncherContentAnimator(true,
                    getSingleFrameMs(mLauncher), false);
            animation.play(content.first);
            animation.play(getBackgroundAnimator(scrim != null));
            animation.addListener(AnimatorListeners.forEndCallback(content.second));
        }
        return spring;
    }

    /**
     * Return the window bounds of the opening target.
     * In multiwindow mode, we need to get the final size of the opening app window target to help
     * figure out where the floating view should animate to.
     */
    private Rect getWindowTargetBounds(@NonNull RemoteAnimationTarget[] appTargets,
            int rotationChange) {
        RemoteAnimationTarget target = null;
        for (RemoteAnimationTarget t : appTargets) {
            if (t.mode != MODE_OPENING) continue;
            target = t;
            break;
        }
        final int widthPx = mDeviceProfile.getDeviceProperties().getWidthPx();
        final int heightPx = mDeviceProfile.getDeviceProperties().getHeightPx();
        if (target == null) return new Rect(0, 0, widthPx, heightPx);
        final Rect bounds = new Rect(target.screenSpaceBounds);
        if (target.localBounds != null) {
            bounds.set(target.localBounds);
        } else {
            bounds.offsetTo(target.position.x, target.position.y);
        }
        if (rotationChange != 0) {
            if ((rotationChange % 2) == 1) {
                // undoing rotation, so our "original" parent size is actually flipped
                Utilities.rotateBounds(bounds, heightPx, widthPx,
                        4 - rotationChange);
            } else {
                Utilities.rotateBounds(bounds, widthPx, heightPx,
                        4 - rotationChange);
            }
        }
        return bounds;
    }

    /** Dump debug logs to bug report. */
    public void dump(@NonNull String prefix, @NonNull PrintWriter printWriter) {}

    /**
     * Content is everything on screen except the background and the floating view (if any).
     *
     * @param isAppOpening     True when this is called when an app is opening.
     *                         False when this is called when an app is closing.
     * @param startDelay       Start delay duration.
     * @param skipAllAppsScale True if we want to avoid scaling All Apps
     */
    private Pair<AnimatorSet, Runnable> getLauncherContentAnimator(boolean isAppOpening,
            int startDelay, boolean skipAllAppsScale) {
        AnimatorSet launcherAnimator = new AnimatorSet();
        Runnable endListener;

        float[] alphas = isAppOpening
                ? new float[]{1, 0}
                : new float[]{0, 1};

        float[] scales = isAppOpening
                ? new float[]{1, mDeviceProfile.mWorkspaceProfile.getWorkspaceContentScale()}
                : new float[]{mDeviceProfile.mWorkspaceProfile.getWorkspaceContentScale(), 1};

        // Pause expensive view updates as they can lead to layer thrashing and skipped frames.
        mLauncher.pauseExpensiveViewUpdates();

        if (mLauncher.isInState(ALL_APPS)) {
            // All Apps in portrait mode is full screen, so we only animate AllAppsContainerView.
            final View appsView = mLauncher.getAppsView();
            final float startAlpha = appsView.getAlpha();
            final float startScale = SCALE_PROPERTY.get(appsView);
            if (mDeviceProfile.getDeviceProperties().isTablet()) {

                // AllApps should not fade at all in tablets.
                alphas = new float[]{1, 1};
            }
            appsView.setAlpha(alphas[0]);

            ObjectAnimator alpha = ObjectAnimator.ofFloat(appsView, View.ALPHA, alphas);
            alpha.setDuration(CONTENT_ALPHA_DURATION);
            alpha.setInterpolator(LINEAR);

            if (!mDeviceProfile.getDeviceProperties().isTablet()) {
                appsView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                alpha.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        appsView.setLayerType(View.LAYER_TYPE_NONE, null);
                    }
                });
            }

            if (!skipAllAppsScale) {
                SCALE_PROPERTY.set(appsView, scales[0]);
                ObjectAnimator scale = ObjectAnimator.ofFloat(appsView, SCALE_PROPERTY, scales);
                scale.setInterpolator(AGGRESSIVE_EASE);
                scale.setDuration(CONTENT_SCALE_DURATION);
                launcherAnimator.play(scale);
            }

            launcherAnimator.play(alpha);

            endListener = () -> {
                appsView.setAlpha(startAlpha);
                SCALE_PROPERTY.set(appsView, startScale);
                appsView.setLayerType(View.LAYER_TYPE_NONE, null);
                mLauncher.resumeExpensiveViewUpdates();
            };
        } else if (mLauncher.isInState(OVERVIEW)) {
            endListener = composeViewContentAnimator(launcherAnimator, alphas, scales);
        } else {
            List<View> viewsToAnimate = new ArrayList<>();
            viewsToAnimate.add(mLauncher.getWorkspace());

            Hotseat hotseat = mLauncher.getHotseat();
            // Do not scale hotseat as a whole when taskbar is present, and scale QSB only if it's
            // not inline.
            if (mDeviceProfile.isTaskbarPresent) {
                if (!mDeviceProfile.isQsbInline) {
                    viewsToAnimate.add(hotseat.getQsb());
                }
            } else {
                viewsToAnimate.add(hotseat);
            }

            final float pushbackScale = isAppOpening
                    ? (mCosAppAnimations ? CosAppAnimation.HOME_SCALE : scales[1]) : 1f;
            final float pushbackAlpha = isAppOpening
                    ? (mCosAppAnimations ? 0f : 1f) : 1f;

            viewsToAnimate.forEach(view -> {
                if (mCosAppAnimations) {
                    Luminous.cancel(view, Luminous.ALPHA);
                }
                // Start the animation from the current value, instead of assuming the views are
                // in their resting state, so interrupted animations merge seamlessly.
                // TODO(b/367591368): ideally these animations would be refactored to be
                //  controlled centrally so each instances doesn't need to care about this
                //  coordination.
                float[] scale = new float[]{view.getScaleX(), pushbackScale};
                float[] alpha = new float[]{view.getAlpha(), pushbackAlpha};

                ObjectAnimator scaleAnim = ObjectAnimator.ofFloat(view, SCALE_PROPERTY, scale)
                        .setDuration(mCosAppAnimations
                                ? CosAppAnimation.LAUNCH_DURATION
                                : NothingAppAnimation.WORKSPACE_DURATION_MS);
                scaleAnim.setInterpolator(mCosAppAnimations
                        ? CosAppAnimation.OPEN : DECELERATE_1_5);
                launcherAnimator.play(scaleAnim);

                if (mCosAppAnimations) {
                    ObjectAnimator alphaAnim = ObjectAnimator.ofFloat(view, View.ALPHA, alpha)
                            .setDuration(CosAppAnimation.LAUNCH_DURATION);
                    alphaAnim.setInterpolator(CosAppAnimation.OPEN);
                    launcherAnimator.play(alphaAnim);
                }
            });

            // Capture all starting values before cancelling: a shared reveal animation resets
            // both the workspace and hotseat when either view interrupts it.
            viewsToAnimate.forEach(Animations.Companion::cancelOngoingAnimation);
            viewsToAnimate.forEach(view -> {
                // Cancellation restores the old hardware layer; cache only after that cleanup.
                view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                Animations.Companion.setOngoingAnimation(view, launcherAnimator);
            });

            endListener = () -> {
                viewsToAnimate.forEach(view -> {
                    SCALE_PROPERTY.set(view, 1f);
                    view.setAlpha(1f);
                    view.setLayerType(View.LAYER_TYPE_NONE, null);

                    // Reset the cached animation.
                    Animations.Companion.setOngoingAnimation(view, null /* animation */);
                });
                mLauncher.resumeExpensiveViewUpdates();
            };
        }

        launcherAnimator.setStartDelay(startDelay);
        if (mCosAppAnimations) {
            RunnableList cleanup = new RunnableList();
            cleanup.add(endListener);
            launcherAnimator.addListener(
                    AnimatorListeners.forEndCallback(cleanup::executeAllAndDestroy));
            return new Pair<>(launcherAnimator, cleanup::executeAllAndDestroy);
        }
        return new Pair<>(launcherAnimator, endListener);
    }

    /**
     * Compose recents view alpha and translation Y animation when launcher opens/closes apps.
     *
     * @param anim   the animator set to add to
     * @param alphas the alphas to animate to over time
     * @param scales the scale values to animator to over time
     * @return listener to run when the animation ends
     */
    protected Runnable composeViewContentAnimator(@NonNull AnimatorSet anim,
            float[] alphas, float[] scales) {
        RecentsView overview = mLauncher.getOverviewPanel();
        ObjectAnimator alpha = ObjectAnimator.ofFloat(overview,
                RecentsView.CONTENT_ALPHA, alphas);
        alpha.setDuration(CONTENT_ALPHA_DURATION);
        alpha.setInterpolator(LINEAR);
        anim.play(alpha);
        overview.setFreezeViewVisibility(true);

        ObjectAnimator scaleAnim = ObjectAnimator.ofFloat(overview, SCALE_PROPERTY, scales);
        scaleAnim.setInterpolator(AGGRESSIVE_EASE);
        scaleAnim.setDuration(CONTENT_SCALE_DURATION);
        anim.play(scaleAnim);

        return () -> {
            overview.setFreezeViewVisibility(false);
            SCALE_PROPERTY.set(overview, 1f);
            mLauncher.getStateManager().reapplyState();
            mLauncher.resumeExpensiveViewUpdates();
        };
    }

    private boolean shouldCropToInset(RemoteAnimationTarget target) {
        return enableDynamicInsetsForAppLaunch()
                && mDeviceProfile.isTaskbarPresent
                && mDeviceProfile.isTaskbarPresentInApps
                && target != null && !target.willShowImeOnTarget
                && !isTransientTaskbar();
    }

    /**
     * @return Animator that controls the window of the opening targets from app icons.
     */
    private Animator getCosOpeningWindowAnimators(View v,
            RemoteAnimationTarget[] appTargets,
            RemoteAnimationTarget[] wallpaperTargets,
            RemoteAnimationTarget[] nonAppTargets,
            boolean launcherClosing, @Nullable LaunchPreview preview, long previewPlayTime) {
        final long launchDuration = CosAppAnimation.LAUNCH_DURATION;
        RemoteAnimationTargets openingTargets = new RemoteAnimationTargets(appTargets,
                wallpaperTargets, nonAppTargets, MODE_OPENING);
        int rotationChange = getRotationChange(appTargets);
        Rect windowTargetBounds = getWindowTargetBounds(appTargets, rotationChange);
        final int displayWidth = mDeviceProfile.getDeviceProperties().getWidthPx();
        final int displayHeight = mDeviceProfile.getDeviceProperties().getHeightPx();
        final int[] bottomInsetPos = new int[]{
                mSystemUiProxy.getHomeVisibilityState().getNavbarInsetPosition()};
        final int[] cachedNavbarInset = new int[]{bottomInsetPos[0]};
        final RemoteAnimationTarget target = openingTargets.getFirstAppTarget();
        final boolean cropToInset = shouldCropToInset(target);
        if (cropToInset) {
            // Animate to above the taskbar.
            windowTargetBounds.bottom = Math.min(bottomInsetPos[0],
                    windowTargetBounds.bottom);
        }
        boolean appTargetsAreTranslucent = areAllTargetsTranslucent(appTargets);

        RectF launcherIconBounds = new RectF();
        FloatingIconView floatingView = getFloatingIconView(mLauncher, v,
                (mLauncher.getTaskbarInteractor() == null || !isTransientTaskbar())
                        ? null
                        : mLauncher.getTaskbarInteractor().findMatchingAsyncView(v),
                null /* fadeOutView */, !appTargetsAreTranslucent, launcherIconBounds,
                true /* isOpening */);
        floatingView.setFinishOnTouchDown(false);
        Rect crop = new Rect();
        Matrix matrix = new Matrix();
        Rect closingTargetCrop = new Rect();

        SurfaceTransactionApplier surfaceApplier =
                new SurfaceTransactionApplier(floatingView);
        openingTargets.addReleaseCheck(surfaceApplier);
        RemoteAnimationTarget navBarTarget = openingTargets.getNavBarRemoteAnimationTarget();

        int[] dragLayerBounds = new int[2];
        mDragLayer.getLocationOnScreen(dragLayerBounds);
        if (preview != null) {
            launcherIconBounds.set(preview.iconBounds);
            launcherIconBounds.offset(preview.predictedScreenBounds.left - dragLayerBounds[0],
                    preview.predictedScreenBounds.top - dragLayerBounds[1]);
        }

        final boolean hasSplashScreen;
        if (ENABLE_SHELL_STARTING_SURFACE) {
            int taskId = openingTargets.getFirstAppTargetTaskId();
            Pair<Integer, Integer> defaultParams = Pair.create(STARTING_WINDOW_TYPE_NONE, 0);
            Pair<Integer, Integer> taskParams =
                    mTaskStartParams.getOrDefault(taskId, defaultParams);
            mTaskStartParams.remove(taskId);
            hasSplashScreen = taskParams.first == STARTING_WINDOW_TYPE_SPLASH_SCREEN;
        } else {
            hasSplashScreen = false;
        }

        final int windowIconSize = ResourceUtils.getDimenByName("starting_surface_icon_size",
                mLauncher.getResources(), 108);

        AnimOpenProperties prop = new AnimOpenProperties(mLauncher.getResources(), mDeviceProfile,
                windowTargetBounds, launcherIconBounds, v, dragLayerBounds[0], dragLayerBounds[1],
                hasSplashScreen, floatingView.isDifferentFromAppIcon());
        int left = prop.cropCenterXStart - prop.cropWidthStart / 2;
        int top = prop.cropCenterYStart - prop.cropHeightStart / 2;
        int right = left + prop.cropWidthStart;
        int bottom = top + prop.cropHeightStart;
        // Set the crop here so we can calculate the corner radius below.
        crop.set(left, top, right, bottom);

        RectF floatingIconBounds = new RectF();
        RectF tmpRectF = new RectF();
        Point tmpPos = new Point();

        final SurfaceControl scrimLayer = launcherClosing && !appTargetsAreTranslucent
                ? addScrimLayer(surfaceApplier, openingTargets) : null;
        final float scrimAlpha = getScrimAlpha();

        AnimatorSet animatorSet = new AnimatorSet();
        ValueAnimator appAnimator = ValueAnimator.ofFloat(0, 1);
        appAnimator.setDuration(launchDuration);
        appAnimator.setInterpolator(LINEAR);
        appAnimator.addListener(floatingView);
        appAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                if (shouldShowEduOnAppLaunch()) {
                    // LAUNCHER_TASKBAR_EDUCATION_SHOWING is set to true here, when the education
                    // flow is about to start, to avoid a race condition with other components
                    // that would show something else to the user as soon as the app is opened.
                    Settings.Secure.putInt(mLauncher.getContentResolver(),
                            LAUNCHER_TASKBAR_EDUCATION_SHOWING, 1);
                }
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (v instanceof BubbleTextView) {
                    ((BubbleTextView) v).setStayPressed(false);
                }
                TaskbarInteractor taskbarInteractor = mLauncher.getTaskbarInteractor();
                if (taskbarInteractor != null) {
                    taskbarInteractor.showEduOnAppLaunch();
                }
                resetScrim(surfaceApplier, scrimLayer);
                openingTargets.release();
            }

            private boolean shouldShowEduOnAppLaunch() {
                if (refactorTaskbarUiState()) {
                    final boolean ret = newShouldShowEduOnAppLaunch();
                    if (BuildConfig.IS_STUDIO_BUILD && ret != legacyShouldShowEduOnAppLaunch()) {
                        throw new IllegalStateException("shouldShowEduOnAppLaunch() doesn't match");
                    }
                    return ret;
                } else {
                    return legacyShouldShowEduOnAppLaunch();
                }
            }

            private boolean legacyShouldShowEduOnAppLaunch() {
                return mLauncher.getTaskbarInteractor() != null
                        && mLauncher.getTaskbarInteractor().shouldShowEduOnAppLaunch();
            }

            private boolean newShouldShowEduOnAppLaunch() {
                return mLauncher.getTaskbarUiState().getShouldShowEduOnAppLaunchRef().getValue();
            }
        });

        final float initialWindowRadius = supportsRoundedCornersOnWindows(mLauncher.getResources())
                ? Math.max(crop.width(), crop.height()) / 2f
                : 0f;
        final float finalShadowRadius = appTargetsAreTranslucent ? 0 : mMaxShadowRadius;

        MultiValueUpdateListener listener = new MultiValueUpdateListener() {
            FloatProp mDx = new FloatProp(0, prop.dX, mCosOpeningInterpolator);
            FloatProp mDy = new FloatProp(0, prop.dY, mCosOpeningInterpolator);

            FloatProp mIconScaleToFitScreen = new FloatProp(prop.initialAppIconScale,
                    prop.finalAppIconScale, mCosOpeningInterpolator);
            FloatProp mIconAlpha = new FloatProp(prop.iconAlphaStart, 0f,
                    clampToDuration(LINEAR, 0, CosAppAnimation.ICON_FADE_DURATION, launchDuration));

            FloatProp mWindowRadius = new FloatProp(initialWindowRadius,
                    getWindowCornerRadius(mLauncher),
                    CosAppAnimation.OPEN);
            FloatProp mShadowRadius = new FloatProp(0, finalShadowRadius,
                    mCosOpeningInterpolator);

            FloatProp mCropRectCenterX = new FloatProp(prop.cropCenterXStart, prop.cropCenterXEnd,
                    mCosOpeningInterpolator);
            FloatProp mCropRectCenterY = new FloatProp(prop.cropCenterYStart, prop.cropCenterYEnd,
                    mCosOpeningInterpolator);
            FloatProp mCropRectWidth = new FloatProp(prop.cropWidthStart, prop.cropWidthEnd,
                    mCosOpeningInterpolator);
            FloatProp mCropRectHeight = new FloatProp(prop.cropHeightStart, prop.cropHeightEnd,
                    mCosOpeningInterpolator);

            FloatProp mNavFadeOut = new FloatProp(1f, 0f, clampToDuration(
                    NAV_FADE_OUT_INTERPOLATOR, 20, ANIMATION_NAV_FADE_OUT_DURATION,
                    launchDuration));
            FloatProp mNavFadeIn = new FloatProp(0f, 1f, clampToDuration(
                    NAV_FADE_IN_INTERPOLATOR, launchDuration - ANIMATION_NAV_FADE_IN_DURATION,
                    ANIMATION_NAV_FADE_IN_DURATION, launchDuration));

            FloatProp mBlurRadius = new FloatProp(0f, mMaxBlurRadius,
                    CosAppAnimation.OPEN);
            FloatProp mBlurScrimAlpha = new FloatProp(0f, scrimAlpha,
                    CosAppAnimation.OPEN);
            FloatProp mCosProgress = new FloatProp(0f, 1f, mCosOpeningInterpolator);

            int mLastBlurRadius = -1;

            @Override
            public void onUpdate(float percent, boolean initOnly) {
                if (cropToInset && bottomInsetPos[0] != cachedNavbarInset[0]) {
                    cachedNavbarInset[0] = mSystemUiProxy.getHomeVisibilityState()
                            .getNavbarInsetPosition();
                    bottomInsetPos[0] = cachedNavbarInset[0];
                    final RemoteAnimationTarget target = openingTargets.getFirstAppTarget();
                    final Rect bounds = target != null
                            ? target.screenSpaceBounds : windowTargetBounds;
                    // Animate to above the taskbar.
                    int bottomLevel = Math.min(bottomInsetPos[0], bounds.bottom);
                    windowTargetBounds.bottom = bottomLevel;
                    final int endHeight = bottomLevel - bounds.top;

                    float smallestSize = Math.min(windowTargetBounds.height(),
                            windowTargetBounds.width());
                    float maxIconScale = Math.max(smallestSize / launcherIconBounds.width(),
                            smallestSize / launcherIconBounds.height());
                    float newDY = windowTargetBounds.centerY() - dragLayerBounds[1]
                            - launcherIconBounds.centerY();
                    float interpolatedPercent = mCosOpeningInterpolator.getInterpolation(percent);
                    mCropRectCenterY.value = windowTargetBounds.centerY();
                    mCropRectHeight.value = windowIconSize
                            + interpolatedPercent * (windowTargetBounds.height() - windowIconSize);
                    mDy.value = interpolatedPercent * newDY;
                    mIconScaleToFitScreen.value = prop.initialAppIconScale
                            + interpolatedPercent * (maxIconScale - prop.initialAppIconScale);
                }

                // Calculate the size of the scaled icon.
                float iconWidth = launcherIconBounds.width() * mIconScaleToFitScreen.value;
                float iconHeight = launcherIconBounds.height() * mIconScaleToFitScreen.value;

                int left = (int) (mCropRectCenterX.value - mCropRectWidth.value / 2);
                int top = (int) (mCropRectCenterY.value - mCropRectHeight.value / 2);
                int right = (int) (left + mCropRectWidth.value);
                int bottom = (int) (top + mCropRectHeight.value);
                crop.set(left, top, right, bottom);

                final int windowCropWidth = crop.width();
                final int windowCropHeight = crop.height();
                if (rotationChange != 0) {
                    Utilities.rotateBounds(crop, displayWidth,
                            displayHeight, rotationChange);
                }

                // Scale the size of the icon to match the size of the window crop.
                float scaleX = iconWidth / windowCropWidth;
                float scaleY = iconHeight / windowCropHeight;
                float scale = Math.min(1f, Math.max(scaleX, scaleY));

                float scaledCropWidth = windowCropWidth * scale;
                float scaledCropHeight = windowCropHeight * scale;
                float offsetX = (scaledCropWidth - iconWidth) / 2;
                float offsetY = (scaledCropHeight - iconHeight) / 2;

                // Calculate the window position to match the icon position.
                tmpRectF.set(launcherIconBounds);
                tmpRectF.offset(dragLayerBounds[0], dragLayerBounds[1]);
                tmpRectF.offset(mDx.value, mDy.value);
                Utilities.scaleRectFAboutCenter(tmpRectF, mIconScaleToFitScreen.value);
                float windowTransX0 = tmpRectF.left - offsetX - crop.left * scale;
                float windowTransY0 = tmpRectF.top - offsetY - crop.top * scale;

                // Calculate the icon position.
                floatingIconBounds.set(launcherIconBounds);
                floatingIconBounds.offset(mDx.value, mDy.value);
                Utilities.scaleRectFAboutCenter(floatingIconBounds, mIconScaleToFitScreen.value);
                floatingIconBounds.left -= offsetX;
                floatingIconBounds.top -= offsetY;
                floatingIconBounds.right += offsetX;
                floatingIconBounds.bottom += offsetY;

                float windowRadius = mWindowRadius.value;
                float iconRadius = windowRadius * scale;
                if (rotationChange == 0 && !launcherIconBounds.isEmpty()
                        && !windowTargetBounds.isEmpty()) {
                    cosOpenRect(launcherIconBounds, dragLayerBounds[0], dragLayerBounds[1],
                            windowTargetBounds, mCosProgress.value, tmpRectF);
                    scale = Math.max(tmpRectF.width() / windowTargetBounds.width(),
                            tmpRectF.height() / windowTargetBounds.height());
                    crop.set(windowTargetBounds.left, windowTargetBounds.top,
                            windowTargetBounds.left + Math.min(windowTargetBounds.width(),
                                    Math.round(tmpRectF.width() / scale)),
                            windowTargetBounds.top + Math.min(windowTargetBounds.height(),
                                    Math.round(tmpRectF.height() / scale)));
                    windowTransX0 = tmpRectF.left - crop.left * scale;
                    windowTransY0 = tmpRectF.top - crop.top * scale;
                    iconRadius = cosOpenRadius(launcherIconBounds, mCosProgress.value);
                    windowRadius = iconRadius / scale;
                    floatingIconBounds.set(tmpRectF);
                    floatingIconBounds.offset(-dragLayerBounds[0], -dragLayerBounds[1]);
                }

                if (initOnly) {
                    // For the init pass, we want full alpha since the window is not yet ready.
                    floatingView.update(1f, floatingIconBounds, percent, 0f,
                            iconRadius, true /* isOpening */);
                    return;
                }

                if (preview != null) preview.setProgress(percent);
                SurfaceTransaction transaction = new SurfaceTransaction();

                for (int i = appTargets.length - 1; i >= 0; i--) {
                    RemoteAnimationTarget target = appTargets[i];
                    SurfaceProperties builder = transaction.forSurface(target.leash);

                    if (target.mode == MODE_OPENING) {
                        matrix.setScale(scale, scale);
                        if (rotationChange == 1) {
                            matrix.postTranslate(windowTransY0,
                                    displayWidth - (windowTransX0 + scaledCropWidth));
                        } else if (rotationChange == 2) {
                            matrix.postTranslate(
                                    displayWidth - (windowTransX0 + scaledCropWidth),
                                    displayHeight - (windowTransY0 + scaledCropHeight));
                        } else if (rotationChange == 3) {
                            matrix.postTranslate(
                                    displayHeight - (windowTransY0 + scaledCropHeight),
                                    windowTransX0);
                        } else {
                            matrix.postTranslate(windowTransX0, windowTransY0);
                        }

                        floatingView.update(mIconAlpha.value, floatingIconBounds, percent, 0f,
                                iconRadius, true /* isOpening */);
                        builder.setMatrix(matrix)
                                .setWindowCrop(crop)
                                .setAlpha(1f - mIconAlpha.value)
                                .setCornerRadius(windowRadius)
                                .setShadowRadius(mShadowRadius.value);
                    } else if (target.mode == MODE_CLOSING) {
                        if (target.localBounds != null) {
                            tmpPos.set(target.localBounds.left, target.localBounds.top);
                        } else {
                            tmpPos.set(target.position.x, target.position.y);
                        }
                        if (target.screenSpaceBounds != null) {
                            closingTargetCrop.set(target.screenSpaceBounds);
                            closingTargetCrop.offsetTo(0, 0);
                        } else {
                            closingTargetCrop.setEmpty();
                        }

                        if ((rotationChange % 2) != 0) {
                            int tmp = closingTargetCrop.right;
                            closingTargetCrop.right = closingTargetCrop.bottom;
                            closingTargetCrop.bottom = tmp;
                            tmp = tmpPos.x;
                            tmpPos.x = tmpPos.y;
                            tmpPos.y = tmp;
                        }
                        matrix.setTranslate(tmpPos.x, tmpPos.y);
                        builder.setMatrix(matrix)
                                .setWindowCrop(closingTargetCrop)
                                .setAlpha(1f);
                    }
                }

                if (navBarTarget != null) {
                    SurfaceProperties navBuilder =
                            transaction.forSurface(navBarTarget.leash);
                    if (mNavFadeIn.value > mNavFadeIn.getStartValue()) {
                        matrix.setScale(scale, scale);
                        matrix.postTranslate(windowTransX0, windowTransY0);
                        navBuilder.setMatrix(matrix)
                                .setWindowCrop(crop)
                                .setAlpha(mNavFadeIn.value);
                    } else {
                        navBuilder.setAlpha(mNavFadeOut.value);
                    }
                }

                if (scrimLayer != null && scrimLayer.isValid()) {
                    SurfaceProperties builder = transaction.forSurface(scrimLayer);
                    float blur = mBlurRadius.value;
                    float dim = mBlurScrimAlpha.value;
                    if (previewPlayTime > 0) {
                        float t = mCosOpeningInterpolator.getInterpolation(Utilities.boundToRange(
                                (percent * launchDuration - previewPlayTime) / launchDuration,
                                0f, 1f));
                        blur = mMaxBlurRadius * t;
                        dim = scrimAlpha * t;
                    }
                    builder.setAlpha(dim);
                    int blurRadius = (int) blur;
                    if (Math.abs(blurRadius - mLastBlurRadius) >= MIN_BLUR_RADIUS_DELTA_PX
                            || blurRadius >= mMaxBlurRadius) {
                        builder.setBackgroundBlurRadius(blurRadius);
                        mLastBlurRadius = blurRadius;
                    }
                }

                surfaceApplier.scheduleApply(transaction);
            }
        };
        appAnimator.addUpdateListener(listener);
        // Since we added a start delay, call update here to init the FloatingIconView properly.
        listener.onUpdate(0, true /* initOnly */);

        // If app targets are translucent, do not animate the background as it causes a visible
        // flicker when it resets itself at the end of its animation.
        if (appTargetsAreTranslucent || !launcherClosing) {
            animatorSet.play(appAnimator);
        } else {
            Animator background = getBackgroundAnimator(scrimLayer != null);
            background.setStartDelay(previewPlayTime);
            animatorSet.playTogether(appAnimator, background);
        }
        return animatorSet;
    }

    private boolean isTransientTaskbar() {
        return TaskbarFeatureEvaluator.INSTANCE.get(mLauncher).isTransient();
    }

    private Animator getCosOpeningWindowAnimatorsForWidget(LauncherAppWidgetHostView v,
            RemoteAnimationTarget[] appTargets,
            RemoteAnimationTarget[] wallpaperTargets,
            RemoteAnimationTarget[] nonAppTargets, boolean launcherClosing) {
        final long launchDuration = CosAppAnimation.LAUNCH_DURATION;
        Rect windowTargetBounds = getWindowTargetBounds(appTargets, getRotationChange(appTargets));
        boolean appTargetsAreTranslucent = areAllTargetsTranslucent(appTargets);

        final RectF widgetBackgroundBounds = new RectF();
        final Rect appWindowCrop = new Rect();
        final Matrix matrix = new Matrix();
        RemoteAnimationTargets openingTargets = new RemoteAnimationTargets(appTargets,
                wallpaperTargets, nonAppTargets, MODE_OPENING);

        RemoteAnimationTarget openingTarget = openingTargets.getFirstAppTarget();
        int fallbackBackgroundColor = 0;
        if (openingTarget != null && ENABLE_SHELL_STARTING_SURFACE) {
            fallbackBackgroundColor = mTaskStartParams.containsKey(openingTarget.taskId)
                    ? mTaskStartParams.get(openingTarget.taskId).second : 0;
            mTaskStartParams.remove(openingTarget.taskId);
        }
        if (fallbackBackgroundColor == 0) {
            fallbackBackgroundColor =
                    FloatingWidgetView.getDefaultBackgroundColor(mLauncher, openingTarget);
        }

        final float finalWindowRadius = getWindowCornerRadius(mLauncher);
        final FloatingWidgetView floatingView = FloatingWidgetView.getFloatingWidgetView(mLauncher,
                v, widgetBackgroundBounds,
                new Size(windowTargetBounds.width(), windowTargetBounds.height()),
                finalWindowRadius, appTargetsAreTranslucent, fallbackBackgroundColor);
        final float initialWindowRadius = supportsRoundedCornersOnWindows(mLauncher.getResources())
                ? floatingView.getInitialCornerRadius() : 0;

        SurfaceTransactionApplier surfaceApplier = new SurfaceTransactionApplier(floatingView);
        openingTargets.addReleaseCheck(surfaceApplier);

        RemoteAnimationTarget navBarTarget = openingTargets.getNavBarRemoteAnimationTarget();
        final SurfaceControl scrimLayer = launcherClosing && !appTargetsAreTranslucent
                ? addScrimLayer(surfaceApplier, openingTargets) : null;
        final float scrimAlpha = getScrimAlpha();

        AnimatorSet animatorSet = new AnimatorSet();
        ValueAnimator appAnimator = ValueAnimator.ofFloat(0, 1);
        appAnimator.setDuration(launchDuration);
        appAnimator.setInterpolator(LINEAR);
        appAnimator.addListener(floatingView);
        appAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                resetScrim(surfaceApplier, scrimLayer);
                openingTargets.release();
            }
        });
        floatingView.setFastFinishRunnable(animatorSet::end);

        appAnimator.addUpdateListener(new MultiValueUpdateListener() {
            float mAppWindowScale = 1;
            final FloatProp mWidgetForegroundAlpha = new FloatProp(1, 0, clampToDuration(
                    DECELERATE_1_5, 0, WIDGET_CROSSFADE_DURATION_MILLIS / 2, launchDuration));

            final FloatProp mWidgetFallbackBackgroundAlpha = new FloatProp(0, 1,
                    clampToDuration(LINEAR, 0, 75, launchDuration));
            final FloatProp mPreviewAlpha = new FloatProp(0, 1, clampToDuration(
                    LINEAR,
                    WIDGET_CROSSFADE_DURATION_MILLIS / 2 /* delay */,
                    WIDGET_CROSSFADE_DURATION_MILLIS / 2 /* duration */,
                    launchDuration));
            final FloatProp mWindowRadius = new FloatProp(initialWindowRadius, finalWindowRadius,
                    CosAppAnimation.OPEN);
            final FloatProp mCornerRadiusProgress = new FloatProp(0, 1, mCosOpeningInterpolator);

            // Window & widget background positioning bounds
            final FloatProp mDx = new FloatProp(widgetBackgroundBounds.centerX(),
                    windowTargetBounds.centerX(), mCosOpeningInterpolator);
            final FloatProp mDy = new FloatProp(widgetBackgroundBounds.centerY(),
                    windowTargetBounds.centerY(), mCosOpeningInterpolator);
            final FloatProp mWidth = new FloatProp(widgetBackgroundBounds.width(),
                    windowTargetBounds.width(), mCosOpeningInterpolator);
            final FloatProp mHeight = new FloatProp(widgetBackgroundBounds.height(),
                    windowTargetBounds.height(), mCosOpeningInterpolator);

            final FloatProp mNavFadeOut = new FloatProp(1f, 0f, clampToDuration(
                    NAV_FADE_OUT_INTERPOLATOR, 0, ANIMATION_NAV_FADE_OUT_DURATION,
                    launchDuration));
            final FloatProp mNavFadeIn = new FloatProp(0f, 1f, clampToDuration(
                    NAV_FADE_IN_INTERPOLATOR, launchDuration - ANIMATION_NAV_FADE_IN_DURATION,
                    ANIMATION_NAV_FADE_IN_DURATION, launchDuration));

            final FloatProp mBlurRadius = new FloatProp(0f, mMaxBlurRadius,
                    CosAppAnimation.OPEN);
            final FloatProp mBlurScrimAlpha = new FloatProp(0f, scrimAlpha,
                    CosAppAnimation.OPEN);

            int mLastBlurRadius = -1;

            @Override
            public void onUpdate(float percent, boolean initOnly) {
                widgetBackgroundBounds.set(mDx.value - mWidth.value / 2f,
                        mDy.value - mHeight.value / 2f, mDx.value + mWidth.value / 2f,
                        mDy.value + mHeight.value / 2f);
                // Set app window scaling factor to match widget background width
                mAppWindowScale = widgetBackgroundBounds.width() / windowTargetBounds.width();
                // Crop scaled app window to match widget
                appWindowCrop.set(0 /* left */, 0 /* top */,
                        windowTargetBounds.width() /* right */,
                        Math.round(widgetBackgroundBounds.height() / mAppWindowScale) /* bottom */);
                matrix.setTranslate(widgetBackgroundBounds.left, widgetBackgroundBounds.top);
                matrix.postScale(mAppWindowScale, mAppWindowScale, widgetBackgroundBounds.left,
                        widgetBackgroundBounds.top);

                SurfaceTransaction transaction = new SurfaceTransaction();
                float floatingViewAlpha = appTargetsAreTranslucent ? 1 - mPreviewAlpha.value : 1;
                for (int i = appTargets.length - 1; i >= 0; i--) {
                    RemoteAnimationTarget target = appTargets[i];
                    SurfaceProperties builder = transaction.forSurface(target.leash);
                    if (target.mode == MODE_OPENING) {
                        floatingView.update(widgetBackgroundBounds, floatingViewAlpha,
                                mWidgetForegroundAlpha.value, mWidgetFallbackBackgroundAlpha.value,
                                mCornerRadiusProgress.value);
                        builder.setMatrix(matrix)
                                .setWindowCrop(appWindowCrop)
                                .setAlpha(mPreviewAlpha.value)
                                .setCornerRadius(mWindowRadius.value / mAppWindowScale);
                    }
                }

                if (navBarTarget != null) {
                    SurfaceProperties navBuilder = transaction.forSurface(navBarTarget.leash);
                    if (mNavFadeIn.value > mNavFadeIn.getStartValue()) {
                        navBuilder.setMatrix(matrix)
                                .setWindowCrop(appWindowCrop)
                                .setAlpha(mNavFadeIn.value);
                    } else {
                        navBuilder.setAlpha(mNavFadeOut.value);
                    }
                }

                if (scrimLayer != null && scrimLayer.isValid()) {
                    SurfaceProperties builder = transaction.forSurface(scrimLayer);
                    builder.setAlpha(mBlurScrimAlpha.value);
                    int blurRadius = (int) mBlurRadius.value;
                    if (Math.abs(blurRadius - mLastBlurRadius) >= MIN_BLUR_RADIUS_DELTA_PX
                            || blurRadius >= mMaxBlurRadius) {
                        builder.setBackgroundBlurRadius(blurRadius);
                        mLastBlurRadius = blurRadius;
                    }
                }

                surfaceApplier.scheduleApply(transaction);
            }
        });

        // If app targets are translucent, do not animate the background as it causes a visible
        // flicker when it resets itself at the end of its animation.
        if (appTargetsAreTranslucent || !launcherClosing) {
            animatorSet.play(appAnimator);
        } else {
            animatorSet.playTogether(appAnimator, getBackgroundAnimator(scrimLayer != null));
        }
        return animatorSet;
    }

    private SurfaceControl addScrimLayer(SurfaceTransactionApplier applier,
            RemoteAnimationTargets targets) {
        if (!appLaunchBlur() || !isAppLaunchBlurEnabled()) {
            return null;
        }

        RemoteAnimationTarget launcherTarget = null;
        for (final RemoteAnimationTarget target : targets.unfilteredApps) {
            if (MODE_CLOSING == target.mode) {
                launcherTarget = target;
                break;
            }
        }

        SurfaceControl parent = launcherTarget != null ? launcherTarget.leash : null;
        if (parent == null || !parent.isValid()) {
            // Parent surface is not ready at the moment. Retry later.
            return null;
        }
        SurfaceControl scrimLayer = new SurfaceControl.Builder()
                .setName("App launch background scrim")
                .setCallsite("AppLaunchAnimationRunner")
                .setEffectLayer()
                .setOpaque(false)
                .setHidden(true)
                .build();
        final float[] colorComponents = new float[] { 0f, 0f, 0f };

        SurfaceTransaction transaction = new SurfaceTransaction();
        SurfaceProperties builder = transaction.forSurface(scrimLayer);
        builder
                .setColor(colorComponents)
                .setAlpha(0)
                .reparent(launcherTarget.leash)
                .setShow()
                // Ensure the scrim layer occludes wallpaper
                .setLayer(1000);
        applier.scheduleApply(transaction);
        notifyRendererOfUpcomingBlur();
        return scrimLayer;
    }

    private float getScrimAlpha() {
        return Utilities.isDarkTheme(mLauncher) ? MAX_SCRIM_ALPHA_DARK : MAX_SCRIM_ALPHA_LIGHT;
    }

    private void resetScrim(SurfaceTransactionApplier applier, SurfaceControl scrimLayer) {
        if (scrimLayer != null && scrimLayer.isValid()) {
            SurfaceTransaction surfaceTransaction = new SurfaceTransaction();
            surfaceTransaction.getTransaction().remove(scrimLayer);
            applier.scheduleApply(surfaceTransaction);
        }
    }

    private void notifyRendererOfUpcomingBlur() {
        ViewRootImpl viewRootImpl = mDragLayer.getViewRootImpl();
        if (viewRootImpl == null) {
            return;
        }
        try {
            viewRootImpl.notifyRendererForGpuLoadUp("applyBlur");
            viewRootImpl.notifyRendererOfExpensiveFrame("applyBlur");
        } catch (Exception e) {
            Log.w(TAG, "Failed to notify renderer of upcoming blur frame", e);
        }
    }

    private boolean isAppLaunchBlurEnabled() {
        return LauncherPrefs.get(mLauncher).get(APP_LAUNCH_BLUR_ENABLED);
    }

    /** Returns animator that controls depth/blur of the background during app/widget opening. */
    private Animator getBackgroundAnimator(boolean scrimHandlesBlur) {
        if (mCosAppAnimations) {
            return getCosBackgroundAnimator(scrimHandlesBlur);
        }
        boolean skipLauncherBlur = Flags.allAppsBlur() && !scrimHandlesBlur;
        boolean appLaunchBlurEnabled = isAppLaunchBlurEnabled();

        // When launching an app from overview that doesn't map to a task, we still want to just
        // blur the wallpaper instead of the launcher surface as well
        LauncherState launcherState = mLauncher.getStateManager().getState();
        boolean allowBlurringLauncher =
                appLaunchBlurEnabled && !skipLauncherBlur && !scrimHandlesBlur
                        && launcherState != OVERVIEW
                        && BlurUtils.supportsBlursOnWindows();

        DepthController depthController = mLauncher.getDepthController();
        ObjectAnimator backgroundRadiusAnim = ObjectAnimator.ofFloat(
                        depthController.stateDepth, MULTI_PROPERTY_VALUE,
                        BACKGROUND_APP.getDepth(mLauncher))
                .setDuration(NothingAppAnimation.WORKSPACE_DURATION_MS);
        backgroundRadiusAnim.setInterpolator(DECELERATE_1_5);

        if (scrimHandlesBlur || skipLauncherBlur || !appLaunchBlurEnabled) {
            backgroundRadiusAnim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    depthController.pauseBlursOnWindows(true);
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    depthController.pauseBlursOnWindows(false);
                }
            });
        }

        if (allowBlurringLauncher) {
            // Create a temporary effect layer, that lives on top of launcher, so we can apply
            // the blur to it. The EffectLayer will be fullscreen, which will help with caching
            // optimizations on the SurfaceFlinger side:
            // - Results would be able to be cached as a texture
            // - There won't be texture allocation overhead, because EffectLayers don't have
            //   buffers
            ViewRootImpl viewRootImpl = mLauncher.getDragLayer().getViewRootImpl();
            SurfaceControl parent = viewRootImpl != null
                    ? viewRootImpl.getSurfaceControl()
                    : null;
            SurfaceControl dimLayer = new SurfaceControl.Builder()
                    .setName("Blur layer")
                    .setParent(parent)
                    .setOpaque(false)
                    .setHidden(false)
                    .setEffectLayer()
                    .build();

            backgroundRadiusAnim.addListener(AnimatorListeners.forEndCallback(() -> {
                // Use try-with-resources to ensure the transaction gets closed.
                try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                    transaction.remove(dimLayer).apply();
                }
            }));
        }

        return backgroundRadiusAnim;
    }

    private Animator getCosBackgroundAnimator(boolean scrimHandlesBlur) {
        DepthController depth = mLauncher.getDepthController();
        ObjectAnimator state = ObjectAnimator.ofFloat(depth.stateDepth, MULTI_PROPERTY_VALUE,
                BACKGROUND_APP.getDepth(mLauncher));
        state.setDuration(CosAppAnimation.LAUNCH_DURATION);
        state.setInterpolator(CosAppAnimation.OPEN);
        AnimatorSet result = new AnimatorSet();
        result.playTogether(state, depth.animateAppWallpaperDepth(depth.stateDepth.getValue(),
                BACKGROUND_APP.getDepth(mLauncher), CosAppAnimation.WALLPAPER_OPEN_DURATION,
                CosAppAnimation.WALLPAPER_OPEN));
        boolean contentBlur = !scrimHandlesBlur && isAppLaunchBlurEnabled()
                && !mLauncher.isInState(OVERVIEW);
        if (contentBlur) {
            ValueAnimator blur = ValueAnimator.ofFloat(0f, mMaxBlurRadius);
            blur.setDuration(CosAppAnimation.LAUNCH_DURATION);
            blur.setInterpolator(CosAppAnimation.OPEN);
            blur.addUpdateListener(value ->
                    depth.setAppAnimationBlur((Float) value.getAnimatedValue()));
            result.play(blur);
        }
        result.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                depth.pauseBlursOnWindows(true);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                depth.setAppAnimationBlur(-1f);
                depth.pauseBlursOnWindows(false);
            }
        });
        return result;
    }

    /**
     * Registers remote animations used when closing apps to home screen.
     */
    public void registerRemoteAnimations() {
        if (SEPARATE_RECENTS_ACTIVITY.get()) {
            return;
        }
        RemoteAnimationDefinition definition = new RemoteAnimationDefinition();
        addRemoteAnimations(definition);
        mLauncher.registerRemoteAnimations(definition);
    }

    /**
     * Adds remote animations to a {@link RemoteAnimationDefinition}. May be overridden to add
     * additional animations.
     */
    private void addRemoteAnimations(RemoteAnimationDefinition definition) {
        mWallpaperOpenRunner = new WallpaperOpenLauncherAnimationRunner();
        definition.addRemoteAnimation(WindowManager.TRANSIT_OLD_WALLPAPER_OPEN,
                WindowConfiguration.ACTIVITY_TYPE_STANDARD,
                new RemoteAnimationAdapter(
                        new LauncherAnimationRunner(mHandler, mWallpaperOpenRunner,
                                false /* startAtFrontOfQueue */),
                        mCosAppAnimations ? CLOSING_TRANSITION_DURATION_MS
                                : NothingAppAnimation.FALLBACK_CLOSE_DURATION_MS, 0));
    }

    /**
     * Registers remote animations used when closing apps to home screen.
     */
    public void registerRemoteTransitions() {
        SystemUiProxy.INSTANCE.get(mLauncher).shareTransactionQueue();
        if (SEPARATE_RECENTS_ACTIVITY.get()) {
            return;
        }

        mWallpaperOpenTransitionRunner = new WallpaperOpenLauncherAnimationRunner();
        mLauncherOpenTransition = new RemoteTransition(
                new LauncherAnimationRunner(mHandler, mWallpaperOpenTransitionRunner,
                        false /* startAtFrontOfQueue */).toRemoteTransition(),
                mLauncher.getIApplicationThread(), "QuickstepLaunchHome");

        TransitionFilter homeCheck = new TransitionFilter();
        // No need to handle the transition that also dismisses keyguard.
        homeCheck.mNotFlags = TRANSIT_FLAG_KEYGUARD_GOING_AWAY;

        homeCheck.mRequirements =
                new TransitionFilter.Requirement[]{new TransitionFilter.Requirement(),
                        new TransitionFilter.Requirement(),
                        new TransitionFilter.Requirement()};

        homeCheck.mRequirements[0].mActivityType = ACTIVITY_TYPE_HOME;
        homeCheck.mRequirements[0].mTopActivity = mLauncher.getComponentName();
        homeCheck.mRequirements[0].mModes = new int[]{TRANSIT_OPEN, TRANSIT_TO_FRONT};
        if (!com.android.window.flags.Flags.polishCloseWallpaperIncludesOpenChange()) {
            homeCheck.mRequirements[0].mOrder = CONTAINER_ORDER_TOP;
        }

        homeCheck.mRequirements[1].mActivityType = ACTIVITY_TYPE_STANDARD;
        homeCheck.mRequirements[1].mModes = new int[]{TRANSIT_CLOSE, TRANSIT_TO_BACK};

        homeCheck.mRequirements[2].mNot = true;
        homeCheck.mRequirements[2].mCustomAnimation = true;
        homeCheck.mRequirements[2].mMustBeTask = true;
        homeCheck.mRequirements[2].mMustBeIndependent = true;

        SystemUiProxy.INSTANCE.get(mLauncher)
                .registerRemoteTransition(mLauncherOpenTransition, homeCheck);
        if (mBackAnimationController != null) {
            mBackAnimationController.registerComponentCallbacks();
            if (isHomeRoleHeld()) {
                mBackAnimationController.registerBackCallbacks(mHandler);
            }
        }

        /*
         * For cross-display moves, moving to the default display is handled by the LaunchOptions
         * transition setup, which forwards to AppLaunchRemoteAnimationRunner. However, if the
         * launch happens via a different means (e.g. desktop mode), we also need to handle the
         * cross-display move via a remote transition.
         */
        if (com.android.window.flags.Flags.enableCrossDisplaysAppLaunchTransition()) {
            mMoveDisplayTransition = new RemoteTransition(new MoveDisplayChangeRunner(),
                    mLauncher.getIApplicationThread(), "QuickstepDisplayMove");
            TransitionFilter changeCheck = new TransitionFilter();
            changeCheck.mRequirements = new TransitionFilter.Requirement[]{
                    new TransitionFilter.Requirement()};
            changeCheck.mRequirements[0].mModes = new int[]{TRANSIT_CHANGE};
            changeCheck.mRequirements[0].mMustBeTask = true;
            // (TRANSIT_CHANGE is never independent.)
            changeCheck.mRequirements[0].mMustBeIndependent = false;
            changeCheck.mRequirements[0].mActivityType = ACTIVITY_TYPE_STANDARD;
            changeCheck.mRequirements[0].mIsCrossDisplayMove = true;
            SystemUiProxy.INSTANCE.get(mLauncher)
                    .registerRemoteTransition(mMoveDisplayTransition, changeCheck);
        }
    }

    public void onActivityDestroyed() {
        cancelImmediateAppLaunch();
        unregisterRemoteAnimations();
        unregisterRemoteTransitions();
        mLauncher.removeOnDeviceProfileChangeListener(this);
        SystemUiProxy.INSTANCE.get(mLauncher).setStartingWindowListener(null);
        if (BuildConfig.IS_STUDIO_BUILD && !mRegisteredTaskStackChangeListener.isEmpty()) {
            Log.e(TAG, "IllegalState: Failed to run onEndCallback created from"
                    + " getActivityLaunchOptions()");
        }
        mRegisteredTaskStackChangeListener.forEach(TaskRestartedDuringLaunchListener::unregister);
        mRegisteredTaskStackChangeListener.clear();
    }

    /**
     * Called when the overview-target changes. Updates the back callback registration state.
     */
    public void onOverviewTargetChange() {
        if (mBackAnimationController == null) {
            return;
        }
        if (isHomeRoleHeld()) {
            mBackAnimationController.registerBackCallbacks(mHandler);
        } else {
            mBackAnimationController.unregisterBackCallbacks();
        }
    }

    private boolean isHomeRoleHeld() {
        RoleManager roleManager = mLauncher.getSystemService(RoleManager.class);
        return roleManager == null || roleManager.isRoleHeld(ROLE_HOME);
    }

    private void unregisterRemoteAnimations() {
        if (SEPARATE_RECENTS_ACTIVITY.get()) {
            return;
        }
        mLauncher.unregisterRemoteAnimations();

        // Also clear strong references to the runners registered with the remote animation
        // definition so we don't have to wait for the system gc
        mWallpaperOpenRunner = null;
        mAppLaunchRunner = null;
    }

    protected void unregisterRemoteTransitions() {
        SystemUiProxy.INSTANCE.get(mLauncher).unshareTransactionQueue();
        if (!SEPARATE_RECENTS_ACTIVITY.get()) {
            if (mLauncherOpenTransition != null) {
                SystemUiProxy.INSTANCE.get(mLauncher).unregisterRemoteTransition(
                        mLauncherOpenTransition);
                mLauncherOpenTransition = null;
                mWallpaperOpenTransitionRunner = null;
            }
            if (mMoveDisplayTransition != null) {
                SystemUiProxy.INSTANCE.get(mLauncher)
                        .unregisterRemoteTransition(mMoveDisplayTransition);
                mMoveDisplayTransition = null;
            }
        }
        if (mBackAnimationController != null) {
            mBackAnimationController.cleanupForDestroy();
            mBackAnimationController.unregisterBackCallbacks();
            mBackAnimationController.unregisterComponentCallbacks();
            mBackAnimationController = null;
        }
    }

    private boolean launcherIsATargetWithMode(RemoteAnimationTarget[] targets, int mode) {
        for (RemoteAnimationTarget target : targets) {
            if (target.mode == mode && target.taskInfo != null
                    // Compare component name instead of task-id because transitions will promote
                    // the target up to the root task while getTaskId returns the leaf.
                    && target.taskInfo.topActivity != null
                    && target.taskInfo.topActivity.equals(mLauncher.getComponentName())) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldPlayFallbackClosingAnimation(RemoteAnimationTarget[] targets) {
        int numTargets = 0;
        for (RemoteAnimationTarget target : targets) {
            if (target.mode == MODE_CLOSING) {
                numTargets++;
                if (numTargets > 1 || target.windowConfiguration.getWindowingMode()
                        == WINDOWING_MODE_MULTI_WINDOW) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int getRotationChange(RemoteAnimationTarget[] appTargets) {
        int rotationChange = 0;
        for (RemoteAnimationTarget target : appTargets) {
            if (Math.abs(target.rotationChange) > Math.abs(rotationChange)) {
                rotationChange = target.rotationChange;
            }
        }
        return ((rotationChange % 4) + 4) % 4;
    }

    /**
     * Returns view on launcher that corresponds to the closing app in the list of app targets
     */
    public @Nullable View findLauncherView(RemoteAnimationTarget[] appTargets) {
        for (RemoteAnimationTarget appTarget : appTargets) {
            if (appTarget.mode == MODE_CLOSING) {
                View launcherView = findLauncherView(appTarget);
                if (launcherView != null) {
                    return launcherView;
                }
            }
        }
        return null;
    }

    /**
     * Returns view on launcher that corresponds to the {@param runningTaskTarget}.
     */
    private @Nullable View findLauncherView(RemoteAnimationTarget runningTaskTarget) {
        if (runningTaskTarget == null || runningTaskTarget.taskInfo == null) {
            return null;
        }

        final ComponentName[] taskInfoActivities = new ComponentName[]{
                runningTaskTarget.taskInfo.baseActivity,
                runningTaskTarget.taskInfo.origActivity,
                runningTaskTarget.taskInfo.realActivity,
                runningTaskTarget.taskInfo.topActivity};

        String packageName = null;
        for (ComponentName component : taskInfoActivities) {
            if (component != null && component.getPackageName() != null) {
                packageName = component.getPackageName();
                break;
            }
        }

        if (packageName == null) {
            return null;
        }

        // Find the associated item info for the launch cookie (if available), note that predicted
        // apps actually have an id of -1, so use another default id here
        final List<IBinder> launchCookies = runningTaskTarget.taskInfo.launchCookies == null
                ? Collections.EMPTY_LIST
                : runningTaskTarget.taskInfo.launchCookies;

        return mLauncher.getFirstVisibleElementForAppClose(
                StableViewInfo.fromLaunchCookies(launchCookies), packageName,
                UserHandle.of(runningTaskTarget.taskInfo.userId));
    }

    private @NonNull RectF getDefaultWindowTargetRect() {
        RecentsView recentsView = mLauncher.getOverviewPanel();
        PagedOrientationHandler orientationHandler = recentsView.getPagedOrientationHandler();
        DeviceProfile dp = mLauncher.getDeviceProfile();
        final int halfIconSize = dp.getWorkspaceIconProfile().getIconSizePx() / 2;
        float primaryDimension = orientationHandler
                .getPrimaryValue(dp.getDeviceProperties().getAvailableWidthPx(), dp.getDeviceProperties().getAvailableHeightPx());
        float secondaryDimension = orientationHandler
                .getSecondaryValue(dp.getDeviceProperties().getAvailableWidthPx(), dp.getDeviceProperties().getAvailableHeightPx());
        if (!mCosAppAnimations) {
            return NothingAppAnimation.getDefaultWindowTargetRect(
                    dp.getWorkspaceIconProfile().getIconSizePx(), primaryDimension,
                    secondaryDimension);
        }
        final float targetX = primaryDimension / 2f;
        final float targetY = secondaryDimension - dp.hotseatBarSizePx;
        return new RectF(targetX - halfIconSize, targetY - halfIconSize,
                targetX + halfIconSize, targetY + halfIconSize);
    }

    /**
     * Closing animator that animates the window into its final location on the workspace.
     */
    protected RectFSpringAnim getClosingWindowAnimators(AnimatorSet animation,
            RemoteAnimationTarget[] targets, View launcherView, PointF velocityPxPerS,
            RectF closingWindowStartRectF, float startWindowCornerRadius) {
        return getClosingWindowAnimators(animation, targets, launcherView, velocityPxPerS,
                closingWindowStartRectF, startWindowCornerRadius, null);
    }

    private RectFSpringAnim getClosingWindowAnimators(AnimatorSet animation,
            RemoteAnimationTarget[] targets, View launcherView, PointF velocityPxPerS,
            RectF closingWindowStartRectF, float startWindowCornerRadius,
            @Nullable RectF islandTarget) {
        FloatingIconView floatingIconView = null;
        FloatingWidgetView floatingWidget = null;
        RectF targetRect = new RectF();

        RemoteAnimationTarget runningTaskTarget = null;
        boolean isTransluscent = false;
        for (RemoteAnimationTarget target : targets) {
            if (target.mode == MODE_CLOSING) {
                runningTaskTarget = target;
                isTransluscent = runningTaskTarget.isTranslucent;
                break;
            }
        }

        // Get floating view and target rect.
        boolean isInHotseat = false;
        if (islandTarget != null) {
            targetRect.set(islandTarget);
        } else if (launcherView instanceof LauncherAppWidgetHostView) {
            Size windowSize = new Size(mDeviceProfile.getDeviceProperties().getWidthPx(),
                    mDeviceProfile.getDeviceProperties().getHeightPx());
            int fallbackBackgroundColor =
                    FloatingWidgetView.getDefaultBackgroundColor(mLauncher, runningTaskTarget);
            floatingWidget = FloatingWidgetView.getFloatingWidgetView(mLauncher,
                    (LauncherAppWidgetHostView) launcherView, targetRect, windowSize,
                    getWindowCornerRadius(mLauncher), isTransluscent, fallbackBackgroundColor);
        } else if (launcherView != null && !RemoveAnimationSettingsTracker.INSTANCE.get(
                mLauncher).isRemoveAnimationEnabled()) {
            floatingIconView = getFloatingIconView(mLauncher, launcherView, null,
                    mLauncher.getTaskbarInteractor() == null
                            ? null
                            : mLauncher.getTaskbarInteractor().findMatchingAsyncView(launcherView),
                    true /* hideOriginal */, targetRect, false /* isOpening */);
        } else {
            targetRect.set(getDefaultWindowTargetRect());
        }

        boolean useTaskbarHotseatParams = mDeviceProfile.isTaskbarPresent && isInHotseat;
        final boolean useNothingSpring = !mCosAppAnimations;
        final boolean useCosSpring = mCosAppAnimations
                && (floatingIconView != null || islandTarget != null)
                && !isTransluscent;
        RectFSpringAnim anim = new RectFSpringAnim(useCosSpring
                ? new RectFSpringAnim.CosSpringConfig(mLauncher, mDeviceProfile,
                        closingWindowStartRectF, targetRect)
                : useNothingSpring
                ? new NothingSpringConfig(mLauncher, mDeviceProfile, closingWindowStartRectF,
                        targetRect)
                : useTaskbarHotseatParams
                ? new TaskbarHotseatSpringConfig(mLauncher, closingWindowStartRectF, targetRect)
                : floatingWidget != null
                                ? new WidgetSpringConfig(mLauncher, mDeviceProfile,
                                        closingWindowStartRectF, targetRect)
                                : new DefaultSpringConfig(mLauncher, mDeviceProfile,
                                        closingWindowStartRectF, targetRect));

        // Hook up floating views to the closing window animators.
        // note the coordinate of closingWindowStartRect is based on launcher
        Rect closingWindowStartRect = new Rect();
        closingWindowStartRectF.round(closingWindowStartRect);
        Rect closingWindowOriginalRect =
                new Rect(0, 0, mDeviceProfile.getDeviceProperties().getWidthPx(), mDeviceProfile.getDeviceProperties().getHeightPx());
        if (floatingIconView != null) {
            anim.addAnimatorListener(floatingIconView);
            floatingIconView.setAllowParallelAnimation(useCosSpring);
            floatingIconView.setOnTargetChangeListener(anim::onTargetPositionChanged);
            floatingIconView.setFastFinishRunnable(anim::end);
            FloatingIconView finalFloatingIconView = floatingIconView;

            // We want the window alpha to be 0 once this threshold is met, so that the
            // FloatingIconView can be seen morphing into the icon shape.
            final float windowAlphaThreshold = useCosSpring ? 0f : useNothingSpring
                    ? NothingAppAnimation.SHAPE_PROGRESS_START : 1f - SHAPE_PROGRESS_DURATION;

            final float customEndRadius =
                    (launcherView instanceof BubbleTextView btv && btv.isMultiSpan())
                            ? btv.getIconBackgroundCornerRadius() : -1f;

            RectFSpringAnim.OnUpdateListener runner = new SpringAnimRunner(targets, targetRect,
                    closingWindowStartRect, closingWindowOriginalRect, startWindowCornerRadius) {
                @Override
                protected float getWindowAlpha(float progress) {
                    if (useCosSpring) return 1f - anim.getCosFadeProgress();
                    return super.getWindowAlpha(progress);
                }

                @Override
                public float getCornerRadius(float progress) {
                    if (customEndRadius >= 0f) {
                        return startWindowCornerRadius
                                + progress * (customEndRadius - startWindowCornerRadius);
                    }
                    return super.getCornerRadius(progress);
                }

                @Override
                public void onUpdate(RectF currentRectF, float progress) {
                    // We want the icon alpha to be 1 once this threshold is met, so that it can be
                    // seen morphing into the icon shape. But before the threshold, we want to limit
                    // the alpha to reduce the blur effect behind the window.
                    float iconAlpha = useCosSpring ? anim.getCosFadeProgress() : useNothingSpring
                            ? 1f
                            : Interpolators.clampToProgress(progress, 0f, windowAlphaThreshold);
                    finalFloatingIconView.update(iconAlpha, currentRectF, progress,
                            windowAlphaThreshold, getCornerRadius(progress), false);

                    if (!anim.isWindowDetached()) super.onUpdate(currentRectF, progress);
                }
            };
            anim.addOnUpdateListener(runner);
        } else if (floatingWidget != null) {
            anim.addAnimatorListener(floatingWidget);
            floatingWidget.setOnTargetChangeListener(anim::onTargetPositionChanged);
            floatingWidget.setFastFinishRunnable(anim::end);

            final float floatingWidgetAlpha = isTransluscent ? 0 : 1;
            FloatingWidgetView finalFloatingWidget = floatingWidget;
            final Function<RectF, Float> posProvider = LauncherAnimUtils.getPosProviderForRect(
                    closingWindowStartRectF, targetRect);
            final float startPos = posProvider.apply(closingWindowStartRectF);
            final float totalDiff = Math.abs(startPos - posProvider.apply(targetRect));
            RectFSpringAnim.OnUpdateListener runner = new SpringAnimRunner(targets, targetRect,
                    closingWindowStartRect, closingWindowOriginalRect, startWindowCornerRadius) {
                private float mWidgetAlphaLowerBound = 1f;
                private boolean mThresholdCaptured;

                @Override
                protected boolean isWidget() {
                    return true;
                }

                @Override
                protected float getWindowAlpha(float progress) {
                    if (useNothingSpring) return super.getWindowAlpha(progress);
                    return 1f - mapBoundToRange(progress, 0f, WIDGET_CLOSE_ALPHA_END_PROGRESS,
                            0f, 1f, ACCELERATE_1_5);
                }

                @Override
                public float getCornerRadius(float progress) {
                    if (useNothingSpring) {
                        return Utilities.mapRange(progress, startWindowCornerRadius,
                                finalFloatingWidget.getInitialCornerRadius());
                    }
                    return finalFloatingWidget.getOutlineRadius();
                }

                @Override
                public void onUpdate(RectF currentRectF, float progress) {
                    if (useNothingSpring) {
                        float foreground = mapBoundToRange(progress, 0.8f, 1f, 0f, 1f, LINEAR);
                        finalFloatingWidget.update(currentRectF, floatingWidgetAlpha,
                                foreground, 1f - foreground, 1f - progress);
                        super.onUpdate(currentRectF, progress);
                        return;
                    }
                    float currentProgress = totalDiff > 0f
                            ? Math.abs(posProvider.apply(currentRectF) - startPos) / totalDiff : 1f;
                    if (!mThresholdCaptured && progress >= 0.99f) {
                        mWidgetAlphaLowerBound = currentProgress;
                        mThresholdCaptured = true;
                    }
                    finalFloatingWidget.update(currentProgress, mWidgetAlphaLowerBound,
                            currentRectF, floatingWidgetAlpha, 1 - progress);

                    super.onUpdate(currentRectF, progress);
                }
            };
            anim.addOnUpdateListener(runner);
        } else if (islandTarget != null) {
            anim.addOnUpdateListener(new SpringAnimRunner(targets, targetRect,
                    closingWindowStartRect, closingWindowOriginalRect, startWindowCornerRadius) {
                @Override
                protected boolean isWidget() {
                    return true;
                }

                @Override
                protected float getWindowAlpha(float progress) {
                    return LunarisBarAnimation.getWindowAlpha(progress);
                }

                @Override
                public float getCornerRadius(float progress) {
                    return LunarisBarAnimation.getCornerRadius(
                            startWindowCornerRadius, islandTarget.height() / 2f, progress);
                }
            });
        } else {
            // If no floating icon or widget is present, animate the to the default window
            // target rect.
            anim.addOnUpdateListener(new SpringAnimRunner(
                    targets, targetRect, closingWindowStartRect, closingWindowOriginalRect,
                    startWindowCornerRadius) {
                @Override
                protected float getWindowAlpha(float progress) {
                    return useNothingSpring
                            ? NothingAppAnimation.getFallbackWindowAlpha(progress)
                            : super.getWindowAlpha(progress);
                }
            });
        }

        // Use a fixed velocity to start the animation. Let the spring finish independently:
        // predictive back to All Apps can have an empty content AnimatorSet, which ends
        // immediately after starting the spring.
        animation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                anim.start(mLauncher, mDeviceProfile, velocityPxPerS);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                if (useCosSpring) anim.detachWindow();
                else if (useNothingSpring || islandTarget != null) anim.cancel();
            }
        });
        return anim;
    }

    /**
     * Closing window animator that moves the window down and offscreen.
     */
    private Animator getFallbackClosingWindowAnimators(RemoteAnimationTarget[] appTargets) {
        final int rotationChange = getRotationChange(appTargets);
        SurfaceTransactionApplier surfaceApplier = new SurfaceTransactionApplier(mDragLayer);
        Matrix matrix = new Matrix();
        Point tmpPos = new Point();
        Rect tmpRect = new Rect();
        Rect fallbackCrop = new Rect();
        SurfaceTransaction cosTransaction = mCosAppAnimations ? new SurfaceTransaction() : null;
        ValueAnimator closingAnimator = ValueAnimator.ofFloat(0, 1);
        int duration = mCosAppAnimations ? CLOSING_TRANSITION_DURATION_MS
                : NothingAppAnimation.FALLBACK_CLOSE_DURATION_MS;
        float durationScale = duration / 330f;
        float windowCornerRadius = getWindowCornerRadius(mLauncher);
        float startShadowRadius = areAllTargetsTranslucent(appTargets) ? 0 : mMaxShadowRadius;
        closingAnimator.setDuration(duration);
        boolean isFreeform = isFreeformAnimation(appTargets);
        float translateY = isFreeform
            ? mClosingFreeformWindowTransY
            : mClosingWindowTransY * 1.0f;
        float endScale = isFreeform ? 0.95f : mCosAppAnimations ? 0.94f : 1f;
        Interpolator alphaInterpolator = !mCosAppAnimations
                ? clampToDuration(LINEAR, isFreeform ? 0 : 25, isFreeform ? 100 : 125, duration)
                : isFreeform
                ? clampToDuration(LINEAR, 0, Math.round(100 * durationScale), duration)
                : clampToDuration(LINEAR, Math.round(40 * durationScale),
                        Math.round(210 * durationScale), duration);
        closingAnimator.addUpdateListener(new MultiValueUpdateListener() {
            FloatProp mDy = new FloatProp(0, translateY, DECELERATE_1_7);
            FloatProp mScale = new FloatProp(1f, endScale, DECELERATE_1_7);
            FloatProp mAlpha = new FloatProp(1f, 0f, alphaInterpolator);
            FloatProp mShadowRadius = new FloatProp(startShadowRadius, 0, DECELERATE_1_7);

            @Override
            public void onUpdate(float percent, boolean initOnly) {
                SurfaceTransaction transaction = mCosAppAnimations
                        ? cosTransaction : new SurfaceTransaction();
                for (int i = appTargets.length - 1; i >= 0; i--) {
                    RemoteAnimationTarget target = appTargets[i];
                    SurfaceProperties builder = transaction.forSurface(target.leash);

                    if (target.screenSpaceBounds != null) {
                        tmpPos.set(target.screenSpaceBounds.left, target.screenSpaceBounds.top);
                    } else {
                        tmpPos.set(target.position.x, target.position.y);
                    }

                    if (target.localBounds != null) {
                        fallbackCrop.set(target.localBounds);
                        fallbackCrop.offsetTo(0, 0);
                    } else {
                        fallbackCrop.setEmpty();
                    }
                    if (target.mode == MODE_CLOSING) {
                        if (target.screenSpaceBounds != null) {
                            tmpRect.set(target.screenSpaceBounds);
                        } else {
                            tmpRect.set(tmpPos.x, tmpPos.y,
                                    tmpPos.x + fallbackCrop.width(),
                                    tmpPos.y + fallbackCrop.height());
                        }
                        if ((rotationChange % 2) != 0) {
                            final int right = fallbackCrop.right;
                            fallbackCrop.right = fallbackCrop.bottom;
                            fallbackCrop.bottom = right;
                        }
                        matrix.setScale(mScale.value, mScale.value,
                                tmpRect.centerX(),
                                tmpRect.centerY());
                        matrix.postTranslate(0, mDy.value);
                        matrix.postTranslate(tmpPos.x, tmpPos.y);
                        builder.setMatrix(matrix)
                                .setWindowCrop(fallbackCrop)
                                .setAlpha(mAlpha.value)
                                .setCornerRadius(windowCornerRadius)
                                .setShadowRadius(mShadowRadius.value);
                    } else if (target.mode == MODE_OPENING) {
                        matrix.setTranslate(tmpPos.x, tmpPos.y);
                        builder.setMatrix(matrix)
                                .setWindowCrop(fallbackCrop)
                                .setAlpha(1f);
                    }
                }
                surfaceApplier.scheduleApply(transaction);
            }
        });

        return closingAnimator;
    }

    private boolean isFreeformAnimation(RemoteAnimationTarget[] appTargets) {
        return DesktopModeStatus.canEnterDesktopMode(mLauncher.getApplicationContext())
                && DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_EXIT_TRANSITIONS_BUGFIX.isTrue()
                && Arrays.stream(appTargets)
                        .anyMatch(app -> app.taskInfo != null && app.taskInfo.isFreeform());
    }

    private void addCujInstrumentation(Animator anim, int cuj) {
        anim.addListener(getCujAnimationSuccessListener(cuj, /* cujPreStartCallback= */null));
    }

    private void addCujInstrumentation(Animator anim, int cuj, Runnable cujPreStartCallback) {
        anim.addListener(getCujAnimationSuccessListener(cuj, cujPreStartCallback));
    }

    private void addCujInstrumentation(RectFSpringAnim anim, int cuj) {
        anim.addAnimatorListener(
                getCujAnimationSuccessListener(cuj, /* cujPreStartCallback= */null));
    }

    private AnimationSuccessListener getCujAnimationSuccessListener(
            int cuj, Runnable cujPreStartCallback) {
        return new AnimationSuccessListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                mDragLayer.getViewTreeObserver().addOnDrawListener(
                        new ViewTreeObserver.OnDrawListener() {
                            boolean mHandled = false;

                            @Override
                            public void onDraw() {
                                if (mHandled) {
                                    return;
                                }
                                mHandled = true;
                                if (cujPreStartCallback != null) {
                                    cujPreStartCallback.run();
                                }
                                InteractionJankMonitorWrapper.begin(mDragLayer, cuj);

                                mDragLayer.post(() ->
                                        mDragLayer.getViewTreeObserver().removeOnDrawListener(
                                                this));
                            }
                        });
                super.onAnimationStart(animation);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                super.onAnimationCancel(animation);
                InteractionJankMonitorWrapper.cancel(cuj);
            }

            @Override
            public void onAnimationSuccess(Animator animator) {
                InteractionJankMonitorWrapper.end(cuj);
            }
        };
    }

    /**
     * Creates the {@link RectFSpringAnim} and {@link AnimatorSet} required to animate
     * the transition.
     */
    @NonNull
    public BackAnimState createWallpaperOpenAnimations(
            RemoteAnimationTarget[] appTargets,
            RemoteAnimationTarget[] wallpapers,
            RemoteAnimationTarget[] nonAppTargets,
            RectF startRect,
            float startWindowCornerRadius,
            boolean fromPredictiveBack) {
        InteractionBoost.requestForAnimation(mDragLayer);
        RectF islandTarget = null;
        if (!fromPredictiveBack && !shouldPlayFallbackClosingAnimation(appTargets)
                && getRotationChange(appTargets) == 0) {
            for (RemoteAnimationTarget target : appTargets) {
                if (target.mode == MODE_CLOSING) {
                    islandTarget = LunarisBarAnimation.getTarget(mLauncher, target);
                    break;
                }
            }
        }
        View launcherView = findLauncherView(appTargets);
        if (mCosAppAnimations && islandTarget == null && launcherView != null
                && launcherView.getTag() instanceof ItemInfo info
                && info.shouldUseBackgroundAnimation()) {
            // Try to create a return animation
            RunnableList onEndCallback = new RunnableList();
            WindowAnimationState windowState = new WindowAnimationState();
            windowState.bounds = startRect;
            windowState.bottomLeftRadius = windowState.bottomRightRadius =
                    windowState.topLeftRadius = windowState.topRightRadius =
                            startWindowCornerRadius;
            ContainerAnimationRunner runner = ContainerAnimationRunner.fromView(
                    launcherView, false /* forLaunch */, mLauncher, mStartingWindowListener,
                    onEndCallback, windowState);
            if (runner != null) {
                runner.startAnimation(TRANSIT_CLOSE,
                        appTargets, wallpapers, nonAppTargets,
                        new IRemoteAnimationFinishedCallback.Stub() {
                            @Override
                            public void onAnimationFinished() {
                                onEndCallback.executeAllAndDestroy();
                            }
                        });
                return new AlreadyStartedBackAnimState(onEndCallback);
            }
        }

        AnimatorSet anim = new AnimatorSet();
        RectFSpringAnim rectFSpringAnim = null;

        final boolean launcherIsForceInvisibleOrOpening = mLauncher.isForceInvisible()
                || launcherIsATargetWithMode(appTargets, MODE_OPENING);

        boolean playFallBackAnimation = (islandTarget == null && launcherView == null
                && launcherIsForceInvisibleOrOpening)
                || mLauncher.getWorkspace().isOverlayShown()
                || shouldPlayFallbackClosingAnimation(appTargets);

        boolean playWorkspaceReveal = true;
        boolean skipAllAppsScale = false;
        if (!mCosAppAnimations && islandTarget == null
                && (playFallBackAnimation || mLauncher.isInState(OVERVIEW))) {
            rectFSpringAnim = getClosingWindowAnimators(anim, appTargets,
                    playFallBackAnimation ? launcherView : null, new PointF(),
                    startRect, startWindowCornerRadius);
            playWorkspaceReveal = !mLauncher.isInState(OVERVIEW);
        } else if (mLauncher.isInState(OVERVIEW)) {
            playWorkspaceReveal = false;
        } else if (!playFallBackAnimation) {
            rectFSpringAnim = islandTarget == null
                    ? getClosingWindowAnimators(anim, appTargets, launcherView, new PointF(),
                            startRect, startWindowCornerRadius)
                    : getClosingWindowAnimators(anim, appTargets, launcherView, new PointF(),
                            startRect, startWindowCornerRadius, islandTarget);
            if (mLauncher.isInState(LauncherState.ALL_APPS)) {
                // Skip scaling all apps, otherwise FloatingIconView will get wrong
                // layout bounds.
                skipAllAppsScale = true;
            } else {
                anim.play(mCosAppAnimations
                        ? new CosWorkspaceRevealAnim(mLauncher, rectFSpringAnim,
                                rectFSpringAnim.getTargetRect(), !fromPredictiveBack, true)
                                .getAnimators()
                        : new ScalingWorkspaceRevealAnim(mLauncher, rectFSpringAnim,
                                rectFSpringAnim.getTargetRect(),
                                !fromPredictiveBack /* playAlphaReveal */,
                                true /* playBlur */).getAnimators());

                // We play StaggeredWorkspaceAnim as a part of the closing window animation.
                playWorkspaceReveal = false;
            }
        } else {
            anim.play(getFallbackClosingWindowAnimators(appTargets));
        }

        AnimatorListenerAdapter endListener = new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                AccessibilityManagerCompat.sendTestProtocolEventToTest(
                        mLauncher, WALLPAPER_OPEN_ANIMATION_FINISHED_MESSAGE);
            }
        };
        if (rectFSpringAnim != null) {
            rectFSpringAnim.addAnimatorListener(endListener);
        } else {
            anim.addListener(endListener);
        }

        // Normally, we run the launcher content animation when we are transitioning
        // home, but if home is already visible, then we don't want to animate the
        // contents of launcher unless we know that we are animating home as a result
        // of the home button press with quickstep, which will result in launcher being
        // started on touch down, prior to the animation home (and won't be in the
        // targets list because it is already visible). In that case, we force
        // invisibility on touch down, and only reset it after the animation to home
        // is initialized.
        if (launcherIsForceInvisibleOrOpening) {
            if (rectFSpringAnim != null && anim.getChildAnimations().isEmpty()) {
                addCujInstrumentation(rectFSpringAnim, Cuj.CUJ_LAUNCHER_APP_CLOSE_TO_HOME);
            } else {
                if (isFreeformAnimation(appTargets)) {
                    addCujInstrumentation(
                            anim,
                            Cuj.CUJ_DESKTOP_MODE_EXIT_MODE_ON_LAST_WINDOW_CLOSE,
                            /* cujPreStartCallback= */ () -> {
                                mLatencyTracker.onActionEnd(
                                        ACTION_DESKTOP_MODE_EXIT_MODE_ON_LAST_WINDOW_CLOSE);
                            });
                }
                addCujInstrumentation(anim, playFallBackAnimation
                        ? Cuj.CUJ_LAUNCHER_APP_CLOSE_TO_HOME_FALLBACK
                        : Cuj.CUJ_LAUNCHER_APP_CLOSE_TO_HOME);
            }

            // Only register the content animation for cancellation when state changes
            if (!mCosAppAnimations) mLauncher.getStateManager().setCurrentAnimation(anim);

            if (mLauncher.isInState(LauncherState.ALL_APPS)) {
                Pair<AnimatorSet, Runnable> contentAnimator =
                        getLauncherContentAnimator(false, LAUNCHER_RESUME_START_DELAY,
                                skipAllAppsScale);
                anim.play(contentAnimator.first);
                anim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        contentAnimator.second.run();
                    }
                });
            } else if (playWorkspaceReveal) {
                anim.play(mCosAppAnimations
                        ? new CosWorkspaceRevealAnim(mLauncher, null, null, true, true)
                                .getAnimators()
                        : new WorkspaceRevealAnim(mLauncher, false).getAnimators());
            }
        }

        return new AnimatorBackState(rectFSpringAnim, anim, !mCosAppAnimations);
    }

    /** Get animation duration for taskbar for going to home. */
    public static int getTaskbarToHomeDuration(
            boolean isPersistentTaskbarAndNotInDesktopMode) {
        return getTaskbarToHomeDuration(false, isPersistentTaskbarAndNotInDesktopMode);
    }

    /**
     * Get animation duration for taskbar for going to home.
     *
     * @param shouldOverrideToFastAnimation should overwrite scaling reveal home animation duration
     */
    public static int getTaskbarToHomeDuration(boolean shouldOverrideToFastAnimation,
            boolean isPersistentTaskbarAndNotInDesktopMode) {
        if (isPersistentTaskbarAndNotInDesktopMode) {
            return PINNED_TASKBAR_TRANSITION_DURATION;
        } else if (!shouldOverrideToFastAnimation) {
            return TASKBAR_TO_HOME_DURATION_SLOW;
        } else {
            return TASKBAR_TO_HOME_DURATION_FAST;
        }
    }

    /**
     * Remote animation runner for animation from the app to Launcher, including recents.
     */
    protected class WallpaperOpenLauncherAnimationRunner implements RemoteAnimationFactory {

        private BackAnimState mBackAnimState;

        @Override
        public void onAnimationStart(int transit,
                RemoteAnimationTarget[] appTargets,
                RemoteAnimationTarget[] wallpaperTargets,
                RemoteAnimationTarget[] nonAppTargets,
                LauncherAnimationRunner.AnimationResult result) {
            onAnimationCancelled();
            if (mLauncher.isDestroyed()) {
                AnimatorSet anim = new AnimatorSet();
                anim.play(getFallbackClosingWindowAnimators(appTargets));
                applyAnimation(new AnimatorBackState(null, anim, !mCosAppAnimations), result);
                return;
            }

            if (mLauncher.hasSomeInvisibleFlag(PENDING_INVISIBLE_BY_WALLPAPER_ANIMATION)) {
                mLauncher.addForceInvisibleFlag(INVISIBLE_BY_PENDING_FLAGS);
                mLauncher.getStateManager().moveToRestState();
            }

            RectF windowTargetBounds =
                    new RectF(getWindowTargetBounds(appTargets, getRotationChange(appTargets)));

            final RectF resolveRectF = new RectF(windowTargetBounds);
            for (RemoteAnimationTarget t : appTargets) {
                if (t.mode == MODE_CLOSING) {
                    transferRectToTargetCoordinate(
                            t, windowTargetBounds, true, resolveRectF);
                    break;
                }
            }

            BackAnimState backAnimState = createWallpaperOpenAnimations(
                    appTargets, wallpaperTargets, nonAppTargets, resolveRectF,
                    QuickStepContract.getWindowCornerRadius(mLauncher),
                    false /* fromPredictiveBack */);

            TaskViewUtils.createSplitAuxiliarySurfacesAnimator(nonAppTargets, false, null);
            mLauncher.clearForceInvisibleFlag(INVISIBLE_ALL);
            applyAnimation(backAnimState, result);
        }

        private void applyAnimation(BackAnimState state,
                LauncherAnimationRunner.AnimationResult result) {
            if (mCosAppAnimations) {
                mBackAnimState = state;
                state.addOnAnimCompleteCallback(() -> {
                    if (mBackAnimState == state) mBackAnimState = null;
                });
            }
            state.applyToAnimationResult(result, mLauncher);
        }

        @Override
        public void onAnimationCancelled() {
            BackAnimState state = mBackAnimState;
            mBackAnimState = null;
            if (state != null) state.cancel();
        }
    }

    /**
     * Remote animation runner for animation to launch an app.
     */
    private class AppLaunchAnimationRunner implements RemoteAnimationFactory {

        private final View mV;
        private final RunnableList mOnEndCallback;
        private AnimatorSet mCosAnimation;
        private RectFSpringAnim mNothingSpring;
        private AnimatorSet mNothingAnimation;
        private final LaunchPreview mPreview;
        private boolean mCancelled;

        AppLaunchAnimationRunner(View v, RunnableList onEndCallback) {
            mV = v;
            mOnEndCallback = onEndCallback;
            mPreview = mImmediatePreview != null && mImmediatePreview.source == v
                    ? mImmediatePreview : null;
        }

        @Override
        public void onAnimationStart(int transit,
                RemoteAnimationTarget[] appTargets,
                RemoteAnimationTarget[] wallpaperTargets,
                RemoteAnimationTarget[] nonAppTargets,
                LauncherAnimationRunner.AnimationResult result) {
            if (mCancelled || mLauncher.isDestroyed()) {
                if (mPreview != null) mPreview.remove();
                result.setAnimation(null, mLauncher, mOnEndCallback::executeAllAndDestroy, false);
                return;
            }
            if (!mCosAppAnimations && (!ValueAnimator.areAnimatorsEnabled()
                    || !(mV.getParent() instanceof ViewGroup)
                    || RemoveAnimationSettingsTracker.INSTANCE.get(mLauncher)
                            .isRemoveAnimationEnabled())) {
                result.setAnimation(null, mLauncher, mOnEndCallback::executeAllAndDestroy, false);
                return;
            }
            if (mCosAnimation != null) mCosAnimation.cancel();
            InteractionBoost.requestForAnimation(mDragLayer);
            AnimatorSet anim = new AnimatorSet();
            if (mCosAppAnimations) {
                mCosAnimation = anim;
                anim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (mCosAnimation == anim) mCosAnimation = null;
                    }
                });
            }
            boolean launcherClosing =
                    launcherIsATargetWithMode(appTargets, MODE_CLOSING);

            final boolean launchingFromWidget = mV instanceof LauncherAppWidgetHostView;
            final boolean launchingFromRecents = isLaunchingFromRecents(mV, appTargets);
            final boolean skipFirstFrame;
            RectFSpringAnim windowSpring = null;
            boolean continuePreview = mPreview != null && !mPreview.removed
                    && !launchingFromWidget && !launchingFromRecents
                    && getRotationChange(appTargets) == 0
                    && !areAllTargetsTranslucent(appTargets)
                    && mPreview.matchesWindow(appTargets);
            if (mPreview != null) {
                if (!continuePreview) mPreview.remove();
                else mPreview.restoreSource();
            }
            final long previewPlayTime = continuePreview ? mPreview.elapsed() : 0;
            if (!mCosAppAnimations && !launchingFromRecents) {
                if (mNothingSpring != null) mNothingSpring.cancel();
                if (mNothingAnimation != null) mNothingAnimation.cancel();
                windowSpring = composeNothingLaunchAnimator(anim, mV, appTargets,
                        wallpaperTargets, nonAppTargets, launcherClosing);
                mNothingSpring = windowSpring;
                mNothingAnimation = anim;
                RectFSpringAnim currentSpring = windowSpring;
                windowSpring.addAnimatorListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator ignored) {
                        if (mNothingSpring == currentSpring) {
                            mNothingSpring = null;
                            mNothingAnimation = null;
                        }
                    }
                });
                addCujInstrumentation(windowSpring, launchingFromWidget
                        ? Cuj.CUJ_LAUNCHER_APP_LAUNCH_FROM_WIDGET
                        : Cuj.CUJ_LAUNCHER_APP_LAUNCH_FROM_ICON);
                skipFirstFrame = launchingFromWidget;
            } else if (launchingFromWidget) {
                composeCosWidgetLaunchAnimator(anim, (LauncherAppWidgetHostView) mV, appTargets,
                        wallpaperTargets, nonAppTargets, launcherClosing);
                addCujInstrumentation(anim, Cuj.CUJ_LAUNCHER_APP_LAUNCH_FROM_WIDGET);
                skipFirstFrame = true;
            } else if (launchingFromRecents) {
                composeRecentsLaunchAnimator(anim, mV, appTargets, wallpaperTargets, nonAppTargets,
                        launcherClosing);
                addCujInstrumentation(
                        anim, Cuj.CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS);
                skipFirstFrame = true;
            } else {
                composeCosIconLaunchAnimator(anim, mV, appTargets, wallpaperTargets, nonAppTargets,
                        launcherClosing, continuePreview ? mPreview : null, previewPlayTime);
                addCujInstrumentation(anim, Cuj.CUJ_LAUNCHER_APP_LAUNCH_FROM_ICON);
                skipFirstFrame = false;
            }

            if (launcherClosing) {
                anim.addListener(mForceInvisibleListener);
            }

            // Syncs the app launch animation and taskbar stash animation (if exists).
            if (syncAppLaunchWithTaskbarStash()) {
                TaskbarInteractor taskbarInteractor = mLauncher.getTaskbarInteractor();
                if (taskbarInteractor != null) {
                    taskbarInteractor.setIgnoreInAppFlagForSync(false);

                    if (launcherClosing) {
                        taskbarInteractor.createAnimToAppAndPlay(anim);
                    }
                }
            }

            result.setAnimation(anim, mLauncher, mOnEndCallback::executeAllAndDestroy,
                    skipFirstFrame, windowSpring);
            if (continuePreview && anim.isStarted()) {
                anim.setCurrentPlayTime(Math.min(previewPlayTime,
                        Math.max(0, anim.getTotalDuration() - 1)));
                mPreview.handOff();
            }
        }

        @Override
        public void onAnimationCancelled() {
            mCancelled = true;
            if (mCosAnimation != null) mCosAnimation.cancel();
            if (mNothingAnimation != null) mNothingAnimation.cancel();
            if (mNothingSpring != null) mNothingSpring.cancel();
            mOnEndCallback.executeAllAndDestroy();
        }
    }

    /** Remote animation runner to launch an app using System UI's animation library. */
    private static class ContainerAnimationRunner implements RemoteAnimationFactory {

        /** The delegate runner that handles the actual animation. */
        private final RemoteAnimationDelegate<IRemoteAnimationFinishedCallback> mDelegate;

        private ContainerAnimationRunner(
                RemoteAnimationDelegate<IRemoteAnimationFinishedCallback> delegate) {
            mDelegate = delegate;
        }

        @Nullable
        static ContainerAnimationRunner fromView(
                View v,
                boolean forLaunch,
                Launcher launcher,
                StartingWindowListener startingWindowListener,
                RunnableList onEndCallback,
                @Nullable WindowAnimationState windowState) {
            // First the controller is created. This is used by the runner to animate the
            // origin/target view.
            ActivityTransitionAnimator.Controller controller =
                    buildController(v, forLaunch, windowState);
            if (controller == null) {
                return null;
            }

            // The callback is used to make sure that we use the right color to fade between view
            // and the window.
            ActivityTransitionAnimator.Callback callback = task -> {
                final int backgroundColor =
                        startingWindowListener.mBackgroundColor == Color.TRANSPARENT
                                ? launcher.getScrimView().getBackgroundColor()
                                : startingWindowListener.mBackgroundColor;
                return ColorUtils.setAlphaComponent(backgroundColor, 255);
            };

            ActivityTransitionAnimator.Listener listener =
                    new ActivityTransitionAnimator.Listener() {
                        @Override
                        public void onTransitionAnimationStart() {
                            InteractionBoost.requestForAnimation(launcher.getDragLayer());
                        }

                        @Override
                        public void onTransitionAnimationEnd() {
                            onEndCallback.executeAllAndDestroy();
                        }
                    };

            return new ContainerAnimationRunner(
                    new ActivityTransitionAnimator.LegacyAnimationDelegate(
                            MAIN_EXECUTOR, controller, callback, listener));
        }

        /**
         * Constructs a {@link ActivityTransitionAnimator.Controller} that can be used by a
         * {@link ContainerAnimationRunner} to animate a view into an opening window or from a
         * closing one.
         */
        @Nullable
        private static ActivityTransitionAnimator.Controller buildController(
                View v, boolean isLaunching, @Nullable WindowAnimationState windowState) {
            View viewToUse = findLaunchableViewWithBackground(v);
            if (viewToUse == null) {
                return null;
            }

            // The CUJ is logged by the click handler, so we don't log it inside the animation
            // library. TODO: figure out return CUJ.
            ActivityTransitionAnimator.Controller controllerDelegate =
                    ActivityTransitionAnimator.Controller.fromView(viewToUse, null /* cujType */);

            if (controllerDelegate == null) {
                return null;
            }

            // This wrapper allows us to override the default value, telling the controller that the
            // current window is below the animating window as well as information about the return
            // animation.
            return new DelegateTransitionAnimatorController(controllerDelegate) {
                @Override
                public boolean isLaunching() {
                    return isLaunching;
                }

                @Override
                public boolean isBelowAnimatingWindow() {
                    return true;
                }

                @Nullable
                @Override
                public WindowAnimationState getWindowAnimatorState() {
                    return windowState;
                }
            };
        }

        /**
         * Finds the closest parent of [view] (inclusive) that implements {@link LaunchableView} and
         * has a background drawable.
         */
        @Nullable
        private static <T extends View & LaunchableView> T findLaunchableViewWithBackground(
                View view) {
            View current = view;
            while (current.getBackground() == null || !(current instanceof LaunchableView)) {
                if (current.getParent() instanceof View v) {
                    current = v;
                } else {
                    return null;
                }
            }
            return (T) current;
        }

        @Override
        public void onAnimationStart(int transit, RemoteAnimationTarget[] appTargets,
                RemoteAnimationTarget[] wallpaperTargets, RemoteAnimationTarget[] nonAppTargets,
                LauncherAnimationRunner.AnimationResult result) {
            startAnimation(
                    transit, appTargets, wallpaperTargets, nonAppTargets, result);
        }

        public void startAnimation(int transit, RemoteAnimationTarget[] appTargets,
                RemoteAnimationTarget[] wallpaperTargets, RemoteAnimationTarget[] nonAppTargets,
                IRemoteAnimationFinishedCallback result) {
            mDelegate.onAnimationStart(
                    transit, appTargets, wallpaperTargets, nonAppTargets, result);
        }

        @Override
        public void onAnimationCancelled() {
            mDelegate.onAnimationCancelled();
        }
    }

    /**
     * Class that holds all the variables for the app open animation.
     */
    static class AnimOpenProperties {

        public final int cropCenterXStart;
        public final int cropCenterYStart;
        public final int cropWidthStart;
        public final int cropHeightStart;

        public final int cropCenterXEnd;
        public final int cropCenterYEnd;
        public final int cropWidthEnd;
        public final int cropHeightEnd;

        public final float dX;
        public final float dY;

        public final float initialAppIconScale;
        public final float finalAppIconScale;

        public final float iconAlphaStart;

        AnimOpenProperties(Resources r, DeviceProfile dp, Rect windowTargetBounds,
                RectF launcherIconBounds, View view, int dragLayerLeft, int dragLayerTop,
                boolean hasSplashScreen, boolean hasDifferentAppIcon) {
            // Scale the app icon to take up the entire screen. This simplifies the math when
            // animating the app window position / scale.
            float smallestSize = Math.min(windowTargetBounds.height(), windowTargetBounds.width());
            float maxScaleX = smallestSize / launcherIconBounds.width();
            float maxScaleY = smallestSize / launcherIconBounds.height();
            float iconStartScale = 1f;
            if (view instanceof BubbleTextView && !(view.getParent() instanceof DeepShortcutView)) {
                Drawable dr = ((BubbleTextView) view).getIcon();
                if (dr instanceof FastBitmapDrawable) {
                    iconStartScale = ((FastBitmapDrawable) dr).getAnimatedScale();
                }
            }

            initialAppIconScale = iconStartScale;
            finalAppIconScale = Math.max(maxScaleX, maxScaleY);

            // Animate the app icon to the center of the window bounds in screen coordinates.
            float centerX = windowTargetBounds.centerX() - dragLayerLeft;
            float centerY = windowTargetBounds.centerY() - dragLayerTop;

            dX = centerX - launcherIconBounds.centerX();
            dY = centerY - launcherIconBounds.centerY();

            iconAlphaStart = hasSplashScreen && !hasDifferentAppIcon ? 0 : 1f;

            final int windowIconSize = ResourceUtils.getDimenByName("starting_surface_icon_size",
                    r, 108);

            cropCenterXStart = windowTargetBounds.centerX();
            cropCenterYStart = windowTargetBounds.centerY();

            cropWidthStart = windowIconSize;
            cropHeightStart = windowIconSize;

            cropWidthEnd = windowTargetBounds.width();
            cropHeightEnd = windowTargetBounds.height();

            cropCenterXEnd = windowTargetBounds.centerX();
            cropCenterYEnd = windowTargetBounds.centerY();
        }
    }

    private static class StartingWindowListener extends IStartingWindowListener.Stub {
        private final WeakReference<QuickstepTransitionManager> mTransitionManagerRef;
        private int mBackgroundColor;

        private StartingWindowListener(QuickstepTransitionManager transitionManager) {
            mTransitionManagerRef = new WeakReference<>(transitionManager);
        }

        @Override
        public void onTaskLaunching(int taskId, int supportedType, int color) {
            QuickstepTransitionManager transitionManager = mTransitionManagerRef.get();
            if (transitionManager != null) {
                transitionManager.mTaskStartParams.put(taskId, Pair.create(supportedType, color));
            }
            mBackgroundColor = color;
        }
    }

    /**
     * Transfer the rectangle to another coordinate if needed.
     *
     * @param toLauncher which one is the anchor of this transfer, if true then transfer from
     *                   animation target to launcher, false transfer from launcher to animation
     *                   target.
     */
    public void transferRectToTargetCoordinate(RemoteAnimationTarget target, RectF currentRect,
            boolean toLauncher, RectF resultRect) {
        mCoordinateTransfer.transferRectToTargetCoordinate(
                target, currentRect, toLauncher, resultRect);
    }

    private static class RemoteAnimationCoordinateTransfer {
        private final QuickstepLauncher mLauncher;
        private final Rect mDisplayRect = new Rect();
        private final Rect mTmpResult = new Rect();

        RemoteAnimationCoordinateTransfer(QuickstepLauncher launcher) {
            mLauncher = launcher;
        }

        void transferRectToTargetCoordinate(RemoteAnimationTarget target, RectF currentRect,
                boolean toLauncher, RectF resultRect) {
            final int taskRotation = target.windowConfiguration.getRotation();
            final DeviceProfile profile = mLauncher.getDeviceProfile();
            final int rotation = profile.getDeviceProperties().getRotationHint();
            final int widthPx = profile.getDeviceProperties().getWidthPx();
            final int heightPx = profile.getDeviceProperties().getHeightPx();

            final int rotationDelta = toLauncher
                    ? android.util.RotationUtils.deltaRotation(taskRotation, rotation)
                    : android.util.RotationUtils.deltaRotation(rotation, taskRotation);
            if (rotationDelta != ROTATION_0) {
                // Get original display size when task is on top but with different rotation
                if (rotationDelta % 2 != 0 && toLauncher && (rotation == ROTATION_0
                        || rotation == ROTATION_180)) {
                    mDisplayRect.set(0, 0, heightPx, widthPx);
                } else {
                    mDisplayRect.set(0, 0, widthPx, heightPx);
                }
                currentRect.round(mTmpResult);
                android.util.RotationUtils.rotateBounds(mTmpResult, mDisplayRect, rotationDelta);
                resultRect.set(mTmpResult);
            } else {
                resultRect.set(currentRect);
            }
        }
    }

    /**
     * RectFSpringAnim update listener to be used for app to home animation.
     */
    private class SpringAnimRunner implements RectFSpringAnim.OnUpdateListener {
        private final int mMode;
        private final RemoteAnimationTarget[] mAppTargets;
        private final Matrix mMatrix = new Matrix();
        private final Point mTmpPos = new Point();
        private final RectF mCurrentRectF = new RectF();
        private final float mStartRadius;
        private final float mEndRadius;
        private final SurfaceTransactionApplier mSurfaceApplier;
        private final Rect mWindowStartBounds = new Rect();
        private final Rect mWindowOriginalBounds = new Rect();
        private final RectF mWidgetSourceBounds = new RectF();
        private final RectF mWidgetCropBounds = new RectF();

        private float mLastCornerRadius = -1f;
        private final Rect mTmpRect = new Rect();

        /**
         * Constructor for SpringAnimRunner
         *
         * @param appTargets                the list of opening/closing apps
         * @param targetRect                target rectangle
         * @param closingWindowStartRect    start position of the window when the spring animation
         *                                  is started. In the predictive back to home case this
         *                                  will be smaller than closingWindowOriginalRect because
         *                                  the window is already scaled by the user gesture
         * @param closingWindowOriginalRect Original unscaled window rect
         * @param startWindowCornerRadius   corner radius of window at the start position
         */
        SpringAnimRunner(RemoteAnimationTarget[] appTargets, RectF targetRect,
                Rect closingWindowStartRect, Rect closingWindowOriginalRect,
                float startWindowCornerRadius) {
            this(appTargets, closingWindowStartRect, closingWindowOriginalRect,
                    startWindowCornerRadius, Math.max(1, targetRect.width()) / 2f, MODE_CLOSING,
                    new SurfaceTransactionApplier(mDragLayer));
        }

        SpringAnimRunner(RemoteAnimationTarget[] appTargets, Rect startBounds, Rect originalBounds,
                float startRadius, float endRadius, int mode, SurfaceTransactionApplier applier) {
            mMode = mode;
            mAppTargets = appTargets;
            mStartRadius = startRadius;
            mEndRadius = endRadius;
            mSurfaceApplier = applier;
            mWindowStartBounds.set(startBounds);
            mWindowOriginalBounds.set(originalBounds);

            // transfer the coordinate based on animation target.
            if (mAppTargets != null) {
                for (RemoteAnimationTarget t : mAppTargets) {
                    if (t.mode == mMode) {
                        final RectF transferRect = new RectF(mWindowStartBounds);
                        final RectF result = new RectF();
                        transferRectToTargetCoordinate(t, transferRect, false, result);
                        result.round(mWindowStartBounds);

                        transferRect.set(originalBounds);
                        transferRectToTargetCoordinate(t, transferRect, false, result);
                        result.round(mWindowOriginalBounds);
                        break;
                    }
                }
            }
        }

        private static final Interpolator CORNER_LEAD_INTERPOLATOR =
        new PathInterpolator(0.05f, 0f, 0.1f, 1f);

        public float getCornerRadius(float progress) {
            float curvedProgress = mCosAppAnimations
                    ? CORNER_LEAD_INTERPOLATOR.getInterpolation(progress) : progress;
            return mStartRadius + curvedProgress * (mEndRadius - mStartRadius);
        }

        protected boolean isWidget() {
            return false;
        }

        @Override
        public void onUpdate(RectF currentRectF, float progress) {
            SurfaceTransaction transaction = new SurfaceTransaction();
            for (int i = mAppTargets.length - 1; i >= 0; i--) {
                RemoteAnimationTarget target = mAppTargets[i];
                SurfaceProperties builder = transaction.forSurface(target.leash);

                if (target.localBounds != null) {
                    mTmpPos.set(target.localBounds.left, target.localBounds.top);
                } else {
                    mTmpPos.set(target.position.x, target.position.y);
                }

                if (target.mode == mMode) {
                    transferRectToTargetCoordinate(target, currentRectF, false, mCurrentRectF);

                    // Scale the target window to match the currentRectF.
                    final float scale;

                    // We need to infer the crop (we crop the window to match the currentRectF).
                    if (!mCosAppAnimations) {
                        mWidgetSourceBounds.set(0f, 0f, mWindowOriginalBounds.width(),
                                mWindowOriginalBounds.height());
                        scale = NothingAppAnimation.updateWindowCrop(mWidgetSourceBounds,
                                mCurrentRectF, mMatrix, mWidgetCropBounds);
                        if (scale <= 0f) continue;
                        mWidgetCropBounds.round(mTmpRect);
                    } else if (isWidget()) {
                        mWidgetSourceBounds.set(0f, 0f, mWindowOriginalBounds.width(),
                                mWindowOriginalBounds.height());
                        scale = WidgetAnimationUtils.updateWindowCrop(mWidgetSourceBounds,
                                mCurrentRectF, mMatrix, mWidgetCropBounds);
                        if (scale <= 0f) {
                            continue;
                        }
                        mWidgetCropBounds.round(mTmpRect);
                    } else if (mWindowStartBounds.height() > mWindowStartBounds.width()) {
                        scale = Math.min(1f, mCurrentRectF.width() / mWindowOriginalBounds.width());

                        int unscaledHeight = (int) (mCurrentRectF.height() * (1f / scale));
                        mTmpRect.set(0, 0, mWindowOriginalBounds.width(), unscaledHeight);
                    } else {
                        scale = Math.min(1f, mCurrentRectF.height()
                                / mWindowOriginalBounds.height());

                        int unscaledWidth = (int) (mCurrentRectF.width() * (1f / scale));
                        mTmpRect.set(0, 0, unscaledWidth, mWindowOriginalBounds.height());
                    }

                    // Match size and position of currentRect.
                    if (mCosAppAnimations && !isWidget()) {
                        mMatrix.setScale(scale, scale);
                        mMatrix.postTranslate(mCurrentRectF.left, mCurrentRectF.top);
                    }

                    builder.setMatrix(mMatrix)
                            .setWindowCrop(mTmpRect)
                            .setAlpha(getWindowAlpha(progress));
                    if (!mCosAppAnimations) builder.setShow();

                    float cornerRadius = getCornerRadius(progress) / scale;
                    if (!mCosAppAnimations
                            || Math.abs(mLastCornerRadius - cornerRadius) >= 4f || progress >= 1f) {
                        builder.setCornerRadius(cornerRadius);
                        mLastCornerRadius = cornerRadius;
                    }
                } else if (target.mode == (mMode == MODE_OPENING ? MODE_CLOSING : MODE_OPENING)) {
                    mMatrix.setTranslate(mTmpPos.x, mTmpPos.y);
                    builder.setMatrix(mMatrix)
                            .setAlpha(1f);
                }
            }
            updateParallelSurface(transaction, progress);
            mSurfaceApplier.scheduleApply(transaction);
        }

        protected void updateParallelSurface(SurfaceTransaction transaction, float progress) { }

        protected float getWindowAlpha(float progress) {
            if (!mCosAppAnimations) {
                return NothingAppAnimation.getWindowAlpha(progress, mMode == MODE_OPENING);
            }
            // Alpha interpolates between [1, 0] between progress values [start, end]
            final float start = 0f;
            final float end = 0.85f;

            if (progress <= start) {
                return 1f;
            }
            if (progress >= end) {
                return 0f;
            }
            return Utilities.mapToRange(progress, start, end, 1, 0, ACCELERATE_1_5);
        }
    }
}
