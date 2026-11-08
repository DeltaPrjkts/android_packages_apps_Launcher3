package com.android.launcher3.allapps;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Pair;
import android.view.CrossWindowBlurListeners;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.OneShotPreDrawListener;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.BubbleTextView;
import com.android.launcher3.DropTarget.DragObject;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.dragndrop.DragController;
import com.android.launcher3.dragndrop.DragOptions;
import com.android.launcher3.keyboard.FocusedItemDecorator;
import com.android.launcher3.keyboard.ViewGroupFocusHelper;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.views.ActivityContext;
import com.android.launcher3.views.BaseDragLayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

public class CaddyLibrarySheet extends AbstractFloatingView implements DragController.DragListener {
    private static final int MAX_PANEL_WIDTH_DP = 380;
    private static final int MAX_RECYCLER_HEIGHT_DP = 420;
    private static final PathInterpolator OPEN_INTERPOLATOR = new PathInterpolator(0.25f, 0.0f, 0.15f, 1.0f);
    private static final PathInterpolator CLOSE_INTERPOLATOR = new PathInterpolator(0.20f, 0.0f, 0.10f, 1.0f);

    private final ActivityContext mActivityContext;
    private final View mSourceView;
    private final Rect mSourceRect = new Rect();
    private final PanelView mPanel;
    private final LinearLayout mHeading;
    private final TextView mTitle;
    private final EditText mSearch;
    private TextView mEditingDescription;
    private LinearLayout mEditingSelection;
    private final RecyclerView mRecycler;
    private final TextView mEmpty;
    private final TextView mSelectionCount;
    private final List<AppInfo> mApps;
    private final List<AppInfo> mFilteredApps;
    private final Set<String> mSelected;
    private final boolean mEditing;
    private final DragController mDragController;
    private final OnFocusChangeListener mFocusListener;
    private final AppAdapter mAdapter = new AppAdapter();
    private final AllAppsStore.OnUpdateListener mAppsListener = () -> close(false);

    private ValueAnimator mCurrentAnimator;
    private OneShotPreDrawListener mOpenListener;
    private float mStartScale = 0.92f;
    private float mScrimProgress = 0f;
    private int mLastBlurRadius = 0;
    private boolean mIsClosing = false;

    private static class IconTransition {
        View view;
        float targetDx;
        float targetDy;
        float targetScale;
        float targetAlpha;
        float stagger;
    }

    private final List<IconTransition> mIconTransitions = new ArrayList<>();

