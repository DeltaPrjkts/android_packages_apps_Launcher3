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
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.android.app.animation.Interpolators;
import com.android.axion.blur.ui.view.AxViewBackdropBlur;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.Reorderable;
import com.android.launcher3.Utilities;
import com.android.launcher3.graphics.ThemeManager;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.util.MultiTranslateDelegate;
import com.android.launcher3.util.Themes;
import com.android.launcher3.views.FloatingIconViewCompanion;

import java.util.function.Consumer;

public class QsbLayout extends FrameLayout
        implements Reorderable, FloatingIconViewCompanion,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "QsbLayout";

    private static final long SQUISH_DURATION_MS = 400L;
    private static final long ACTION_LAUNCH_DELAY_MS = 120L;

    private ImageView micIcon;
    private ImageView gIcon;
    private ImageView lensIcon;
    private ImageView geminiIcon;
    private FrameLayout inner;

    private final MultiTranslateDelegate mTranslateDelegate = new MultiTranslateDelegate(this);
    private float mScaleForReorderBounce = 1f;

    private boolean mIsThemed;

    private final AxViewBackdropBlur mBackdropBlur = new AxViewBackdropBlur(this, false);
    private final Consumer<Boolean> mCrossWindowBlurListener = this::onCrossWindowBlurEnabledChanged;
    private final ThemeManager.ThemeChangeListener mThemeChangeListener = () -> {
        mIsThemed = LauncherPrefs.DOCK_THEME.get(getContext());
        setupGIcon();
        setupLensIcon();
        setupMicIcon();
        setupGeminiIcon();
        setUpBackground();
        clipIconRipples();
        postInvalidate();
    };
    private boolean mCrossWindowBlurEnabled;
    private boolean mBlurEnabled;
    private int mBlurDepth;
    private boolean mBlurDrawn;

    private boolean mLandingSquish;
    private static final float LANDING_SQUISH_INTENSITY = 0.55f;

    private ObjectAnimator mSquishAnimator;
    private View mSquishingView;
    private boolean mIsLaunching;
    private Runnable mPendingLaunchAction;
    private final Runnable mLaunchRunnable = this::launchPendingAction;
    private final Runnable mResetLaunchingRunnable = () -> mIsLaunching = false;

    public QsbLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public QsbLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        micIcon = findViewById(R.id.mic_icon);
        gIcon = findViewById(R.id.g_icon);
        lensIcon = findViewById(R.id.lens_icon);
        geminiIcon = findViewById(R.id.gemini_icon);
        inner = findViewById(R.id.inner);

        ItemInfo qsbInfo = createQsbItemInfo(new Intent("android.search.action.GLOBAL_SEARCH")
                .setPackage(Utilities.GSA_PACKAGE));
        setTag(qsbInfo);
        if (inner != null) {
            inner.setTag(qsbInfo);
        }

        updateBlurPreferences();
        setUpMainSearch();
        setUpBackground();
        clipIconRipples();

        mIsThemed = LauncherPrefs.DOCK_THEME.get(getContext());

        setupGIcon();
        setupLensIcon();
        setupMicIcon();
        setupGeminiIcon();
    }

    private void clipIconRipples() {
        float cornerRadius = getCornerRadius();
        PaintDrawable pd = new PaintDrawable(Color.TRANSPARENT);
        pd.setCornerRadius(cornerRadius);
        if (micIcon != null) {
            micIcon.setClipToOutline(cornerRadius > 0);
            micIcon.setBackground(pd);
        }
        if (lensIcon != null) {
            lensIcon.setClipToOutline(cornerRadius > 0);
            lensIcon.setBackground(pd);
        }
        if (gIcon != null) {
            gIcon.setClipToOutline(cornerRadius > 0);
            gIcon.setBackground(pd);
        }
        if (geminiIcon != null) {
            geminiIcon.setClipToOutline(cornerRadius > 0);
            geminiIcon.setBackground(pd);
        }
    }

    private void setUpBackground() {
        if (inner == null) return;
        float cornerRadius = getCornerRadius();
        boolean canBlur = mBlurDrawn && isBlurAvailable();
        int opacity = Utilities.boundToRange(LauncherPrefs.HOTSEAT_QSB_OPACITY.get(getContext()), 0, 100);
        int alphaValue = Math.round(255f * opacity / 100f * (canBlur ? 0.45f : 1f));
        int baseColor = Themes.getAttrColor(getContext(), R.attr.qsbFillColor);
        if (LauncherPrefs.DOCK_THEME.get(getContext()))
            baseColor = Themes.getAttrColor(getContext(), R.attr.qsbFillColorThemed);
        int color = Color.argb(alphaValue, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor));
        float strokeWidth = LauncherPrefs.HOTSEAT_QSB_STROKE_WIDTH.get(getContext());

        PaintDrawable backgroundDrawable = new PaintDrawable(color);
        backgroundDrawable.setCornerRadius(cornerRadius);

        if (strokeWidth != 0f) {
            PaintDrawable strokeDrawable = new PaintDrawable(Themes.getColorAccent(getContext()));
            strokeDrawable.getPaint().setStyle(Paint.Style.STROKE);
            strokeDrawable.getPaint().setStrokeWidth(strokeWidth);
            strokeDrawable.setCornerRadius(cornerRadius);
            LayerDrawable combinedDrawable = new LayerDrawable(new Drawable[]{backgroundDrawable, strokeDrawable});

            inner.setClipToOutline(cornerRadius > 0);
            inner.setBackground(combinedDrawable);
        } else {
            inner.setClipToOutline(cornerRadius > 0);
            inner.setBackground(backgroundDrawable);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        setMeasuredDimension(width, height);

        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child != null) {
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        cancelSquishAnimation();
        ThemeManager.INSTANCE.get(getContext()).addChangeListener(mThemeChangeListener);
        LauncherPrefs.getPrefs(getContext()).registerOnSharedPreferenceChangeListener(this);
        mBackdropBlur.onAttachedToWindow();
        mCrossWindowBlurEnabled = CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled();
        CrossWindowBlurListeners.getInstance().addListener(
                getContext().getMainExecutor(), mCrossWindowBlurListener);
        updateBlurPreferences();
        mIsThemed = LauncherPrefs.DOCK_THEME.get(getContext());
        setUpMainSearch();
        setUpBackground();
        clipIconRipples();
        setupGIcon();
        setupLensIcon();
        setupMicIcon();
        setupGeminiIcon();
        postInvalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelSquishAnimation();
        mBackdropBlur.onDetachedFromWindow();
        mBlurDrawn = false;
        CrossWindowBlurListeners.getInstance().removeListener(mCrossWindowBlurListener);
        ThemeManager.INSTANCE.get(getContext()).removeChangeListener(mThemeChangeListener);
        LauncherPrefs.getPrefs(getContext()).unregisterOnSharedPreferenceChangeListener(this);
        super.onDetachedFromWindow();
        setOnClickListener(null);
        if (gIcon != null) gIcon.setOnClickListener(null);
        if (lensIcon != null) lensIcon.setOnClickListener(null);
        if (micIcon != null) micIcon.setOnClickListener(null);
        if (geminiIcon != null) geminiIcon.setOnClickListener(null);
        if (inner != null) inner.setBackground(null);
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
            cancelSquishAnimation();
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        mBackdropBlur.onVisibilityAggregated(isVisible);
        if (!isVisible) {
            hideBlur();
            cancelSquishAnimation();
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
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed && mBlurEnabled) {
            postInvalidate();
        }
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return super.verifyDrawable(who)
                || (mBackdropBlur != null && mBackdropBlur.verifyDrawable(who));
    }

    private void onCrossWindowBlurEnabledChanged(boolean enabled) {
        mCrossWindowBlurEnabled = enabled;
        updateBlurState();
        setUpBackground();
        postInvalidate();
    }

    private void updateBlurPreferences() {
        Context context = getContext();
        mBlurEnabled = LauncherPrefs.SEARCH_BAR_BLUR.get(context);
        mBlurDepth = Math.max(0, LauncherPrefs.BLUR_DEPTH.get(context));
        mBackdropBlur.setBlurRadiusPx(mBlurDepth);
        updateBlurState();
    }

    private boolean isBlurAvailable() {
        return mBlurEnabled && mCrossWindowBlurEnabled && mBlurDepth > 0
                && isHardwareAccelerated();
    }

    private boolean canDrawBlur() {
        return isBlurAvailable() && isAttachedToWindow() && inner != null && inner.isShown()
                && inner.getWidth() > 0 && inner.getHeight() > 0
                && getWindowVisibility() == VISIBLE;
    }

    private void updateBlurState() {
        mBackdropBlur.setEnabled(canDrawBlur());
        if (inner != null) {
            mBackdropBlur.setSurfaceAlpha(Math.round(255f
                    * Utilities.boundToRange(inner.getAlpha(), 0f, 1f)));
        }
    }

    private void hideBlur() {
        mBackdropBlur.setEnabled(false);
        if (mBlurDrawn) {
            mBlurDrawn = false;
            setUpBackground();
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        drawBlur(canvas);
        super.dispatchDraw(canvas);
    }

    private void drawBlur(Canvas canvas) {
        if (!canvas.isHardwareAccelerated() || !canDrawBlur()) {
            hideBlur();
            return;
        }

        updateBlurState();
        int left = inner.getLeft();
        int top = inner.getTop();
        int right = inner.getRight();
        int bottom = inner.getBottom();
        float cornerRadius = getCornerRadius();

        float scaleX = inner.getScaleX();
        float scaleY = inner.getScaleY();
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

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (LauncherPrefs.DOCK_THEME.getSharedPrefKey().equals(key)
                || LauncherPrefs.DOCK_MUSIC_SEARCH.getSharedPrefKey().equals(key)
                || LauncherPrefs.DOCK_UNIVERSAL_SEARCH.getSharedPrefKey().equals(key)
                || LauncherPrefs.HOTSEAT_QSB_OPACITY.getSharedPrefKey().equals(key)
                || LauncherPrefs.HOTSEAT_QSB_STROKE_WIDTH.getSharedPrefKey().equals(key)
                || LauncherPrefs.SEARCH_RADIUS_SIZE.getSharedPrefKey().equals(key)
                || LauncherPrefs.SEARCH_BAR_BLUR.getSharedPrefKey().equals(key)
                || LauncherPrefs.BLUR_DEPTH.getSharedPrefKey().equals(key)) {
            mIsThemed = LauncherPrefs.DOCK_THEME.get(getContext());
            updateBlurPreferences();
            setupGIcon();
            setupLensIcon();
            setupMicIcon();
            setupGeminiIcon();
            setUpBackground();
            clipIconRipples();
            postInvalidate();
        }
    }

    private void setUpMainSearch() {
        setOnClickListener(view -> {
            View target = inner != null ? inner : this;
            handleClickWithSquish(target, () -> {
                String searchPackage = QsbContainerView.getSearchWidgetPackageName(view.getContext());
                if (searchPackage == null) {
                    Toast.makeText(getContext(), R.string.activity_not_found, Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    Intent intent = new Intent("android.search.action.GLOBAL_SEARCH");
                    intent.setPackage(searchPackage);
                    startActivitySafely(target, intent);
                    return;
                } catch (ActivityNotFoundException | SecurityException e) {
                    Log.d(TAG, "GLOBAL_SEARCH not found for " + searchPackage);
                }

                try {
                    Intent intent = view.getContext().getPackageManager().getLaunchIntentForPackage(searchPackage);
                    if (intent != null) {
                        startActivitySafely(target, intent);
                        return;
                    }
                } catch (ActivityNotFoundException | SecurityException e) {
                    Log.d(TAG, "Launch intent not found for " + searchPackage);
                }

                try {
                    Intent intent = new Intent(Intent.ACTION_WEB_SEARCH);
                    intent.setPackage(searchPackage);
                    startActivitySafely(target, intent);
                    return;
                } catch (ActivityNotFoundException | SecurityException e) {
                    Log.d(TAG, "ACTION_WEB_SEARCH not found for " + searchPackage);
                }

                try {
                    Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"));
                    startActivitySafely(target, fallback);
                } catch (Exception e) {
                    Log.e(TAG, "Main search launch failed", e);
                    Toast.makeText(getContext(), R.string.activity_not_found, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void setupGIcon() {
        if (gIcon == null) return;

        gIcon.setImageResource(mIsThemed
                ? R.drawable.ic_super_g_themed
                : R.drawable.ic_super_g_color);

        gIcon.setOnClickListener(view -> handleClickWithSquish(gIcon, () -> {
            try {
                Intent intent = view.getContext().getPackageManager().getLaunchIntentForPackage(Utilities.GSA_PACKAGE);
                if (intent != null) {
                    startActivitySafely(gIcon, intent);
                }
            } catch (Exception e) {
                Log.e(TAG, "Google icon launch failed", e);
            }
        }));
    }

    private void setupLensIcon() {
        if (lensIcon == null) return;

        lensIcon.setImageResource(mIsThemed
                ? R.drawable.ic_lens_themed
                : R.drawable.ic_lens_color);

        lensIcon.setOnClickListener(view -> handleClickWithSquish(lensIcon, () -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setComponent(new ComponentName(Utilities.GSA_PACKAGE, Utilities.LENS_ACTIVITY));
                intent.setData(Uri.parse(Utilities.LENS_URI));
                intent.putExtra("LensHomescreenShortcut", true);
                startActivitySafely(lensIcon, intent);
            } catch (Exception e) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(Utilities.LENS_URI));
                    intent.setPackage(Utilities.GSA_PACKAGE);
                    intent.putExtra("LensHomescreenShortcut", true);
                    startActivitySafely(lensIcon, intent);
                } catch (Exception ex) {
                    lensIcon.setVisibility(View.GONE);
                    Log.e(TAG, "Lens icon launch failed", ex);
                }
            }
        }));
    }

    private void setupMicIcon() {
        if (micIcon == null) return;

        if (Utilities.isMusicSearchEnabled(getContext())) {
            micIcon.setImageResource(mIsThemed
                    ? R.drawable.ic_music_themed
                    : R.drawable.ic_music_color);
        } else {
            micIcon.setImageResource(mIsThemed
                    ? R.drawable.ic_mic_themed
                    : R.drawable.ic_mic_color);
        }

        micIcon.setOnClickListener(view -> handleClickWithSquish(micIcon, () -> {
            try {
                Intent intent = new Intent();
                if (Utilities.isMusicSearchEnabled(view.getContext())) {
                    intent.setAction("com.google.android.googlequicksearchbox.MUSIC_SEARCH");
                    intent.setPackage(QsbContainerView.getSearchWidgetPackageName(view.getContext()));
                } else {
                    intent.setAction("android.intent.action.VOICE_COMMAND");
                }
                startActivitySafely(micIcon, intent);
            } catch (Exception e) {
                micIcon.setVisibility(View.GONE);
                Log.e(TAG, "Mic icon launch failed", e);
            }
        }));
    }

    private void setupGeminiIcon() {
        if (geminiIcon == null) return;

        boolean universalSearch = LauncherPrefs.DOCK_UNIVERSAL_SEARCH.get(getContext());
        if (!universalSearch
                && !Utilities.isPackageInstalled(getContext(), Utilities.GEMINI_PACKAGE)) {
            geminiIcon.setVisibility(View.GONE);
            return;
        }

        geminiIcon.setVisibility(View.VISIBLE);
        geminiIcon.setContentDescription(getContext().getString(universalSearch
                ? R.string.universal_search_title : R.string.label_gemini_search));
        geminiIcon.setImageResource(mIsThemed
                ? R.drawable.ic_gemini_themed
                : R.drawable.ic_gemini_color);

        geminiIcon.setOnClickListener(view -> handleClickWithSquish(geminiIcon, () -> {
            if (LauncherPrefs.DOCK_UNIVERSAL_SEARCH.get(view.getContext())) {
                UniversalSearchLauncher.launch(view.getContext());
                return;
            }
            try {
                Intent intent = view.getContext().getPackageManager().getLaunchIntentForPackage(Utilities.GEMINI_PACKAGE);
                if (intent != null) {
                    startActivitySafely(geminiIcon, intent);
                }
            } catch (Exception e) {
                geminiIcon.setVisibility(View.GONE);
                Log.e(TAG, "Gemini launch failed", e);
            }
        }));
    }

    private void handleClickWithSquish(View target, Runnable action) {
        if (mIsLaunching || !isAttachedToWindow() || !isShown()) return;
        removeCallbacks(mResetLaunchingRunnable);
        mIsLaunching = true;
        mPendingLaunchAction = action;

        if (!ValueAnimator.areAnimatorsEnabled()) {
            launchPendingAction();
            return;
        }

        playSquishAnimation(target);
        postDelayed(mLaunchRunnable,
                (long) (ACTION_LAUNCH_DELAY_MS * ValueAnimator.getDurationScale()));
    }

    private void launchPendingAction() {
        Runnable action = mPendingLaunchAction;
        mPendingLaunchAction = null;
        if (action == null) return;
        if (!isAttachedToWindow() || !isShown() || getWindowVisibility() != VISIBLE) {
            cancelSquishAnimation();
            return;
        }
        try {
            action.run();
        } finally {
            if (isAttachedToWindow() && isShown()) {
                postDelayed(mResetLaunchingRunnable, 300);
            } else {
                mIsLaunching = false;
            }
        }
    }

    /** Gentler squish played when the app-close animation lands on the search bar. */
    public void playLandingSquishAnimation() {
        if (mIsLaunching || !isAttachedToWindow() || !isShown()) return;
        mLandingSquish = true;
        playSquishAnimation();
    }

    public void playSquishAnimation() {
        playSquishAnimation(inner != null ? inner : this);
    }

    private void playSquishAnimation(View target) {
        if (target == null) return;
        final float k = mLandingSquish ? LANDING_SQUISH_INTENSITY : 1f;
        mLandingSquish = false;

        if (mSquishAnimator != null) {
            mSquishAnimator.cancel();
        }

        mSquishingView = target;
        target.setScaleX(1f);
        target.setScaleY(1f);
        if (target.getWidth() > 0 && target.getHeight() > 0) {
            target.setPivotX(target.getWidth() / 2f);
            target.setPivotY(target.getHeight() / 2f);
        }

        Keyframe kf0X = Keyframe.ofFloat(0f, 1f);
        Keyframe kf1X = Keyframe.ofFloat(120f / 400f, 1f + 0.066f * k);
        kf1X.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        Keyframe kf2X = Keyframe.ofFloat(260f / 400f, 1f - 0.033f * k);
        kf2X.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        Keyframe kf3X = Keyframe.ofFloat(1f, 1f);
        kf3X.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        PropertyValuesHolder pvhScaleX = PropertyValuesHolder.ofKeyframe(
                View.SCALE_X, kf0X, kf1X, kf2X, kf3X);

        Keyframe kf0Y = Keyframe.ofFloat(0f, 1f);
        Keyframe kf1Y = Keyframe.ofFloat(120f / 400f, 1f - 0.055f * k);
        kf1Y.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        Keyframe kf2Y = Keyframe.ofFloat(260f / 400f, 1f + 0.033f * k);
        kf2Y.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        Keyframe kf3Y = Keyframe.ofFloat(1f, 1f);
        kf3Y.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        PropertyValuesHolder pvhScaleY = PropertyValuesHolder.ofKeyframe(
                View.SCALE_Y, kf0Y, kf1Y, kf2Y, kf3Y);

        mSquishAnimator = ObjectAnimator.ofPropertyValuesHolder(target, pvhScaleX, pvhScaleY);
        mSquishAnimator.setDuration(SQUISH_DURATION_MS);
        mSquishAnimator.setInterpolator(Interpolators.LINEAR);
        mSquishAnimator.addUpdateListener(anim -> {
            target.invalidate();
            invalidate();
            if (target.getParent() instanceof View parent) {
                parent.invalidate();
            }
        });
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
        if (mSquishingView != null) {
            mSquishingView.setScaleX(1f);
            mSquishingView.setScaleY(1f);
            mSquishingView.invalidate();
            mSquishingView = null;
        }
        mSquishAnimator = null;
        invalidate();
    }

    private void cancelSquishAnimation() {
        removeCallbacks(mLaunchRunnable);
        removeCallbacks(mResetLaunchingRunnable);
        mPendingLaunchAction = null;
        mLandingSquish = false;
        if (mSquishAnimator != null) {
            mSquishAnimator.cancel();
        }
        resetSquishState();
        mIsLaunching = false;
    }

    private ItemInfo createQsbItemInfo(Intent intent) {
        return QsbActivityLauncher.createItemInfo(intent);
    }

    private void startActivitySafely(View view, Intent intent) {
        QsbActivityLauncher.startActivity(getContext(), view, intent);
    }

    @Override
    public void setIconVisible(boolean visible) {
    }

    @Override
    public void setForceHideDot(boolean hide) {}

    private float getCornerRadius() {
        Resources res = getContext().getResources();
        float qsbWidgetHeight = res.getDimension(R.dimen.qsb_widget_height);
        float qsbWidgetPadding = res.getDimension(R.dimen.qsb_widget_vertical_padding);
        float innerHeight = qsbWidgetHeight - 2 * qsbWidgetPadding;
        return (innerHeight / 2) * ((float)LauncherPrefs.SEARCH_RADIUS_SIZE.get(getContext()) / 100f);
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
