package dev.ntsv.cleantv;

import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Universal adapter for all rails (Apps, Channels, Inputs).
 * Takes RailCard data sources and applies uniform motion, elevation shadow, and typography.
 */
public class RailCardsAdapter extends RecyclerView.Adapter<RailCardsAdapter.ViewHolder> {

    public interface FocusListener {
        void onCardFocus(RailCard card, View view);
    }

    public interface NavigationListener {
        boolean onNavigateUp(int position);
        boolean onNavigateDown(int position);
    }

    private final List<RailCard> items = new ArrayList<>();
    private FocusListener focusListener;
    private NavigationListener navigationListener;
    private int cornerRadiusPx;
    private boolean coloredGlow = true;
    /** Alpha of a card's title while it is not focused: 0 (hidden) or 0.75 (always show names). */
    private float restNameAlpha;

    public void setAlwaysShowNames(boolean always) {
        restNameAlpha = always ? 0.75f : 0f;
    }

    /** Glow of the focused card in the app's colour (true) or plain white (false). */
    public void setColoredGlow(boolean colored) {
        coloredGlow = colored;
    }

    /** Corner radius of every card, in pixels (0 = square). */
    public void setCornerRadius(int px) {
        cornerRadiusPx = px;
    }

    public void setItems(List<RailCard> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public List<RailCard> getItems() {
        return items;
    }

    public void setFocusListener(FocusListener l) {
        this.focusListener = l;
    }

    public void setNavigationListener(NavigationListener l) {
        this.navigationListener = l;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_rail_card, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        RailCard card = items.get(position);
        applyScale(holder, card.scale);
        holder.setCornerRadius(cornerRadiusPx);
        holder.title.setText(card.title);
        float titleDensity = holder.title.getResources().getDisplayMetrics().density;
        boolean focusedNow = holder.card.isFocused();   // a rebind can hit the focused card
        holder.title.setVisibility(focusedNow || restNameAlpha > 0f ? View.VISIBLE : View.INVISIBLE);
        holder.title.setAlpha(focusedNow ? 1f : restNameAlpha);
        holder.title.setTranslationY(focusedNow ? 4f * titleDensity : (restNameAlpha > 0f ? 0f : -6f * titleDensity));

        // Reset visibility
        holder.bannerFill.setVisibility(View.GONE);
        holder.artworkCenter.setVisibility(View.GONE);
        holder.artworkIcon.setVisibility(View.GONE);
        holder.cardLabel.setVisibility(View.GONE);
        holder.textFallback.setVisibility(View.GONE);

        // Bind artwork according to style
        if (card.artworkStyle == RailCard.ArtworkStyle.BANNER_FILL && card.artworkDrawable != null) {
            holder.bannerFill.setImageDrawable(card.artworkDrawable);
            holder.bannerFill.setVisibility(View.VISIBLE);
        } else if (card.artworkStyle == RailCard.ArtworkStyle.LOGO_CENTER) {
            if (card.artworkRes != 0) {
                holder.artworkCenter.setImageResource(card.artworkRes);
                holder.artworkCenter.setVisibility(View.VISIBLE);
            } else if (card.artworkDrawable != null) {
                holder.artworkCenter.setImageDrawable(card.artworkDrawable);
                holder.artworkCenter.setVisibility(View.VISIBLE);
            } else {
                holder.textFallback.setText(card.title);
                holder.textFallback.setVisibility(View.VISIBLE);
            }
        } else if (card.artworkStyle == RailCard.ArtworkStyle.ICON_CENTER) {
            if (card.artworkRes != 0) {
                holder.artworkCenter.setImageResource(card.artworkRes);
                holder.artworkCenter.setVisibility(View.VISIBLE);
            } else if (card.artworkDrawable != null) {
                holder.artworkCenter.setImageDrawable(card.artworkDrawable);
                holder.artworkCenter.setVisibility(View.VISIBLE);
            }
        } else if (card.artworkStyle == RailCard.ArtworkStyle.APP_ICON) {
            if (card.artworkDrawable != null) {
                holder.artworkIcon.setImageDrawable(card.artworkDrawable);
                holder.artworkIcon.setVisibility(View.VISIBLE);
            }
            holder.cardLabel.setText(card.title);
            holder.cardLabel.setVisibility(View.VISIBLE);
        }

        // Subtle badge (LCN, Active, etc.)
        if (card.badge != null && !card.badge.isEmpty()) {
            holder.badge.setText(card.badge);
            holder.badge.setVisibility(View.VISIBLE);
        } else {
            holder.badge.setVisibility(View.GONE);
        }

        // Clicks
        holder.card.setOnClickListener(v -> {
            if (card.actionListener != null) card.actionListener.onClick(card);
        });
        holder.card.setOnLongClickListener(v -> {
            if (card.actionListener != null) return card.actionListener.onLongClick(card);
            return false;
        });

        // Unified elevation shadow & text motion from TvDesignTokens
        holder.card.setOnFocusChangeListener((v, hasFocus) -> {
            TvDesignTokens.animateFocus(v, holder.title, hasFocus, restNameAlpha);
            holder.setFocusFx(hasFocus, coloredGlow ? card.accentColor : 0);
            if (hasFocus && focusListener != null) {
                focusListener.onCardFocus(card, v);
            }
        });

        // DPAD Direction navigation
        holder.card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int pos = holder.getBindingAdapterPosition();
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && navigationListener != null) {
                return navigationListener.onNavigateUp(pos);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && navigationListener != null) {
                return navigationListener.onNavigateDown(pos);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && pos == 0) {
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && pos == getItemCount() - 1) {
                return true;
            }
            return false;
        });
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        holder.setFocusFx(false, 0);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** item_rail_card.xml is 180x101dp with the title 7dp under it; scale all of that together. */
    private static void applyScale(ViewHolder h, float scale) {
        float d = h.itemView.getResources().getDisplayMetrics().density;
        int w = Math.round(180 * d * scale);
        int hgt = Math.round(101 * d * scale);
        setSize(h.card, w, hgt);
        FrameLayout.LayoutParams tlp = (FrameLayout.LayoutParams) h.title.getLayoutParams();
        int top = hgt + Math.round(4 * d);
        if (tlp.width != w || tlp.topMargin != top) {
            tlp.width = w;
            tlp.topMargin = top;
            h.title.setLayoutParams(tlp);
        }
        setSize(h.artworkCenter, Math.round(92 * d * scale), Math.round(54 * d * scale));
        setSize(h.artworkIcon, Math.round(48 * d * scale), Math.round(48 * d * scale));
    }

    private static void setSize(View v, int w, int h) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            v.setLayoutParams(lp);
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final FrameLayout card;
        final ImageView bannerFill;
        final ImageView artworkCenter;
        final ImageView artworkIcon;
        final TextView cardLabel;
        final TextView textFallback;
        final TextView badge;
        final TextView title;
        /**
         * Border drawn over the focused card: white with a black edge on both sides, so it shows on white
         * icons as well as dark ones. Its alpha pulses so the selection is easy to find.
         */
        private final GradientDrawable ringOuter = new GradientDrawable();
        private final GradientDrawable ringInner = new GradientDrawable();
        private final LayerDrawable ring = new LayerDrawable(new Drawable[]{ringOuter, ringInner});
        private final int ringInset;
        private int radius;
        private ValueAnimator pulse;

        void setCornerRadius(int px) {
            if (px == radius) return;
            radius = px;
            ringOuter.setCornerRadius(px);
            ringInner.setCornerRadius(Math.max(0, px - ringInset));
            card.invalidateOutline();
        }

        /** Focused card: a bright border that breathes, plus a glow (a coloured shadow, Android 9 and up). */
        void setFocusFx(boolean focused, int accent) {
            if (pulse != null) {
                pulse.cancel();
                pulse = null;
            }
            if (!focused) {
                ring.setAlpha(0);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    card.setOutlineSpotShadowColor(Color.BLACK);
                    card.setOutlineAmbientShadowColor(Color.BLACK);
                }
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                int glow = accent == 0 ? Color.WHITE : blendToWhite(accent, 0.55f);
                // Light glow all around, black key shadow underneath: visible on any background.
                card.setOutlineAmbientShadowColor(glow);
                card.setOutlineSpotShadowColor(Color.BLACK);
            }
            ring.setAlpha(255);
            pulse = ValueAnimator.ofInt(255, 140);
            pulse.setDuration(850);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.addUpdateListener(a -> ring.setAlpha((Integer) a.getAnimatedValue()));
            pulse.start();
        }

        private static int blendToWhite(int color, float amount) {
            int r = Math.round(Color.red(color) + (255 - Color.red(color)) * amount);
            int g = Math.round(Color.green(color) + (255 - Color.green(color)) * amount);
            int b = Math.round(Color.blue(color) + (255 - Color.blue(color)) * amount);
            return Color.rgb(r, g, b);
        }

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.card);
            float d = itemView.getResources().getDisplayMetrics().density;
            ringInset = Math.round(1.5f * d);
            for (GradientDrawable g : new GradientDrawable[]{ringOuter, ringInner}) {
                g.setShape(GradientDrawable.RECTANGLE);
                g.setColor(Color.TRANSPARENT);
            }
            ringOuter.setStroke(Math.round(5 * d), Color.BLACK);
            ringInner.setStroke(Math.round(2 * d), Color.WHITE);
            ring.setLayerInset(1, ringInset, ringInset, ringInset, ringInset);
            ring.setAlpha(0);
            card.setForeground(ring);
            bannerFill = itemView.findViewById(R.id.banner_fill);
            artworkCenter = itemView.findViewById(R.id.artwork_center);
            artworkIcon = itemView.findViewById(R.id.artwork_icon);
            cardLabel = itemView.findViewById(R.id.card_label);
            textFallback = itemView.findViewById(R.id.text_fallback);
            badge = itemView.findViewById(R.id.badge);
            title = itemView.findViewById(R.id.title);

            card.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    if (radius > 0) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                    } else {
                        outline.setRect(0, 0, view.getWidth(), view.getHeight());
                    }
                }
            });
            card.setClipToOutline(true);
        }
    }
}

