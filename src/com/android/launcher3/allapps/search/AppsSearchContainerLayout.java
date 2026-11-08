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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.PaintDrawable;
import android.graphics.Rect;
import android.net.Uri;
import android.speech.RecognizerIntent;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.method.TextKeyListener;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewGroup.MarginLayoutParams;

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
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mAppsView != null) {
            mAppsView.getAppsStore().addUpdateListener(this);
            mSearchBarController.refreshSearchResult();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAppsView != null) {
            mAppsView.getAppsStore().removeUpdateListener(this);
            mSearchBarController.cancelSearch();
        }
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

        Drawable gIcon = getContext().getDrawable(R.drawable.ic_allapps_g_color);
        Drawable gIconThemed = getContext().getDrawable(R.drawable.ic_allapps_g_themed);
        Drawable sIcon = getContext().getDrawable(R.drawable.ic_allapps_search_color);
        Drawable actions = getContext().getDrawable(R.drawable.ic_allapps_actions_color);
        Drawable actionsThemed = getContext().getDrawable(R.drawable.ic_allapps_actions_themed);

        // Shift the widget horizontally so that its centered in the parent (b/63428078)
        View parent = (View) getParent();
        if (parent != null) {
            int availableWidth = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            int myWidth = right - left;
            int expectedLeft = parent.getPaddingLeft() + (availableWidth - myWidth) / 2;
            int shift = expectedLeft - left;
            setTranslationX(shift);
        }

        boolean isCompact = LauncherPrefs.COMPACT_SEARCH_BAR.get(getContext());
        boolean isDockSearch = LauncherPrefs.DOCK_SEARCH.get(getContext());
        boolean hasGoogleApp = Utilities.isGSAEnabled(getContext());
        boolean showQSB = Utilities.showQSB(getContext()) || (isCompact && isDockSearch && hasGoogleApp);
        boolean isDockThemed = ThemeManager.INSTANCE.get(getContext()).isMonoThemeEnabled();

        if (showQSB) {
            if (!isDockThemed) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(gIcon, null, actions, null);
            } else {
                setCompoundDrawablesRelativeWithIntrinsicBounds(gIconThemed, null, actionsThemed, null);
            }
        } else {
            setCompoundDrawablesRelativeWithIntrinsicBounds(sIcon, null, null, null);
        }

        int leftSlotWidth = getResources().getDimensionPixelSize(R.dimen.qsb_icon_tap_size);
        int actionSlotWidth = getResources().getDimensionPixelSize(R.dimen.qsb_icon_tap_size);
        int rightGroupWidth = actionSlotWidth * 2;
        int rightGroupStart = getWidth() - getPaddingEnd() - rightGroupWidth;

        setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    float touchX = getLayoutDirection() == LAYOUT_DIRECTION_RTL
                            ? getWidth() - event.getX() : event.getX();
                    Drawable rightDrawable = getCompoundDrawablesRelative()[2];
                    Drawable leftDrawable = getCompoundDrawablesRelative()[0];

                    // Left slot (G icon when showQSB)
                    if (leftDrawable != null && touchX <= (getPaddingStart() + leftSlotWidth)) {
                        if (hasGoogleApp && showQSB) {
                            Intent gIntent = getContext().getPackageManager()
                                    .getLaunchIntentForPackage(Utilities.GSA_PACKAGE);
                            if (gIntent != null) {
                                gIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                mLauncher.startActivitySafely(v, gIntent, null);
                                return true;
                            }
                        }
                        return false;
                    }

                    // Right two slots: mic then lens
                    if (rightDrawable != null && touchX >= rightGroupStart) {
                        if (touchX < (rightGroupStart + actionSlotWidth)) {
                            // Mic slot – voice search
                            Intent voiceIntent = new Intent(RecognizerIntent.ACTION_WEB_SEARCH)
                                    .setPackage(Utilities.GSA_PACKAGE)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                            RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
                            if (voiceIntent.resolveActivity(getContext().getPackageManager())
                                    == null) {
                                voiceIntent = new Intent(Intent.ACTION_VOICE_COMMAND);
                            }
                            voiceIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            mLauncher.startActivitySafely(v, voiceIntent, null);
                        } else {
                            // Lens slot
                            if (hasGoogleApp) {
                                Intent lensIntent = new Intent();
                                lensIntent.setAction(Intent.ACTION_VIEW)
                                        .setComponent(new ComponentName(Utilities.GSA_PACKAGE, Utilities.LENS_ACTIVITY))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        .setData(Uri.parse(Utilities.LENS_URI))
                                        .putExtra("LensHomescreenShortcut", true);
                                mLauncher.startActivitySafely(v, lensIntent, null);
                            }
                        }
                        return true;
                    }


                }
                return false;
            }
        });

        offsetTopAndBottom(mContentOverlap);

        setUpBackground(showQSB);
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
    public void focusSearchField() {
        mSearchBarController.focusSearchField();
    }

    @Override
    public void preDispatchKeyEvent(KeyEvent event) {
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
