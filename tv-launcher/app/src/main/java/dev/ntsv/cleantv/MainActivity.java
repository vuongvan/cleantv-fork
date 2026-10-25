package dev.ntsv.cleantv;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import android.view.ViewParent;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity {

    public static volatile boolean isForeground = false;
    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private boolean rtlAppGrid;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private int ambientColor = -1;
    private ValueAnimator ambientAnimator;
    private boolean heroShown;
    private boolean animations;
    private boolean advancedEffects;
    private View btnInputs;
    private TextView btnWifi;
    private TextView shamsiDate;
    private boolean shamsi;
    private TextView lunarDate;
    private boolean lunar;
    /** Favorite apps in order: dock_apps, or what the owner pinned on the TV (long-press an app). */
    private List<String> pinnedApps = new ArrayList<>();
    private boolean showAllAppsRail = true;
    private boolean favoritesBottom;
    /** Rail types in the order they are shown, top to bottom. */
    private final List<Integer> railOrder = new ArrayList<>();
    /** Size of every Favorite Apps card, 1 = normal (Config.favoriteSize / 10). */
    private float favoriteScale = 1f;

    private RailsAdapter railsAdapter;
    private RailCardsAdapter favAppsAdapter;
    private RailCardsAdapter allAppsAdapter;
    private Config cfg;
    /** Optional time-of-day backdrop (config.xml, sky_intensity); 0 keeps it hidden. */
    private SkyView sky;
    private String vpnApp;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    private boolean glassShown;
    private boolean glassEnabled;
    private boolean appGlow;
    /** Glow behind the screen: 0 off, 1 radial, 2 horizon. */
    private int ambientStyle = 1;
    // Where the grid (vertical) and the focused card's rail (horizontal) have scrolled the glass; the springs
    // work in content space, so a scroll moves the glass in lockstep with its card.
    private float glassShiftX, glassShiftY;
    private RecyclerView glassRail;
    private int glassCardW;
    private int glassAccent;
    private int cornerPx;
    private final Rect tileRect = new Rect();
    private final java.util.Map<String, Integer> accentCache = new HashMap<>();
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    private boolean reducedMotion;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** Rebuild the tiles on resume: Home was fully hidden by an app, or Home was pressed. Not after a side panel such as Wi-Fi settings. */
    private boolean rebuildOnResume = true;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            reloadApps(false);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            sky.update();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        COLUMNS = cfg.columns;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;
        reducedMotion = cfg.reducedMotion;
        liveTvPkg = cfg.liveTvPackage;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        root.setBackgroundColor(cfg.backgroundColor);
        if (cfg.wallpaper != null) showWallpaper(cfg.wallpaper);
        sky = findViewById(R.id.sky);
        sky.setIntensity(cfg.skyIntensity);
        glass = findViewById(R.id.glass);
        glassEnabled = cfg.glassEffect && glass != null;
        appGlow = cfg.appGlow;
        ambientStyle = cfg.ambientStyle;
        if (glass != null) glass.setVisibility(glassEnabled ? View.VISIBLE : View.GONE);
        if (ambientStyle == 0) ambient.setVisibility(View.GONE);
        ambient = findViewById(R.id.ambient);
        // The glow is one bitmap, so its alpha can be applied while drawing it. Otherwise every frame of
        // a fade renders it into a full-screen offscreen buffer first.
        ambient.forceHasOverlappingRendering(false);
        heroName = findViewById(R.id.heroName);
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        shamsi = cfg.shamsiDate;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        lunarDate = findViewById(R.id.lunarDate);
        lunar = cfg.lunarDate && lunarDate != null;
        if (lunarDate != null) lunarDate.setVisibility(lunar ? View.VISIBLE : View.GONE);
        pinnedApps = new ArrayList<>(cfg.dockApps);
        showAllAppsRail = cfg.showAllAppsRail;
        favoritesBottom = cfg.favoritesBottom;
        favoriteScale = cfg.favoriteSize / 10f;
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);
        this.cfg = cfg;

        grid = findViewById(R.id.grid);
        LinearLayoutManager gridManager = new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false);
        // Rows sit at the bottom of the screen when Favorite Apps is moved down: the space above shows the wallpaper.
        gridManager.setStackFromEnd(favoritesBottom);
        grid.setLayoutManager(gridManager);
        grid.setHasFixedSize(true);
        // Favorite Apps as the bottom row sit right against the bottom edge of the screen.
        if (favoritesBottom) grid.setPadding(grid.getPaddingLeft(), grid.getPaddingTop(), grid.getPaddingRight(), 0);
        grid.setItemAnimator(null);

        favAppsAdapter = new RailCardsAdapter();
        favAppsAdapter.setFocusListener((card, view) -> onCardFocused(view, card.accentColor));
        favAppsAdapter.setNavigationListener(new RailCardsAdapter.NavigationListener() {
            @Override
            public boolean onNavigateUp(int position) {
                return moveRailFocus(RailsAdapter.RAIL_APPS, -1);
            }

            @Override
            public boolean onNavigateDown(int position) {
                return moveRailFocus(RailsAdapter.RAIL_APPS, 1);
            }
        });

        allAppsAdapter = new RailCardsAdapter();
        allAppsAdapter.setFocusListener((card, view) -> onCardFocused(view, card.accentColor));
        allAppsAdapter.setNavigationListener(new RailCardsAdapter.NavigationListener() {
            @Override
            public boolean onNavigateUp(int position) {
                return moveRailFocus(RailsAdapter.RAIL_ALL_APPS, -1);
            }

            @Override
            public boolean onNavigateDown(int position) {
                return true;
            }
        });

        railsAdapter = new RailsAdapter(this, railType -> { });
        railsAdapter.setInnerScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown || rv != glassRail || dx == 0) return;
                glassShiftX -= dx;
                glass.setTranslationX(glass.getTranslationX() - dx);
            }
        });
        railsAdapter.setFavoriteOffset(favoritesBottom ? Math.round(cfg.favoriteOffset * density) : 0);
        cornerPx = Math.round(cfg.cornerRadius * density);
        favAppsAdapter.setColoredGlow(appGlow);
        allAppsAdapter.setColoredGlow(appGlow);
        favAppsAdapter.setCornerRadius(cornerPx);
        allAppsAdapter.setCornerRadius(cornerPx);

        List<RailsAdapter.RailConfig> configs = new ArrayList<>();
        RailsAdapter.RailConfig favoritesRail = new RailsAdapter.RailConfig(RailsAdapter.RAIL_APPS, "Favorite Apps", R.drawable.ic_apps, 0xFFE11D48, favAppsAdapter);
        if (!favoritesBottom) configs.add(favoritesRail);
        if (showAllAppsRail) {
            configs.add(new RailsAdapter.RailConfig(RailsAdapter.RAIL_ALL_APPS, "All Apps", R.drawable.ic_apps, 0xFF8B5CF6, allAppsAdapter));
        }
        if (favoritesBottom) configs.add(favoritesRail);
        railOrder.clear();
        for (RailsAdapter.RailConfig rc : configs) railOrder.add(rc.type);
        railsAdapter.setRails(configs);

        grid.setAdapter(railsAdapter);
        reloadApps();
        grid.post(() -> focusRail(RailsAdapter.RAIL_APPS));

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassShiftY -= dy;
                glass.setTranslationY(glass.getTranslationY() - dy);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentX") {
            @Override
            public float getValue(View v) {
                return v.getTranslationX() - glassShiftX;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationX(value + glassShiftX);
            }
        });
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassShiftY;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassShiftY);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion) {
                Motion.reduce(s);
            } else {
                // Critically damped: glides straight onto the card without swinging past it.
                s.setSpring(new SpringForce().setStiffness(advancedEffects ? 520f : 440f).setDampingRatio(1f));
            }
        }

        btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            openSystemSettings(Settings.ACTION_WIFI_SETTINGS);
            return true;
        });
        btnSettings.setOnClickListener(v -> showSettingsMenu());
        btnSettings.setOnLongClickListener(v -> {
            openSystemSettings(Settings.ACTION_SETTINGS);
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        boolean rtlMenuBar = getResources().getBoolean(R.bool.rtl_menu_bar);
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusRail(railOrder.isEmpty() ? RailsAdapter.RAIL_APPS : railOrder.get(0));
                return true;
            }
            int firstButton = (btnInputs.getVisibility() == View.VISIBLE ? R.id.btnInputs : R.id.btnWifi);
            int leftButton = rtlMenuBar ? R.id.btnSettings : firstButton;
            int rightButton = rtlMenuBar ? firstButton : R.id.btnSettings;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == leftButton;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == rightButton;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        if (advancedEffects) {
            for (View button : new View[]{btnInputs, btnWifi, btnSettings}) {
                if (button == null) continue;
                button.setOnFocusChangeListener((view, focused) -> view.animate()
                        .scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                        .setDuration(160).start());
            }
        }

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && (newF.getId() == R.id.card);
            if (!onTile) hideGlass();
        });

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
    }

    /**
     * A theme's full-screen picture behind everything. Decoded once, downsampled to the screen so a 4K
     * JPEG costs a 1080p bitmap at most (about 8 MB), which is the price of a wallpaper on a 1 GB TV.
     */
    private void showWallpaper(File file) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= dm.widthPixels && opts.outHeight / (sample * 2) >= dm.heightPixels) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;   // no alpha needed, half the memory
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            if (bmp == null) return;
            android.widget.ImageView wall = new android.widget.ImageView(this);
            wall.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            wall.setImageBitmap(bmp);
            root.addView(wall, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take the home screen down; the plain colour stays.
        }
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged (#118).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        if (grid != null) {
            grid.scrollToPosition(0);
        }
        lastFocused = 0;
        lastFocusedKey = null;
        rebuildOnResume = true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isForeground = true;
        promptsPaused = false;
        if (rebuildOnResume) {
            tilesReady = false;
            reloadApps(true);
        }
        rebuildOnResume = false;
        updateGreeting();
        updateWifiLabel();
        updateInputsButton();
        sky.update();
        if (getCurrentFocus() == null && grid != null) {
            grid.post(() -> focusRail(RailsAdapter.RAIL_APPS));
        }
        handler.postDelayed(minuteTick, MINUTE_MS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LAUNCHER_SETTINGS_REQUEST && resultCode == RESULT_OK) recreate();
    }

    /** Scrolls a rail to a card and focuses it; retries while the card is still being laid out. */
    private void focusRailItem(int railType, int pos) {
        if (railsAdapter == null) return;
        int railIdx = railsAdapter.positionOfType(railType);
        if (railIdx < 0) return;   // that row is hidden
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(railIdx);
        if (vh instanceof RailsAdapter.RailViewHolder) {
            RecyclerView r = ((RailsAdapter.RailViewHolder) vh).recycler;
            r.scrollToPosition(pos);
            Runnable focusTask = new Runnable() {
                int attempts = 0;
                @Override
                public void run() {
                    attempts++;
                    RecyclerView.ViewHolder cardVh = r.findViewHolderForAdapterPosition(pos);
                    View v = cardVh != null ? cardVh.itemView : (r.getLayoutManager() != null ? r.getLayoutManager().findViewByPosition(pos) : null);
                    if (v != null) {
                        View card = v.findViewById(R.id.card);
                        View target = card != null ? card : v;
                        target.setFocusableInTouchMode(true);
                        target.requestFocus();
                        target.setFocusableInTouchMode(false);
                    } else if (attempts < 6) {
                        r.postDelayed(this, 50);
                    }
                }
            };
            r.postDelayed(focusTask, 50);
        } else {
            grid.smoothScrollToPosition(railIdx);
            grid.postDelayed(() -> focusRailItem(railType, pos), 100);
        }
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        final boolean restoreFocus = animate || grid.hasFocus();
        reloadTiles();
        if (animate && animations && !reducedMotion) grid.scheduleLayoutAnimation();
        if (restoreFocus) {
            restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            tilesReady = true;
            showCardPrompt();
        }
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        reloadApps();
    }

    private RailCard createAppCard(AppsRowAdapter.AppItem item) {
        RailCard.ArtworkStyle style = (item.banner != null)
                ? RailCard.ArtworkStyle.BANNER_FILL
                : RailCard.ArtworkStyle.APP_ICON;
        Drawable art = (item.banner != null) ? item.banner : item.icon;

        return new RailCard(
                item.pkg,
                item.label,
                null,
                art,
                0,
                style,
                item.accentColor,
                item,
                new RailCard.ActionListener() {
                    @Override
                    public void onClick(RailCard card) {
                        onAppSelected((AppsRowAdapter.AppItem) card.payload);
                    }

                    @Override
                    public boolean onLongClick(RailCard card) {
                        AppsRowAdapter.AppItem ai = (AppsRowAdapter.AppItem) card.payload;
                        if (ai != null && !ai.pkg.isEmpty()) {
                            showAppActions(ai);
                            return true;
                        }
                        return false;
                    }
                }
        );
    }

    /** Hold OK on an app: open, pin or unpin, and for a favorite also move it left or right; or app info. */
    private void showAppActions(AppsRowAdapter.AppItem ai) {
        final boolean pinned = pinnedApps.contains(ai.pkg);
        final int favIndex = indexOfCard(favAppsAdapter, ai.pkg);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(getString(R.string.open_app));
        actions.add(() -> onAppSelected(ai));
        labels.add(getString(pinned ? R.string.unpin_from_favorites : R.string.pin_to_favorites));
        actions.add(() -> togglePin(ai));
        if (favIndex >= 0) {
            if (favIndex > 0) {
                labels.add(getString(R.string.move_left));
                actions.add(() -> moveFavorite(ai, -1));
            }
            if (favIndex < favAppsAdapter.getItemCount() - 1) {
                labels.add(getString(R.string.move_right));
                actions.add(() -> moveFavorite(ai, 1));
            }
        }
        labels.add(getString(R.string.app_info));
        actions.add(() -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ai.pkg));
            try {
                startActivity(i);
            } catch (ActivityNotFoundException ignored) {}
        });
        new android.app.AlertDialog.Builder(this)
                .setTitle(ai.label)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .show();
    }

    private static int indexOfCard(RailCardsAdapter adapter, String id) {
        List<RailCard> items = adapter.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(items.get(i).id)) return i;
        }
        return -1;
    }

    /** Swaps a favorite with its neighbour in the row and keeps focus on it. */
    private void moveFavorite(AppsRowAdapter.AppItem ai, int delta) {
        int index = indexOfCard(favAppsAdapter, ai.pkg);
        int other = index + delta;
        if (index < 0 || other < 0 || other >= favAppsAdapter.getItemCount()) return;
        List<String> list = new ArrayList<>(pinnedApps);
        int a = list.indexOf(ai.pkg);
        int b = list.indexOf(favAppsAdapter.getItems().get(other).id);
        if (a < 0 || b < 0) return;
        Collections.swap(list, a, b);
        pinnedApps = list;
        Config.savePinned(getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE), list);
        reloadApps();
        grid.post(() -> focusRailItem(RailsAdapter.RAIL_APPS, other));
    }

    private void togglePin(AppsRowAdapter.AppItem ai) {
        List<String> list = new ArrayList<>(pinnedApps);
        boolean nowPinned = !list.remove(ai.pkg);
        if (nowPinned) list.add(ai.pkg);
        pinnedApps = list;
        Config.savePinned(getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE), list);
        reloadApps();
        Toast.makeText(this, getString(nowPinned ? R.string.pinned_toast : R.string.unpinned_toast, ai.label),
                Toast.LENGTH_SHORT).show();
        // The card moved to the other row: follow it, so focus is not lost with the rebuilt rows.
        final int railType = nowPinned ? RailsAdapter.RAIL_APPS : RailsAdapter.RAIL_ALL_APPS;
        RailCardsAdapter target = nowPinned ? favAppsAdapter : allAppsAdapter;
        int index = -1;
        for (int i = 0; i < target.getItems().size(); i++) {
            if (ai.pkg.equals(target.getItems().get(i).id)) {
                index = i;
                break;
            }
        }
        final int focusIndex = index;
        grid.post(() -> {
            if (focusIndex >= 0 && isRailVisible(railType)) {
                focusRailItem(railType, focusIndex);
            } else {
                focusRail(RailsAdapter.RAIL_APPS);
                if (favAppsAdapter.getItemCount() == 0) moveRailFocus(RailsAdapter.RAIL_APPS, 1);
            }
        });
    }

    /** The app's colour, sampled once from its banner or icon. */
    private int accentFor(String pkg, Drawable art) {
        Integer c = accentCache.get(pkg);
        if (c == null) {
            c = AccentColor.vivid(art);
            accentCache.put(pkg, c);
        }
        return c;
    }

    private void reloadApps() {
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<AppsRowAdapter.AppItem> favs = new ArrayList<>();
        List<AppsRowAdapter.AppItem> others = new ArrayList<>();
        List<String> dock = pinnedApps;
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || hiddenApps.contains(pkg)) continue;
            ComponentName comp = new ComponentName(pkg, ri.activityInfo.name);
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null && ri.activityInfo.applicationInfo != null) {
                banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            }
            Drawable icon = ri.loadIcon(pm);
            String label = Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), pkg);
            int accent = accentFor(pkg, banner != null ? banner : icon);
            AppsRowAdapter.AppItem item = new AppsRowAdapter.AppItem(label, comp, banner, icon, accent);
            if (dock.contains(pkg)) {
                favs.add(item);
            } else {
                others.add(item);
            }
        }
        Collections.sort(favs, (a, b) -> Integer.compare(dock.indexOf(a.pkg), dock.indexOf(b.pkg)));
        Collections.sort(others, (a, b) -> a.label.compareToIgnoreCase(b.label));

        List<RailCard> favCards = new ArrayList<>();
        for (AppsRowAdapter.AppItem it : favs) {
            RailCard fc = createAppCard(it);
            fc.scale = favoriteScale;
            favCards.add(fc);
        }
        List<RailCard> otherCards = new ArrayList<>();
        for (AppsRowAdapter.AppItem it : others) {
            otherCards.add(createAppCard(it));
        }
        if (favAppsAdapter != null) favAppsAdapter.setItems(favCards);
        if (allAppsAdapter != null) allAppsAdapter.setItems(otherCards);
    }

    private void onAppSelected(AppsRowAdapter.AppItem item) {
        if (item.component == null) return;
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(item.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot launch " + item.label, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isRailVisible(int railType) {
        switch (railType) {
            case RailsAdapter.RAIL_ALL_APPS: return showAllAppsRail;
            default: return true;   // Favorite Apps is always there
        }
    }

    /** D-pad up or down between rails, skipping hidden ones. Always consumes the key, so focus never jumps elsewhere. */
    private boolean moveRailFocus(int fromType, int delta) {
        int idx = railOrder.indexOf(fromType) + delta;
        if (idx < 0) {
            if (btnWifi != null) btnWifi.requestFocus();   // above the top row: the top bar
        } else if (idx < railOrder.size()) {
            focusRail(railOrder.get(idx));
        }
        return true;
    }

    /** Focuses the first card of a rail, given as a RailsAdapter.RAIL_* type (not a position: hidden rails shift those). */
    private void focusRail(int railType) {
        if (railsAdapter == null) return;
        int position = railsAdapter.positionOfType(railType);
        int target = Math.max(0, Math.min(position < 0 ? 0 : position, railsAdapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(target);
        if (vh instanceof RailsAdapter.RailViewHolder) {
            ((RailsAdapter.RailViewHolder) vh).focusFirstItem();
        } else {
            grid.smoothScrollToPosition(target);
            grid.postDelayed(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(target);
                if (h instanceof RailsAdapter.RailViewHolder) {
                    ((RailsAdapter.RailViewHolder) h).focusFirstItem();
                }
            }, 100);
        }
    }

    private void onCardFocused(View card, int accentColor) {
        hideHero();
        moveGlass(card, accentColor);
        if (ambientStyle == 0) return;
        if (advancedEffects) {
            transitionAmbient(accentColor);
        } else {
            setAmbient(accentColor);
            ambient.animate().alpha(1f).setDuration(300).start();
        }
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        grid.post(() -> focusRail(RailsAdapter.RAIL_APPS));
    }

    @Override
    protected void onPause() {
        super.onPause();
        isForeground = false;
        resetGlass();
        stopAmbientAnimation();
        heroName.animate().cancel();
        ambient.animate().cancel();
        handler.removeCallbacks(minuteTick);
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (openPopup != null) openPopup.dismiss();
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(packageReceiver);
        rebuildOnResume = true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) showCardPrompt();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    private void setAmbient(int color) {
        if (color == ambientColor && ambient.getBackground() != null) return;
        ambientColor = color;
        BitmapDrawable glow = (BitmapDrawable) ambient.getBackground();
        if (glow == null) {
            int w = 480;
            int h = 270;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (ambientStyle == 2) {
                // Horizon: the colour washes down from the top edge and fades out three quarters of the way
                // down, with a brighter band along the top like light over a horizon.
                paint.setShader(new LinearGradient(0, 0, 0, h * 0.75f,
                        new int[]{0x66FFFFFF, 0x24FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.4f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, paint);
                paint.setShader(new RadialGradient(
                        w * 0.5f, -h * 0.25f, w * 0.55f,
                        new int[]{0x40FFFFFF, 0x00FFFFFF},
                        null,
                        Shader.TileMode.CLAMP));
            } else {
                paint.setShader(new RadialGradient(
                        w * 0.32f, h * 0.42f, w * 0.62f,
                        new int[]{0x5CFFFFFF, 0x26FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.45f, 1f},
                        Shader.TileMode.CLAMP));
            }
            canvas.drawRect(0, 0, w, h, paint);
            glow = new BitmapDrawable(getResources(), bmp);
            glow.setGravity(Gravity.FILL);
            ambient.setBackground(glow);
        }
        // The default tint mode, SRC_IN, keeps the mask's alpha and takes the colour from the tint.
        glow.setTint(0xFF000000 | color);
    }

    private void transitionAmbient(int color) {
        // A rapid focus change continues from the colour actually displayed on screen.
        stopAmbientAnimation();
        ambient.animate().cancel();
        if (color == ambientColor) {
            ambient.animate().alpha(1f).setDuration(180).start();
            return;
        }
        ambient.animate().alpha(1f).setDuration(180).start();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, color);
        ambientAnimator.setDuration(360);
        ambientAnimator.addUpdateListener(animation -> setAmbient((int) animation.getAnimatedValue()));
        ambientAnimator.start();
    }

    private void stopAmbientAnimation() {
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
    }

    private void hideHero() {
        if (!heroShown) return;
        stopAmbientAnimation();
        heroName.animate().cancel();
        ambient.animate().cancel();
        heroShown = false;
        if (animations) {
            heroName.animate().alpha(0f).setDuration(reducedMotion ? 100 : 160).start();
            ambient.animate().alpha(0f).setDuration(reducedMotion ? 140 : 300).start();
        } else {
            heroName.setAlpha(0f);
            ambient.setAlpha(0f);
        }
    }

    /**
     * Glides the glass slab onto the focused card. The card's rect is in root coordinates from layout positions
     * (the focus zoom keeps its centre), plus the translation of the rows above it.
     */
    private void moveGlass(View card, int accentColor) {
        if (!glassEnabled) return;
        // Focus restored before the first layout (cold start): the card has no size yet.
        if (card.getWidth() == 0) {
            card.post(() -> {
                if (card.isFocused() && card.getWidth() > 0) moveGlass(card, accentColor);
            });
            return;
        }
        Rect r = tileRect;
        r.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float tx = 0f, ty = 0f;
        ViewParent p = card.getParent();
        while (p instanceof View && p != root) {
            View v = (View) p;
            tx += v.getTranslationX();
            ty += v.getTranslationY();
            p = v.getParent();
        }
        float pad = 6f * density;   // matches GlassView's glow inset
        float scale = TvDesignTokens.FOCUS_SCALE;
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float x = r.exactCenterX() + tx - w / 2f;
        float y = r.exactCenterY() + ty - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h || card.getWidth() != glassCardW) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(cornerPx * scale);
        }
        glassCardW = card.getWidth();
        int accent = appGlow ? accentColor : 0;
        if (accent != glassAccent) {
            glassAccent = accent;
            glass.setAccent(accent);
        }
        View item = (View) card.getParent();
        ViewParent rail = item == null ? null : item.getParent();
        glassRail = rail instanceof RecyclerView ? (RecyclerView) rail : null;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            if (animations && !reducedMotion) glass.animate().alpha(1f).setDuration(180).start();
            else glass.setAlpha(1f);
            glassShown = true;
        } else if (!animations) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
        } else {
            springX.animateToFinalPosition(x - glassShiftX);
            springY.animateToFinalPosition(y - glassShiftY);
        }
    }

    /** Nothing of the glass stays on screen over another app, or from before a rebuild. */
    private void resetGlass() {
        if (springX != null) springX.cancel();
        if (springY != null) springY.cancel();
        glass.animate().cancel();
        glass.setAlpha(0f);
        glassShown = false;
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().cancel();
        if (animations) glass.animate().alpha(0f).setDuration(160).start();
        else glass.setAlpha(0f);
    }

    // ---- navigation ------------------------------------------------------------------------------

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        if (shamsi) shamsiDate.setText(Shamsi.today());
        if (lunar) lunarDate.setText(LunarVN.today().toUpperCase(new Locale("vi")));
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // Icon only: the network name and the VPN state go to the accessibility label.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        String description = text;
        if (vpn) description += " \u00B7 " + getString(R.string.vpn);
        else if (vpnDown) description += " \u00B7 " + getString(R.string.vpn_off);
        btnWifi.setText("");
        btnWifi.setContentDescription(description);
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        if (i != null) {
            open(i);
        } else {
            openSystemSettings(Settings.ACTION_WIFI_SETTINGS);
        }
    }

    private void openSystemSettings(String standardAction) {
        Intent intent = systemIntent(standardAction);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }


    /**
     * China-market MIUI TV firmware answers no standard Settings intents at all; open Xiaomi's own
     * settings app (com.xiaomi.mitv.settings) there, so the Settings and Wi-Fi pills keep working.
     * On firmware with a standard settings handler the behaviour is unchanged.
     */
    private Intent systemIntent(String standardAction) {
        Intent i = new Intent(standardAction);
        if (i.resolveActivity(getPackageManager()) != null) return i;
        i = new Intent().setComponent(new ComponentName(
                "com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
        if (i.resolveActivity(getPackageManager()) != null) return i;
        return new Intent(standardAction);   // nothing found: let open() show the can't-open toast
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput()) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    private void openInput(String inputId) {
        Intent intent = new Intent(this, TvPlayerActivity.class);
        intent.putExtra(TvPlayerActivity.EXTRA_INPUT_ID, inputId);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    if (tunerId == null || (hw && !tunerIsHw) || (hw == tunerIsHw && info.getId().compareTo(tunerId) < 0)) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                    }
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        int alignment = getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.END : Gravity.START;
        dropIn(popup, panel, anchor, alignment);
        rows.getChildAt(0).requestFocus();
    }

    private PopupWindow glassPopup(View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- launcher settings and module cards -------------------------------------------------------

    /** Keep a normal Settings click for the TV; the launcher's choices live behind hold OK. */
    private void showSettingsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (240 * density));

        addSettingsMenuRow(rows, popup, "TV Home Settings", () -> startActivityForResult(
                new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST));

        addSettingsMenuRow(rows, popup, "Check for Updates", () -> UpdateManager.checkForUpdates(this, true));

        addSettingsMenuRow(rows, popup, "Android TV Settings", () -> openSystemSettings(Settings.ACTION_SETTINGS));

        if (moduleCards) addSettingsMenuRow(rows, popup, getString(R.string.module_cards), this::showModuleCards);

        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (240 * density), panel.getMeasuredWidth()));
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, CharSequence title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK-up after opening the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
    }

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