    private CaddyLibrarySheet(ActivityContext activityContext, String title, List<AppInfo> apps,
            boolean editing, Runnable onFavoritesChanged, @Nullable View sourceView) {
        super(activityContext.asContext(), null);
        mActivityContext = activityContext;
        mDragController = activityContext.getDragController();
        mApps = List.copyOf(apps);
        mFilteredApps = new ArrayList<>(apps);
        mEditing = editing;
        mSelected = CaddyAppLibrary.favorites(getContext(), apps);
        mSourceView = sourceView;
        if (sourceView != null && sourceView.isAttachedToWindow()) {
            mActivityContext.getDragLayer().getDescendantRectRelativeToSelf(sourceView, mSourceRect);
        }
        setWillNotDraw(false);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setClipChildren(false);
        setClipToPadding(false);

        mPanel = new PanelView(getContext());
        mPanel.setClipChildren(true);
        mPanel.setClipToPadding(false);
        addView(mPanel, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        mHeading = new LinearLayout(getContext());
        mHeading.setOrientation(HORIZONTAL);
        mHeading.setGravity(Gravity.CENTER_VERTICAL);
        mHeading.setPadding(dp(4), 0, dp(4), dp(10));

        LinearLayout titleContainer = new LinearLayout(getContext());
        titleContainer.setOrientation(VERTICAL);

        mTitle = text(title, 20);
        mTitle.setMaxLines(1);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        mTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mTitle.setAccessibilityHeading(true);
        titleContainer.addView(mTitle, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        if (!editing && !apps.isEmpty()) {
            TextView countText = text(getResources().getQuantityString(
                    R.plurals.caddy_app_count, apps.size(), apps.size()), 12);
            countText.setTextColor(ColorUtils.setAlphaComponent(CaddyStyle.textColor(getContext()), 140));
            countText.setPadding(0, dp(2), 0, 0);
            titleContainer.addView(countText, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
        mHeading.addView(titleContainer, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));

        if (editing) {
            TextView save = button(R.string.caddy_save);
            save.setOnClickListener(v -> {
                CaddyAppLibrary.saveFavorites(getContext(), mApps, mSelected);
                onFavoritesChanged.run();
                close(true);
            });
            mHeading.addView(save);
        } else {
            ImageView closeBtn = new ImageView(getContext());
            closeBtn.setImageResource(R.drawable.ic_gm_close_24);
            closeBtn.setImageTintList(ColorStateList.valueOf(CaddyStyle.textColor(getContext())));
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(ColorUtils.setAlphaComponent(CaddyStyle.textColor(getContext()), 26));
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.OVAL);
            mask.setColor(Color.WHITE);
            closeBtn.setBackground(new RippleDrawable(ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(CaddyStyle.textColor(getContext()), 50)), circle, mask));
            closeBtn.setPadding(dp(15), dp(15), dp(15), dp(15));
            closeBtn.setContentDescription(getContext().getString(R.string.caddy_close));
            closeBtn.setOnClickListener(v -> close(true));
            mHeading.addView(closeBtn, new LayoutParams(dp(48), dp(48)));
        }
        mPanel.addView(mHeading, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        mSelectionCount = text("", 13);
        mSelectionCount.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        if (editing) {
            mEditingDescription = text(getContext().getString(R.string.caddy_choose_favorites), 14);
            mEditingDescription.setPadding(0, dp(6), 0, dp(6));
            mPanel.addView(mEditingDescription);
            mEditingSelection = new LinearLayout(getContext());
            mEditingSelection.setGravity(Gravity.CENTER_VERTICAL);
            mEditingSelection.addView(mSelectionCount, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            TextView reset = button(R.string.caddy_use_suggestions);
            reset.setOnClickListener(v -> {
                mSelected.clear();
                updateSelectionCount();
                mAdapter.notifyDataSetChanged();
            });
            mEditingSelection.addView(reset);
            mPanel.addView(mEditingSelection);
            updateSelectionCount();
        }

        mSearch = new EditText(getContext());
        mSearch.setSingleLine(true);
        mSearch.setTextSize(15);
        mSearch.setTextColor(CaddyStyle.textColor(getContext()));
        mSearch.setHintTextColor(ColorUtils.setAlphaComponent(
                CaddyStyle.textColor(getContext()), 150));
        mSearch.setHint(R.string.caddy_search_apps);
        mSearch.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        mSearch.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        mSearch.setOnEditorActionListener((view, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                return false;
            }
            getContext().getSystemService(InputMethodManager.class)
                    .hideSoftInputFromWindow(getWindowToken(), 0);
            mPanel.requestFocus();
            return true;
        });
        mSearch.setPadding(dp(14), 0, dp(14), 0);
        mSearch.setBackground(CaddyStyle.background(getContext(), false));
        LayoutParams searchParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(44));
        searchParams.topMargin = dp(2);
        searchParams.bottomMargin = dp(10);
        mSearch.setVisibility(editing || apps.size() > 12 ? VISIBLE : GONE);
        mPanel.addView(mSearch, searchParams);
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        mEmpty = text(getContext().getString(R.string.caddy_no_apps), 15);
        mEmpty.setGravity(Gravity.CENTER);
        mEmpty.setTextColor(ColorUtils.setAlphaComponent(CaddyStyle.textColor(getContext()), 160));
        mEmpty.setVisibility(GONE);
        mPanel.addView(mEmpty, new LayoutParams(LayoutParams.MATCH_PARENT, dp(60)));

        int initialSpan = (apps.size() <= 3 && !apps.isEmpty()) ? apps.size() : 4;
        mRecycler = new RecyclerView(getContext()) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int maxHeight = dp(MAX_RECYCLER_HEIGHT_DP);
                int mode = MeasureSpec.getMode(heightSpec);
                int size = MeasureSpec.getSize(heightSpec);
                if (mode == MeasureSpec.UNSPECIFIED || size > maxHeight) {
                    heightSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        mRecycler.setClipToPadding(false);
        mRecycler.setClipChildren(true);
        mRecycler.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        mRecycler.setLayoutManager(editing ? new LinearLayoutManager(getContext())
                : new GridLayoutManager(getContext(), initialSpan));
        FocusedItemDecorator focus = new FocusedItemDecorator(new ViewGroupFocusHelper(mRecycler));
        mRecycler.addItemDecoration(focus);
        mFocusListener = focus.getFocusListener();
        mRecycler.setAdapter(mAdapter);
        mPanel.addView(mRecycler, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    public static void showCategory(ActivityContext context, CaddyAppLibrary.Category category) {
        showCategory(context, category, null);
    }

    public static void showCategory(ActivityContext context, CaddyAppLibrary.Category category,
            @Nullable View sourceView) {
        new CaddyLibrarySheet(context, category.title, category.apps, false, () -> {
        }, sourceView).show();
    }

    public static void editFavorites(ActivityContext context, List<AppInfo> apps, Runnable changed) {
        editFavorites(context, apps, null, changed);
    }

    public static void editFavorites(ActivityContext context, List<AppInfo> apps,
            @Nullable View sourceView, Runnable changed) {
        if (!apps.isEmpty()) {
            new CaddyLibrarySheet(context, context.asContext().getString(R.string.caddy_edit_favorites),
                    apps, true, changed, sourceView).show();
        }
    }

    private void show() {
        AbstractFloatingView.closeOpenViews(mActivityContext, false, TYPE_CADDY_LIBRARY);
        mIsOpen = true;
        mIsClosing = false;
        BaseDragLayer.LayoutParams params = new BaseDragLayer.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        params.ignoreInsets = true;
        mActivityContext.getDragLayer().addView(this, params);

        mScrimProgress = 0f;
        mPanel.setPanelBackgroundAlpha(0f);
        mPanel.setAlpha(0f);

        if (!ValueAnimator.areAnimatorsEnabled()) {
            finishOpen();
            return;
        }

        mOpenListener = OneShotPreDrawListener.add(this, () -> {
            mOpenListener = null;
            if (mIsOpen && !mIsClosing && isAttachedToWindow()) {
                animateOpen();
            }
        });
    }

    @Nullable
    private View getSourcePreviewGrid() {
        if (mSourceView instanceof ViewGroup group && group.getChildCount() > 1) {
            View child = group.getChildAt(1);
            if (child instanceof GridLayout) {
                return child;
            }
        }
        return null;
    }

    private void hideSourceView() {
        View previewGrid = getSourcePreviewGrid();
        if (previewGrid != null) {
            previewGrid.setVisibility(View.INVISIBLE);
        } else if (mSourceView != null) {
            mSourceView.setVisibility(View.INVISIBLE);
        }
    }

    private void restoreSourceView() {
        View previewGrid = getSourcePreviewGrid();
        if (previewGrid != null) {
            previewGrid.setVisibility(View.VISIBLE);
        } else if (mSourceView != null) {
            mSourceView.setVisibility(View.VISIBLE);
            mSourceView.setAlpha(1f);
        }
    }

    @Nullable
    private View findSourcePreviewIcon(int appIndex) {
        if (mSourceView == null || !(mSourceView instanceof ViewGroup group)) {
            return null;
        }
        if (group.getChildCount() < 2) {
            return null;
        }
        View gridView = group.getChildAt(1);
        if (!(gridView instanceof GridLayout grid)) {
            return null;
        }
        int totalApps = mApps.size();
        if (totalApps <= 4) {
            if (appIndex >= 0 && appIndex < grid.getChildCount()) {
                View cell = grid.getChildAt(appIndex);
                if (cell instanceof BubbleTextView) {
                    return cell;
                }
            }
            return null;
        }
        if (appIndex >= 0 && appIndex < 3) {
            if (appIndex < grid.getChildCount()) {
                View cell = grid.getChildAt(appIndex);
                if (cell instanceof BubbleTextView) {
                    return cell;
                }
            }
        } else if (appIndex >= 3 && appIndex < 7) {
            if (grid.getChildCount() > 3) {
                View previewGrid = grid.getChildAt(3);
                if (previewGrid instanceof GridLayout miniGrid) {
                    int miniIndex = appIndex - 3;
                    if (miniIndex < miniGrid.getChildCount()) {
                        View cell = miniGrid.getChildAt(miniIndex);
                        if (cell instanceof BubbleTextView) {
                            return cell;
                        }
                    }
                }
            }
        }
        return null;
    }

    private void prepareIconTransitions() {
        mIconTransitions.clear();
        if (mRecycler == null || mPanel == null) {
            return;
        }
        BaseDragLayer dragLayer = mActivityContext.getDragLayer();
        if (dragLayer == null) {
            return;
        }

        if (mSourceView != null && mSourceView.isAttachedToWindow()) {
            dragLayer.getDescendantRectRelativeToSelf(mSourceView, mSourceRect);
        }

        int panelLeft = mPanel.getLeft();
        int panelTop = mPanel.getTop();
        int panelWidth = mPanel.getWidth();
        int panelHeight = mPanel.getHeight();
        if (panelWidth <= 0 || panelHeight <= 0) {
            return;
        }

        float panelCenterX = panelLeft + panelWidth / 2f;
        float panelCenterY = panelTop + panelHeight / 2f;

        int childCount = mRecycler.getChildCount();
        Rect itemDragRect = new Rect();
        Rect iconBounds = new Rect();
        Rect targetDragRect = new Rect();

        for (int i = 0; i < childCount; i++) {
            View child = mRecycler.getChildAt(i);
            if (child == null)
                continue;

            IconTransition it = new IconTransition();
            it.view = child;
            it.stagger = Math.min(0.18f, i * 0.035f);

            int adapterPos = mRecycler.getChildAdapterPosition(child);
            int appIndex = adapterPos >= 0 && adapterPos < mFilteredApps.size()
                    ? mApps.indexOf(mFilteredApps.get(adapterPos)) : -1;
            View sourcePreview = findSourcePreviewIcon(appIndex);

            dragLayer.getDescendantRectRelativeToSelf(child, itemDragRect);

            BubbleTextView childBtv = child instanceof BubbleTextView b ? b
                    : (child instanceof ViewGroup vg && vg.getChildCount() > 0
                            && vg.getChildAt(0) instanceof BubbleTextView b2 ? b2 : null);

            float itemIconCenterX;
            float itemIconCenterY;
            float itemIconSize = dp(56);

            if (childBtv != null) {
                childBtv.getIconBounds(iconBounds);
                if (iconBounds.width() > 0) {
                    itemIconCenterX = itemDragRect.left + iconBounds.centerX();
                    itemIconCenterY = itemDragRect.top + iconBounds.centerY();
                    itemIconSize = iconBounds.width();
                    child.setPivotX(iconBounds.centerX());
                    child.setPivotY(iconBounds.centerY());
                } else {
                    itemIconCenterX = itemDragRect.centerX();
                    itemIconCenterY = itemDragRect.centerY();
                    child.setPivotX(child.getWidth() / 2f);
                    child.setPivotY(child.getHeight() / 2f);
                }
            } else {
                itemIconCenterX = itemDragRect.centerX();
                itemIconCenterY = itemDragRect.centerY();
                child.setPivotX(child.getWidth() / 2f);
                child.setPivotY(child.getHeight() / 2f);
            }

            if (sourcePreview != null && sourcePreview.isAttachedToWindow()) {
                dragLayer.getDescendantRectRelativeToSelf(sourcePreview, targetDragRect);
                float targetCenterX;
                float targetCenterY;
                float targetIconSize;

                BubbleTextView targetBtv = sourcePreview instanceof BubbleTextView b ? b : null;
                if (targetBtv != null) {
                    targetBtv.getIconBounds(iconBounds);
                    if (iconBounds.width() > 0) {
                        targetCenterX = targetDragRect.left + iconBounds.centerX();
                        targetCenterY = targetDragRect.top + iconBounds.centerY();
                        targetIconSize = iconBounds.width();
                    } else {
                        targetCenterX = targetDragRect.centerX();
                        targetCenterY = targetDragRect.centerY();
                        targetIconSize = targetDragRect.width();
                    }
                } else {
                    targetCenterX = targetDragRect.centerX();
                    targetCenterY = targetDragRect.centerY();
                    targetIconSize = Math.min(targetDragRect.width(), targetDragRect.height());
                }

                it.targetDx = (targetCenterX - panelCenterX) / mStartScale - (itemIconCenterX - panelCenterX);
                it.targetDy = (targetCenterY - panelCenterY) / mStartScale - (itemIconCenterY - panelCenterY);

                float desiredScale = targetIconSize / itemIconSize;
                it.targetScale = Math.max(0.35f, Math.min(2.0f, desiredScale / mStartScale));
                it.targetAlpha = 1f;
            } else {
                float targetCenterX = panelCenterX;
                float targetCenterY = panelCenterY;
                if (mSourceView instanceof ViewGroup group && group.getChildCount() > 1
                        && group.getChildAt(1) instanceof GridLayout grid && grid.getChildCount() > 3) {
                    View previewGrid = grid.getChildAt(3);
                    if (previewGrid.isAttachedToWindow()) {
                        dragLayer.getDescendantRectRelativeToSelf(previewGrid, targetDragRect);
                        targetCenterX = targetDragRect.centerX();
                        targetCenterY = targetDragRect.centerY();
                    }
                } else if (!mSourceRect.isEmpty()) {
                    targetCenterX = mSourceRect.centerX();
                    targetCenterY = mSourceRect.centerY();
                }
                it.targetDx = (targetCenterX - panelCenterX) / mStartScale - (itemIconCenterX - panelCenterX);
                it.targetDy = (targetCenterY - panelCenterY) / mStartScale - (itemIconCenterY - panelCenterY);
                it.targetScale = 0.4f / mStartScale;
                it.targetAlpha = 0f;
            }

            mIconTransitions.add(it);
        }
    }

    private void resetIconTransitions() {
        for (IconTransition it : mIconTransitions) {
            if (it.view != null) {
                it.view.setTranslationX(0f);
                it.view.setTranslationY(0f);
                it.view.setScaleX(1f);
                it.view.setScaleY(1f);
                it.view.setAlpha(1f);
                BubbleTextView btv = it.view instanceof BubbleTextView b ? b
                        : (it.view instanceof ViewGroup vg && vg.getChildCount() > 0
                                && vg.getChildAt(0) instanceof BubbleTextView b2 ? b2 : null);
                if (btv != null) {
                    BubbleTextView.TEXT_ALPHA_PROPERTY.set(btv, 1f);
                }
            }
        }
        if (mHeading != null) {
            mHeading.setAlpha(1f);
        }
        if (mSearch != null) {
            mSearch.setAlpha(1f);
        }
    }

    private void animateOpen() {
        int targetWidth = mPanel.getWidth();
        int targetHeight = mPanel.getHeight();
        if (targetWidth <= 0 || targetHeight <= 0) {
            finishOpen();
            return;
        }

        mStartScale = 0.92f;

        mPanel.setPivotX(targetWidth / 2f);
        mPanel.setPivotY(targetHeight / 2f);

        prepareIconTransitions();
        setContentClippingEnabled(false);
        hideSourceView();

        applyAnimationProgress(0f);

        cancelAnimation();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(300);
        animator.setInterpolator(OPEN_INTERPOLATOR);
        animator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            applyAnimationProgress(progress);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mCurrentAnimator != animation) {
                    return;
                }
                mCurrentAnimator = null;
                finishOpen();
            }
        });
        mCurrentAnimator = animator;
        animator.start();
    }

