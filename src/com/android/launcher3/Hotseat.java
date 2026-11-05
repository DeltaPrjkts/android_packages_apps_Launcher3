/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static android.view.View.MeasureSpec.makeMeasureSpec;

import static com.android.launcher3.LauncherAnimUtils.VIEW_TRANSLATE_X;
import static com.android.launcher3.util.MultiTranslateDelegate.INDEX_BUBBLE_ADJUSTMENT_ANIM;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.CrossWindowBlurListeners;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.launcher3.ShortcutAndWidgetContainer.TranslationProvider;
import com.android.launcher3.celllayout.CellLayoutLayoutParams;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.LauncherPrefChangeListener;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.qsb.DockSearchWidgetHelper;
import com.android.launcher3.statemanager.StateManager.StateListener;
import com.android.launcher3.util.HorizontalInsettableView;
import com.android.launcher3.util.LauncherBindableItemsContainer.ItemOperator;
import com.android.launcher3.util.MultiPropertyFactory;
import com.android.launcher3.util.MultiPropertyFactory.MultiProperty;
import com.android.launcher3.util.MultiTranslateDelegate;
import com.android.launcher3.util.MultiValueAlpha;
import com.android.launcher3.views.ActivityContext;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.function.Consumer;

/**
 * View class that represents the bottom row of the home screen.
 */
public class Hotseat extends CellLayout implements Insettable {

    public static final int ALPHA_CHANNEL_TASKBAR_ALIGNMENT = 0;
    public static final int ALPHA_CHANNEL_PREVIEW_RENDERER = 1;
    public static final int ALPHA_CHANNEL_TASKBAR_STASH = 2;
    public static final int ALPHA_CHANNEL_ASSISTANT_VISIBILITY = 3;
    public static final int ALPHA_CHANNEL_STATE_VISIBILITY = 4;
    public static final int ALPHA_CHANNEL_CHANNELS_COUNT = 5;

    @Retention(RetentionPolicy.RUNTIME)
    @IntDef({ALPHA_CHANNEL_TASKBAR_ALIGNMENT, ALPHA_CHANNEL_PREVIEW_RENDERER,
            ALPHA_CHANNEL_TASKBAR_STASH, ALPHA_CHANNEL_ASSISTANT_VISIBILITY,
            ALPHA_CHANNEL_STATE_VISIBILITY})
    public @interface HotseatQsbAlphaId {
    }

    public static final int ICONS_TRANSLATION_X_NAV_BAR_ALIGNMENT = 0;
    public static final int ICONS_TRANSLATION_X_CHANNELS_COUNT = 1;

    @Retention(RetentionPolicy.RUNTIME)
    @IntDef({ICONS_TRANSLATION_X_NAV_BAR_ALIGNMENT})
    public @interface IconsTranslationX {
    }

    // Ratio of empty space, qsb should take up to appear visually centered.
    public static final float QSB_CENTER_FACTOR = .325f;
    private static final int BUBBLE_BAR_ADJUSTMENT_ANIMATION_DURATION_MS = 250;

    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mHasVerticalHotseat;
    private Workspace<?> mWorkspace;
    private boolean mSendTouchToWorkspace;
    private final MultiValueAlpha mIconsAlphaChannels;
    private MultiValueAlpha mQsbAlphaChannels;

    private @Nullable MultiProperty mQsbTranslationX;

    private final MultiPropertyFactory mIconsTranslationXFactory;

    private View mQsb;

    private BackgroundBlurDrawable mBackgroundBlurDrawable;
    private GradientDrawable mBackgroundTintDrawable;
    private final Rect mPillRect = new Rect();
    private final Rect mLastBlurBounds = new Rect();
    private float mLastCornerRadius = -1f;
    private int mLastBlurRadius = -1;
    private final Rect mTempCellRect1 = new Rect();
    private final Rect mTempCellRect2 = new Rect();
    private boolean mShowHotseatBg;
    private boolean mHotseatBlur;
    private int mBlurDepth;
    private int mOpacity;
    private boolean mCrossWindowBlurEnabled;
    private final Consumer<Boolean> mCrossWindowBlurListener = this::onCrossWindowBlurEnabledChanged;
    private final LauncherPrefChangeListener mPrefChangeListener = key -> updateBackgroundPreferences();
    private final StateListener<LauncherState> mStateListener = new StateListener<>() {
        @Override
        public void onStateTransitionStart(LauncherState toState) {
            invalidate();
        }

        @Override
        public void onStateTransitionComplete(LauncherState finalState) {
            invalidate();
        }
    };

