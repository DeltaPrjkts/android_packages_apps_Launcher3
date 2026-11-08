/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.launcher3.allapps.search;

import static android.view.View.MeasureSpec.EXACTLY;
import static android.view.View.MeasureSpec.getSize;
import static android.view.View.MeasureSpec.makeMeasureSpec;

import static com.android.launcher3.Utilities.prefixTextWithIcon;
import static com.android.launcher3.icons.IconNormalizer.ICON_VISIBLE_AREA_FACTOR;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.PaintDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.TextKeyListener;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.CrossWindowBlurListeners;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.ViewOutlineProvider;

import com.android.axion.blur.ui.view.AxViewBackdropBlur;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.ExtendedEditText;
import com.android.launcher3.Insettable;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.allapps.ActivityAllAppsContainerView;
import com.android.launcher3.allapps.AllAppsStore;
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem;
import com.android.launcher3.allapps.PrivateProfileManager;
import com.android.launcher3.allapps.SearchUiManager;
import com.android.launcher3.search.SearchCallback;
import com.android.launcher3.search.universal.UniversalSearchAlgorithm;
import com.android.launcher3.util.ApiWrapper;
import com.android.launcher3.util.Themes;
import com.android.launcher3.graphics.ThemeManager;
import com.android.launcher3.views.ActivityContext;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Layout to contain the All-apps search UI.
 */