    private void finishOpen() {
        hideSourceView();
        applyAnimationProgress(1f);
        resetIconTransitions();
        setContentClippingEnabled(true);
        mPanel.requestFocus();
        announceAccessibilityChanges();
    }

    private void setContentClippingEnabled(boolean enabled) {
        mPanel.setClipChildren(enabled);
        mPanel.setClipToOutline(enabled);
        mRecycler.setClipChildren(enabled);
    }

    private void applyAnimationProgress(float progress) {
        float scale = Utilities.mapRange(progress, mStartScale, 1f);
        mPanel.setScaleX(scale);
        mPanel.setScaleY(scale);
        mPanel.setTranslationX(0f);
        mPanel.setTranslationY(0f);

        mPanel.setAlpha(1f);
        float bgAlpha = Utilities.boundToRange(progress, 0f, 1f);
        mPanel.setPanelBackgroundAlpha(bgAlpha);

        float headerAlpha = Utilities.boundToRange((progress - 0.20f) / 0.80f, 0f, 1f);
        if (mHeading != null) {
            mHeading.setAlpha(headerAlpha);
        }
        if (mSearch != null && mSearch.getVisibility() == VISIBLE) {
            mSearch.setAlpha(headerAlpha);
        }

        float textAlpha = Utilities.boundToRange((progress - 0.35f) / 0.65f, 0f, 1f);

        for (IconTransition it : mIconTransitions) {
            if (it.view == null)
                continue;

            float iconProgress = Utilities.boundToRange(
                    (progress - it.stagger) / (1f - it.stagger), 0f, 1f);

            float tx = Utilities.mapRange(iconProgress, it.targetDx, 0f);
            float ty = Utilities.mapRange(iconProgress, it.targetDy, 0f);
            it.view.setTranslationX(tx);
            it.view.setTranslationY(ty);

            float s = Utilities.mapRange(iconProgress, it.targetScale, 1f);
            it.view.setScaleX(s);
            it.view.setScaleY(s);

            if (it.targetAlpha < 1f) {
                float extraAlpha = Utilities.boundToRange((iconProgress - 0.35f) / 0.65f, 0f, 1f);
                it.view.setAlpha(extraAlpha);
            } else {
                it.view.setAlpha(1f);
            }

            BubbleTextView btv = it.view instanceof BubbleTextView b ? b
                    : (it.view instanceof ViewGroup vg && vg.getChildCount() > 0
                            && vg.getChildAt(0) instanceof BubbleTextView b2 ? b2 : null);
            if (btv != null) {
                BubbleTextView.TEXT_ALPHA_PROPERTY.set(btv, textAlpha);
            }
        }

        mScrimProgress = progress;
        invalidate();
        updateAppsViewBlur(progress);
    }

