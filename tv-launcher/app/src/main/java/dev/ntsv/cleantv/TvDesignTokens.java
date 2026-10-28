package dev.ntsv.cleantv;

import android.view.View;
import android.widget.TextView;

/**
 * Central design tokens & animations for TV cards and rails.
 * Single source of truth for scale, elevations, and motion curves.
 */
public final class TvDesignTokens {

    public static final float FOCUS_SCALE = 1.08f;
    public static final float FOCUS_ELEVATION_DP = 18f;
    public static final int FOCUS_DURATION_MS = 160;
    public static final int BLUR_DURATION_MS = 120;
    public static final int TEXT_FADE_DURATION_MS = 160;
    public static final int TEXT_BLUR_DURATION_MS = 100;

    private TvDesignTokens() {}

    /**
     * Binds the standard tvOS/Android TV focus scale and subtitle animation to a card.
     */
    public static void animateFocus(View card, TextView title, boolean hasFocus) {
        animateFocus(card, title, hasFocus, 0f);
    }

    /**
     * As above; restTitleAlpha is the alpha of an unfocused card's title (0 hides it until the card is focused,
     * 0.75 keeps every title dimly visible and the focused one fully white).
     */
    public static void animateFocus(View card, TextView title, boolean hasFocus, float restTitleAlpha) {
        if (card == null) return;
        float density = card.getResources().getDisplayMetrics().density;

        if (hasFocus) {
            card.animate()
                    .scaleX(FOCUS_SCALE)
                    .scaleY(FOCUS_SCALE)
                    .translationZ(FOCUS_ELEVATION_DP * density)
                    .setDuration(FOCUS_DURATION_MS)
                    .start();

            if (title != null) {
                title.setVisibility(View.VISIBLE);
                title.animate().cancel();
                title.animate()
                        .alpha(1f)
                        .translationY(4f * density)   // ends just under the zoomed card
                        .setDuration(220)
                        .start();
            }
        } else {
            card.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .translationZ(0f)
                    .setDuration(BLUR_DURATION_MS)
                    .start();

            if (title != null) {
                title.animate().cancel();
                if (restTitleAlpha > 0f) {
                    title.animate().alpha(restTitleAlpha).translationY(0f).setDuration(220).start();
                } else {
                    title.animate()
                            .alpha(0f)
                            .translationY(-6f * density)
                            .setDuration(220)
                            .withEndAction(() -> title.setVisibility(View.INVISIBLE))
                            .start();
                }
            }
        }
    }
}

