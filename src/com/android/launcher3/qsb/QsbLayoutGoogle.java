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
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import com.android.app.animation.Interpolators;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.Reorderable;
import com.android.launcher3.Utilities;
import com.android.launcher3.graphics.ThemeManager;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.util.MultiTranslateDelegate;
import com.android.launcher3.views.FloatingIconViewCompanion;

public class QsbLayoutGoogle extends FrameLayout
        implements Reorderable, FloatingIconViewCompanion,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "QsbLayoutGoogle";

    private static final long SQUISH_DURATION_MS = 400L;
    private static final float LANDING_SQUISH_INTENSITY = 0.55f;
    private boolean mLandingSquish;
    private static final long ACTION_LAUNCH_DELAY_MS = 120L;

    private ImageView mMicIcon;
    private ImageView mGoogleIcon;
    private ImageView mLensIcon;
    private ImageView mAiModeButton;
    private final Context mContext;

    private final MultiTranslateDelegate mTranslateDelegate = new MultiTranslateDelegate(this);
    private float mScaleForReorderBounce = 1f;
    private final ThemeManager.ThemeChangeListener mThemeChangeListener = this::setIcons;

    private ObjectAnimator mSquishAnimator;
    private View mSquishingView;
    private boolean mIsLaunching;
    private Runnable mPendingLaunchAction;
    private final Runnable mLaunchRunnable = this::launchPendingAction;
    private final Runnable mResetLaunchingRunnable = () -> mIsLaunching = false;

    public QsbLayoutGoogle(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    public QsbLayoutGoogle(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mMicIcon = findViewById(R.id.mic_icon);
        mGoogleIcon = findViewById(R.id.g_icon);
        mLensIcon = findViewById(R.id.lens_icon);
        mAiModeButton = findViewById(R.id.ai_mode_button);

        setTag(createQsbItemInfo(new Intent("android.search.action.GLOBAL_SEARCH")
                .setPackage(Utilities.GSA_PACKAGE)));

        setIcons();
        setupMicIcon();
        setupLensIcon();
        setupAiModeButton();

        setupMainSearch();

        post(() -> {
            View parent = (View) getParent();
            if (parent != null && parent.getBackground() == null) {
                parent.setBackground(new QsbOuterDrawable(mContext));
            }
        });
    }

    private void setupMainSearch() {
        String searchPackage = QsbContainerView.getSearchWidgetPackageName(mContext);
        if (searchPackage != null) {
            setOnClickListener(v -> {
                View target = getParent() instanceof View ? (View) getParent() : this;
                handleClickWithSquish(target, () -> launchSearchActivity(target, searchPackage));
            });
        } else {
            setOnClickListener(null);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width  = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child != null) {
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        cancelSquishAnimation();
        setIcons();
        setupMainSearch();
        setupMicIcon();
        setupLensIcon();
        setupAiModeButton();
        ThemeManager.INSTANCE.get(mContext).addChangeListener(mThemeChangeListener);
        LauncherPrefs.getPrefs(mContext).registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelSquishAnimation();
        super.onDetachedFromWindow();
        ThemeManager.INSTANCE.get(mContext).removeChangeListener(mThemeChangeListener);
        LauncherPrefs.getPrefs(mContext).unregisterOnSharedPreferenceChangeListener(this);
        setOnClickListener(null);
        if (mMicIcon != null) mMicIcon.setOnClickListener(null);
        if (mLensIcon != null) mLensIcon.setOnClickListener(null);
        if (mAiModeButton != null) mAiModeButton.setOnClickListener(null);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) {
            cancelSquishAnimation();
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (!isVisible) {
            cancelSquishAnimation();
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (LauncherPrefs.DOCK_MUSIC_SEARCH.getSharedPrefKey().equals(key)
                || LauncherPrefs.DOCK_UNIVERSAL_SEARCH.getSharedPrefKey().equals(key)) {
            setIcons();
        }
    }

    private void setIcons() {
        boolean isThemed = LauncherPrefs.DOCK_THEME.get(getContext());
        boolean isMusicSearch = Utilities.isMusicSearchEnabled(mContext);

        mGoogleIcon.setImageResource(isThemed
                ? R.drawable.ic_super_g_themed
                : R.drawable.ic_super_g_color);

        if (mLensIcon != null) {
            mLensIcon.setImageResource(isThemed
                    ? R.drawable.ic_lens_themed_google
                    : R.drawable.ic_lens_color_google);
        }

        if (mMicIcon != null) {
            mMicIcon.setImageResource(isThemed
                    ? (isMusicSearch ? R.drawable.ic_music_themed : R.drawable.ic_mic_themed_google)
                    : (isMusicSearch ? R.drawable.ic_music_color : R.drawable.ic_mic_color_google));
        }

        if (mAiModeButton != null) {
            mAiModeButton.setContentDescription(mContext.getString(
                    LauncherPrefs.DOCK_UNIVERSAL_SEARCH.get(mContext)
                            ? R.string.universal_search_title : R.string.label_ai_mode));
            mAiModeButton.setImageResource(isThemed
                    ? R.drawable.ic_ai_mode_themed_google
                    : R.drawable.ic_ai_mode_color_google);
        }
    }

    private void setupMicIcon() {
        if (mMicIcon == null) return;
        mMicIcon.setOnClickListener(view -> handleClickWithSquish(mMicIcon, () -> {
            if (Utilities.isMusicSearchEnabled(mContext)) {
                launchAiMusicSearch(mMicIcon);
                return;
            }

            Intent intent = new Intent(Intent.ACTION_VOICE_COMMAND);
            try {
                startActivitySafely(mMicIcon, intent);
            } catch (ActivityNotFoundException e) {
                try {
                    String searchPackage = QsbContainerView.getSearchWidgetPackageName(mContext);
                    if (searchPackage != null) {
                        Intent gsaVoice = new Intent(Intent.ACTION_VOICE_COMMAND).setPackage(searchPackage);
                        startActivitySafely(mMicIcon, gsaVoice);
                    } else {
                        Toast.makeText(mContext, R.string.ai_mode_not_available, Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception ex) {
                    Toast.makeText(mContext, R.string.ai_mode_not_available, Toast.LENGTH_SHORT).show();
                }
            } catch (SecurityException e) {
                Toast.makeText(mContext, R.string.ai_mode_not_available, Toast.LENGTH_SHORT).show();
            }
        }));
    }

    private void setupLensIcon() {
        if (mLensIcon == null) return;
        if (!Utilities.isGSAEnabled(mContext)) {
            mLensIcon.setVisibility(View.GONE);
            return;
        }
        mLensIcon.setVisibility(View.VISIBLE);
        mLensIcon.setOnClickListener(v -> handleClickWithSquish(mLensIcon, () -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setComponent(new ComponentName(
                        Utilities.GSA_PACKAGE, Utilities.LENS_ACTIVITY));
                intent.setData(Uri.parse(Utilities.LENS_URI));
                intent.putExtra("LensHomescreenShortcut", true);
                startActivitySafely(mLensIcon, intent);
            } catch (Exception e) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(Utilities.LENS_URI));
                    intent.setPackage(Utilities.GSA_PACKAGE);
                    intent.putExtra("LensHomescreenShortcut", true);
                    startActivitySafely(mLensIcon, intent);
                } catch (Exception ex) {
                    mLensIcon.setVisibility(View.GONE);
                    Log.e(TAG, "Lens launch failed", ex);
                }
            }
        }));
    }

    private void launchSearchActivity(View view, String searchPackage) {
        try {
            Intent intent = new Intent("android.search.action.GLOBAL_SEARCH")
                    .setPackage(searchPackage);
            startActivitySafely(view, intent);
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.d(TAG, "GLOBAL_SEARCH not found for " + searchPackage);
        }

        try {
            Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(searchPackage);
            if (intent != null) {
                startActivitySafely(view, intent);
                return;
            }
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.d(TAG, "Launch intent not found for " + searchPackage);
        }

        try {
            Intent intent = new Intent(Intent.ACTION_WEB_SEARCH)
                    .setPackage(searchPackage);
            startActivitySafely(view, intent);
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.d(TAG, "ACTION_WEB_SEARCH not found for " + searchPackage);
        }

        try {
            Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"));
            startActivitySafely(view, fallback);
        } catch (Exception e) {
            Toast.makeText(mContext, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
            Log.e(TAG, "No search activity found for: " + searchPackage);
        }
    }

    private void setupAiModeButton() {
        if (mAiModeButton == null) return;
        mAiModeButton.setOnClickListener(v -> handleClickWithSquish(mAiModeButton, () -> {
            if (LauncherPrefs.DOCK_UNIVERSAL_SEARCH.get(mContext)) {
                UniversalSearchLauncher.launch(mContext);
                return;
            }
            if (Utilities.isAiMusicSearchEnabled(mContext)) {
                launchAiMusicSearch(mAiModeButton);
                return;
            }

            String[] aiActivities = {
                "com.google.android.googlequicksearchbox.OneSearchAimActivity",
                "com.google.android.googlequicksearchbox.GeminiGatewayActivity",
                "com.google.android.googlequicksearchbox.SearchActivity",
                "com.google.android.googlequicksearchbox.VoiceSearchActivity",
                "com.google.android.googlequicksearchbox.OneSearchActivity",
                "com.google.android.googlequicksearchbox.GoogleAppVoiceAssistEntrypoint"
            };

            for (String activityName : aiActivities) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW)
                            .setComponent(new ComponentName(Utilities.GSA_PACKAGE, activityName));
                    startActivitySafely(mAiModeButton, intent);
                    Log.d(TAG, "Successfully launched AI activity: " + activityName);
                    return;
                } catch (ActivityNotFoundException e) {
                    Log.d(TAG, "Activity not found: " + activityName);
                } catch (SecurityException e) {
                    Log.w(TAG, "Security exception: " + activityName + ", " + e.getMessage());
                }
            }

            String[] aiIntents = {
                "com.google.android.PIXEL_SEARCH",
                "android.intent.action.ASSIST",
                "android.intent.action.VOICE_ASSIST"
            };

            for (String intentAction : aiIntents) {
                try {
                    Intent intent = new Intent(intentAction)
                            .setPackage(Utilities.GSA_PACKAGE);
                    startActivitySafely(mAiModeButton, intent);
                    Log.d(TAG, "Launched via intent action: " + intentAction);
                    return;
                } catch (ActivityNotFoundException e) {
                    Log.d(TAG, "Intent action not found: " + intentAction);
                } catch (SecurityException e) {
                    Log.w(TAG, "Security exception: " + intentAction + ", " + e.getMessage());
                }
            }

            String searchPackage = QsbContainerView.getSearchWidgetPackageName(mContext);
            if (searchPackage != null) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VOICE_COMMAND)
                            .setPackage(searchPackage);
                    startActivitySafely(mAiModeButton, intent);
                    Log.d(TAG, "Falling back to voice command");
                } catch (Exception e) {
                    Log.e(TAG, "No AI or voice activities found");
                    Toast.makeText(mContext, R.string.ai_mode_not_available, Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(mContext, R.string.ai_mode_not_available, Toast.LENGTH_SHORT).show();
            }
        }));
    }

    private void launchAiMusicSearch(View view) {
        String searchPackage = QsbContainerView.getSearchWidgetPackageName(mContext);
        if (searchPackage == null) {
            Toast.makeText(mContext, R.string.ai_mode_not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .setAction("com.google.android.googlequicksearchbox.MUSIC_SEARCH")
                    .setPackage(searchPackage);
            startActivitySafely(view, intent);
            Log.d(TAG, "Successfully launched AI music search");
        } catch (ActivityNotFoundException e) {
            Log.d(TAG, "Music search not found, falling back to voice command");
            try {
                Intent intent = new Intent(Intent.ACTION_VOICE_COMMAND)
                        .setPackage(searchPackage);
                startActivitySafely(view, intent);
            } catch (Exception e2) {
                Toast.makeText(mContext, R.string.ai_mode_not_available, Toast.LENGTH_SHORT).show();
            }
        } catch (SecurityException e) {
            Toast.makeText(mContext, R.string.ai_mode_not_available, Toast.LENGTH_SHORT).show();
        }
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
        playSquishAnimation(getParent() instanceof View parent ? parent : this);
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
        QsbActivityLauncher.startActivity(mContext, view, intent);
    }

    @Override
    public void setIconVisible(boolean visible) {
    }

    @Override
    public void setForceHideDot(boolean hide) {}

    @Override public MultiTranslateDelegate getTranslateDelegate() { return mTranslateDelegate; }

    @Override
    public void setReorderBounceScale(float scale) {
        mScaleForReorderBounce = scale;
        super.setScaleX(scale);
        super.setScaleY(scale);
    }

    @Override public float getReorderBounceScale() { return mScaleForReorderBounce; }
}
