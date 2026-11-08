/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3;

import static com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP;

import android.app.AlertDialog;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import com.android.launcher3.celllayout.CellLayoutLayoutParams;
import com.android.launcher3.folder.Folder;
import com.android.launcher3.folder.FolderIcon;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.util.GridOccupancy;
import com.android.launcher3.util.WorkspaceBatchPlacement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class WorkspaceFolderActions {
    private final Launcher mLauncher;
    private final Workspace<?> mWorkspace;
    private AlertDialog mDialog;

    WorkspaceFolderActions(Launcher launcher, Workspace<?> workspace) {
        mLauncher = launcher;
        mWorkspace = workspace;
    }

    void show(ArrayList<BubbleTextView> selection) {
        if (!canEdit()) return;
        AbstractFloatingView.closeOpenViews(mLauncher, false, AbstractFloatingView.TYPE_SNACKBAR);
        CharSequence[] actions = {
                mLauncher.getText(R.string.batch_edit_create_folder),
                mLauncher.getText(R.string.batch_edit_move_to_folder),
                mLauncher.getText(R.string.batch_edit_ungroup_folder)
        };
        showDialog(new AlertDialog.Builder(mLauncher)
                .setTitle(R.string.batch_edit_folders)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        if (validateSelection(selection, 2)) askFolderName(selection);
                    } else if (which == 1) {
                        if (validateSelection(selection, 1)) {
                            chooseFolder(R.string.batch_edit_move_to_folder,
                                    folder -> moveToFolder(folder, selection));
                        }
                    } else {
                        chooseFolder(R.string.batch_edit_ungroup_folder, this::confirmUngroup);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    void close() {
        if (mDialog != null) {
            AlertDialog dialog = mDialog;
            mDialog = null;
            dialog.dismiss();
        }
    }

    private void showDialog(AlertDialog dialog) {
        close();
        mDialog = dialog;
        dialog.setOnDismissListener(dismissed -> {
            if (mDialog == dialog) mDialog = null;
        });
        dialog.show();
    }

    private boolean canEdit() {
        return mWorkspace.getBatchEditor().isActive()
                && !mLauncher.getDragController().isDragging()
                && !mLauncher.isWorkspaceLocked()
                && !LauncherPrefs.WORKSPACE_LOCK.get(mLauncher)
                && mWorkspace.isFinishedSwitchingState()
                && !mWorkspace.isPageInTransition();
    }

    private boolean validateSelection(List<BubbleTextView> selection, int minimum) {
        if (!canEdit()) return false;
        if (selection.size() < minimum) {
            showMessage(minimum == 2 ? R.string.batch_edit_folder_minimum
                    : R.string.batch_edit_folder_select_apps);
            return false;
        }
        for (BubbleTextView view : selection) {
            if (!(view.getTag() instanceof WorkspaceItemInfo info) || info.id == ItemInfo.NO_ID
                    || info.container != CONTAINER_DESKTOP
                    || mWorkspace.indexOfChild(parentLayout(view)) < 0) {
                showMessage(R.string.batch_edit_folder_unavailable);
                return false;
            }
            CellLayoutLayoutParams lp = (CellLayoutLayoutParams) view.getLayoutParams();
            if (!Folder.willAccept(info) || lp.cellHSpan != 1 || lp.cellVSpan != 1) {
                showMessage(R.string.batch_edit_folder_standard_icons);
                return false;
            }
        }
        return true;
    }

    private void askFolderName(ArrayList<BubbleTextView> selection) {
        EditText name = new EditText(mLauncher);
        name.setSingleLine(true);
        name.setHint(R.string.batch_edit_folder_name);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(50)});
        int margin = Math.round(24 * mLauncher.getResources().getDisplayMetrics().density);
        AlertDialog dialog = new AlertDialog.Builder(mLauncher)
                .setTitle(R.string.batch_edit_create_folder)
                .setView(name, margin, 0, margin, 0)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.batch_edit_create_folder,
                        (ignored, which) -> createFolder(selection, name.getText().toString().trim()))
                .create();
        showDialog(dialog);
        name.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private void chooseFolder(int title, Consumer<FolderIcon> action) {
        if (!canEdit()) return;
        ArrayList<FolderIcon> folders = new ArrayList<>();
        for (int page = 0; page < mWorkspace.getPageCount(); page++) {
            collectFolders((CellLayout) mWorkspace.getPageAt(page), folders);
        }
        collectFolders(mLauncher.getHotseat(), folders);
        if (folders.isEmpty()) {
            showMessage(R.string.batch_edit_no_folders);
            return;
        }
        CharSequence[] labels = new CharSequence[folders.size()];
        for (int i = 0; i < folders.size(); i++) {
            FolderIcon folder = folders.get(i);
            int page = mWorkspace.indexOfChild(parentLayout(folder));
            labels[i] = page < 0
                    ? mLauncher.getString(R.string.batch_edit_folder_in_dock, folderName(folder))
                    : mLauncher.getString(R.string.batch_edit_folder_on_page, folderName(folder),
                            page + 1);
        }
        showDialog(new AlertDialog.Builder(mLauncher)
                .setTitle(title)
                .setItems(labels, (dialog, which) -> action.accept(folders.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    private void collectFolders(CellLayout layout, List<FolderIcon> folders) {
        ShortcutAndWidgetContainer children = layout.getShortcutsAndWidgets();
        for (int i = 0; i < children.getChildCount(); i++) {
            if (children.getChildAt(i) instanceof FolderIcon folder && isAvailable(folder)) {
                folders.add(folder);
            }
        }
    }

    private boolean isAvailable(FolderIcon folder) {
        CellLayout parent = parentLayout(folder);
        return folder.mInfo != null && folder.mInfo.id != ItemInfo.NO_ID
                && folder.getFolder().isReadyForBatchEdit()
                && !folder.mInfo.getContents().isEmpty()
                && (mWorkspace.indexOfChild(parent) >= 0 || parent == mLauncher.getHotseat());
    }

    private String folderName(FolderIcon folder) {
        return TextUtils.isEmpty(folder.mInfo.title)
                ? mLauncher.getString(R.string.batch_edit_default_folder_name)
                : folder.mInfo.title.toString();
    }

    private void createFolder(ArrayList<BubbleTextView> selection, String name) {
        if (!validateSelection(selection, 2)) return;
        CellLayout target = (CellLayout) mWorkspace.getPageAt(mWorkspace.getNextPage());
        PagePlan page = new PagePlan(target);
        for (BubbleTextView icon : selection) {
            if (parentLayout(icon) == target) {
                CellLayoutLayoutParams lp = (CellLayoutLayoutParams) icon.getLayoutParams();
                page.occupied.markCells(lp.getCellX(), lp.getCellY(), 1, 1, false);
            }
        }
        int[][] position = WorkspaceBatchPlacement.findSpace(page.occupied.cells,
                new int[][]{{1, 1}}, 0, 0, page.boundary);
        if (position == null) {
            showMessage(R.string.batch_edit_no_space);
            return;
        }
        if (Workspace.EXTRA_EMPTY_SCREEN_IDS.contains(mWorkspace.getCellLayoutId(target))) {
            mWorkspace.commitExtraEmptyScreens();
        }
        ArrayList<ItemInfo> items = detachSelection(selection);
        FolderIcon folder = mLauncher.addFolder(target, CONTAINER_DESKTOP,
                mWorkspace.getCellLayoutId(target), position[0][0], position[0][1]);
        folder.mInfo.setTitle(TextUtils.isEmpty(name)
                ? mLauncher.getString(R.string.batch_edit_default_folder_name) : name,
                mLauncher.getModelWriter());
        folder.getFolder().reapplyItemInfo();
        folder.onTitleChanged(folder.mInfo.title);
        folder.getFolder().addFolderContents(items);
        target.onDropChild(folder);
        complete(mLauncher.getString(R.string.batch_edit_folder_created));
    }

    private void moveToFolder(FolderIcon folder, ArrayList<BubbleTextView> selection) {
        if (!validateSelection(selection, 1)) return;
        if (!isAvailable(folder)
                || selection.stream().anyMatch(v -> !folder.acceptDrop((ItemInfo) v.getTag()))) {
            showMessage(R.string.batch_edit_folder_unavailable);
            return;
        }
        folder.getFolder().addFolderContents(detachSelection(selection));
        int page = mWorkspace.indexOfChild(parentLayout(folder));
        if (page >= 0) mWorkspace.snapToPage(page);
        complete(mLauncher.getString(R.string.batch_edit_apps_grouped, folderName(folder)));
    }

    private ArrayList<ItemInfo> detachSelection(List<BubbleTextView> selection) {
        ArrayList<ItemInfo> items = new ArrayList<>(selection.size());
        for (BubbleTextView icon : selection) {
            items.add((ItemInfo) icon.getTag());
            mWorkspace.removeWorkspaceItem(icon);
        }
        return items;
    }

    private void confirmUngroup(FolderIcon folder) {
        if (!canEdit() || !isAvailable(folder)) return;
        showDialog(new AlertDialog.Builder(mLauncher)
                .setTitle(R.string.batch_edit_ungroup_folder)
                .setMessage(mLauncher.getString(R.string.batch_edit_ungroup_message,
                        folderName(folder)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.batch_edit_ungroup_folder,
                        (dialog, which) -> ungroup(folder))
                .create());
    }

    private void ungroup(FolderIcon folder) {
        if (!canEdit() || !isAvailable(folder)) return;
        FolderInfo info = folder.mInfo;
        String name = folderName(folder);
        ArrayList<ItemInfo> items = new ArrayList<>(info.getContents());
        items.sort(Folder.ITEM_POS_COMPARATOR);
        CellLayout source = parentLayout(folder);
        int firstPage = mWorkspace.indexOfChild(source);
        if (firstPage < 0) firstPage = mWorkspace.getNextPage();
        ArrayList<PagePlan> pages = new ArrayList<>();
        for (int i = 0; i < mWorkspace.getPageCount(); i++) {
            CellLayout layout = (CellLayout) mWorkspace.getPageAt(
                    (firstPage + i) % mWorkspace.getPageCount());
            PagePlan page = new PagePlan(layout);
            if (layout == source) {
                CellLayoutLayoutParams lp = (CellLayoutLayoutParams) folder.getLayoutParams();
                page.occupied.markCells(lp.getCellX(), lp.getCellY(), lp.cellHSpan, lp.cellVSpan, false);
            }
            pages.add(page);
        }
        ArrayList<Placement> placements = new ArrayList<>(items.size());
        CellLayout template = pages.get(0).layout;
        for (ItemInfo item : items) {
            if (item.id == ItemInfo.NO_ID || item.container != info.id
                    || !FolderInfo.willAcceptItemType(item.itemType)) {
                showMessage(R.string.batch_edit_folder_unavailable);
                return;
            }
            Placement placement = null;
            for (PagePlan page : pages) {
                placement = page.place(item);
                if (placement != null) break;
            }
            if (placement == null) {
                PagePlan page = new PagePlan(template);
                page.layout = null;
                page.occupied.clear();
                placement = page.place(item);
                if (placement == null) {
                    showMessage(R.string.batch_edit_no_space);
                    return;
                }
                pages.add(page);
            }
            placement.view = mLauncher.getItemInflater().inflateItem(item, template, CONTAINER_DESKTOP);
            if (placement.view == null) {
                showMessage(R.string.batch_edit_folder_unavailable);
                return;
            }
            placements.add(placement);
        }
        if (mWorkspace.getScreenWithId(Workspace.EXTRA_EMPTY_SCREEN_ID) != null) {
            mWorkspace.commitExtraEmptyScreens();
        }
        ArrayDeque<CellLayout> extraPages = new ArrayDeque<>();
        for (PagePlan page : pages) {
            if (page.layout != null) continue;
            if (extraPages.isEmpty()) {
                mWorkspace.addExtraEmptyScreens();
                for (int i = 0; i < mWorkspace.getPageCount(); i++) {
                    CellLayout layout = (CellLayout) mWorkspace.getPageAt(i);
                    if (Workspace.EXTRA_EMPTY_SCREEN_IDS.contains(mWorkspace.getCellLayoutId(layout))) {
                        extraPages.add(layout);
                    }
                }
                mWorkspace.commitExtraEmptyScreens();
            }
            page.layout = extraPages.removeFirst();
        }
        mWorkspace.removeWorkspaceItem(folder);
        info.getContents().clear();
        for (Placement placement : placements) {
            ItemInfo item = placement.item;
            CellLayout target = placement.page.layout;
            int screenId = mWorkspace.getCellLayoutId(target);
            mLauncher.getModelWriter().updateItemInfoProps(item, CONTAINER_DESKTOP,
                    screenId, placement.x, placement.y);
            item.rank = 0;
            mWorkspace.addInScreen(placement.view, CONTAINER_DESKTOP, screenId,
                    placement.x, placement.y, item.spanX, item.spanY);
            target.onDropChild(placement.view);
        }
        mLauncher.getModelWriter().ungroupFolderInDatabase(info, items);
        mWorkspace.snapToPage(firstPage);
        complete(mLauncher.getString(R.string.batch_edit_folder_ungrouped, name));
    }

    private void complete(String announcement) {
        mWorkspace.getBatchEditor().onFolderActionCompleted();
        mWorkspace.announceForAccessibility(announcement);
    }

    private void showMessage(int message) {
        Toast.makeText(mLauncher, message, Toast.LENGTH_SHORT).show();
    }

    private static CellLayout parentLayout(View view) {
        return view.getParent() instanceof ShortcutAndWidgetContainer container
                && container.getParent() instanceof CellLayout layout ? layout : null;
    }

    private static final class PagePlan {
        CellLayout layout;
        final GridOccupancy occupied;
        final int boundary;

        PagePlan(CellLayout layout) {
            this.layout = layout;
            occupied = new GridOccupancy(layout.getCountX(), layout.getCountY());
            layout.getOccupied().copyTo(occupied);
            boundary = layout instanceof MultipageCellLayout ? layout.getCountX() / 2 : 0;
        }

        Placement place(ItemInfo item) {
            int[][] result = WorkspaceBatchPlacement.findSpace(occupied.cells,
                    new int[][]{{item.spanX, item.spanY}}, 0, 0, boundary);
            if (result == null) return null;
            occupied.markCells(result[0][0], result[0][1], item.spanX, item.spanY, true);
            return new Placement(item, this, result[0][0], result[0][1]);
        }
    }

    private static final class Placement {
        final ItemInfo item;
        final PagePlan page;
        final int x;
        final int y;
        View view;

        Placement(ItemInfo item, PagePlan page, int x, int y) {
            this.item = item;
            this.page = page;
            this.x = x;
            this.y = y;
        }
    }
}
