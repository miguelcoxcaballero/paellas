package com.inhouse.paellas;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The phone bottom bar (Inicio, Mi unidad, Papelera, Perfil) drawn natively, so taps,
 * the sliding yellow pill and haptics feel like Android rather than a web page. It is
 * the same design as the page's own bar (index.html, theme-mercado.css .mobile-nav),
 * which the page hides when the user agent says PaellasNav/1.
 *
 * The page stays the source of truth: it reports {type:"nav", active, visible} and the
 * shell calls window.paellasNav(name) for each tap. The pill moves on the tap itself
 * and settles on whatever the page reports next.
 */
final class NativeNav extends FrameLayout {
    interface Listener {
        void onTab(String name);
    }

    static final String[] TABS = {"home", "drive", "trash", "profile"};
    private static final int[] ICONS = {R.drawable.nav_home, R.drawable.nav_drive, R.drawable.nav_trash, R.drawable.nav_profile};
    private static final int[] LABELS = {R.string.nav_home, R.string.nav_drive, R.string.nav_trash, R.string.nav_profile};

    private final int ink;
    private final int sun;
    private final int idle;
    private final View pill;
    private final LinearLayout row;
    private final ImageView[] icons = new ImageView[TABS.length];
    private final TextView[] labels = new TextView[TABS.length];
    private final ValueAnimator[] tints = new ValueAnimator[TABS.length];
    private Listener listener;
    private int active = 0;
    private boolean shown = true;
    private boolean laidOut;
    private boolean pillMoving;

    NativeNav(Context context) {
        super(context);
        ink = context.getColor(R.color.nav_ink);
        sun = context.getColor(R.color.app_band);
        idle = context.getColor(R.color.nav_idle);
        int pad = dp(6);
        setPadding(pad, pad, pad, pad);
        setClipToPadding(false);

        GradientDrawable background = new GradientDrawable();
        background.setColor(ink);
        background.setCornerRadius(dp(32));
        setBackground(background);
        setElevation(dp(14));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
            }
        });

        pill = new View(context);
        GradientDrawable pillShape = new GradientDrawable();
        pillShape.setColor(sun);
        pillShape.setCornerRadius(dp(26));
        pill.setBackground(pillShape);
        addView(pill, new LayoutParams(0, LayoutParams.MATCH_PARENT));

        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        for (int i = 0; i < TABS.length; i++) row.addView(makeTab(context, i), new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        paint(active, false);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    String activeTab() {
        return TABS[active];
    }

    @SuppressLint("ClickableViewAccessibility")
    private View makeTab(Context context, int index) {
        LinearLayout tab = new LinearLayout(context);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setFocusable(true);
        tab.setClickable(true);
        tab.setContentDescription(context.getString(LABELS[index]));

        ImageView icon = new ImageView(context);
        icon.setImageResource(ICONS[index]);
        tab.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

        TextView label = new TextView(context);
        label.setText(LABELS[index]);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setIncludeFontPadding(false);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(3);
        tab.addView(label, labelParams);

        icons[index] = icon;
        labels[index] = label;
        // Pressed tabs sink a little and spring back, like a physical key.
        tab.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(.9f).scaleY(.9f).setDuration(70).setInterpolator(EASE).start();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(new OvershootInterpolator(3f)).start();
            }
            return false;
        });
        tab.setOnClickListener(view -> {
            Haptics.tap(view, "tap");
            select(TABS[index], true);
            if (listener != null) listener.onTab(TABS[index]);
        });
        return tab;
    }

    /** Moves the pill to {@code name}; unknown names are ignored. */
    void select(String name, boolean animate) {
        for (int i = 0; i < TABS.length; i++) {
            if (TABS[i].equals(name)) {
                if (i != active) paint(i, animate && laidOut);
                return;
            }
        }
    }

    private void paint(int index, boolean animate) {
        int previous = active;
        active = index;
        for (int i = 0; i < TABS.length; i++) {
            int target = i == index ? ink : idle;
            if (tints[i] != null) tints[i].cancel();
            ImageView icon = icons[i];
            TextView label = labels[i];
            if (!animate || (i != index && i != previous)) {
                applyTint(icon, label, target);
                icon.setScaleX(i == index ? 1.06f : 1f);
                icon.setScaleY(i == index ? 1.06f : 1f);
                continue;
            }
            int from = label.getCurrentTextColor();
            ValueAnimator tint = ValueAnimator.ofObject(new ArgbEvaluator(), from, target);
            tint.setDuration(180);
            tint.addUpdateListener(a -> applyTint(icon, label, (int) a.getAnimatedValue()));
            tint.start();
            tints[i] = tint;
            float scale = i == index ? 1.06f : 1f;
            icon.animate().scaleX(scale).scaleY(scale).setDuration(260).setInterpolator(new OvershootInterpolator(2.5f)).start();
        }
        if (!laidOut) return;
        float x = slot(index);
        if (animate) {
            pillMoving = true;
            pill.animate().translationX(x).setDuration(280).setInterpolator(new OvershootInterpolator(1.1f))
                    .withEndAction(() -> pillMoving = false).start();
        } else {
            pill.animate().cancel();
            pillMoving = false;
            pill.setTranslationX(x);
        }
    }

    private static void applyTint(ImageView icon, TextView label, int color) {
        icon.setImageTintList(ColorStateList.valueOf(color));
        label.setTextColor(color);
    }

    private float slot(int index) {
        return index * (float) (getWidth() - getPaddingLeft() - getPaddingRight()) / TABS.length;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // The pill is exactly one tab wide.
        int width = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        pill.getLayoutParams().width = Math.max(0, width / TABS.length);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        laidOut = true;
        if (!pillMoving) pill.setTranslationX(slot(active));
    }

    /** Slides the bar out of the way (dialogs, an open group, the keyboard) and back. */
    void setShown(boolean show) {
        if (show == shown) return;
        shown = show;
        animate().cancel();
        if (show) {
            setVisibility(VISIBLE);
            animate().translationY(0).alpha(1f).setDuration(220).setInterpolator(EASE).withEndAction(null).start();
        } else {
            float away = getHeight() + dp(28);
            animate().translationY(away).alpha(0f).setDuration(160).setInterpolator(EASE)
                    .withEndAction(() -> { if (!shown) setVisibility(INVISIBLE); }).start();
        }
    }

    static final PathInterpolator EASE = new PathInterpolator(.2f, .9f, .3f, 1f);

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