    private void updateAppsViewBlur(float progress) {
        ActivityAllAppsContainerView<?> appsView = mActivityContext.getAppsView();
        if (appsView == null || !appsView.isAttachedToWindow() || !appsView.isHardwareAccelerated()) {
            return;
        }
        if (!CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled()
                || LauncherPrefs.BLUR_DEPTH.get(getContext()) <= 0) {
            mLastBlurRadius = 0;
            appsView.setRenderEffect(null);
            return;
        }
        float targetBlurRadius = Math.min(dp(24), LauncherPrefs.BLUR_DEPTH.get(getContext()));
        int rounded = Math.round(progress * targetBlurRadius);
        if (rounded <= 0 || progress <= 0f) {
            if (mLastBlurRadius != 0) {
                mLastBlurRadius = 0;
                appsView.setRenderEffect(null);
            }
            return;
        }
        if (rounded != mLastBlurRadius) {
            mLastBlurRadius = rounded;
            appsView.setRenderEffect(RenderEffect.createBlurEffect(
                    rounded, rounded, Shader.TileMode.CLAMP));
        }
    }

    private void cleanupBlur() {
        ActivityAllAppsContainerView<?> appsView = mActivityContext.getAppsView();
        if (appsView != null) {
            appsView.setRenderEffect(null);
        }
        mLastBlurRadius = 0;
        restoreSourceView();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mScrimProgress > 0f) {
            int maxAlpha = Utilities.isDarkTheme(getContext()) ? 120 : 75;
            int alpha = Math.round(mScrimProgress * maxAlpha);
            canvas.drawColor(Color.argb(alpha, 0, 0, 0));
        }
        super.onDraw(canvas);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        Rect insets = mActivityContext.getDragLayer().getInsets();
        android.view.WindowInsets windowInsets = getRootWindowInsets();
        int keyboard = windowInsets == null ? 0
                : windowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
        setPadding(insets.left + dp(20), insets.top + dp(24),
                insets.right + dp(20), Math.max(insets.bottom, keyboard) + dp(24));