    public Hotseat(Context context) {
        this(context, null);
    }

    public Hotseat(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public Hotseat(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mQsb = createQsbView(context);
        addView(mQsb);
        mIconsAlphaChannels = new MultiValueAlpha(getShortcutsAndWidgets(),
                ALPHA_CHANNEL_CHANNELS_COUNT) {
            @Override
            protected void apply(float value) {
                super.apply(value);
                invalidate();
            }
        };
        mIconsAlphaChannels.setUpdateVisibility(true);
        updateQsbAnimationChannels();
        mIconsTranslationXFactory = new MultiPropertyFactory<>(getShortcutsAndWidgets(),
                VIEW_TRANSLATE_X, ICONS_TRANSLATION_X_CHANNELS_COUNT, Float::sum);
        updateBackgroundPreferences();
    }

    private View createQsbView(Context context) {
        if (Utilities.isGSAEnabled(context)
                && LauncherPrefs.DOCK_SEARCH.get(context)
                && LauncherPrefs.COMPACT_SEARCH_BAR.get(context)) {
            return LayoutInflater.from(context).inflate(R.layout.search_container_hotseat_compact,
                    this, false);
        }
        if (!Utilities.showQSB(context)) {
            return LayoutInflater.from(context).inflate(R.layout.empty_view, this, false);
        }
        if (DockSearchWidgetHelper.isCustomWidgetEnabled(context)) {
            return LayoutInflater.from(context).inflate(
                    R.layout.search_container_hotseat_widget, this, false);
        }
        boolean useGoogleStyle = LauncherPrefs.get(context).get(LauncherPrefs.QSB_STYLE_GOOGLE);
        int qsbLayout = useGoogleStyle
                ? R.layout.search_container_hotseat_google
                : R.layout.search_container_hotseat;
        return LayoutInflater.from(context).inflate(qsbLayout, this, false);
    }

    private void updateQsbAnimationChannels() {
        if (mQsb instanceof Reorderable qsbReorderable) {
            mQsbTranslationX = qsbReorderable.getTranslateDelegate()
                    .getTranslationX(MultiTranslateDelegate.INDEX_NAV_BAR_ANIM);
        } else {
            mQsbTranslationX = null;
        }
        mQsbAlphaChannels = new MultiValueAlpha(mQsb, ALPHA_CHANNEL_CHANNELS_COUNT);
        mQsbAlphaChannels.setUpdateVisibility(true);
    }

    /** Provides translation X for hotseat icons for the channel. */
    public MultiProperty getIconsTranslationX(@IconsTranslationX int channelId) {
        return mIconsTranslationXFactory.get(channelId);
    }

    /** Provides translation X for hotseat Qsb. */
    @Nullable
    public MultiProperty getQsbTranslationX() {
        return mQsbTranslationX;
    }

    /**
     * Returns orientation specific cell X given invariant order in the hotseat
     */
    public int getCellXFromOrder(int rank) {
        return mHasVerticalHotseat ? 0 : rank;
    }

    /**
     * Returns orientation specific cell Y given invariant order in the hotseat
     */
    public int getCellYFromOrder(int rank) {
        return mHasVerticalHotseat ? (getCountY() - (rank + 1)) : 0;
    }

    boolean isHasVerticalHotseat() {
        return mHasVerticalHotseat;
    }

    public void resetLayout(boolean hasVerticalHotseat) {
        ActivityContext activityContext = ActivityContext.lookupContext(getContext());
        boolean bubbleBarEnabled = activityContext.isBubbleBarEnabled();
        boolean hasBubbles = activityContext.hasBubbles();
        removeAllViewsInLayout();
        mHasVerticalHotseat = hasVerticalHotseat;
        DeviceProfile dp = mActivity.getDeviceProfile();

        if (bubbleBarEnabled) {
            if (dp.shouldAdjustHotseatForBubbleBar(getContext(), hasBubbles)) {
                getShortcutsAndWidgets().setTranslationProvider(
                        cellX -> dp.getHotseatAdjustedTranslation(getContext(), cellX));
                if (mQsb instanceof HorizontalInsettableView) {
                    HorizontalInsettableView insettableQsb = (HorizontalInsettableView) mQsb;
                    final float insetFraction =
                            (float) dp.getWorkspaceIconProfile().getIconSizePx() / dp.hotseatQsbWidth;
                    // post this to the looper so that QSB has a chance to redraw itself, e.g.
                    // after device rotation
                    mQsb.post(() -> insettableQsb.setHorizontalInsets(insetFraction));
                }
            } else {
                getShortcutsAndWidgets().setTranslationProvider(null);
                if (mQsb instanceof HorizontalInsettableView) {
                    ((HorizontalInsettableView) mQsb).setHorizontalInsets(0);
                }
            }
        }

        resetCellSize(dp);
        if (hasVerticalHotseat) {
            setGridSize(1, dp.numShownHotseatIcons);
        } else {
            setGridSize(dp.numShownHotseatIcons, 1);
        }
    }

    /**
     * Adjust the hotseat icons for the bubble bar.
     *
     * <p>When the bubble bar becomes visible, if needed, this method animates the hotseat icons
     * to reduce the spacing between them and make room for the bubble bar. The QSB width is
     * animated as well to align with the hotseat icons.
     *
     * <p>When the bubble bar goes away, any adjustments that were previously made are reversed.
     */
    public void adjustForBubbleBar(boolean isBubbleBarVisible) {
        DeviceProfile dp = mActivity.getDeviceProfile();
        boolean shouldAdjust = isBubbleBarVisible
                && dp.shouldAdjustHotseatOrQsbForBubbleBar(getContext());
        boolean shouldAdjustHotseat = shouldAdjust
                && dp.shouldAlignBubbleBarWithHotseat();
        ShortcutAndWidgetContainer icons = getShortcutsAndWidgets();
        // update the translation provider for future layout passes of hotseat icons.
        if (shouldAdjustHotseat) {
            icons.setTranslationProvider(
                    cellX -> dp.getHotseatAdjustedTranslation(getContext(), cellX));
        } else {
            icons.setTranslationProvider(null);
        }
        AnimatorSet animatorSet = new AnimatorSet();
        for (int i = 0; i < icons.getChildCount(); i++) {
            View child = icons.getChildAt(i);
            if (child.getLayoutParams() instanceof CellLayoutLayoutParams lp) {
                float tx = shouldAdjustHotseat
                        ? dp.getHotseatAdjustedTranslation(getContext(), lp.getCellX()) : 0;
                if (child instanceof Reorderable) {
                    MultiTranslateDelegate mtd = ((Reorderable) child).getTranslateDelegate();
                    animatorSet.play(
                            mtd.getTranslationX(INDEX_BUBBLE_ADJUSTMENT_ANIM).animateToValue(tx));
                } else {
                    animatorSet.play(ObjectAnimator.ofFloat(child, VIEW_TRANSLATE_X, tx));
                }
            }
        }
        //TODO(b/381109832) refactor & simplify adjustment logic
        boolean shouldAdjustQsb =
                shouldAdjustHotseat || (shouldAdjust && dp.shouldAlignBubbleBarWithQSB());
        if (mQsb instanceof HorizontalInsettableView horizontalInsettableQsb) {
            final float currentInsetFraction = horizontalInsettableQsb.getHorizontalInsets();
            final float targetInsetFraction = shouldAdjustQsb
                    ? (float) dp.getWorkspaceIconProfile().getIconSizePx() / dp.hotseatQsbWidth : 0;
            ValueAnimator qsbAnimator =
                    ValueAnimator.ofFloat(currentInsetFraction, targetInsetFraction);
            qsbAnimator.addUpdateListener(animation -> {
                float insetFraction = (float) animation.getAnimatedValue();
                horizontalInsettableQsb.setHorizontalInsets(insetFraction);
            });
            animatorSet.play(qsbAnimator);
        }
        animatorSet.setDuration(BUBBLE_BAR_ADJUSTMENT_ANIMATION_DURATION_MS).start();
    }

    @Override
    protected int getTranslationXForCell(int cellX, int cellY) {
        TranslationProvider translationProvider = getShortcutsAndWidgets().getTranslationProvider();
        if (translationProvider == null) return 0;
        return (int) translationProvider.getTranslationX(cellX);
    }

    @Override
    public void setInsets(Rect insets) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        DeviceProfile grid = mActivity.getDeviceProfile();

        if (grid.isVerticalBarLayout()) {
            mQsb.setVisibility(View.GONE);
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            if (grid.isSeascape()) {
                lp.gravity = Gravity.LEFT;
                lp.width = grid.hotseatBarSizePx + insets.left;
            } else {
                lp.gravity = Gravity.RIGHT;
                lp.width = grid.hotseatBarSizePx + insets.right;
            }
        } else {
            mQsb.setVisibility(View.VISIBLE);
            lp.gravity = Gravity.BOTTOM;
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = grid.hotseatBarSizePx;
        }

        Rect padding = grid.getHotseatLayoutPadding(getContext());
        if (isCompactSearchBarEnabled() && !grid.isVerticalBarLayout()) {
            int compactSearchSpace = grid.getHotseatProfile().getQsbHeight()
                    + grid.hotseatQsbSpace;
            lp.height += compactSearchSpace;
            padding.top += compactSearchSpace;
        }
        setPadding(padding.left, padding.top, padding.right, padding.bottom);
        setLayoutParams(lp);
        InsettableFrameLayout.dispatchInsets(this, insets);
    }

