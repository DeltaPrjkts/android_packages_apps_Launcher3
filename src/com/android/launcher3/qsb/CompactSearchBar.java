package com.android.launcher3.qsb;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.PaintDrawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.CrossWindowBlurListeners;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.app.animation.Interpolators;
import com.android.axion.blur.ui.view.AxViewBackdropBlur;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.Reorderable;
import com.android.launcher3.Utilities;
import com.android.launcher3.graphics.ThemeManager;
import com.android.launcher3.util.MultiTranslateDelegate;
import com.android.launcher3.util.Themes;

import java.util.function.Consumer;

public class CompactSearchBar extends FrameLayout
        implements Reorderable, SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "CompactSearchBar";
    private static final String ACTION_GOOGLE_SEARCH = "google_search";
    private static final String ACTION_LENS = "lens";
    private static final String ACTION_MIC = "mic";
    private static final String ACTION_GEMINI = "gemini";
    private static final String ACTION_UNIVERSAL_SEARCH = "universal_search";

    private static final long SQUISH_DURATION_MS = 400L;
    private static final long ACTION_LAUNCH_DELAY_MS = 120L;

    private final MultiTranslateDelegate mTranslateDelegate = new MultiTranslateDelegate(this);
    private final Context mContext;
    private float mScaleForReorderBounce = 1f;

    private ImageView mActionIcon;
    private View mInner;
    private ObjectAnimator mSquishAnimator;
    private boolean mIsLaunching;
    private final Runnable mLaunchRunnable = this::launchActionInternal;
    private final ThemeManager.ThemeChangeListener mThemeChangeListener = () -> {
        setIcon();
        setUpBackground();
    };

    private final AxViewBackdropBlur mBackdropBlur = new AxViewBackdropBlur(this, false);
    private boolean mBlurDrawn;
    private boolean mCrossWindowBlurEnabled;
    private boolean mBlurEnabled = true;
    private int mBlurDepth = 45;

    private final Consumer<Boolean> mCrossWindowBlurListener =
            this::onCrossWindowBlurEnabledChanged;

    public CompactSearchBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    public CompactSearchBar(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
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
        removeCallbacks(mLaunchRunnable);
        if (mSquishAnimator != null) {
            mSquishAnimator.cancel();
            mSquishAnimator = null;
        }
        mIsLaunching = false;
        if (mInner != null) {
            mInner.setScaleX(1f);
            mInner.setScaleY(1f);
        }
        ThemeManager.INSTANCE.get(mContext).addChangeListener(mThemeChangeListener);
        LauncherPrefs.getPrefs(mContext).registerOnSharedPreferenceChangeListener(this);
        mBackdropBlur.onAttachedToWindow();
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
        removeCallbacks(mLaunchRunnable);
        if (mSquishAnimator != null) {
            mSquishAnimator.cancel();
            mSquishAnimator = null;
        }
        mIsLaunching = false;
        if (mInner != null) {
            mInner.setScaleX(1f);
            mInner.setScaleY(1f);
            mInner.setOnClickListener(null);
            mInner.setBackground(null);
        }
        mBackdropBlur.onDetachedFromWindow();
        mBlurDrawn = false;
        CrossWindowBlurListeners.getInstance().removeListener(mCrossWindowBlurListener);
        ThemeManager.INSTANCE.get(mContext).removeChangeListener(mThemeChangeListener);
        LauncherPrefs.getPrefs(mContext).unregisterOnSharedPreferenceChangeListener(this);
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
        mBackdropBlur.setBlurRadiusPx(mBlurDepth);
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
            setIcon();
            setUpBackground();
            postInvalidate();
        } else {
            hideBlur();
            removeCallbacks(mLaunchRunnable);
            if (mSquishAnimator != null) {
                mSquishAnimator.cancel();
                mSquishAnimator = null;
            }
            mIsLaunching = false;
            if (mInner != null) {
                mInner.setScaleX(1f);
                mInner.setScaleY(1f);
            }
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        mBackdropBlur.onVisibilityAggregated(isVisible);
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
        return super.verifyDrawable(who)
                || (mBackdropBlur != null && mBackdropBlur.verifyDrawable(who));
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

        updateBlurState();
        int left = mInner.getLeft();
        int top = mInner.getTop();
        int right = mInner.getRight();
        int bottom = mInner.getBottom();
        float cornerRadius = getCornerRadius();

        float scaleX = mInner.getScaleX();
        float scaleY = mInner.getScaleY();
        if (scaleX != 1f || scaleY != 1f) {
            float cx = (left + right) / 2f;
            float cy = (top + bottom) / 2f;
            float halfW = ((right - left) / 2f) * scaleX;
            float halfH = ((bottom - top) / 2f) * scaleY;
            left = Math.round(cx - halfW);
            top = Math.round(cy - halfH);
            right = Math.round(cx + halfW);
            bottom = Math.round(cy + halfH);
            cornerRadius = cornerRadius * Math.min(scaleX, scaleY);
        }

        boolean blurDrawn = mBackdropBlur.draw(canvas, left, top, right, bottom, cornerRadius);
        if (mBlurDrawn != blurDrawn) {
            mBlurDrawn = blurDrawn;
            setUpBackground();
        }
    }

    private boolean isBlurAvailable() {
        return mBlurEnabled && mCrossWindowBlurEnabled && mBlurDepth > 0
                && isHardwareAccelerated();
    }

    private boolean canDrawBlur() {
        return isBlurAvailable() && isAttachedToWindow() && mInner != null && mInner.isShown()
                && mInner.getWidth() > 0 && mInner.getHeight() > 0
                && getWindowVisibility() == VISIBLE;
    }

    private void updateBlurState() {
        mBackdropBlur.setEnabled(canDrawBlur());
        if (mInner != null) {
            mBackdropBlur.setSurfaceAlpha(Math.round(255f
                    * Utilities.boundToRange(mInner.getAlpha(), 0f, 1f)));
        }
    }

    private void hideBlur() {
        mBackdropBlur.setEnabled(false);
        if (mBlurDrawn) {
            mBlurDrawn = false;
            setUpBackground();
        }
    }

    private void setIcon() {
        if (mActionIcon == null) return;
        boolean isThemed = LauncherPrefs.DOCK_THEME.get(mContext);
        switch (getAction()) {
            case ACTION_LENS:
                mActionIcon.setImageTintList(null);
                mActionIcon.setImageResource(isThemed
                        ? R.drawable.ic_lens_themed
                        : R.drawable.ic_lens_color);
                break;
            case ACTION_MIC:
                mActionIcon.setImageTintList(null);
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
                mActionIcon.setImageTintList(null);
                mActionIcon.setImageResource(isThemed
                        ? R.drawable.ic_gemini_themed
                        : R.drawable.ic_gemini_color);
                break;
            case ACTION_UNIVERSAL_SEARCH:
                mActionIcon.setImageResource(R.drawable.ic_compact_search);
                TextView textView = findViewById(R.id.compact_search_bar_text);
                if (textView != null && textView.getTextColors() != null) {
                    mActionIcon.setImageTintList(textView.getTextColors());
                } else {
                    int textColor = Themes.getAttrColor(mContext, android.R.attr.textColorPrimary);
                    mActionIcon.setImageTintList(ColorStateList.valueOf(textColor));
                }
                break;
            case ACTION_GOOGLE_SEARCH:
            default:
                mActionIcon.setImageTintList(null);
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
        boolean canBlur = mBlurDrawn && isBlurAvailable();
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
        if (mInner != null) {
            mInner.setOnClickListener(view -> handleSearchClick());
        }
    }

    private void handleSearchClick() {
        if (mIsLaunching) return;
        mIsLaunching = true;

        if (!ValueAnimator.areAnimatorsEnabled()) {
            launchActionInternal();
            return;
        }

        playSquishAnimation();
        postDelayed(mLaunchRunnable, ACTION_LAUNCH_DELAY_MS);
    }

    private void launchActionInternal() {
        try {
            launchAction();
        } finally {
            postDelayed(() -> mIsLaunching = false, 300);
        }
    }

    private void playSquishAnimation() {
        if (mInner == null) return;

        if (mSquishAnimator != null) {
            mSquishAnimator.cancel();
        }

        mInner.setScaleX(1f);
        mInner.setScaleY(1f);
        if (mInner.getWidth() > 0 && mInner.getHeight() > 0) {
            mInner.setPivotX(mInner.getWidth() / 2f);
            mInner.setPivotY(mInner.getHeight() / 2f);
        }

        Keyframe kf0X = Keyframe.ofFloat(0f, 1f);
        Keyframe kf1X = Keyframe.ofFloat(120f / 400f, 1.066f);
        kf1X.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        Keyframe kf2X = Keyframe.ofFloat(260f / 400f, 0.967f);
        kf2X.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        Keyframe kf3X = Keyframe.ofFloat(1f, 1f);
        kf3X.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        PropertyValuesHolder pvhScaleX = PropertyValuesHolder.ofKeyframe(
                View.SCALE_X, kf0X, kf1X, kf2X, kf3X);

        Keyframe kf0Y = Keyframe.ofFloat(0f, 1f);
        Keyframe kf1Y = Keyframe.ofFloat(120f / 400f, 0.945f);
        kf1Y.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        Keyframe kf2Y = Keyframe.ofFloat(260f / 400f, 1.033f);
        kf2Y.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        Keyframe kf3Y = Keyframe.ofFloat(1f, 1f);
        kf3Y.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        PropertyValuesHolder pvhScaleY = PropertyValuesHolder.ofKeyframe(
                View.SCALE_Y, kf0Y, kf1Y, kf2Y, kf3Y);

        mSquishAnimator = ObjectAnimator.ofPropertyValuesHolder(mInner, pvhScaleX, pvhScaleY);
        mSquishAnimator.setDuration(SQUISH_DURATION_MS);
        mSquishAnimator.addUpdateListener(anim -> invalidate());
        mSquishAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                resetSquishState();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                resetSquishState();
            }
        });
        mSquishAnimator.start();
    }

    private void resetSquishState() {
        if (mInner != null) {
            mInner.setScaleX(1f);
            mInner.setScaleY(1f);
        }
        mSquishAnimator = null;
        invalidate();
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
