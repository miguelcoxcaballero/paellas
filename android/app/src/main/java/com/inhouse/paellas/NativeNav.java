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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The phone bottom bar drawn natively, so taps, the sliding yellow pill and haptics
 * feel like Android rather than a web page. Same design as the page's own bar
 * (index.html initPlaces, theme-mercado.css .mobile-nav), which the page hides when
 * the user agent says PaellasNav/1.
 *
 * The bar shows the tabs of the screen the page is on (Inicio: Paellas, Archivos,
 * Papelera; a sheet: Grupos, Tabla, Entradas, Historial; a form: Formulario,
 * Respuestas, Ajustes). The page sends them with
 * {type:"nav", items:[{id, label, icon}], active, visible} and the shell calls
 * window.paellasNav(id) for each tap. The pill moves on the tap itself and settles
 * on whatever the page reports next.
 */
final class NativeNav extends FrameLayout {
    interface Listener {
        void onTab(String id);
    }

    private final int ink;
    private final int idle;
    private final View pill;
    private final LinearLayout row;
    private final List<String> ids = new ArrayList<>();
    private final List<ImageView> icons = new ArrayList<>();
    private final List<TextView> labels = new ArrayList<>();
    private final List<ValueAnimator> tints = new ArrayList<>();
    private String itemsKey = "";
    private Listener listener;
    private int active = -1;
    private boolean shown = true;
    private boolean laidOut;
    private boolean pillMoving;

    NativeNav(Context context) {
        super(context);
        ink = context.getColor(R.color.nav_ink);
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
        pillShape.setColor(context.getColor(R.color.app_band));
        pillShape.setCornerRadius(dp(26));
        pill.setBackground(pillShape);
        pill.setAlpha(0f);
        addView(pill, new LayoutParams(0, LayoutParams.MATCH_PARENT));

        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    int tabCount() {
        return ids.size();
    }

    /** Rebuilds the tabs when the page's list changes (another screen). */
    void setItems(JSONArray items) {
        StringBuilder key = new StringBuilder();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null) key.append(item.optString("id")).append('|').append(item.optString("label")).append(',');
        }
        if (key.toString().equals(itemsKey)) return;
        itemsKey = key.toString();
        for (ValueAnimator tint : tints) if (tint != null) tint.cancel();
        row.removeAllViews();
        ids.clear();
        icons.clear();
        labels.clear();
        tints.clear();
        active = -1;
        pill.animate().cancel();
        pill.setAlpha(0f);
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            row.addView(makeTab(item.optString("id"), item.optString("label"), item.optString("icon")),
                    new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        }
        requestLayout();
    }

    private static int iconFor(String name) {
        switch (name) {
            case "paellas":
                return R.drawable.nav_paellas;
            case "files":
                return R.drawable.nav_drive;
            case "trash":
                return R.drawable.nav_trash;
            case "groups":
                return R.drawable.nav_groups;
            case "table":
                return R.drawable.nav_table;
            case "entries":
                return R.drawable.nav_entries;
            case "history":
                return R.drawable.nav_history;
            case "form":
                return R.drawable.nav_form;
            case "settings":
                return R.drawable.nav_settings;
            default:
                return R.drawable.nav_home;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private View makeTab(String id, String text, String iconName) {
        Context context = getContext();
        LinearLayout tab = new LinearLayout(context);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setFocusable(true);
        tab.setClickable(true);
        tab.setContentDescription(text);

        ImageView icon = new ImageView(context);
        icon.setImageResource(iconFor(iconName));
        tab.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setIncludeFontPadding(false);
        label.setSingleLine(true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(3);
        tab.addView(label, labelParams);
        applyTint(icon, label, idle);

        ids.add(id);
        icons.add(icon);
        labels.add(label);
        tints.add(null);
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
            select(id, true);
            if (listener != null) listener.onTab(id);
        });
        return tab;
    }

    /** Moves the pill to tab {@code id}; an unknown id leaves no tab marked. */
    void select(String id, boolean animate) {
        int index = ids.indexOf(id);
        if (index == active) return;
        paint(index, animate && laidOut && active >= 0);
    }

    private void paint(int index, boolean animate) {
        int previous = active;
        active = index;
        for (int i = 0; i < ids.size(); i++) {
            int target = i == index ? ink : idle;
            if (tints.get(i) != null) tints.get(i).cancel();
            ImageView icon = icons.get(i);
            TextView label = labels.get(i);
            float scale = i == index ? 1.06f : 1f;
            if (!animate || (i != index && i != previous)) {
                applyTint(icon, label, target);
                icon.setScaleX(scale);
                icon.setScaleY(scale);
                continue;
            }
            ValueAnimator tint = ValueAnimator.ofObject(new ArgbEvaluator(), label.getCurrentTextColor(), target);
            tint.setDuration(180);
            tint.addUpdateListener(a -> applyTint(icon, label, (int) a.getAnimatedValue()));
            tint.start();
            tints.set(i, tint);
            icon.animate().scaleX(scale).scaleY(scale).setDuration(260).setInterpolator(new OvershootInterpolator(2.5f)).start();
        }
        if (index < 0) {
            pill.animate().alpha(0f).setDuration(120).start();
            return;
        }
        if (!laidOut) return;
        float x = slot(index);
        if (animate) {
            pillMoving = true;
            pill.animate().translationX(x).alpha(1f).setDuration(280).setInterpolator(new OvershootInterpolator(1.1f))
                    .withEndAction(() -> pillMoving = false).start();
        } else {
            pill.animate().cancel();
            pillMoving = false;
            pill.setTranslationX(x);
            pill.setAlpha(1f);
        }
    }

    private static void applyTint(ImageView icon, TextView label, int color) {
        icon.setImageTintList(ColorStateList.valueOf(color));
        label.setTextColor(color);
    }

    private float slot(int index) {
        int count = Math.max(1, ids.size());
        return index * (float) (getWidth() - getPaddingLeft() - getPaddingRight()) / count;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // The pill is exactly one tab wide.
        int width = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        pill.getLayoutParams().width = Math.max(0, width / Math.max(1, ids.size()));
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        laidOut = true;
        if (!pillMoving && active >= 0) {
            pill.setTranslationX(slot(active));
            pill.setAlpha(1f);
        }
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