        int availWidth = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
        int availHeight = Math.max(0, MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom());

        mPanel.getLayoutParams().width = Math.min(dp(MAX_PANEL_WIDTH_DP), availWidth);
        mPanel.getLayoutParams().height = LayoutParams.WRAP_CONTENT;

        if (!mEditing) {
            GridLayoutManager layout = (GridLayoutManager) mRecycler.getLayoutManager();
            int count = mFilteredApps.size();
            int maxSpan = Math.max(1, Math.min(4,
                    (mPanel.getLayoutParams().width - mPanel.getPaddingLeft()
                            - mPanel.getPaddingRight()) / dp(80)));
            int span = Math.min(Math.max(1, count), maxSpan);
            if (layout.getSpanCount() != span) {
                layout.setSpanCount(span);
            }
        }
        super.onMeasure(widthSpec, heightSpec);

        if (mPanel.getMeasuredHeight() > availHeight) {
            mPanel.measure(
                    MeasureSpec.makeMeasureSpec(mPanel.getMeasuredWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(availHeight, MeasureSpec.EXACTLY));
        }
    }

    @Override
    public android.view.WindowInsets onApplyWindowInsets(android.view.WindowInsets insets) {
        requestLayout();
        return super.onApplyWindowInsets(insets);
    }

    @Override
    public boolean canInterceptEventsInSystemGestureRegion() {
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (mIsClosing || mCurrentAnimator != null) {
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mIsClosing) {
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_DOWN
                && !mActivityContext.getDragLayer().isEventOverView(mPanel, event)) {
            close(true);
            return true;
        }
        return true;
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent event) {
        if (mIsClosing) {
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_DOWN
                && !mActivityContext.getDragLayer().isEventOverView(mPanel, event)) {
            close(true);
            return true;
        }
        return false;
    }