    public void setWorkspace(Workspace<?> w) {
        mWorkspace = w;
        setCellLayoutContainer(w);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // We allow horizontal workspace scrolling from within the Hotseat. We do this by delegating
        // touch intercept the Workspace, and if it intercepts, delegating touch to the Workspace
        // for the remainder of the this input stream.
        int yThreshold = getMeasuredHeight() - getPaddingBottom();
        if (mWorkspace != null && ev.getY() <= yThreshold) {
            mSendTouchToWorkspace = mWorkspace.onInterceptTouchEvent(ev);
            return mSendTouchToWorkspace;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // See comment in #onInterceptTouchEvent
        if (mSendTouchToWorkspace) {
            final int action = event.getAction();
            switch (action & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mSendTouchToWorkspace = false;
            }
            return mWorkspace.onTouchEvent(event);
        }
        // Always let touch follow through to Workspace.
        return false;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        DeviceProfile dp = mActivity.getDeviceProfile();
        int qsbWidth = isCompactSearchBarEnabled() && !dp.isQsbInline
                ? MeasureSpec.getSize(widthMeasureSpec)
                : dp.hotseatQsbWidth;
        mQsb.measure(makeMeasureSpec(qsbWidth, MeasureSpec.EXACTLY),
                makeMeasureSpec(dp.getHotseatProfile().getQsbHeight(), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        int qsbMeasuredWidth = mQsb.getMeasuredWidth();
        int left;
        DeviceProfile dp = mActivity.getDeviceProfile();
        if (dp.isQsbInline) {
            int qsbSpace = dp.hotseatBorderSpace;
            left = Utilities.isRtl(getResources()) ? r - getPaddingRight() + qsbSpace
                    : l + getPaddingLeft() - qsbMeasuredWidth - qsbSpace;
        } else {
            left = (r - l - qsbMeasuredWidth) / 2;
        }
        int right = left + qsbMeasuredWidth;

        int top;
        int bottom;
        if (isCompactSearchBarEnabled() && !dp.isQsbInline) {
            top = 0;
            bottom = top + dp.getHotseatProfile().getQsbHeight();
        } else {
            bottom = b - t - dp.getQsbOffsetY();
            top = bottom - dp.getHotseatProfile().getQsbHeight();
        }
        mQsb.layout(left, top, right, bottom);
        if (mShowHotseatBg) {
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mCrossWindowBlurEnabled = CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled();
        CrossWindowBlurListeners.getInstance().addListener(
                getContext().getMainExecutor(), mCrossWindowBlurListener);
        LauncherPrefs.get(getContext()).addListener(mPrefChangeListener,
                LauncherPrefs.SHOW_HOTSEAT_BG,
                LauncherPrefs.HOTSEAT_BLUR,
                LauncherPrefs.BLUR_DEPTH,
                LauncherPrefs.HOTSEAT_OPACITY);
        Launcher launcher = mActivity instanceof Launcher l ? l : null;
        if (launcher != null && launcher.getStateManager() != null) {
            launcher.getStateManager().addStateListener(mStateListener);
        }
        updateBackgroundPreferences();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mBackgroundBlurDrawable != null) {
            mBackgroundBlurDrawable.setVisible(false, false);
            mBackgroundBlurDrawable.setCallback(null);
            mBackgroundBlurDrawable = null;
        }
        mLastBlurBounds.setEmpty();
        mLastCornerRadius = -1f;
        mLastBlurRadius = -1;
        Launcher launcher = mActivity instanceof Launcher l ? l : null;
        if (launcher != null && launcher.getStateManager() != null) {
            launcher.getStateManager().removeStateListener(mStateListener);
        }
        CrossWindowBlurListeners.getInstance().removeListener(mCrossWindowBlurListener);
        LauncherPrefs.get(getContext()).removeListener(mPrefChangeListener,
                LauncherPrefs.SHOW_HOTSEAT_BG,
                LauncherPrefs.HOTSEAT_BLUR,
                LauncherPrefs.BLUR_DEPTH,
                LauncherPrefs.HOTSEAT_OPACITY);
    }

    @Override
    public void setAlpha(float alpha) {
        float oldAlpha = getAlpha();
        super.setAlpha(alpha);
        if (mShowHotseatBg && (alpha >= 0.99f || alpha <= 0.01f || (oldAlpha <= 0.01f && alpha > 0.01f))) {
            invalidate();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            updateBackgroundPreferences();
            postInvalidate();
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible) {
            postInvalidate();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            postInvalidate();
        }
    }

    private void onCrossWindowBlurEnabledChanged(boolean enabled) {
        mCrossWindowBlurEnabled = enabled;
        if (!enabled && mBackgroundBlurDrawable != null) {
            mBackgroundBlurDrawable.setVisible(false, false);
        }
        invalidate();
    }

    private void updateBackgroundPreferences() {
        Context context = getContext();
        mShowHotseatBg = LauncherPrefs.SHOW_HOTSEAT_BG.get(context);
        mHotseatBlur = LauncherPrefs.HOTSEAT_BLUR.get(context);
        mBlurDepth = LauncherPrefs.BLUR_DEPTH.get(context);
        mOpacity = LauncherPrefs.HOTSEAT_OPACITY.get(context);
        invalidate();
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who)
                || (who == mBackgroundBlurDrawable)
                || (who == mBackgroundTintDrawable);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        drawHotseatBackground(canvas);
        super.dispatchDraw(canvas);
    }

    private void drawHotseatBackground(Canvas canvas) {
        if (!mShowHotseatBg || getVisibility() != VISIBLE || !isAttachedToWindow()) {
            if (mBackgroundBlurDrawable != null && mBackgroundBlurDrawable.isVisible()) {
                mBackgroundBlurDrawable.setVisible(false, false);
            }
            return;
        }

        float iconAlpha = mShortcutsAndWidgets != null ? mShortcutsAndWidgets.getAlpha() : 1f;
        if (iconAlpha <= 0.001f) {
            if (mBackgroundBlurDrawable != null && mBackgroundBlurDrawable.isVisible()) {
                mBackgroundBlurDrawable.setVisible(false, false);
            }
            return;
        }

        DeviceProfile dp = mActivity.getDeviceProfile();
        int iconSize = dp.getWorkspaceIconProfile().getIconSizePx();

        int childWidthSize = getWidth() - (getPaddingLeft() + getPaddingRight());
        int childHeightSize = getHeight() - (getPaddingTop() + getPaddingBottom());
        if (childWidthSize <= 0) childWidthSize = dp.getCellLayoutWidth();
        if (childHeightSize <= 0) childHeightSize = dp.hotseatBarSizePx;

        int count = mHasVerticalHotseat
                ? (mCountY > 0 ? mCountY : dp.numShownHotseatIcons)
                : (mCountX > 0 ? mCountX : dp.numShownHotseatIcons);
        if (count <= 0) count = 4;

        int borderSpaceX = mBorderSpace != null ? mBorderSpace.x : dp.hotseatBorderSpace;
        int borderSpaceY = mBorderSpace != null ? mBorderSpace.y : dp.hotseatBorderSpace;

        int cellWidth = mCellWidth > 0 ? mCellWidth
                : DeviceProfile.calculateCellWidth(childWidthSize, borderSpaceX, count);
        int cellHeight = mCellHeight > 0 ? mCellHeight
                : DeviceProfile.calculateCellHeight(childHeightSize, borderSpaceY, mHasVerticalHotseat ? count : 1);

        float containerTx = mShortcutsAndWidgets != null ? mShortcutsAndWidgets.getTranslationX() : 0f;
        float containerTy = mShortcutsAndWidgets != null ? mShortcutsAndWidgets.getTranslationY() : 0f;

        int padHorizontal = Utilities.dpToPx(14f);
        int padVertical = Utilities.dpToPx(10f);

        float cornerRadius;
        if (mHasVerticalHotseat) {
            getHotseatCellRect(0, 0, cellWidth, cellHeight, borderSpaceX, borderSpaceY, count, mTempCellRect1);
            getHotseatCellRect(0, count - 1, cellWidth, cellHeight, borderSpaceX, borderSpaceY, count, mTempCellRect2);

            float iconLeft = mTempCellRect1.centerX() - iconSize / 2f + containerTx;
            float iconRight = iconLeft + iconSize;
            float iconTop = Math.min(mTempCellRect1.centerY(), mTempCellRect2.centerY()) - iconSize / 2f + containerTy;
            float iconBottom = Math.max(mTempCellRect1.centerY(), mTempCellRect2.centerY()) + iconSize / 2f + containerTy;

            mPillRect.left = Math.round(iconLeft - padVertical);
            mPillRect.right = Math.round(iconRight + padVertical);
            mPillRect.top = Math.round(iconTop - padHorizontal);
            mPillRect.bottom = Math.round(iconBottom + padHorizontal);

            cornerRadius = mPillRect.width() / 2f;
        } else {
            getHotseatCellRect(0, 0, cellWidth, cellHeight, borderSpaceX, borderSpaceY, count, mTempCellRect1);
            getHotseatCellRect(count - 1, 0, cellWidth, cellHeight, borderSpaceX, borderSpaceY, count, mTempCellRect2);

            float iconLeft = Math.min(mTempCellRect1.centerX(), mTempCellRect2.centerX()) - iconSize / 2f + containerTx;
            float iconRight = Math.max(mTempCellRect1.centerX(), mTempCellRect2.centerX()) + iconSize / 2f + containerTx;
            float iconTop = mTempCellRect1.centerY() - iconSize / 2f + containerTy;
            float iconBottom = iconTop + iconSize;

            mPillRect.left = Math.round(iconLeft - padHorizontal);
            mPillRect.right = Math.round(iconRight + padHorizontal);
            mPillRect.top = Math.round(iconTop - padVertical);
            mPillRect.bottom = Math.round(iconBottom + padVertical);

            cornerRadius = mPillRect.height() / 2f;
        }

        boolean canBlur = mHotseatBlur && mCrossWindowBlurEnabled && mBlurDepth > 0;

        if (canBlur) {
            if (mBackgroundBlurDrawable == null && getViewRootImpl() != null) {
                mBackgroundBlurDrawable = getViewRootImpl().createBackgroundBlurDrawable();
                if (mBackgroundBlurDrawable != null) {
                    mBackgroundBlurDrawable.setCallback(this);
                    mBackgroundBlurDrawable.setApplyRenderNodeAlpha(true);
                }
            }
            if (mBackgroundBlurDrawable != null) {
                if (!mLastBlurBounds.equals(mPillRect)) {
                    mBackgroundBlurDrawable.setBounds(mPillRect);
                    mLastBlurBounds.set(mPillRect);
                }
                if (mLastCornerRadius != cornerRadius) {
                    mBackgroundBlurDrawable.setCornerRadius(cornerRadius);
                    mLastCornerRadius = cornerRadius;
                }
                if (mLastBlurRadius != mBlurDepth) {
                    mBackgroundBlurDrawable.setBlurRadius(mBlurDepth);
                    mLastBlurRadius = mBlurDepth;
                }
                if (!mBackgroundBlurDrawable.isVisible()) {
                    mBackgroundBlurDrawable.setVisible(true, false);
                }
                mBackgroundBlurDrawable.draw(canvas);
            }
        } else {
            if (mBackgroundBlurDrawable != null && mBackgroundBlurDrawable.isVisible()) {
                mBackgroundBlurDrawable.setVisible(false, false);
                mLastBlurBounds.setEmpty();
            }
        }

        if (mBackgroundTintDrawable == null) {
            mBackgroundTintDrawable = new GradientDrawable();
            mBackgroundTintDrawable.setShape(GradientDrawable.RECTANGLE);
            mBackgroundTintDrawable.setCallback(this);
        }
        mBackgroundTintDrawable.setCornerRadius(cornerRadius);
        mBackgroundTintDrawable.setBounds(mPillRect);

        int baseColor = getContext().getColor(R.color.appseat_color);
        int targetAlpha = Math.round(255f * (mOpacity / 100f) * iconAlpha);
        mBackgroundTintDrawable.setColor(ColorUtils.setAlphaComponent(baseColor, targetAlpha));

        int strokeWidth = Math.max(1, Utilities.dpToPx(1f));
        int strokeAlpha = Math.round(35f * iconAlpha);
        mBackgroundTintDrawable.setStroke(strokeWidth, Color.argb(strokeAlpha, 255, 255, 255));

        mBackgroundTintDrawable.draw(canvas);
    }

    private void getHotseatCellRect(int cellX, int cellY, int cellWidth, int cellHeight,
            int borderSpaceX, int borderSpaceY, int count, Rect resultRect) {
        int unusedSpace = getWidth() - getPaddingLeft() - getPaddingRight()
                - (count * cellWidth) - ((count - 1) * borderSpaceX);
        int hStartPadding = getPaddingLeft() + (int) Math.ceil(Math.max(0, unusedSpace) / 2f);
        int vStartPadding = getPaddingTop();

        int x = hStartPadding + (cellX * borderSpaceX) + (cellX * cellWidth)
                + getTranslationXForCell(cellX, cellY);
        int y = vStartPadding + (cellY * borderSpaceY) + (cellY * cellHeight);

        resultRect.set(x, y, x + cellWidth, y + cellHeight);
    }

    /**
     * Sets the alpha value of the specified alpha channel of just our ShortcutAndWidgetContainer.
     */
    public void setIconsAlpha(float alpha, @HotseatQsbAlphaId int channelId) {
        getIconsAlpha(channelId).setValue(alpha);
        invalidate();
    }

    /**
     * Sets the alpha value of just our QSB.
     */
    public void setQsbAlpha(float alpha, @HotseatQsbAlphaId int channelId) {
        getQsbAlpha(channelId).setValue(alpha);
    }

    /** Returns the alpha channel for ShortcutAndWidgetContainer */
    public MultiProperty getIconsAlpha(@HotseatQsbAlphaId int channelId) {
        return mIconsAlphaChannels.get(channelId);
    }

    /** Returns the alpha channel for Qsb */
    public MultiProperty getQsbAlpha(@HotseatQsbAlphaId int channelId) {
        return mQsbAlphaChannels.get(channelId);
    }

    public boolean isCompactSearchBarEnabled() {
        return LauncherPrefs.COMPACT_SEARCH_BAR.get(getContext());
    }

    /**
     * Returns the QSB inside hotseat
     */
    public View getQsb() {
        return mQsb;
    }

    @Nullable
    @Override
    public View mapOverItems(ItemOperator op) {
        if (Flags.enableQsbOnHotseat()
                && mQsb != null
                && mQsb.getTag() instanceof ItemInfo info
                && op.evaluate(info, mQsb)) {
            return mQsb;
        }
        return super.mapOverItems(op);
    }

    /** Dumps the Hotseat internal state */
    public void dump(String prefix, PrintWriter writer) {
        writer.println(prefix + "Hotseat:");
        mIconsAlphaChannels.dump(
                prefix + "\t",
                writer,
                "mIconsAlphaChannels",
                "ALPHA_CHANNEL_TASKBAR_ALIGNMENT",
                "ALPHA_CHANNEL_PREVIEW_RENDERER",
                "ALPHA_CHANNEL_TASKBAR_STASH",
                "ALPHA_CHANNEL_ASSISTANT_VISIBILITY",
                "ALPHA_CHANNEL_STATE_VISIBILITY");
        mQsbAlphaChannels.dump(
                prefix + "\t",
                writer,
                "mQsbAlphaChannels",
                "ALPHA_CHANNEL_TASKBAR_ALIGNMENT",
                "ALPHA_CHANNEL_PREVIEW_RENDERER",
                "ALPHA_CHANNEL_TASKBAR_STASH",
                "ALPHA_CHANNEL_ASSISTANT_VISIBILITY",
                "ALPHA_CHANNEL_STATE_VISIBILITY"
        );
    }

}
