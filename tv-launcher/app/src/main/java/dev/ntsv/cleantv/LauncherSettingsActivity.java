package dev.ntsv.cleantv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView lunarChoice;
    private TextView railAllAppsChoice;
    private TextView favoritesBottomChoice;
    private boolean favoritesBottom;
    private static final int PICK_WALLPAPER = 41;
    private int columns;
    private boolean animations;
    private boolean advancedEffects;
    private boolean lunarDate;
    private boolean showAllAppsRail;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
        lunarDate = config.lunarDate;
        showAllAppsRail = config.showAllAppsRail;
        favoritesBottom = config.favoritesBottom;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove("animations").remove("advanced_effects").commit();
            Config current = Config.load(this);
            columns = current.columns;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            setResult(RESULT_OK);
            refresh();
        });
        // Home screen: lunar date, hideable rows, pinned apps. MainActivity rebuilds on RESULT_OK.
        lunarChoice = findViewById(R.id.lunarChoice);
        lunarChoice.setOnClickListener(v -> {
            lunarDate = !lunarDate;
            choices.edit().putBoolean(Config.KEY_LUNAR_DATE, lunarDate).apply();
            setResult(RESULT_OK);
            refresh();
        });
        railAllAppsChoice = findViewById(R.id.railAllAppsChoice);
        railAllAppsChoice.setOnClickListener(v -> {
            showAllAppsRail = !showAllAppsRail;
            choices.edit().putBoolean(Config.KEY_RAIL_ALL_APPS, showAllAppsRail).apply();
            setResult(RESULT_OK);
            refresh();
        });
        favoritesBottomChoice = findViewById(R.id.favoritesBottomChoice);
        favoritesBottomChoice.setOnClickListener(v -> {
            favoritesBottom = !favoritesBottom;
            choices.edit().putBoolean(Config.KEY_FAVORITES_BOTTOM, favoritesBottom).apply();
            setResult(RESULT_OK);
            refresh();
        });
        bindSlider(R.id.favoriteSizeLabel, R.id.favoriteSizeBar, R.string.favorite_size_label,
                Config.MIN_FAVORITE_SIZE, Config.MAX_FAVORITE_SIZE, config.favoriteSize, Config.KEY_FAVORITE_SIZE);
        bindSlider(R.id.favoriteOffsetLabel, R.id.favoriteOffsetBar, R.string.favorite_offset_label,
                0, Config.MAX_FAVORITE_OFFSET, config.favoriteOffset, Config.KEY_FAVORITE_OFFSET);
        bindSlider(R.id.cornerRadiusLabel, R.id.cornerRadiusBar, R.string.corner_radius_label,
                0, Config.MAX_CORNER_RADIUS, config.cornerRadius, Config.KEY_CORNER_RADIUS);
        findViewById(R.id.chooseWallpaperChoice).setOnClickListener(v -> pickWallpaper());
        findViewById(R.id.removeWallpaperChoice).setOnClickListener(v -> {
            java.io.File dir = Config.dir(this);
            java.io.File file = dir != null ? new java.io.File(dir, Config.WALLPAPER_NAME) : null;
            if (file != null && file.isFile() && file.delete()) {
                Toast.makeText(this, R.string.wallpaper_removed, Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
            } else {
                Toast.makeText(this, R.string.wallpaper_none, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.resetFavoritesChoice).setOnClickListener(v -> {
            choices.edit().remove(Config.KEY_PINNED_APPS).apply();
            Toast.makeText(this, R.string.reset_favorites_done, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));

        TextView checkUpdatesChoice = findViewById(R.id.checkUpdatesChoice);
        if (checkUpdatesChoice != null) {
            checkUpdatesChoice.setOnClickListener(v -> UpdateManager.checkForUpdates(this, true));
        }

        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();

    }

    /**
     * A D-pad friendly slider (left/right change it by one) that saves its value right away.
     * The label shows the value; MainActivity rebuilds when this screen closes with RESULT_OK.
     */
    private void bindSlider(int labelId, int barId, int labelRes, int min, int max, int value, String key) {
        TextView label = findViewById(labelId);
        SeekBar bar = findViewById(barId);
        bar.setMax(max - min);
        bar.setKeyProgressIncrement(1);
        bar.setProgress(value - min);
        label.setText(getString(labelRes, value));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                int v = progress + min;
                label.setText(getString(labelRes, v));
                choices.edit().putInt(key, v).apply();
                setResult(RESULT_OK);
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
            }
        });
    }

    /** Opens the system file picker for an image; TVs without one get the manual route. */
    private void pickWallpaper() {
        String[] actions = {Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT};
        for (String action : actions) {
            Intent intent = new Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
            try {
                startActivityForResult(intent, PICK_WALLPAPER);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        java.io.File dir = Config.dir(this);
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_wallpaper)
                .setMessage(getString(R.string.wallpaper_no_picker,
                        (dir != null ? dir.getAbsolutePath() : "?") + "/" + Config.WALLPAPER_NAME))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_WALLPAPER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        java.io.File dir = Config.dir(this);
        if (dir == null) {
            Toast.makeText(this, R.string.wallpaper_failed, Toast.LENGTH_LONG).show();
            return;
        }
        final java.io.File dest = new java.io.File(dir, Config.WALLPAPER_NAME);
        Toast.makeText(this, R.string.wallpaper_working, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = WallpaperImporter.importImage(getApplicationContext(), uri, dest);
            runOnUiThread(() -> {
                Toast.makeText(this, ok ? R.string.wallpaper_set : R.string.wallpaper_failed,
                        Toast.LENGTH_LONG).show();
                if (ok) setResult(RESULT_OK);
            });
        }).start();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        followThemeChoice.setSelected(!hasOnTvChoices());
        lunarChoice.setText(getString(R.string.lunar_date_state,
                getString(lunarDate ? R.string.on : R.string.off)));
        railAllAppsChoice.setText(getString(R.string.rail_all_apps_state,
                getString(showAllAppsRail ? R.string.rail_shown : R.string.rail_hidden)));
        favoritesBottomChoice.setText(getString(R.string.favorites_bottom_state,
                getString(favoritesBottom ? R.string.on : R.string.off)));
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains("animations")
                || choices.contains("advanced_effects");
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
