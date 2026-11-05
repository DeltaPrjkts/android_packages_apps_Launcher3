package com.android.launcher3.allapps;

import android.content.Context;
import android.util.AttributeSet;

import com.android.launcher3.BubbleTextView;
import com.android.launcher3.model.data.ItemInfoWithIcon;

public class CaddyIconView extends BubbleTextView {
    private int mAvailableIconSize = Integer.MAX_VALUE;

    public CaddyIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setCompoundDrawablePadding(0);
    }

    @Override
    public boolean shouldShowLabel() {
        return false;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE : MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE : MeasureSpec.getSize(heightSpec);
        if (width > 0 && height > 0) {
            mAvailableIconSize = Math.max(1,
                    Math.min(width, height) - CaddyStyle.dp(getContext(), 4));
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override
    protected int getIconSizeForItem(ItemInfoWithIcon info) {
        return Math.min(super.getIconSizeForItem(info), mAvailableIconSize);
    }

    @Override
    protected boolean shouldIgnoreTouchDown(float x, float y) {
        return false;
    }
}
