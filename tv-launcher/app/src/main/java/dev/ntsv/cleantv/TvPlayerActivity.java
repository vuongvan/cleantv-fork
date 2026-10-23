package dev.ntsv.cleantv;

import android.app.Activity;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Shows a passthrough input (HDMI, AV) full screen with TvView. Back returns to the home screen.
 */
public class TvPlayerActivity extends Activity {

    public static final String EXTRA_INPUT_ID = "extra_input_id";

    private TvView tvView;
    private String inputId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv_player);
        tvView = findViewById(R.id.tv_view);
        tvView.setFocusable(false);
        inputId = getIntent().getStringExtra(EXTRA_INPUT_ID);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        inputId = intent.getStringExtra(EXTRA_INPUT_ID);
        tune();
    }

    @Override
    protected void onStart() {
        super.onStart();
        tune();
    }

    @Override
    protected void onStop() {
        super.onStop();
        tvView.reset();
    }

    private void tune() {
        if (inputId == null || inputId.isEmpty()) {
            finish();
            return;
        }
        try {
            tvView.tune(inputId, TvContract.buildChannelUriForPassthroughInput(inputId));
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
