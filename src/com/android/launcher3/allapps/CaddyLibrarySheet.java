package com.android.launcher3.allapps;

import android.graphics.Rect;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Pair;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.BubbleTextView;
import com.android.launcher3.DropTarget.DragObject;
import com.android.launcher3.R;
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

public class CaddyLibrarySheet extends AbstractFloatingView implements DragController.DragListener {
    private final ActivityContext mActivityContext;
    private final LinearLayout mPanel;
    private final RecyclerView mRecycler;
    private final TextView mTitle;
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

    private CaddyLibrarySheet(ActivityContext activityContext, String title, List<AppInfo> apps,
            boolean editing, Runnable onFavoritesChanged) {
        super(activityContext.asContext(), null);
        mActivityContext = activityContext;
        mDragController = activityContext.getDragController();
        mApps = List.copyOf(apps);
        mFilteredApps = new ArrayList<>(apps);
        mEditing = editing;
        mSelected = CaddyAppLibrary.favorites(getContext(), apps);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);

        mPanel = new LinearLayout(getContext());
        mPanel.setOrientation(VERTICAL);
        mPanel.setPadding(dp(16), dp(12), dp(16), dp(16));
        mPanel.setBackground(CaddyStyle.background(getContext(), true));
        mPanel.setClickable(true);
        addView(mPanel, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout heading = new LinearLayout(getContext());
        heading.setGravity(Gravity.CENTER_VERTICAL);
        mTitle = text(title, 22);
        mTitle.setMaxLines(2);
        mTitle.setAccessibilityHeading(true);
        heading.addView(mTitle, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        TextView done = button(editing ? R.string.caddy_save : R.string.caddy_close);
        done.setOnClickListener(v -> {
            if (mEditing) {
                CaddyAppLibrary.saveFavorites(getContext(), mApps, mSelected);
                onFavoritesChanged.run();
            }
            close(true);
        });
        heading.addView(done);
        mPanel.addView(heading, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        mSelectionCount = text("", 13);
        mSelectionCount.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        if (editing) {
            TextView description = text(getContext().getString(R.string.caddy_choose_favorites), 14);
            description.setPadding(0, dp(8), 0, dp(8));
            mPanel.addView(description);
            LinearLayout selection = new LinearLayout(getContext());
            selection.setGravity(Gravity.CENTER_VERTICAL);
            selection.addView(mSelectionCount, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            TextView reset = button(R.string.caddy_use_random);
            reset.setOnClickListener(v -> {
                mSelected.clear();
                updateSelectionCount();
                mAdapter.notifyDataSetChanged();
            });
            selection.addView(reset);
            mPanel.addView(selection);
            updateSelectionCount();
        }

        EditText search = new EditText(getContext());
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setTextColor(CaddyStyle.textColor(getContext()));
        search.setHintTextColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                CaddyStyle.textColor(getContext()), 170));
        search.setHint(R.string.caddy_search_apps);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setOnEditorActionListener((view, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                return false;
            }
            getContext().getSystemService(InputMethodManager.class)
                    .hideSoftInputFromWindow(getWindowToken(), 0);
            mPanel.requestFocus();
            return true;
        });
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setBackground(CaddyStyle.background(getContext(), false));
        LayoutParams searchParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(48));
        searchParams.topMargin = dp(8);
        searchParams.bottomMargin = dp(12);
        mPanel.addView(search, searchParams);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) { }
        });

        mEmpty = text(getContext().getString(R.string.caddy_no_apps), 16);
        mEmpty.setGravity(Gravity.CENTER);
        mEmpty.setVisibility(GONE);
        mPanel.addView(mEmpty, new LayoutParams(LayoutParams.MATCH_PARENT, dp(56)));
        mRecycler = new RecyclerView(getContext());
        mRecycler.setClipToPadding(false);
        mRecycler.setLayoutManager(editing ? new LinearLayoutManager(getContext())
                : new GridLayoutManager(getContext(), 4));
        FocusedItemDecorator focus = new FocusedItemDecorator(new ViewGroupFocusHelper(mRecycler));
        mRecycler.addItemDecoration(focus);
        mFocusListener = focus.getFocusListener();
        mRecycler.setAdapter(mAdapter);
        mPanel.addView(mRecycler, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        mPanel.setFocusableInTouchMode(true);
    }

    public static void showCategory(ActivityContext context, CaddyAppLibrary.Category category) {
        new CaddyLibrarySheet(context, category.title, category.apps, false, () -> { }).show();
    }

    public static void editFavorites(ActivityContext context, List<AppInfo> apps, Runnable changed) {
        if (!apps.isEmpty()) {
            new CaddyLibrarySheet(context, context.asContext().getString(R.string.caddy_edit_favorites),
                    apps, true, changed).show();
        }
    }

    private void show() {
        AbstractFloatingView.closeOpenViews(mActivityContext, false, TYPE_CADDY_LIBRARY);
        mIsOpen = true;
        BaseDragLayer.LayoutParams params = new BaseDragLayer.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        params.ignoreInsets = true;
        mActivityContext.getDragLayer().addView(this, params);
        mPanel.requestFocus();
        announceAccessibilityChanges();
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            mPanel.setAlpha(0);
            mPanel.setTranslationY(dp(16));
            mPanel.animate().alpha(1).translationY(0).setDuration(180).start();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        Rect insets = mActivityContext.getDragLayer().getInsets();
        android.view.WindowInsets windowInsets = getRootWindowInsets();
        int keyboard = windowInsets == null ? 0
                : windowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
        setPadding(insets.left + dp(16), insets.top + dp(16),
                insets.right + dp(16), Math.max(insets.bottom, keyboard) + dp(16));
        int width = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
        int height = Math.max(0, MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom());
        mPanel.getLayoutParams().width = Math.min(dp(600), width);
        mPanel.getLayoutParams().height = Math.min(dp(680), height);
        if (!mEditing) {
            GridLayoutManager layout = (GridLayoutManager) mRecycler.getLayoutManager();
            layout.setSpanCount(Math.max(2, Math.min(5,
                    (mPanel.getLayoutParams().width - dp(32)) / dp(80))));
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override
    public android.view.WindowInsets onApplyWindowInsets(android.view.WindowInsets insets) {
        requestLayout();
        return super.onApplyWindowInsets(insets);
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN
                && !mActivityContext.getDragLayer().isEventOverView(mPanel, event)) {
            close(true);
            return true;
        }
        return false;
    }

    @Override
    protected boolean isOfType(int type) {
        return (type & TYPE_CADDY_LIBRARY) != 0;
    }

    @Override
    protected void handleClose(boolean animate) {
        mPanel.animate().cancel();
        getContext().getSystemService(InputMethodManager.class)
                .hideSoftInputFromWindow(getWindowToken(), 0);
        if (animate) {
            mPanel.animate().alpha(0).translationY(dp(16)).setDuration(140)
                    .withEndAction(this::remove).start();
        } else {
            remove();
        }
    }

    private void remove() {
        mIsOpen = false;
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
    public void onDragEnd() { }

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
        AllAppsStore store = mActivityContext.getActivityComponent().getAppsStore();
        store.unregisterIconContainer(mRecycler);
        store.removeUpdateListener(mAppsListener);
        if (mDragController != null) {
            mDragController.removeDragListener(this);
        }
        mPanel.animate().cancel();
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
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setMinHeight(dp(48));
        button.setMinWidth(dp(48));
        button.setBackground(CaddyStyle.background(getContext(), false));
        button.setFocusable(true);
        return button;
    }

    private int dp(float value) {
        return CaddyStyle.dp(getContext(), value);
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
                icon.setLayoutParams(new RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT,
                        Math.max(dp(96), mActivityContext.getDeviceProfile()
                                .getAllAppsProfile().getCellHeightPx())));
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
