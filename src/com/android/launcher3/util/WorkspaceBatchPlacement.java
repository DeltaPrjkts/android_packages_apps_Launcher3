/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.util;

import java.util.Arrays;

public final class WorkspaceBatchPlacement {
    private static final int MAX_SEARCH_STEPS = 50_000;

    private WorkspaceBatchPlacement() { }

    public static int[][] findSpace(boolean[][] occupied, int[][] spans, int startX, int startY) {
        return findSpace(occupied, spans, startX, startY, 0);
    }

    public static int[][] findSpace(boolean[][] occupied, int[][] spans, int startX, int startY,
            int panelBoundary) {
        int width = occupied.length;
        int height = width == 0 ? 0 : occupied[0].length;
        if (width == 0 || height == 0 || spans.length == 0) return null;

        boolean[][] cells = new boolean[width][];
        int free = 0;
        for (int x = 0; x < width; x++) {
            if (occupied[x].length != height) return null;
            cells[x] = occupied[x].clone();
            for (boolean used : cells[x]) if (!used) free++;
        }
        Integer[] order = new Integer[spans.length];
        long required = 0;
        for (int i = 0; i < spans.length; i++) {
            if (spans[i].length != 2 || spans[i][0] < 1 || spans[i][1] < 1
                    || spans[i][0] > width || spans[i][1] > height) return null;
            required += (long) spans[i][0] * spans[i][1];
            order[i] = i;
        }
        if (required > free) return null;
        Arrays.sort(order, (a, b) -> {
            int area = Integer.compare(spans[b][0] * spans[b][1], spans[a][0] * spans[a][1]);
            return area != 0 ? area : Integer.compare(spans[b][0], spans[a][0]);
        });

        int start = Math.max(0, Math.min(height - 1, startY)) * width
                + Math.max(0, Math.min(width - 1, startX));
        int[][] result = new int[spans.length][2];
        int resized = 0;
        while (resized < order.length
                && spans[order[resized]][0] * spans[order[resized]][1] > 1) resized++;
        int[][] candidates = new int[resized][];
        for (int i = 0; i < resized; i++) {
            int index = order[i];
            int spanX = spans[index][0];
            int spanY = spans[index][1];
            int[] available = new int[width * height];
            int count = 0;
            for (int offset = 0; offset < width * height; offset++) {
                int cell = (start + offset) % (width * height);
                int x = cell % width;
                int y = cell / width;
                if (panelBoundary > 0 && x < panelBoundary && x + spanX > panelBoundary) continue;
                if (!isVacant(cells, x, y, spanX, spanY, width, height)) continue;
                available[count++] = cell;
            }
            if (count == 0) return null;
            candidates[i] = Arrays.copyOf(available, count);
        }
        if (!placeResized(cells, spans, order, candidates, new int[resized], result,
                0, new int[]{MAX_SEARCH_STEPS})) return null;
        int next = resized;
        for (int offset = 0; offset < width * height && next < order.length; offset++) {
            int cell = (start + offset) % (width * height);
            int x = cell % width;
            int y = cell / width;
            if (cells[x][y]) continue;
            result[order[next]][0] = x;
            result[order[next++]][1] = y;
        }
        return result;
    }

    private static boolean placeResized(boolean[][] cells, int[][] spans, Integer[] order,
            int[][] candidates, int[] chosen, int[][] result, int depth, int[] remainingSteps) {
        if (depth == candidates.length) return true;
        int width = cells.length;
        int height = cells[0].length;
        int index = order[depth];
        int spanX = spans[index][0];
        int spanY = spans[index][1];
        int first = depth > 0 && Arrays.equals(spans[index], spans[order[depth - 1]])
                ? chosen[depth - 1] + 1 : 0;
        for (int i = first; i < candidates[depth].length; i++) {
            if (--remainingSteps[0] < 0) return false;
            int cell = candidates[depth][i];
            int x = cell % width;
            int y = cell / width;
            if (!isVacant(cells, x, y, spanX, spanY, width, height)) continue;
            chosen[depth] = i;
            result[index][0] = x;
            result[index][1] = y;
            for (int dx = x; dx < x + spanX; dx++) {
                Arrays.fill(cells[dx], y, y + spanY, true);
            }
            if (placeResized(cells, spans, order, candidates, chosen, result,
                    depth + 1, remainingSteps)) return true;
            for (int dx = x; dx < x + spanX; dx++) {
                Arrays.fill(cells[dx], y, y + spanY, false);
            }
        }
        return false;
    }

    private static boolean isVacant(boolean[][] cells, int x, int y,
            int spanX, int spanY, int width, int height) {
        if (x + spanX > width || y + spanY > height) return false;
        for (int dx = x; dx < x + spanX; dx++) {
            for (int dy = y; dy < y + spanY; dy++) {
                if (cells[dx][dy]) return false;
            }
        }
        return true;
    }
}
