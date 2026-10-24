package com.inhouse.paellas;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The account, opened from the page's avatar ({type:"profile"}): a bottom sheet
 * with the account the page is signed in with and its actions. It is a view
 * inside the activity (not a dialog) so it animates with the page. Drag it down,
 * tap the scrim or press Back to close it.
 */
final class ProfileSheet extends FrameLayout {
    interface Listener {
        void onRefresh();

        void onAppUpdates();

        void onLogout();

        void onClosed();
    }

    private final View scrim;
    private final LinearLayout card;
    private final TextView avatar;
    private final TextView name;
    private final TextView email;
    private Listener listener;
    private boolean open;
    private float dragStart;
    private boolean dragging;

    @SuppressLint("ClickableViewAccessibility")
    ProfileSheet(Context context) {
        super(context);
        int ink = context.getColor(R.color.nav_ink);
        int sun = context.getColor(R.color.app_band);
        setVisibility(GONE);
        setClipChildren(false);

        scrim = new View(context);
        scrim.setBackgroundColor(context.getColor(R.color.sheet_scrim));
        scrim.setOnClickListener(view -> close());
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(10), dp(18), dp(18));
        GradientDrawable cardShape = new GradientDrawable();
        cardShape.setColor(0xFFFFFFFF);
        cardShape.setCornerRadius(dp(26));
        card.setBackground(cardShape);
        card.setElevation(dp(10));
        card.setClickable(true);
        LayoutParams cardParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        cardParams.leftMargin = dp(10);
        cardParams.rightMargin = dp(10);
        // The bottom bar steps aside while the sheet is open.
        cardParams.bottomMargin = dp(10);
        addView(card, cardParams);

        View grabber = new View(context);
        GradientDrawable grabberShape = new GradientDrawable();
        grabberShape.setColor(0x3326170A);
        grabberShape.setCornerRadius(dp(3));
        grabber.setBackground(grabberShape);
        LinearLayout.LayoutParams grabberParams = new LinearLayout.LayoutParams(dp(44), dp(5));
        grabberParams.gravity = Gravity.CENTER_HORIZONTAL;
        grabberParams.bottomMargin = dp(14);
        card.addView(grabber, grabberParams);

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        avatar = new TextView(context);
        avatar.setGravity(Gravity.CENTER);
        avatar.setTextColor(sun);
        avatar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        avatar.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        GradientDrawable avatarShape = new GradientDrawable();
        avatarShape.setShape(GradientDrawable.OVAL);
        avatarShape.setColor(ink);
        avatar.setBackground(avatarShape);
        head.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        name = text(context, 18, ink, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        email = text(context, 14, context.getColor(R.color.sheet_muted), Typeface.DEFAULT);
        copy.addView(name);
        copy.addView(email);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        copyParams.leftMargin = dp(14);
        head.addView(copy, copyParams);
        card.addView(head);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp(18);
        card.addView(actions, actionsParams);
        actions.addView(button(context, R.string.profile_refresh, false, () -> { close(); if (listener != null) listener.onRefresh(); }),
                weighted(0));
        actions.addView(button(context, R.string.profile_logout, false, () -> { close(); if (listener != null) listener.onLogout(); }),
                weighted(dp(10)));
        LinearLayout.LayoutParams updatesParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48));
        updatesParams.topMargin = dp(10);
        card.addView(button(context, R.string.profile_updates, true, () -> { close(); if (listener != null) listener.onAppUpdates(); }),
                updatesParams);

        // Drag the sheet down to close it.
        card.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragStart = event.getRawY();
                    dragging = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) card.setTranslationY(Math.max(0, event.getRawY() - dragStart));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    if (card.getTranslationY() > dp(70)) close();
                    else card.animate().translationY(0).setDuration(200).setInterpolator(new OvershootInterpolator(1.4f)).start();
                    return true;
                default:
                    return false;
            }
        });
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setAccount(String accountName, String accountEmail) {
        String shownName = TextUtils.isEmpty(accountName) ? getContext().getString(R.string.profile_account) : accountName;
        name.setText(shownName);
        email.setText(accountEmail == null ? "" : accountEmail);
        email.setVisibility(TextUtils.isEmpty(accountEmail) ? GONE : VISIBLE);
        avatar.setText(shownName.substring(0, 1).toUpperCase());
    }

    boolean isOpen() {
        return open;
    }

    void show() {
        if (open) return;
        open = true;
        setVisibility(VISIBLE);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(160).start();
        card.setAlpha(0f);
        card.setTranslationY(dp(36));
        card.animate().alpha(1f).translationY(0).setDuration(240).setInterpolator(NativeNav.EASE).start();
    }

    void close() {
        close(true);
    }

    /** {@code notify} false: the caller moves the bar itself (another tab was tapped). */
    void close(boolean notify) {
        if (!open) return;
        open = false;
        scrim.animate().alpha(0f).setDuration(150).start();
        card.animate().alpha(0f).translationY(card.getTranslationY() + dp(36)).setDuration(150).setInterpolator(NativeNav.EASE)
                .withEndAction(() -> { if (!open) setVisibility(GONE); }).start();
        if (notify && listener != null) listener.onClosed();
    }

    private LinearLayout.LayoutParams weighted(int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.leftMargin = leftMargin;
        return params;
    }

    private TextView text(Context context, int sp, int color, Typeface typeface) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(typeface);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    @SuppressLint("ClickableViewAccessibility")
    private TextView button(Context context, int label, boolean filled, Runnable action) {
        int ink = context.getColor(R.color.nav_ink);
        TextView button = new TextView(context);
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(24));
        if (filled) {
            shape.setColor(ink);
            button.setTextColor(context.getColor(R.color.app_band));
        } else {
            shape.setColor(0xFFFFFFFF);
            shape.setStroke(dp(2), ink);
            button.setTextColor(ink);
        }
        button.setBackground(shape);
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(.96f).scaleY(.96f).setDuration(70).start();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
            }
            return false;
        });
        button.setOnClickListener(view -> {
            Haptics.tap(view, "soft");
            action.run();
        });
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