    @Override
    public boolean onControllerTouchEvent(MotionEvent ev) {
        return true;
    }

    @Override
    protected boolean isOfType(int type) {
        return (type & TYPE_CADDY_LIBRARY) != 0;
    }

    @Override
    protected void handleClose(boolean animate) {
        if (mIsClosing && animate) {
            return;
        }
        mIsClosing = true;

        float currentProgress = mScrimProgress;
        cancelAnimation();
        getContext().getSystemService(InputMethodManager.class)
                .hideSoftInputFromWindow(getWindowToken(), 0);
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || currentProgress <= 0f) {
            if (mPanel != null) {
                mPanel.setAlpha(0f);
                mPanel.setPanelBackgroundAlpha(0f);
            }
            cleanupBlur();
            remove();
            return;
        }
        animateClose(currentProgress);
    }

    private void animateClose(float fromProgress) {
        cancelAnimation();
        mRecycler.stopScroll();
        setContentClippingEnabled(false);

        if (fromProgress >= 1f || mIconTransitions.isEmpty()) {
            resetIconTransitions();
            mPanel.setTranslationX(0f);
            mPanel.setTranslationY(0f);
            mPanel.setScaleX(1f);
            mPanel.setScaleY(1f);
            prepareIconTransitions();
            hideSourceView();
        }

        ValueAnimator animator = ValueAnimator.ofFloat(fromProgress, 0f);
        long duration = Math.max(100, (long) (250 * fromProgress));
        animator.setDuration(duration);
        animator.setInterpolator(CLOSE_INTERPOLATOR);
        animator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            applyAnimationProgress(progress);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mCurrentAnimator != animation) {
                    return;
                }
                mCurrentAnimator = null;
                if (mPanel != null) {
                    mPanel.setAlpha(0f);
                    mPanel.setPanelBackgroundAlpha(0f);
                }
                cleanupBlur();
                remove();
            }
        });
        mCurrentAnimator = animator;
        animator.start();
    }

    private void cancelAnimation() {
        if (mOpenListener != null) {
            mOpenListener.removeListener();
            mOpenListener = null;
        }
        if (mCurrentAnimator != null) {
            ValueAnimator animator = mCurrentAnimator;
            mCurrentAnimator = null;
            animator.cancel();
        }
    }

    private void remove() {
        mIsOpen = false;
        mIsClosing = false;
        if (getParent() instanceof ViewGroup parent) {
            parent.removeView(this);
        }
        announceAccessibilityChanges();
    }

    @Override
    protected Pair<View, String> getAccessibilityTarget() {
        return Pair.create(mTitle, mTitle.getText().toString());
    }

    @Override
    protected View getAccessibilityInitialFocusView() {
        return mTitle;
    }

    @Override
    public void onDragStart(DragObject dragObject, DragOptions options) {
        close(false);
    }

    @Override
    public void onDragEnd() {
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        AllAppsStore store = mActivityContext.getActivityComponent().getAppsStore();
        store.registerIconContainer(mRecycler);
        store.addUpdateListener(mAppsListener);
        if (mDragController != null) {
            mDragController.addDragListener(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimation();
        mIsOpen = false;
        cleanupBlur();
        AllAppsStore store = mActivityContext.getActivityComponent().getAppsStore();
        store.unregisterIconContainer(mRecycler);
        store.removeUpdateListener(mAppsListener);
        if (mDragController != null) {
            mDragController.removeDragListener(this);
        }
        super.onDetachedFromWindow();
    }

    private void filter(String query) {
        String normalized = query.trim().toLowerCase(Locale.getDefault());
        mFilteredApps.clear();
        for (AppInfo app : mApps) {
            if (app.title.toString().toLowerCase(Locale.getDefault()).contains(normalized)) {
                mFilteredApps.add(app);
            }
        }
        mAdapter.notifyDataSetChanged();
        mEmpty.setVisibility(mFilteredApps.isEmpty() ? VISIBLE : GONE);
    }

    private void updateSelectionCount() {
        mSelectionCount.setText(getContext().getString(R.string.caddy_favorites_count, mSelected.size()));
    }

    private TextView text(String value, float size) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(CaddyStyle.textColor(getContext()));
        return text;
    }

    private TextView button(int label) {
        TextView button = text(getContext().getString(label), 14);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setMinHeight(dp(48));
        button.setMinWidth(dp(48));
        button.setBackground(CaddyStyle.background(getContext(), false));
        button.setFocusable(true);
        return button;
    }

    private int dp(float value) {
        return CaddyStyle.dp(getContext(), value);
    }

    private final class PanelView extends LinearLayout {
        private final Consumer<Boolean> mCrossWindowBlurListener = this::onCrossWindowBlurEnabledChanged;
        private float mBgAlpha = 0f;

        void setPanelBackgroundAlpha(float alpha) {
            mBgAlpha = Utilities.boundToRange(alpha, 0f, 1f);
            Drawable bg = getBackground();
            if (bg != null) {
                bg.setAlpha(Math.round(mBgAlpha * 255));
            }
            invalidate();
        }

        PanelView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(20), dp(18), dp(20), dp(18));
            setBackground(CaddyStyle.background(context, true));
            setClickable(true);
            setFocusableInTouchMode(true);
            setClipToOutline(true);
            setElevation(0f);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(32));
                }
            });
            setAlpha(0f);
            setPanelBackgroundAlpha(0f);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            CrossWindowBlurListeners.getInstance().addListener(
                    getContext().getMainExecutor(), mCrossWindowBlurListener);
        }

        @Override
        protected void onDetachedFromWindow() {
            CrossWindowBlurListeners.getInstance().removeListener(mCrossWindowBlurListener);
            super.onDetachedFromWindow();
        }

        private void onCrossWindowBlurEnabledChanged(boolean enabled) {
            if (!mIsOpen || !isAttachedToWindow()) {
                return;
            }
            setBackground(CaddyStyle.background(getContext(), true));
            setPanelBackgroundAlpha(mBgAlpha);
            updateAppsViewBlur(mScrimProgress);
        }
    }

    private final class AppHolder extends RecyclerView.ViewHolder {
        final BubbleTextView icon;
        final TextView title;
        final CheckBox check;

        AppHolder(View view, BubbleTextView icon, TextView title, CheckBox check) {
            super(view);
            this.icon = icon;
            this.title = title;
            this.check = check;
        }
    }

    private final class AppAdapter extends RecyclerView.Adapter<AppHolder> {
        @Override
        public AppHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            BubbleTextView icon = (BubbleTextView) mActivityContext.getLayoutInflater().inflate(
                    mEditing ? R.layout.caddy_app_icon : R.layout.all_apps_icon, parent, false);
            if (!mEditing) {
                icon.setOnFocusChangeListener(mFocusListener);
                int cellHeight = Math.max(dp(88), mActivityContext.getDeviceProfile()
                        .getAllAppsProfile().getCellHeightPx());
                icon.setLayoutParams(new RecyclerView.LayoutParams(
                        LayoutParams.MATCH_PARENT, cellHeight));
                return new AppHolder(icon, icon, null, null);
            }
            LinearLayout row = new LinearLayout(getContext()) {
                @Override
                public boolean onInterceptTouchEvent(MotionEvent event) {
                    return true;
                }
            };
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            row.setLayoutParams(new RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, dp(64)));
            row.addView(icon, new LayoutParams(dp(56), LayoutParams.MATCH_PARENT));
            icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            icon.setFocusable(false);
            TextView title = text("", 16);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(dp(12), 0, dp(8), 0);
            row.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            CheckBox check = new CheckBox(getContext());
            check.setClickable(false);
            check.setFocusable(false);
            check.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(check);
            row.setFocusable(true);
            row.setOnFocusChangeListener(mFocusListener);
            row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(CheckBox.class.getName());
                    info.setCheckable(true);
                    info.setChecked(host.isSelected());
                }
            });
            return new AppHolder(row, icon, title, check);
        }

        @Override
        public void onBindViewHolder(AppHolder holder, int position) {
            AppInfo app = mFilteredApps.get(position);
            holder.icon.reset();
            holder.icon.applyFromApplicationInfo(app);
            holder.icon.setTextColor(CaddyStyle.textColor(getContext()));
            if (mEditing) {
                String key = CaddyAppLibrary.appKey(getContext(), app);
                holder.title.setText(app.title);
                holder.check.setChecked(mSelected.contains(key));
                holder.itemView.setContentDescription(app.contentDescription);
                holder.itemView.setSelected(mSelected.contains(key));
                holder.itemView.setOnClickListener(v -> {
                    if (!mSelected.remove(key)) {
                        if (mSelected.size() == CaddyAppLibrary.FAVORITE_COUNT) {
                            Toast.makeText(getContext(), R.string.caddy_favorites_limit,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        mSelected.add(key);
                    }
                    holder.check.setChecked(mSelected.contains(key));
                    holder.itemView.setSelected(mSelected.contains(key));
                    updateSelectionCount();
                });
            } else {
                holder.icon.setOnClickListener(v -> {
                    mActivityContext.getItemOnClickListener().onClick(v);
                    close(false);
                });
                holder.icon.setOnLongClickListener(mActivityContext.getAllAppsItemLongClickListener());
                holder.icon.setLongPressTimeoutFactor(1f);
            }
        }

        @Override
        public int getItemCount() {
            return mFilteredApps.size();
        }
    }
}
