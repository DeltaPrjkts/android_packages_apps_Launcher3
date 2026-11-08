package com.android.launcher3.allapps;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.BubbleTextView;
import com.android.launcher3.R;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.views.ActivityContext;

public class CaddyRowView extends LinearLayout {
    private final ActivityContext mActivityContext;
    private OnFocusChangeListener mFocusListener;

    public CaddyRowView(Context context) {
        super(context);
        mActivityContext = ActivityContext.lookupContext(context);
        setOrientation(HORIZONTAL);
        setBaselineAligned(false);
        setPadding(0, 0, 0, dp(8));
        setLayoutParams(new RecyclerView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    public void bind(BaseAllAppsAdapter.AdapterItem item, OnFocusChangeListener focusListener,
            Runnable onFavoritesChanged) {
        removeAllViews();
        mFocusListener = focusListener;
        if (item.viewType == BaseAllAppsAdapter.VIEW_TYPE_CADDY_FAVORITES) {
            LinearLayout favorites = new LinearLayout(getContext()) {
                @Override
                protected void onMeasure(int widthSpec, int heightSpec) {
                    int height = Math.max(dp(96), Math.min(dp(128),
                            Math.round(MeasureSpec.getSize(widthSpec) * .29f)));
                    super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
                }
            };
            favorites.setGravity(Gravity.CENTER);
            favorites.setPadding(dp(8), dp(8), dp(8), dp(8));
            favorites.setBackground(CaddyStyle.background(getContext(), false));
            favorites.setContentDescription(getContext().getString(R.string.caddy_favorites));
            favorites.setStateDescription(getContext().getString(R.string.caddy_hold_to_edit));
            OnLongClickListener edit = v -> {
                CaddyLibrarySheet.editFavorites(mActivityContext, item.caddyApps, favorites, onFavoritesChanged);
                return true;
            };
            favorites.setOnLongClickListener(edit);
            favorites.setFocusable(true);
            favorites.setOnFocusChangeListener(mFocusListener);
            for (AppInfo app : item.caddySuggestions) {
                BubbleTextView icon = icon(app, false);
                icon.setOnLongClickListener(edit);
                icon.setStateDescription(getContext().getString(R.string.caddy_hold_to_edit));
                favorites.addView(icon, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
            }
            addView(favorites, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        } else {
            for (CaddyAppLibrary.Category category : item.caddyCategories) {
                LayoutParams params = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
                if (getChildCount() > 0) {
                    params.setMarginStart(dp(8));
                }
                addView(categoryCard(category), params);
            }
            if (item.caddyCategories.size() == 1) {
                LayoutParams params = new LayoutParams(0, 0, 1);
                params.setMarginStart(dp(8));
                addView(new Space(getContext()), params);
            }
        }
    }

    private View categoryCard(CaddyAppLibrary.Category category) {
        GridLayout grid = new GridLayout(getContext());
        grid.setColumnCount(2);
        grid.setRowCount(2);
        LinearLayout card = new LinearLayout(getContext()) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                grid.getLayoutParams().height = Math.max(dp(112), Math.min(dp(180),
                        MeasureSpec.getSize(widthSpec) - dp(24)));
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        card.setOrientation(VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(12));
        card.setBackground(CaddyStyle.background(getContext(), false));
        OnClickListener open = v -> CaddyLibrarySheet.showCategory(mActivityContext, category, card);
        card.setOnClickListener(open);
        card.setFocusable(false);

        TextView title = new TextView(getContext());
        title.setText(category.title);
        title.setTextColor(CaddyStyle.textColor(getContext()));
        title.setTextSize(16);
        title.setLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setMinHeight(dp(40));
        title.setOnClickListener(open);
        title.setOnFocusChangeListener(mFocusListener);
        title.setContentDescription(getContext().getString(R.string.caddy_open_category,
                category.title, category.apps.size()));
        card.addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        card.addView(grid, new LayoutParams(LayoutParams.MATCH_PARENT, 0));

        int largeCount = category.apps.size() <= 4 ? category.apps.size() : 3;
        for (int i = 0; i < (category.apps.size() > 4 ? 3 : 4); i++) {
            grid.addView(i < largeCount ? icon(category.apps.get(i), false)
                    : new Space(getContext()), cell(i));
        }
        if (category.apps.size() > 4) {
            GridLayout preview = new GridLayout(getContext()) {
                @Override
                public boolean onInterceptTouchEvent(MotionEvent event) {
                    return true;
                }
            };
            preview.setColumnCount(2);
            preview.setRowCount(2);
            preview.setPadding(dp(5), dp(5), dp(5), dp(5));
            preview.setOnClickListener(open);
            preview.setOnFocusChangeListener(mFocusListener);
            preview.setContentDescription(title.getContentDescription());
            for (int i = 3; i < 7; i++) {
                preview.addView(i < category.apps.size() ? icon(category.apps.get(i), true)
                        : new Space(getContext()), cell(i - 3));
            }
            grid.addView(preview, cell(3));
        }
        return card;
    }

    private GridLayout.LayoutParams cell(int index) {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(index / 2, GridLayout.FILL, 1f),
                GridLayout.spec(index % 2, GridLayout.FILL, 1f));
        params.width = 0;
        params.height = 0;
        return params;
    }

    private BubbleTextView icon(AppInfo app, boolean preview) {
        BubbleTextView icon = (BubbleTextView) mActivityContext.getLayoutInflater().inflate(
                preview ? R.layout.caddy_preview_icon : R.layout.caddy_app_icon, this, false);
        icon.applyFromApplicationInfo(app);
        if (preview) {
            icon.setClickable(false);
            icon.setLongClickable(false);
            icon.setFocusable(false);
        } else {
            icon.setOnClickListener(mActivityContext.getItemOnClickListener());
            icon.setOnLongClickListener(mActivityContext.getAllAppsItemLongClickListener());
            icon.setOnFocusChangeListener(mFocusListener);
            icon.setLongPressTimeoutFactor(1f);
        }
        return icon;
    }

    private int dp(float value) {
        return CaddyStyle.dp(getContext(), value);
    }
}