public class AppsSearchContainerLayout extends ExtendedEditText
        implements SearchUiManager, SearchCallback<AdapterItem>,
        AllAppsStore.OnUpdateListener, Insettable {

    private final ActivityContext mLauncher;
    private final AllAppsSearchBarController mSearchBarController;
    private final SpannableStringBuilder mSearchQueryBuilder;

    private ActivityAllAppsContainerView<?> mAppsView;
    private String mResultQuery;
    private boolean mPrivateSpaceQueryHandled;

    // The amount of pixels to shift down and overlap with the rest of the content.
    private final int mContentOverlap;

    private final AxViewBackdropBlur mBackdropBlur = new AxViewBackdropBlur(this, false);
    private final GradientDrawable mBackgroundTintDrawable = new GradientDrawable();
    private boolean mCrossWindowBlurEnabled;
    private int mBlurDepth = 45;
    private final Consumer<Boolean> mCrossWindowBlurListener =
            this::onCrossWindowBlurEnabledChanged;

    private boolean mIsUniversalSearch;
    private Drawable mSearchIcon;
    private Drawable mClearIcon;
    private Drawable mSpacerIcon;

    private final TextWatcher mTextWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void afterTextChanged(Editable s) {
            if (isUniversalSearch()) {
                updateUniversalSearchDrawablesAndGravity();
            }
        }
    };

    public AppsSearchContainerLayout(Context context) {
        this(context, null);
    }

    public AppsSearchContainerLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AppsSearchContainerLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        mLauncher = ActivityContext.lookupContext(context);
        mSearchBarController = new AllAppsSearchBarController();

        mSearchQueryBuilder = new SpannableStringBuilder();
        Selection.setSelection(mSearchQueryBuilder, 0);

        mContentOverlap =
                getResources().getDimensionPixelSize(R.dimen.all_apps_search_bar_content_overlap);

        addTextChangedListener(mTextWatcher);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                if (isUniversalSearch()) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
                } else {
                    float cornerRadius = getCornerRadius(getContext());
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
                }
            }
        });
        setClipToOutline(true);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mBackdropBlur.onAttachedToWindow();
        mCrossWindowBlurEnabled = CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled();
        CrossWindowBlurListeners.getInstance().addListener(
                getContext().getMainExecutor(), mCrossWindowBlurListener);
        updateBlurPreferences();
        if (mAppsView != null) {
            mAppsView.getAppsStore().addUpdateListener(this);
            mSearchBarController.refreshSearchResult();
            setUniversalSearch(mAppsView.isUniversalSearchActive());
        }
        updateSearchBarStyle();
        postInvalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        mBackdropBlur.onDetachedFromWindow();
        CrossWindowBlurListeners.getInstance().removeListener(mCrossWindowBlurListener);
        super.onDetachedFromWindow();
        if (mAppsView != null) {
            mAppsView.getAppsStore().removeUpdateListener(this);
            mSearchBarController.cancelSearch();
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        mBackdropBlur.onVisibilityAggregated(isVisible);
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
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who)
                || (mBackdropBlur != null && mBackdropBlur.verifyDrawable(who));
    }

    private void onCrossWindowBlurEnabledChanged(boolean enabled) {
        mCrossWindowBlurEnabled = enabled;
        updateBlurState();
        postInvalidate();
    }

    private void updateBlurPreferences() {
        mBlurDepth = Math.max(0, LauncherPrefs.BLUR_DEPTH.get(getContext()));
        mBackdropBlur.setBlurRadiusPx(mBlurDepth);
        updateBlurState();
    }

    private boolean isBlurAvailable() {
        return mCrossWindowBlurEnabled && mBlurDepth > 0 && isHardwareAccelerated();
    }

    private boolean canDrawBlur() {
        return isBlurAvailable() && isAttachedToWindow()
                && getWidth() > 0 && getHeight() > 0
                && getWindowVisibility() == VISIBLE;
    }

    private void updateBlurState() {
        mBackdropBlur.setEnabled(canDrawBlur() && isUniversalSearch());
        mBackdropBlur.setSurfaceAlpha(Math.round(255f
                * Utilities.boundToRange(getAlpha(), 0f, 1f)));
    }

    private void hideBlur() {
        mBackdropBlur.setEnabled(false);
    }

    public void setUniversalSearch(boolean active) {
        mIsUniversalSearch = active;
        updateSearchBarStyle();
        requestLayout();
        invalidate();
    }

    public boolean isUniversalSearch() {
        return true;
    }

    private void updateUniversalSearchDrawablesAndGravity() {
        if (mClearIcon == null) {
            mClearIcon = getContext().getDrawable(R.drawable.ic_allapps_clear);
            if (mClearIcon != null) {
                mClearIcon = mClearIcon.mutate();
                mClearIcon.setTint(Themes.getAttrColor(getContext(), android.R.attr.textColorSecondary));
            }
        }
        if (mSpacerIcon == null) {
            mSpacerIcon = getContext().getDrawable(R.drawable.ic_search_spacer);
        }
        if (mSearchIcon == null) {
            mSearchIcon = getContext().getDrawable(R.drawable.ic_allapps_search_color);
            if (mSearchIcon != null) {
                mSearchIcon = mSearchIcon.mutate();
                mSearchIcon.setTint(Themes.getAttrColor(getContext(), android.R.attr.textColorSecondary));
            }
        }
        boolean hasText = !TextUtils.isEmpty(getText());
        Drawable rightDrawable = hasText ? mClearIcon : mSpacerIcon;
        setCompoundDrawablesRelativeWithIntrinsicBounds(mSearchIcon, null, rightDrawable, null);
        setGravity(hasText ? (Gravity.START | Gravity.CENTER_VERTICAL) : Gravity.CENTER);
        setCursorVisible(hasText);
    }

    private void setUpUniversalSearchBackground() {
        float cornerRadius = getHeight() > 0 ? (getHeight() / 2f) : (Utilities.dpToPx(48) / 2f);
        mBackgroundTintDrawable.setShape(GradientDrawable.RECTANGLE);
        mBackgroundTintDrawable.setCornerRadius(cornerRadius);

        boolean isDarkTheme = Utilities.isDarkTheme(getContext());
        int baseColor = LauncherPrefs.DOCK_THEME.get(getContext())
                ? Themes.getAttrColor(getContext(), R.attr.qsbFillColorThemed)
                : Themes.getAttrColor(getContext(), R.attr.qsbFillColor);

        int opacity = Utilities.boundToRange(LauncherPrefs.HOTSEAT_QSB_OPACITY.get(getContext()), 20, 100);
        int alpha = Math.round((isDarkTheme ? 175f : 195f) * opacity / 100f);
        int fillColor = Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor));

        int strokeWidth = Math.max(1, Utilities.dpToPx(1.5f));
        int strokeColor = isDarkTheme
                ? Color.argb(140, 255, 255, 255)
                : Color.argb(90, 0, 0, 0);

        mBackgroundTintDrawable.setColor(fillColor);
        mBackgroundTintDrawable.setStroke(strokeWidth, strokeColor);
        setClipToOutline(false);
        setBackground(mBackgroundTintDrawable);
    }

    private void updateSearchBarStyle() {
        updateUniversalSearchDrawablesAndGravity();
        setHint(R.string.label_search);
        setHintTextColor(Themes.getAttrColor(getContext(), android.R.attr.textColorSecondary));
        setTextColor(Themes.getAttrColor(getContext(), android.R.attr.textColorPrimary));
        setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.compact_search_bar_text_size));
        setPaddingRelative(Utilities.dpToPx(12), getPaddingTop(), Utilities.dpToPx(12), getPaddingBottom());
        setUpUniversalSearchBackground();
        updateBlurPreferences();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Update the width to match the grid padding
        if (mAppsView == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        DeviceProfile dp = mLauncher.getDeviceProfile();
        int myRequestedWidth = getSize(widthMeasureSpec);
        View widthSource = mAppsView.getActiveRecyclerView();
        if (widthSource == null) {
            widthSource = mAppsView.getAppsRecyclerViewContainer();
        }
        if (widthSource == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        if (mAppsView != null && mAppsView.getActiveRecyclerView() != null) {
            int rowWidth = myRequestedWidth - widthSource.getPaddingLeft()
                    - widthSource.getPaddingRight();

            int cellWidth = DeviceProfile.calculateCellWidth(rowWidth,
                    dp.getWorkspaceIconProfile().getCellLayoutBorderSpacePx().x, dp.numShownHotseatIcons);
            int iconVisibleSize =
                    Math.round(ICON_VISIBLE_AREA_FACTOR * dp.getWorkspaceIconProfile().getIconSizePx());
            int iconPadding = cellWidth - iconVisibleSize;

                int myWidth = rowWidth - iconPadding + getPaddingLeft() + getPaddingRight();
                super.onMeasure(makeMeasureSpec(myWidth, EXACTLY), heightMeasureSpec);
        } else {
            // Fallback to default measurement if mAppsView is not initialized yet
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // Shift the widget horizontally so that its centered in the parent (b/63428078)
        View parent = (View) getParent();
        if (parent != null) {
            int availableWidth = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            int myWidth = right - left;
            int expectedLeft = parent.getPaddingLeft() + (availableWidth - myWidth) / 2;
            int shift = expectedLeft - left;
            setTranslationX(shift);
        }

        updateSearchBarStyle();

        setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    float touchX = getLayoutDirection() == LAYOUT_DIRECTION_RTL
                            ? getWidth() - event.getX() : event.getX();

                    boolean hasText = !TextUtils.isEmpty(getText());
                    if (hasText) {
                        int touchTarget = Math.max(Utilities.dpToPx(48), getPaddingEnd() + Utilities.dpToPx(48));
                        if (touchX >= (getWidth() - touchTarget)) {
                            setText("");
                            return true;
                        }
                    }

                    if (mAppsView != null && !mAppsView.isUniversalSearchActive()) {
                        mAppsView.setUniversalSearchActive(true);
                    }
                }
                return false;
            }
        });

        offsetTopAndBottom(mContentOverlap);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (isUniversalSearch() && h > 0) {
            mBackgroundTintDrawable.setCornerRadius(h / 2f);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        if (isUniversalSearch() && canvas.isHardwareAccelerated()) {
            updateBlurState();
            if (mBackdropBlur.isEnabled()) {
                mBackdropBlur.draw(canvas, 0, 0, getWidth(), getHeight(), getHeight() / 2f);
            }
        }
        super.draw(canvas);
    }

    private void setUpBackground(boolean showQSBStyle) {
        Context context = getContext();
        float cornerRadius = getCornerRadius(context);

        int color = Themes.getAttrColor(context,
                showQSBStyle && LauncherPrefs.DOCK_THEME.get(context)
                        ? R.attr.qsbFillColorThemed
                        : R.attr.qsbFillColor);

        PaintDrawable pd = new PaintDrawable(color);
        pd.setCornerRadius(cornerRadius);
        setClipToOutline(cornerRadius > 0);
        setBackground(pd);
    }

    private float getCornerRadius(Context context) {
        Resources res = context.getResources();
        float qsbWidgetHeight = res.getDimension(R.dimen.qsb_widget_height);
        float qsbWidgetPadding = res.getDimension(R.dimen.qsb_widget_vertical_padding);
        float innerHeight = qsbWidgetHeight - 2 * qsbWidgetPadding;
        return (innerHeight / 2) * ((float)LauncherPrefs.SEARCH_RADIUS_SIZE.get(context) / 100f);
    }

    @Override
    public void initializeSearch(ActivityAllAppsContainerView<?> appsView) {
        mAppsView = appsView;
        UniversalSearchAlgorithm algorithm =
                new UniversalSearchAlgorithm(getContext());
        algorithm.setQueryHandler(query -> {
            setText(query);
            setSelection(query.length());
        });
        mSearchBarController.initialize(algorithm, this, mLauncher, this);
        if (isAttachedToWindow()) {
            mAppsView.getAppsStore().addUpdateListener(this);
        }
        setUniversalSearch(appsView.isUniversalSearchActive());
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus && mAppsView != null) {
            mSearchBarController.refreshSearchResult();
        }
    }

    @Override
    public void onAppsUpdated() {
        mSearchBarController.refreshSearchResult();
    }

    @Override
    public void resetSearch() {
        mSearchBarController.reset();
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        if (focused && mAppsView != null && !mAppsView.isUniversalSearchActive()) {
            mAppsView.setUniversalSearchActive(true);
        }
    }

    @Override
    public void focusSearchField() {
        if (mAppsView != null && !mAppsView.isUniversalSearchActive()) {
            mAppsView.setUniversalSearchActive(true);
        }
        mSearchBarController.focusSearchField();
    }

    @Override
    public void preDispatchKeyEvent(KeyEvent event) {
        if (mAppsView != null && !mAppsView.isUniversalSearchActive()) {
            mAppsView.setUniversalSearchActive(true);
        }
        // Determine if the key event was actual text, if so, focus the search bar and then dispatch
        // the key normally so that it can process this key event
        if (!mSearchBarController.isSearchFieldFocused() &&
                event.getAction() == KeyEvent.ACTION_DOWN) {
            final int unicodeChar = event.getUnicodeChar();
            final boolean isKeyNotWhitespace = unicodeChar > 0 &&
                    !Character.isWhitespace(unicodeChar) && !Character.isSpaceChar(unicodeChar);
            if (isKeyNotWhitespace) {
                boolean gotKey = TextKeyListener.getInstance().onKeyDown(this, mSearchQueryBuilder,
                        event.getKeyCode(), event);
                if (gotKey && mSearchQueryBuilder.length() > 0) {
                    mSearchBarController.focusSearchField();
                }
            }
        }
    }

    @Override
    public void onSearchResult(String query, ArrayList<AdapterItem> items) {
        if (query.equalsIgnoreCase(mContext.getString(R.string.private_space_label))) {
            if (!mPrivateSpaceQueryHandled) {
                mPrivateSpaceQueryHandled = true;
                mAppsView.onClearSearchResult();
                mResultQuery = null;
                privateSpaceQuery();
            }
            return;
        }
        mPrivateSpaceQueryHandled = false;
        if (items != null) {
            if (query.trim().isEmpty() && items.isEmpty()
                    && !mAppsView.isUniversalSearchActive()) {
                mAppsView.onClearSearchResult();
                mResultQuery = null;
            } else {
                mAppsView.setSearchResults(items, !query.equals(mResultQuery));
                mResultQuery = query;
            }
        }
    }

    @Override
    public void clearSearchResult() {
        // Clear the search query
        mSearchQueryBuilder.clear();
        mSearchQueryBuilder.clearSpans();
        Selection.setSelection(mSearchQueryBuilder, 0);
        mAppsView.onClearSearchResult();
        mResultQuery = null;
        mPrivateSpaceQueryHandled = false;
    }

    @Override
    public void setInsets(Rect insets) {
        MarginLayoutParams mlp = (MarginLayoutParams) getLayoutParams();
        mlp.topMargin = getResources().getDimensionPixelSize(R.dimen.all_apps_search_bar_margin_top);
        requestLayout();
    }

    @Override
    public ExtendedEditText getEditText() {
        return this;
    }

    private void privateSpaceQuery() {
        PrivateProfileManager privateProfileManager = mAppsView.getPrivateProfileManager();
        if (privateProfileManager != null && privateProfileManager.isPrivateSpaceHidden()) {
            privateProfileManager.setQuietMode(false);
        } else if (!mAppsView.hasPrivateProfile()) {
            final Intent privateSpaceSettingsIntent =
                    ApiWrapper.INSTANCE.get(mContext).getPrivateSpaceSettingsIntent();
            if (privateSpaceSettingsIntent != null) {
                mLauncher.startActivitySafely(mAppsView, privateSpaceSettingsIntent, null);
            }
        }
    }
}
