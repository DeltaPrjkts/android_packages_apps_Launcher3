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

import com.android.axion.blur.ui.view.AxViewBackdropBlur;
import com.android.launcher3.ShortcutAndWidgetContainer.TranslationProvider;
import com.android.launcher3.celllayout.CellLayoutLayoutParams;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.pageindicators.PageIndicator;
import com.android.launcher3.pageindicators.PageIndicatorDots;
import com.android.launcher3.qsb.CompactSearchBar;
import com.android.launcher3.qsb.DockSearchWidgetHelper;
import com.android.launcher3.util.HorizontalInsettableView;
import com.android.launcher3.util.LauncherBindableItemsContainer.ItemOperator;
import com.android.launcher3.util.MultiPropertyFactory;
import com.android.launcher3.util.MultiPropertyFactory.MultiProperty;
import com.android.launcher3.util.MultiTranslateDelegate;
import com.android.launcher3.util.MultiValueAlpha;
import com.android.launcher3.util.Themes;
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
    private static final float BACKGROUND_ICON_PADDING_DP = 10f;
    private static final float BACKGROUND_END_PADDING_DP = 14f;

    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mHasVerticalHotseat;
    private Workspace<?> mWorkspace;
    private boolean mSendTouchToWorkspace;
    private final MultiValueAlpha mIconsAlphaChannels;
    private MultiValueAlpha mQsbAlphaChannels;

    private @Nullable MultiProperty mQsbTranslationX;

    private final MultiPropertyFactory mIconsTranslationXFactory;

    private View mQsb;

    private final AxViewBackdropBlur mBackdropBlur = new AxViewBackdropBlur(this, false);
    private final GradientDrawable mBackgroundTintDrawable = new GradientDrawable();
    private final Rect mPillRect = new Rect();
    private final Rect mTempCellRect1 = new Rect();
    private final Rect mTempCellRect2 = new Rect();
    private final Rect mInsets = new Rect();
    private boolean mHasInsets;
    private int mBackgroundTopInset;
    private final Rect mSearchPillBounds = new Rect();
    private final Runnable mBindPageIndicatorRunnable = this::bindPageIndicatorToSearchPill;
    private boolean mShowHotseatBg;
    private boolean mHotseatBlur;
    private int mBlurDepth;
    private boolean mCrossWindowBlurEnabled;
    private final Consumer<Boolean> mCrossWindowBlurListener = this::onCrossWindowBlurEnabledChanged;
    private final LauncherPrefChangeListener mPrefChangeListener = key -> updateBackgroundPreferences();

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
                if (mShowHotseatBg) {
                    updateBlurState();
                    invalidate();
                }
            }
        };
        mIconsAlphaChannels.setUpdateVisibility(true);
        updateQsbAnimationChannels();
        mIconsTranslationXFactory = new MultiPropertyFactory<>(getShortcutsAndWidgets(),
                VIEW_TRANSLATE_X, ICONS_TRANSLATION_X_CHANNELS_COUNT, Float::sum) {
            @Override
            protected void apply(float value) {
                super.apply(value);
                if (mShowHotseatBg) {
                    invalidate();
                }
            }
        };
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
        mInsets.set(insets);
        mHasInsets = true;
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
        mBackgroundTopInset = 0;
        if (mShowHotseatBg) {
            // The capsule must fit inside this view, even when an app transition puts the
            // hotseat in a hardware layer. clipChildren=false cannot expand that layer or the
            // compositor's blur region. Grow towards the workspace and keep the cells in place.
            int iconSize = grid.getWorkspaceIconProfile().getIconSizePx();
            int backgroundPadding = Utilities.dpToPx(BACKGROUND_ICON_PADDING_DP);
            if (grid.isVerticalBarLayout()) {
                int cellWidth = lp.width - padding.left - padding.right;
                int overhang = (int) Math.ceil((iconSize - cellWidth) / 2f) + backgroundPadding;
                if (grid.isSeascape()) {
                    int extraWidth = Math.max(0, overhang - padding.right);
                    lp.width += extraWidth;
                    padding.right += extraWidth;
                } else {
                    int extraWidth = Math.max(0, overhang - padding.left);
                    lp.width += extraWidth;
                    padding.left += extraWidth;
                }
            } else {
                int cellHeight = lp.height - padding.top - padding.bottom;
                int overhang = (int) Math.ceil((iconSize - cellHeight) / 2f) + backgroundPadding;
                mBackgroundTopInset = Math.max(0, overhang - padding.top);
                lp.height += mBackgroundTopInset;
                padding.top += mBackgroundTopInset;
            }
        }
        setPadding(padding.left, padding.top, padding.right, padding.bottom);
        setLayoutParams(lp);
        InsettableFrameLayout.dispatchInsets(this, insets);
    }

    public void setWorkspace(Workspace<?> w) {
        mWorkspace = w;
        setCellLayoutContainer(w);
        bindPageIndicatorToSearchPill();
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
            top = mBackgroundTopInset;
            bottom = top + dp.getHotseatProfile().getQsbHeight();
        } else {
            bottom = b - t - dp.getQsbOffsetY();
            top = bottom - dp.getHotseatProfile().getQsbHeight();
        }
        mQsb.layout(left, top, right, bottom);
        if (mShowHotseatBg) {
            updateBlurState();
            invalidate();
        }
        if (changed || isSearchPillPageIndicatorEnabled()) {
            removeCallbacks(mBindPageIndicatorRunnable);
            post(mBindPageIndicatorRunnable);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mBackdropBlur.onAttachedToWindow();
        mCrossWindowBlurEnabled = CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled();
        CrossWindowBlurListeners.getInstance().addListener(
                getContext().getMainExecutor(), mCrossWindowBlurListener);
        LauncherPrefs.get(getContext()).addListener(mPrefChangeListener,
                LauncherPrefs.SHOW_HOTSEAT_BG,
                LauncherPrefs.HOTSEAT_BLUR,
                LauncherPrefs.BLUR_DEPTH,
                LauncherPrefs.HOTSEAT_OPACITY);
        updateBackgroundPreferences();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mBindPageIndicatorRunnable);
        if (mWorkspace != null && mWorkspace.getPageIndicator() != null) {
            mWorkspace.getPageIndicator().setRevealListener(null);
        }
        mBackdropBlur.onDetachedFromWindow();
        CrossWindowBlurListeners.getInstance().removeListener(mCrossWindowBlurListener);
        LauncherPrefs.get(getContext()).removeListener(mPrefChangeListener,
                LauncherPrefs.SHOW_HOTSEAT_BG,
                LauncherPrefs.HOTSEAT_BLUR,
                LauncherPrefs.BLUR_DEPTH,
                LauncherPrefs.HOTSEAT_OPACITY);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            updateBackgroundPreferences();
        } else {
            hideHotseatBlur();
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        mBackdropBlur.onVisibilityAggregated(isVisible);
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
        updateBlurState();
        invalidate();
    }

    private void updateBackgroundPreferences() {
        Context context = getContext();
        boolean wasShowingBackground = mShowHotseatBg;
        mShowHotseatBg = LauncherPrefs.SHOW_HOTSEAT_BG.get(context);
        mHotseatBlur = LauncherPrefs.HOTSEAT_BLUR.get(context);
        mBlurDepth = Math.max(0, LauncherPrefs.BLUR_DEPTH.get(context));
        mBackdropBlur.setBlurRadiusPx(mBlurDepth);
        int opacity = Utilities.boundToRange(LauncherPrefs.HOTSEAT_OPACITY.get(context), 0, 100);
        int baseColor = context.getColor(R.color.appseat_color);
        mBackgroundTintDrawable.setColor(ColorUtils.setAlphaComponent(baseColor,
                Math.round(255f * opacity / 100f)));
        mBackgroundTintDrawable.setStroke(Math.max(1, Utilities.dpToPx(1f)),
                Color.argb(35, 255, 255, 255));
        if (wasShowingBackground != mShowHotseatBg && mHasInsets) {
            setInsets(mInsets);
        }
        updateBlurState();
        invalidate();
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who)
                || (mBackdropBlur != null && mBackdropBlur.verifyDrawable(who));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        drawHotseatBackground(canvas);
        super.dispatchDraw(canvas);
    }

    private void drawHotseatBackground(Canvas canvas) {
        if (!mShowHotseatBg || getVisibility() != VISIBLE
                || mCountX <= 0 || mCountY <= 0 || mCellWidth <= 0 || mCellHeight <= 0) {
            if (canvas.isHardwareAccelerated()) {
                hideHotseatBlur();
            }
            return;
        }

        float iconAlpha = Utilities.boundToRange(mShortcutsAndWidgets.getAlpha(), 0f, 1f);
        if (Math.round(255f * iconAlpha) == 0) {
            if (canvas.isHardwareAccelerated()) {
                hideHotseatBlur();
            }
            return;
        }

        updatePillBounds();
        float cornerRadius = Math.min(mPillRect.width(), mPillRect.height()) / 2f;
        if (canvas.isHardwareAccelerated()) {
            updateBlurState();
            if (mBackdropBlur.isEnabled()) {
                mBackdropBlur.draw(canvas, mPillRect.left, mPillRect.top,
                        mPillRect.right, mPillRect.bottom, cornerRadius);
            }
        }

        if (mBackgroundTintDrawable.getCornerRadius() != cornerRadius) {
            mBackgroundTintDrawable.setCornerRadius(cornerRadius);
        }
        mBackgroundTintDrawable.setBounds(mPillRect);
        mBackgroundTintDrawable.setAlpha(Math.round(255f * iconAlpha));
        mBackgroundTintDrawable.draw(canvas);
    }

    private void updatePillBounds() {
        DeviceProfile dp = mActivity.getDeviceProfile();
        int iconSize = dp.getWorkspaceIconProfile().getIconSizePx();
        float containerTx = mShortcutsAndWidgets.getTranslationX();
        float containerTy = mShortcutsAndWidgets.getTranslationY();
        int paddingX = Utilities.dpToPx(mHasVerticalHotseat
                ? BACKGROUND_ICON_PADDING_DP : BACKGROUND_END_PADDING_DP);
        int paddingY = Utilities.dpToPx(mHasVerticalHotseat
                ? BACKGROUND_END_PADDING_DP : BACKGROUND_ICON_PADDING_DP);

        cellToRect(0, 0, 1, 1, mTempCellRect1);
        cellToRect(mCountX - 1, mCountY - 1, 1, 1, mTempCellRect2);
        mPillRect.set(
                Math.round(Math.min(mTempCellRect1.exactCenterX(), mTempCellRect2.exactCenterX())
                        - iconSize / 2f - paddingX + containerTx),
                Math.round(Math.min(mTempCellRect1.exactCenterY(), mTempCellRect2.exactCenterY())
                        - iconSize / 2f - paddingY + containerTy),
                Math.round(Math.max(mTempCellRect1.exactCenterX(), mTempCellRect2.exactCenterX())
                        + iconSize / 2f + paddingX + containerTx),
                Math.round(Math.max(mTempCellRect1.exactCenterY(), mTempCellRect2.exactCenterY())
                        + iconSize / 2f + paddingY + containerTy));
    }

    private boolean canBlurHotseat() {
        return mShowHotseatBg && mHotseatBlur && mCrossWindowBlurEnabled && mBlurDepth > 0
                && mCountX > 0 && mCountY > 0 && mCellWidth > 0 && mCellHeight > 0
                && isAttachedToWindow() && isHardwareAccelerated() && isShown()
                && getWindowVisibility() == VISIBLE;
    }

    private void updateBlurState() {
        mBackdropBlur.setEnabled(canBlurHotseat());
        mBackdropBlur.setSurfaceAlpha(Math.round(255f
                * Utilities.boundToRange(mShortcutsAndWidgets.getAlpha(), 0f, 1f)));
    }

    private void hideHotseatBlur() {
        mBackdropBlur.setEnabled(false);
    }

    /**
     * Sets the alpha value of the specified alpha channel of just our ShortcutAndWidgetContainer.
     */
    public void setIconsAlpha(float alpha, @HotseatQsbAlphaId int channelId) {
        getIconsAlpha(channelId).setValue(alpha);
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
        return mQsb instanceof CompactSearchBar;
    }

    public boolean isSearchPillPageIndicatorEnabled() {
        return isCompactSearchBarEnabled() && !mActivity.getDeviceProfile().isVerticalBarLayout();
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

    private @Nullable View getActiveSearchPill() {
        if (!isCompactSearchBarEnabled() || mQsb == null) {
            return null;
        }
        if (mQsb instanceof CompactSearchBar) {
            View inner = mQsb.findViewById(R.id.compact_search_bar_inner);
            return inner != null ? inner : mQsb;
        }
        return mQsb;
    }

    /**
     * Positions the workspace page dots over the compact search pill capsule.
     * Returns true if applied, false if compact search bar is not active.
     */
    public boolean applySearchPillPageIndicatorLayout() {
        if (!isSearchPillPageIndicatorEnabled() || mWorkspace == null) {
            return false;
        }
        PageIndicator pageIndicator = mWorkspace.getPageIndicator();
        if (!(pageIndicator instanceof PageIndicatorDots dots)) {
            return false;
        }
        View pageIndicatorContainer = (View) dots.getParent();
        if (pageIndicatorContainer == null
                || !(pageIndicatorContainer.getLayoutParams() instanceof FrameLayout.LayoutParams lp)) {
            return false;
        }

        View leftArrow = pageIndicatorContainer.findViewById(R.id.left_indicator_arrow);
        if (leftArrow != null) {
            leftArrow.setVisibility(View.GONE);
        }
        View rightArrow = pageIndicatorContainer.findViewById(R.id.right_indicator_arrow);
        if (rightArrow != null) {
            rightArrow.setVisibility(View.GONE);
        }

        View pill = getActiveSearchPill();
        int pillHeight = pill != null && pill.getHeight() > 0 ? pill.getHeight()
                : getResources().getDimensionPixelSize(R.dimen.compact_search_bar_height);
        int height = Math.max(
                getResources().getDimensionPixelSize(R.dimen.workspace_page_indicator_height),
                pillHeight);

        int centerFromBottom = mActivity.getDeviceProfile().hotseatBarSizePx;
        if (getSearchPillBounds(mSearchPillBounds)) {
            centerFromBottom = mActivity.getDragLayer().getHeight() - mSearchPillBounds.centerY();
            pageIndicatorContainer.setTranslationX(mSearchPillBounds.centerX()
                    - mActivity.getDragLayer().getWidth() / 2f);
        } else {
            pageIndicatorContainer.setTranslationX(0f);
        }

        int margin = centerFromBottom - height / 2;

        if (lp.height != height || lp.bottomMargin != margin
                || lp.gravity != (Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM)
                || lp.leftMargin != 0 || lp.rightMargin != 0) {
            lp.height = height;
            lp.bottomMargin = margin;
            lp.leftMargin = lp.rightMargin = 0;
            lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
            pageIndicatorContainer.setLayoutParams(lp);
        }
        return true;
    }

    private boolean getSearchPillBounds(Rect bounds) {
        View pill = getActiveSearchPill();
        View dragLayer = mActivity.getDragLayer();
        if (pill == null || !pill.isLaidOut() || dragLayer == null || !dragLayer.isLaidOut()) {
            return false;
        }
        bounds.set(0, 0, pill.getWidth(), pill.getHeight());
        View current = pill;
        while (current != dragLayer) {
            bounds.offset(current.getLeft(), current.getTop());
            if (!(current.getParent() instanceof View parent)) {
                return false;
            }
            bounds.offset(-parent.getScrollX(), -parent.getScrollY());
            current = parent;
        }
        return true;
    }

    public void bindPageIndicatorToSearchPill() {
        if (mWorkspace == null) {
            return;
        }
        PageIndicator pageIndicator = mWorkspace.getPageIndicator();
        if (!(pageIndicator instanceof PageIndicatorDots dots)) {
            return;
        }

        if (!isSearchPillPageIndicatorEnabled()) {
            dots.setRevealListener(null);
            dots.setShouldAutoHide(LauncherPrefs.AUTO_HIDE_DOTS.get(getContext())
                    && !Utilities.shouldEnableMouseInteractionChanges(getContext()));
            dots.setPaintColor(Themes.getAttrBoolean(getContext(), R.attr.isWorkspaceDarkText)
                    ? Color.BLACK : Color.WHITE);
            if (dots.getParent() instanceof View container) {
                container.setAlpha(1f);
                container.setScaleX(1f);
                container.setScaleY(1f);
                container.setTranslationX(0f);
            }
            dots.setScaleX(1f);
            dots.setScaleY(1f);
            setSearchPillReveal(0f);
            mWorkspace.setPageIndicatorInset();
            return;
        }

        applySearchPillPageIndicatorLayout();
        android.widget.TextView searchText = mQsb.findViewById(R.id.compact_search_bar_text);
        if (searchText != null) {
            dots.setPaintColor(searchText.getCurrentTextColor());
        }
        dots.setShouldAutoHide(true);
        dots.setRevealListener(this::onPageDotsRevealChanged);
        onPageDotsRevealChanged();
    }

    private void onPageDotsRevealChanged() {
        if (mWorkspace == null) return;
        PageIndicator pageIndicator = mWorkspace.getPageIndicator();
        if (pageIndicator instanceof PageIndicatorDots dots) {
            float reveal = dots.getReveal();
            setSearchPillReveal(reveal);
            if (dots.getParent() instanceof View container) {
                container.setAlpha(1f);
                float scale = 0.85f + 0.15f * reveal;
                View pill = getActiveSearchPill();
                if (pill != null && pill.getWidth() > 0 && dots.getWidth() > 0) {
                    scale *= Math.min(1f, Math.max(0,
                            pill.getWidth() - Utilities.dpToPx(16)) / (float) dots.getWidth());
                }
                container.setScaleX(scale);
                container.setScaleY(scale);
            }
        }
    }

    private void setSearchPillReveal(float dotsReveal) {
        if (mQsb instanceof CompactSearchBar compactBar) {
            compactBar.setDotsReveal(dotsReveal);
        }
    }

}
