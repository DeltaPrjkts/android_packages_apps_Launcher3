package com.android.launcher3.qsb;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.PaintDrawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.CrossWindowBlurListeners;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.Reorderable;
import com.android.launcher3.Utilities;
import com.android.launcher3.graphics.ThemeManager;
import com.android.launcher3.util.MultiTranslateDelegate;
import com.android.launcher3.util.Themes;
import com.android.launcher3.views.ActivityContext;

import java.util.function.Consumer;

public class CompactSearchBar extends FrameLayout
        implements Reorderable, SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "CompactSearchBar";
    private static final String ACTION_GOOGLE_SEARCH = "google_search";
    private static final String ACTION_LENS = "lens";
    private static final String ACTION_MIC = "mic";
    private static final String ACTION_GEMINI = "gemini";
    private static final String ACTION_UNIVERSAL_SEARCH = "universal_search";

    private final MultiTranslateDelegate mTranslateDelegate = new MultiTranslateDelegate(this);
    private final Context mContext;
    private final ActivityContext mActivityContext;
    private float mScaleForReorderBounce = 1f;

    private ImageView mActionIcon;
    private View mInner;
    private final ThemeManager.ThemeChangeListener mThemeChangeListener = () -> {
        setIcon();
        setUpBackground();
    };

    private BackgroundBlurDrawable mBackgroundBlurDrawable;
    private final Rect mLastBlurBounds = new Rect();
    private final Rect mInnerRect = new Rect();
    private float mLastCornerRadius = -1f;
    private int mLastBlurRadius = -1;
    private boolean mCrossWindowBlurEnabled;
    private boolean mBlurEnabled = true;
    private int mBlurDepth = 45;

    private final Consumer<Boolean> mCrossWindowBlurListener =
            this::onCrossWindowBlurEnabledChanged;
    private final ViewTreeObserver.OnPreDrawListener mBlurUpdateListener = () -> {
        updateBlurState();
        return true;
    };

    public CompactSearchBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mActivityContext = ActivityContext.lookupContextNoThrow(context);
    }

    public CompactSearchBar(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mActivityContext = ActivityContext.lookupContextNoThrow(context);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mActionIcon = findViewById(R.id.gemini_icon);
        mInner = findViewById(R.id.compact_search_bar_inner);

        updateBlurPreferences();
        setIcon();
        setUpBackground();
        setUpClick();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ThemeManager.INSTANCE.get(mContext).addChangeListener(mThemeChangeListener);
        LauncherPrefs.getPrefs(mContext).registerOnSharedPreferenceChangeListener(this);
        getViewTreeObserver().addOnPreDrawListener(mBlurUpdateListener);
        mCrossWindowBlurEnabled = CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled();
        CrossWindowBlurListeners.getInstance().addListener(
                getContext().getMainExecutor(), mCrossWindowBlurListener);
        updateBlurPreferences();
        setIcon();
        setUpBackground();
        setUpClick();
        postInvalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(mBlurUpdateListener);
        CrossWindowBlurListeners.getInstance().removeListener(mCrossWindowBlurListener);
        if (mBackgroundBlurDrawable != null) {
            mBackgroundBlurDrawable.setVisible(false, false);
            mBackgroundBlurDrawable.setCallback(null);
            mBackgroundBlurDrawable = null;
        }
        mLastBlurBounds.setEmpty();
        mLastCornerRadius = -1f;
        mLastBlurRadius = -1;
        ThemeManager.INSTANCE.get(mContext).removeChangeListener(mThemeChangeListener);
        LauncherPrefs.getPrefs(mContext).unregisterOnSharedPreferenceChangeListener(this);
        if (mInner != null) {
            mInner.setOnClickListener(null);
            mInner.setBackground(null);
        }
        super.onDetachedFromWindow();
    }

    private void onCrossWindowBlurEnabledChanged(boolean enabled) {
        mCrossWindowBlurEnabled = enabled;
        updateBlurState();
        setUpBackground();
        postInvalidate();
    }

    private void updateBlurPreferences() {
        mBlurEnabled = LauncherPrefs.COMPACT_SEARCH_BAR_BLUR.get(mContext);
        mBlurDepth = Math.max(0, LauncherPrefs.BLUR_DEPTH.get(mContext));
        updateBlurState();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (LauncherPrefs.DOCK_THEME.getSharedPrefKey().equals(key)
                || LauncherPrefs.DOCK_MUSIC_SEARCH.getSharedPrefKey().equals(key)
                || LauncherPrefs.COMPACT_SEARCH_BAR_ACTION.getSharedPrefKey().equals(key)
                || LauncherPrefs.HOTSEAT_QSB_OPACITY.getSharedPrefKey().equals(key)
                || LauncherPrefs.HOTSEAT_QSB_STROKE_WIDTH.getSharedPrefKey().equals(key)
                || LauncherPrefs.SEARCH_RADIUS_SIZE.getSharedPrefKey().equals(key)
                || LauncherPrefs.COMPACT_SEARCH_BAR_BLUR.getSharedPrefKey().equals(key)
                || LauncherPrefs.BLUR_DEPTH.getSharedPrefKey().equals(key)) {
            updateBlurPreferences();
            setIcon();
            setUpBackground();
            postInvalidate();
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed && mBlurEnabled) {
            postInvalidate();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            updateBlurPreferences();
            setUpBackground();
            postInvalidate();
        } else {
            hideBlur();
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible) {
            postInvalidate();
        } else {
            hideBlur();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            postInvalidate();
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || (who == mBackgroundBlurDrawable);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        drawBlur(canvas);
        super.dispatchDraw(canvas);
    }

    private void drawBlur(Canvas canvas) {
        if (!canvas.isHardwareAccelerated()) {
            return;
        }
        if (!canDrawBlur()) {
            hideBlur();
            return;
        }

        mInnerRect.set(mInner.getLeft(), mInner.getTop(), mInner.getRight(), mInner.getBottom());
        float cornerRadius = getCornerRadius();

        updateBlurState();
        if (mBackgroundBlurDrawable != null && mBackgroundBlurDrawable.isVisible()) {
            if (!mLastBlurBounds.equals(mInnerRect)) {
                mBackgroundBlurDrawable.setBounds(mInnerRect);
                mLastBlurBounds.set(mInnerRect);
            }
            if (mLastCornerRadius != cornerRadius) {
                mBackgroundBlurDrawable.setCornerRadius(cornerRadius);
                mLastCornerRadius = cornerRadius;
            }
            if (mLastBlurRadius != mBlurDepth) {
                mBackgroundBlurDrawable.setBlurRadius(mBlurDepth);
                mLastBlurRadius = mBlurDepth;
            }
            mBackgroundBlurDrawable.draw(canvas);
        }
    }

    private boolean isBlurAvailable() {
        return mBlurEnabled && mCrossWindowBlurEnabled && mBlurDepth > 0
                && isHardwareAccelerated();
    }

    private boolean canDrawBlur() {
        return isBlurAvailable() && isAttachedToWindow() && mInner != null && mInner.isShown()
                && mInner.getWidth() > 0 && mInner.getHeight() > 0
                && getWindowVisibility() == VISIBLE
                && (mActivityContext == null || !mActivityContext.isCrossWindowBlurPaused());
    }

    private void updateBlurState() {
        if (!canDrawBlur()) {
            hideBlur();
            return;
        }
        float alpha = 1f;
        View view = mInner;
        while (true) {
            alpha *= view.getAlpha();
            ViewParent parent = view.getParent();
            if (!(parent instanceof View)) {
                break;
            }
            view = (View) parent;
        }
        int blurAlpha = Math.round(255f * Utilities.boundToRange(alpha, 0f, 1f));
        if (blurAlpha == 0) {
            hideBlur();
            return;
        }
        if (mBackgroundBlurDrawable == null && getViewRootImpl() != null) {
            mBackgroundBlurDrawable = getViewRootImpl().createBackgroundBlurDrawable();
            if (mBackgroundBlurDrawable != null) {
                mBackgroundBlurDrawable.setCallback(this);
                invalidate();
            }
        }
        if (mBackgroundBlurDrawable == null) {
            return;
        }
        mBackgroundBlurDrawable.setAlpha(blurAlpha);
        if (!mBackgroundBlurDrawable.isVisible()) {
            mBackgroundBlurDrawable.setVisible(true, false);
            mLastBlurBounds.setEmpty();
            invalidate();
        }
    }

    private void hideBlur() {
        if (mBackgroundBlurDrawable != null && mBackgroundBlurDrawable.isVisible()) {
            mBackgroundBlurDrawable.setVisible(false, false);
            mLastBlurBounds.setEmpty();
        }
    }

    private void setIcon() {
        if (mActionIcon == null) return;
        boolean isThemed = LauncherPrefs.DOCK_THEME.get(mContext);
        switch (getAction()) {
            case ACTION_LENS:
                mActionIcon.setImageResource(isThemed
                        ? R.drawable.ic_lens_themed
                        : R.drawable.ic_lens_color);
                break;
            case ACTION_MIC:
                if (Utilities.isMusicSearchEnabled(mContext)) {
                    mActionIcon.setImageResource(isThemed
                            ? R.drawable.ic_music_themed
                            : R.drawable.ic_music_color);
                } else {
                    mActionIcon.setImageResource(isThemed
                            ? R.drawable.ic_mic_themed
                            : R.drawable.ic_mic_color);
                }
                break;
            case ACTION_GEMINI:
            case ACTION_UNIVERSAL_SEARCH:
                mActionIcon.setImageResource(isThemed
                        ? R.drawable.ic_gemini_themed
                        : R.drawable.ic_gemini_color);
                break;
            case ACTION_GOOGLE_SEARCH:
            default:
                mActionIcon.setImageResource(isThemed
                        ? R.drawable.ic_super_g_themed
                        : R.drawable.ic_super_g_color);
                break;
        }
        if (mInner != null) {
            mInner.setContentDescription(mContext.getString(
                    ACTION_UNIVERSAL_SEARCH.equals(getAction())
                            ? R.string.universal_search_title : R.string.label_search));
        }
    }

    private String getAction() {
        return LauncherPrefs.COMPACT_SEARCH_BAR_ACTION.get(mContext);
    }

    private void setUpBackground() {
        if (mInner == null) return;

        float cornerRadius = getCornerRadius();
        boolean canBlur = isBlurAvailable();
        int opacity = Utilities.boundToRange(LauncherPrefs.HOTSEAT_QSB_OPACITY.get(mContext), 0, 100);
        int alphaValue = Math.round(255f * opacity / 100f * (canBlur ? 0.4f : 1f));
        int baseColor = LauncherPrefs.DOCK_THEME.get(mContext)
                ? Themes.getAttrColor(mContext, R.attr.qsbFillColorThemed)
                : Themes.getAttrColor(mContext, R.attr.qsbFillColor);
        int color = Color.argb(alphaValue, Color.red(baseColor), Color.green(baseColor),
                Color.blue(baseColor));

        PaintDrawable backgroundDrawable = new PaintDrawable(color);
        backgroundDrawable.setCornerRadius(cornerRadius);

        float strokeWidth = LauncherPrefs.HOTSEAT_QSB_STROKE_WIDTH.get(mContext);
        if (strokeWidth != 0f) {
            PaintDrawable strokeDrawable = new PaintDrawable(Themes.getColorAccent(mContext));
            strokeDrawable.getPaint().setStyle(Paint.Style.STROKE);
            strokeDrawable.getPaint().setStrokeWidth(strokeWidth);
            strokeDrawable.setCornerRadius(cornerRadius);
            mInner.setBackground(new LayerDrawable(new Drawable[]{backgroundDrawable, strokeDrawable}));
        } else if (canBlur) {
            PaintDrawable strokeDrawable = new PaintDrawable(Color.argb(35, 255, 255, 255));
            strokeDrawable.getPaint().setStyle(Paint.Style.STROKE);
            strokeDrawable.getPaint().setStrokeWidth(Math.max(1, Utilities.dpToPx(1f)));
            strokeDrawable.setCornerRadius(cornerRadius);
            mInner.setBackground(new LayerDrawable(new Drawable[]{backgroundDrawable, strokeDrawable}));
        } else {
            mInner.setBackground(backgroundDrawable);
        }
        mInner.setClipToOutline(cornerRadius > 0);
    }

    private float getCornerRadius() {
        Resources res = mContext.getResources();
        return (res.getDimension(R.dimen.compact_search_bar_height) / 2f)
                * ((float) LauncherPrefs.SEARCH_RADIUS_SIZE.get(mContext) / 100f);
    }

    private void setUpClick() {
        View.OnClickListener listener = view -> launchAction();
        if (mInner != null) {
            mInner.setOnClickListener(listener);
        }
    }

    private void launchAction() {
        switch (getAction()) {
            case ACTION_LENS:
                launchLens();
                break;
            case ACTION_MIC:
                launchMicOrMusicSearch();
                break;
            case ACTION_GEMINI:
                launchGemini();
                break;
            case ACTION_UNIVERSAL_SEARCH:
                UniversalSearchLauncher.launch(mContext);
                break;
            case ACTION_GOOGLE_SEARCH:
            default:
                launchSearchActivity();
                break;
        }
    }

    private void launchSearchActivity() {
        String searchPackage = QsbContainerView.getSearchWidgetPackageName(mContext);
        if (searchPackage == null) {
            Toast.makeText(mContext, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            mContext.startActivity(new Intent("android.search.action.GLOBAL_SEARCH")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .setPackage(searchPackage));
            return;
        } catch (ActivityNotFoundException e) {
            Log.d(TAG, "GLOBAL_SEARCH not found for " + searchPackage);
        }

        try {
            Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(searchPackage);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                mContext.startActivity(intent);
                return;
            }
        } catch (ActivityNotFoundException e) {
            Log.d(TAG, "Launch intent not found for " + searchPackage);
        }

        Toast.makeText(mContext, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
    }

    private void launchLens() {
        try {
            mContext.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setComponent(new ComponentName(Utilities.GSA_PACKAGE, Utilities.LENS_ACTIVITY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .setData(Uri.parse(Utilities.LENS_URI))
                    .putExtra("LensHomescreenShortcut", true));
        } catch (Exception e) {
            Log.e(TAG, "Lens launch failed", e);
            Toast.makeText(mContext, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    private void launchMicOrMusicSearch() {
        try {
            Intent intent = new Intent();
            if (Utilities.isMusicSearchEnabled(mContext)) {
                intent.setAction("com.google.android.googlequicksearchbox.MUSIC_SEARCH");
                intent.setPackage(QsbContainerView.getSearchWidgetPackageName(mContext));
            } else {
                intent.setAction("android.intent.action.VOICE_COMMAND");
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            mContext.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Mic launch failed", e);
            Toast.makeText(mContext, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    private void launchGemini() {
        try {
            Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(
                    Utilities.GEMINI_PACKAGE);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                mContext.startActivity(intent);
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Gemini launch failed", e);
        }
        Toast.makeText(mContext, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
    }

    @Override
    public MultiTranslateDelegate getTranslateDelegate() {
        return mTranslateDelegate;
    }

    @Override
    public void setReorderBounceScale(float scale) {
        mScaleForReorderBounce = scale;
        super.setScaleX(scale);
        super.setScaleY(scale);
    }

    @Override
    public float getReorderBounceScale() {
        return mScaleForReorderBounce;
    }
}
