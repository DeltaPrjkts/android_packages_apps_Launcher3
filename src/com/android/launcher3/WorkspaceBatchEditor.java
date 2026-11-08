/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3;

import static com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.android.launcher3.celllayout.CellLayoutLayoutParams;
import com.android.launcher3.dragndrop.DragOptions;
import com.android.launcher3.graphics.DragPreviewProvider;
import com.android.launcher3.graphics.WorkspaceSelectionBadge;
import com.android.launcher3.icons.BitmapRenderer;
import com.android.launcher3.icons.FastBitmapDrawable;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.touch.ItemLongClickListener;
import com.android.launcher3.util.GridOccupancy;
import com.android.launcher3.util.Themes;
import com.android.launcher3.util.WorkspaceBatchPlacement;
import com.android.launcher3.views.BaseDragLayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class WorkspaceBatchEditor {
    private final Launcher mLauncher;
    private final Workspace<?> mWorkspace;
    private final LinkedHashSet<BubbleTextView> mSelected = new LinkedHashSet<>();
    private final Map<BubbleTextView, WorkspaceSelectionBadge> mBadges = new LinkedHashMap<>();
    private final Map<View, NonSelectableState> mNonSelectableViews = new LinkedHashMap<>();
    private final ArrayList<BubbleTextView> mDragged = new ArrayList<>();
    private View mToolbar;
    private TextView mCount;
    private View mMove;
    private boolean mActive;
    private boolean mDragging;
    private boolean mClosing;
    private boolean mBlockTouch;
    private boolean mPendingCleanup;
    private boolean[][] mLastOccupied;
    private int[][] mLastSpans;
    private int[][] mLastPositions;
    private int mLastX;
    private int mLastY;
    private int mLastPanelBoundary;

    public WorkspaceBatchEditor(Launcher launcher, Workspace<?> workspace) {
        mLauncher = launcher;
        mWorkspace = workspace;
    }

    public boolean isActive() { return mActive; }

    public boolean isDragging() { return mDragging; }

    public boolean isSelected(View view) { return mSelected.contains(view); }

    public LauncherState.ScaleAndTranslation getWorkspaceScaleAndTranslation() {
        DeviceProfile profile = mLauncher.getDeviceProfile();
        float scale = profile.getWorkspaceSpringLoadScale(mLauncher);
        if (mWorkspace.getChildCount() == 0) {
            return new LauncherState.ScaleAndTranslation(scale, 0, 0);
        }
        ensureToolbar();
        Rect insets = profile.getInsets();
        int width = Math.max(1, mLauncher.getDragLayer().getWidth()
                - insets.left - insets.right - dp(32));
        mToolbar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        float top = insets.top + dp(16);
        float bottom = mLauncher.getDragLayer().getHeight() - insets.bottom
                - mToolbar.getMeasuredHeight() - dp(24);
        View page = mWorkspace.getChildAt(0);
        if (page.getHeight() > 0) {
            scale = Math.min(scale, Math.max(1, bottom - top) / page.getHeight());
        }
        float halfHeight = mWorkspace.getHeight() / 2f;
        float actualTop = mWorkspace.getTop() + halfHeight
                - (halfHeight - page.getTop()) * scale;
        return new LauncherState.ScaleAndTranslation(scale, 0, top - actualTop);
    }

    private void ensureToolbar() {
        if (mToolbar == null) {
            mToolbar = mLauncher.getLayoutInflater().inflate(
                    R.layout.workspace_batch_toolbar, mLauncher.getDragLayer(), false);
        }
    }

    public void onStateChanged(LauncherState state) {
        if (state == LauncherState.EDIT_MODE) {
            if (!mActive) enter();
        } else {
            close();
        }
    }

    private void enter() {
        mActive = true;
        mPendingCleanup = false;
        ensureToolbar();
        GradientDrawable background = new GradientDrawable();
        background.setColor(Themes.getAttrColor(mLauncher, R.attr.popupColorPrimary));
        background.setCornerRadius(dp(24));
        mToolbar.setBackground(background);
        BaseDragLayer.LayoutParams lp = new BaseDragLayer.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        lp.leftMargin = lp.rightMargin = dp(16);
        lp.bottomMargin = dp(12);
        mLauncher.getDragLayer().addView(mToolbar, lp);
        mCount = mToolbar.findViewById(R.id.batch_edit_count);
        mMove = mToolbar.findViewById(R.id.batch_edit_move);
        mToolbar.findViewById(R.id.batch_edit_done).setOnClickListener(v -> finish());
        mMove.setOnClickListener(v -> moveToCurrentPage());
        mToolbar.findViewById(R.id.batch_edit_new_page).setOnClickListener(v -> {
            if (mDragging || mLauncher.isWorkspaceLocked()
                    || LauncherPrefs.WORKSPACE_LOCK.get(mLauncher)) return;
            mWorkspace.addExtraEmptyScreens();
            CellLayout empty = mWorkspace.getScreenWithId(Workspace.EXTRA_EMPTY_SCREEN_ID);
            if (empty != null) mWorkspace.snapToPage(mWorkspace.indexOfChild(empty));
        });
        refreshIcons();
    }

    public void finish() {
        finish(true);
    }

    public void finish(boolean animated) {
        if (mActive) mLauncher.getStateManager().goToState(LauncherState.NORMAL, animated);
    }

    public void close() {
        if (!mActive || mClosing) return;
        mClosing = true;
        if (mDragging) mLauncher.getDragController().cancelDrag();
        restoreDraggedViews();
        for (WorkspaceSelectionBadge badge : mBadges.values()) badge.remove();
        mBadges.clear();
        mNonSelectableViews.forEach((view, state) -> state.restore(view));
        mNonSelectableViews.clear();
        mSelected.clear();
        mDragged.clear();
        mLastOccupied = null;
        mLastSpans = mLastPositions = null;
        mLauncher.getDragLayer().removeView(mToolbar);
        mToolbar = null;
        mCount = null;
        mMove = null;
        mActive = mDragging = false;
        mClosing = false;
        mPendingCleanup = true;
    }

    public void onStateTransitionComplete(LauncherState state) {
        if (mPendingCleanup && !mActive && state == LauncherState.NORMAL
                && !mLauncher.getDragController().isDragging()) {
            mPendingCleanup = false;
            mWorkspace.removeExtraEmptyScreen(true);
        }
    }

    private boolean isSelectable(View view) {
        return view instanceof BubbleTextView
                && view.getTag() instanceof WorkspaceItemInfo info
                && info.id != ItemInfo.NO_ID && info.container == CONTAINER_DESKTOP
                && parentLayout(view) != null
                && mWorkspace.indexOfChild(parentLayout(view)) >= 0;
    }

    public boolean onClick(View view) {
        if (!mActive) return false;
        if (!mDragging && isSelectable(view) && !mLauncher.isWorkspaceLocked()
                && !LauncherPrefs.WORKSPACE_LOCK.get(mLauncher)) {
            BubbleTextView icon = (BubbleTextView) view;
            if (!mSelected.remove(icon)) mSelected.add(icon);
            refreshIcons();
        }
        return true;
    }

    public boolean interceptTouch(CellLayout layout, MotionEvent event) {
        if (!mActive) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            int[] cell = new int[2];
            layout.pointToCellExact((int) event.getX(), (int) event.getY(), cell);
            View child = layout.getChildAt(cell[0], cell[1]);
            mBlockTouch = child != null && !isSelectable(child);
        }
        return mBlockTouch;
    }

    public void refreshIcons() {
        if (!mActive) return;
        mSelected.removeIf(view -> !isSelectable(view));
        mBadges.entrySet().removeIf(entry -> {
            if (isSelectable(entry.getKey())) return false;
            entry.getValue().remove();
            return true;
        });
        for (int page = 0; page < mWorkspace.getPageCount(); page++) {
            CellLayout layout = (CellLayout) mWorkspace.getPageAt(page);
            ViewGroup children = layout.getShortcutsAndWidgets();
            for (int i = 0; i < children.getChildCount(); i++) {
                View child = children.getChildAt(i);
                if (isSelectable(child)) {
                    BubbleTextView icon = (BubbleTextView) child;
                    WorkspaceSelectionBadge badge = mBadges.get(icon);
                    if (badge == null) {
                        badge = new WorkspaceSelectionBadge(icon);
                        mBadges.put(icon, badge);
                        icon.getOverlay().add(badge);
                    }
                    badge.setSelected(mSelected.contains(icon));
                } else if (!mNonSelectableViews.containsKey(child)) {
                    mNonSelectableViews.put(child, new NonSelectableState(child));
                }
            }
        }
        updateToolbar();
    }

    public boolean startDrag(View view) {
        if (!mActive) return false;
        if (mDragging || !isSelectable(view) || mLauncher.isWorkspaceLocked()
                || LauncherPrefs.WORKSPACE_LOCK.get(mLauncher)) return true;
        mSelected.add((BubbleTextView) view);
        refreshIcons();
        mDragged.clear();
        mDragged.add((BubbleTextView) view);
        for (BubbleTextView icon : mSelected) if (icon != view) mDragged.add(icon);
        mDragging = true;
        DragOptions options = new DragOptions();
        options.isWorkspaceBatchDrag = true;
        try {
            ItemLongClickListener.beginDrag(view, mLauncher, (ItemInfo) view.getTag(), options);
        } catch (RuntimeException e) {
            restoreDraggedViews();
            mDragging = false;
            mDragged.clear();
            updateToolbar();
            throw e;
        }
        return true;
    }

    public void onDragStart() {
        if (!mDragging) return;
        for (BubbleTextView icon : mDragged) {
            CellLayout parent = parentLayout(icon);
            if (parent != null) parent.markCellsAsUnoccupiedForView(icon);
            icon.setVisibility(View.INVISIBLE);
        }
        mToolbar.setVisibility(View.INVISIBLE);
    }

    public void onDragEnd() {
        if (!mDragging) return;
        restoreDraggedViews();
        mDragged.clear();
        mDragging = false;
        if (mActive && !mClosing) {
            mToolbar.setVisibility(View.VISIBLE);
            refreshIcons();
        }
    }

    public void onDropCompleted() {
        restoreDraggedViews();
    }

    private void restoreDraggedViews() {
        for (BubbleTextView icon : mDragged) {
            CellLayout parent = parentLayout(icon);
            if (parent != null) {
                parent.markCellsAsOccupiedForView(icon);
                icon.setVisibility(View.VISIBLE);
            }
        }
    }

    public void onItemsRemoved(Predicate<ItemInfo> matcher) {
        if (!mActive) return;
        if (mDragged.stream().anyMatch(v -> matcher.test((ItemInfo) v.getTag()))) {
            mLauncher.getDragController().cancelDrag();
        }
        mSelected.removeIf(v -> matcher.test((ItemInfo) v.getTag()));
        mWorkspace.post(this::refreshIcons);
    }

    private int[][] plan(CellLayout target, List<BubbleTextView> icons, int x, int y) {
        if (target == null || mWorkspace.indexOfChild(target) < 0 || icons.isEmpty()
                || mLauncher.isWorkspaceLocked() || LauncherPrefs.WORKSPACE_LOCK.get(mLauncher)) {
            return null;
        }
        GridOccupancy occupied = new GridOccupancy(target.getCountX(), target.getCountY());
        target.getOccupied().copyTo(occupied);
        int[][] spans = new int[icons.size()][2];
        for (int i = 0; i < icons.size(); i++) {
            BubbleTextView icon = icons.get(i);
            if (!isSelectable(icon)) return null;
            CellLayoutLayoutParams lp = (CellLayoutLayoutParams) icon.getLayoutParams();
            ItemInfo info = (ItemInfo) icon.getTag();
            if (lp.cellHSpan != info.spanX || lp.cellVSpan != info.spanY) return null;
            if (parentLayout(icon) == target) {
                occupied.markCells(lp.getCellX(), lp.getCellY(), lp.cellHSpan, lp.cellVSpan, false);
            }
            spans[i][0] = lp.cellHSpan;
            spans[i][1] = lp.cellVSpan;
        }
        int panelBoundary = target instanceof MultipageCellLayout ? target.getCountX() / 2 : 0;
        if (x != mLastX || y != mLastY || panelBoundary != mLastPanelBoundary
                || !Arrays.deepEquals(occupied.cells, mLastOccupied)
                || !Arrays.deepEquals(spans, mLastSpans)) {
            mLastOccupied = occupied.cells;
            mLastSpans = spans;
            mLastX = x;
            mLastY = y;
            mLastPanelBoundary = panelBoundary;
            mLastPositions = WorkspaceBatchPlacement.findSpace(occupied.cells, spans, x, y,
                    panelBoundary);
        }
        return mLastPositions;
    }

    public void visualizeDrop(CellLayout target, float x, float y, DropTarget.DragObject drag) {
        int[] cell = new int[2];
        target.pointToCellExact((int) x, (int) y, cell);
        int[][] positions = plan(target, mDragged, cell[0], cell[1]);
        if (positions == null) {
            target.clearDragOutlines();
        } else {
            ItemInfo first = (ItemInfo) mDragged.get(0).getTag();
            target.visualizeDropLocation(positions[0][0], positions[0][1],
                    first.spanX, first.spanY, drag);
        }
    }

    public boolean canDrop(CellLayout target, float x, float y) {
        if (target == null || mWorkspace.indexOfChild(target) < 0) return false;
        int[] cell = new int[2];
        target.pointToCellExact((int) x, (int) y, cell);
        if (plan(target, mDragged, cell[0], cell[1]) != null) return true;
        showNoSpace();
        return false;
    }

    public void drop(CellLayout target, float x, float y, DropTarget.DragObject drag) {
        drag.deferDragViewCleanupPostAnimation = false;
        int[] cell = new int[2];
        target.pointToCellExact((int) x, (int) y, cell);
        move(target, new ArrayList<>(mDragged), cell[0], cell[1]);
    }

    private void moveToCurrentPage() {
        if (mDragging || !mWorkspace.isFinishedSwitchingState()
                || mWorkspace.isPageInTransition()) return;
        move((CellLayout) mWorkspace.getPageAt(mWorkspace.getNextPage()),
                new ArrayList<>(mSelected), 0, 0);
    }

    private void move(CellLayout target, ArrayList<BubbleTextView> icons, int x, int y) {
        int[][] positions = plan(target, icons, x, y);
        if (positions == null) {
            showNoSpace();
            return;
        }
        if (Workspace.EXTRA_EMPTY_SCREEN_IDS.contains(mWorkspace.getCellLayoutId(target))) {
            mWorkspace.commitExtraEmptyScreens();
        }
        int screenId = mWorkspace.getCellLayoutId(target);
        if (screenId < 0) return;

        for (BubbleTextView icon : icons) parentLayout(icon).removeView(icon);
        ArrayList<ItemInfo> items = new ArrayList<>(icons.size());
        for (int i = 0; i < icons.size(); i++) {
            BubbleTextView icon = icons.get(i);
            ItemInfo info = (ItemInfo) icon.getTag();
            CellLayoutLayoutParams lp = (CellLayoutLayoutParams) icon.getLayoutParams();
            lp.useTmpCoords = false;
            lp.setTmpCellX(positions[i][0]);
            lp.setTmpCellY(positions[i][1]);
            mWorkspace.addInScreen(icon, CONTAINER_DESKTOP, screenId,
                    positions[i][0], positions[i][1], info.spanX, info.spanY);
            target.onDropChild(icon);
            icon.setVisibility(View.VISIBLE);
            info.cellX = positions[i][0];
            info.cellY = positions[i][1];
            items.add(info);
        }
        mLauncher.getModelWriter().moveItemsInDatabase(items, CONTAINER_DESKTOP, screenId);
        mWorkspace.snapToPage(mWorkspace.indexOfChild(target));
        mSelected.clear();
        refreshIcons();
        mWorkspace.announceForAccessibility(mLauncher.getResources().getQuantityString(
                R.plurals.batch_edit_moved, icons.size(), icons.size()));
    }

    private void updateToolbar() {
        if (!mActive || mToolbar == null) return;
        int count = mSelected.size();
        mCount.setText(count == 0 ? mLauncher.getString(R.string.batch_edit_select_apps)
                : mLauncher.getResources().getQuantityString(R.plurals.batch_edit_count, count, count));
        mMove.setEnabled(count > 0 && !mDragging);
    }

    private void showNoSpace() {
        Toast.makeText(mLauncher, R.string.batch_edit_no_space, Toast.LENGTH_SHORT).show();
    }

    private static CellLayout parentLayout(View view) {
        return view.getParent() instanceof ShortcutAndWidgetContainer container
                && container.getParent() instanceof CellLayout layout ? layout : null;
    }

    private int dp(int value) {
        return Math.round(value * mLauncher.getResources().getDisplayMetrics().density);
    }

    private static final class NonSelectableState {
        private final int accessibility;
        private final int focusable;
        private final boolean focusableInTouchMode;
        private final int descendants;

        NonSelectableState(View view) {
            accessibility = view.getImportantForAccessibility();
            focusable = view.getFocusable();
            focusableInTouchMode = view.isFocusableInTouchMode();
            descendants = view instanceof ViewGroup group ? group.getDescendantFocusability() : 0;
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            view.setFocusable(false);
            if (view instanceof ViewGroup group) {
                group.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            }
        }

        void restore(View view) {
            view.setImportantForAccessibility(accessibility);
            view.setFocusableInTouchMode(focusableInTouchMode);
            view.setFocusable(focusable);
            if (view instanceof ViewGroup group) group.setDescendantFocusability(descendants);
        }
    }

    public DragPreviewProvider createDragPreview(View view) {
        return new DragPreviewProvider(view) {
            @Override
            public Drawable createDrawable() {
                Drawable base = super.createDrawable();
                int width = base.getIntrinsicWidth();
                int height = base.getIntrinsicHeight();
                Bitmap bitmap = BitmapRenderer.createHardwareBitmap(width, height, canvas -> {
                    base.setBounds(0, 0, width, height);
                    base.draw(canvas);
                    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    float radius = Math.min(dp(12), Math.min(width, height) / 3f);
                    float cx = width - radius;
                    float cy = radius;
                    int accent = Themes.getAttrColor(mLauncher, android.R.attr.colorAccent);
                    paint.setColor(accent);
                    canvas.drawCircle(cx, cy, radius, paint);
                    paint.setColor(Color.luminance(accent) > 0.5f ? Color.BLACK : Color.WHITE);
                    paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setTextSize(radius * (mDragged.size() >= 100 ? 0.85f : 1.15f));
                    canvas.drawText(Integer.toString(mDragged.size()), cx,
                            cy - (paint.ascent() + paint.descent()) / 2, paint);
                });
                return bitmap == null ? base : new FastBitmapDrawable(bitmap);
            }
        };
    }
}
