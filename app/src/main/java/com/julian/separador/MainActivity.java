package com.julian.separador;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1001;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Button btnPick;
    private Button btnPlay;
    private TextView txtFile;
    private TextView txtStatus;
    private TextView txtTime;
    private ProgressBar progress;
    private RadioGroup modeGroup;
    private SeekBar seek;

    private PlayerEngine engine;
    private volatile boolean busy;
    private boolean userSeeking;
    private int totalMs;

    // Solo se usan desde el hilo de procesamiento
    private int lastPct = -1;
    private String lastText = "";

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (engine == null) {
                return;
            }
            if (!userSeeking) {
                int ms = engine.getPositionMs();
                seek.setProgress(ms);
                txtTime.setText(timeText(ms, totalMs));
            }
            ui.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnPick = findViewById(R.id.btnPick);
        btnPlay = findViewById(R.id.btnPlay);
        txtFile = findViewById(R.id.txtFile);
        txtStatus = findViewById(R.id.txtStatus);
        txtTime = findViewById(R.id.txtTime);
        progress = findViewById(R.id.progress);
        modeGroup = findViewById(R.id.modeGroup);
        seek = findViewById(R.id.seek);

        progress.setMax(1000);
        modeGroup.check(R.id.modeAll);
        setControlsEnabled(false);

        btnPick.setOnClickListener(v -> pickSong());

        btnPlay.setOnClickListener(v -> {
            if (engine == null) {
                return;
            }
            if (engine.isPlaying()) {
                engine.pause();
            } else {
                engine.setMode(currentMode());
                engine.play();
            }
            updatePlayButton();
        });

        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (engine != null) {
                engine.setMode(currentMode());
            }
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                if (fromUser) {
                    txtTime.setText(timeText(value, totalMs));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                if (engine != null) {
                    engine.seekToMs(s.getProgress());
                }
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Sin servicio en segundo plano, la reproducción se pausa al salir de la app.
        if (engine != null && engine.isPlaying()) {
            engine.pause();
            updatePlayButton();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        releaseEngine();
    }

    private void pickSong() {
        if (busy) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            processSong(data.getData());
        }
    }

    private void processSong(final Uri uri) {
        if (busy) {
            return;
        }
        releaseEngine();
        busy = true;
        lastPct = -1;
        lastText = "";
        setControlsEnabled(false);
        btnPick.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        txtFile.setText(displayName(uri));
        txtTime.setText(timeText(0, 0));
        seek.setProgress(0);
        setStatus(getString(R.string.status_reading), 0f);

        new Thread(() -> {
            try {
                final AudioDecoder.Result r = AudioDecoder.decode(this, uri,
                        f -> postProgress(getString(R.string.status_reading), f * 0.3f));

                long needed = 12L * r.frames; // original + voz + instrumental
                if (needed > (long) (Runtime.getRuntime().maxMemory() * 0.6)) {
                    postError(getString(R.string.err_too_long));
                    return;
                }
                if (r.sourceWasMono || Separator.isNearlyMono(r.left, r.right, r.frames)) {
                    postError(getString(R.string.err_mono));
                    return;
                }

                short[] vl = new short[r.frames];
                short[] vr = new short[r.frames];
                short[] il = new short[r.frames];
                short[] ir = new short[r.frames];
                Separator.extract(r.left, r.right, r.frames, r.sampleRate, vl, vr, il, ir,
                        f -> postProgress(getString(R.string.status_separating), 0.3f + f * 0.7f));

                final PlayerEngine e = new PlayerEngine(r.left, r.right, vl, vr, il, ir,
                        r.frames, r.sampleRate, this::onTrackFinished);
                ui.post(() -> onReady(e, r.frames, r.sampleRate));
            } catch (OutOfMemoryError oom) {
                postError(getString(R.string.err_too_long));
            } catch (Throwable t) {
                postError(getString(R.string.err_generic) + " " + t.getMessage());
            }
        }, "procesar").start();
    }

    private void onReady(PlayerEngine e, int frames, int rate) {
        engine = e;
        totalMs = (int) ((long) frames * 1000L / rate);
        seek.setMax(totalMs);
        seek.setProgress(0);
        modeGroup.check(R.id.modeAll);
        e.setMode(PlayerEngine.MODE_ALL);
        busy = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        btnPick.setEnabled(true);
        setControlsEnabled(true);
        txtStatus.setText(R.string.status_ready);
        progress.setProgress(1000);
        txtTime.setText(timeText(0, totalMs));
        updatePlayButton();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    private void onTrackFinished() {
        ui.post(() -> {
            if (engine != null) {
                engine.seekToMs(0);
                seek.setProgress(0);
                txtTime.setText(timeText(0, totalMs));
            }
            updatePlayButton();
        });
    }

    private void postProgress(final String text, float fraction) {
        final int pct = (int) (fraction * 100f);
        if (pct == lastPct && text.equals(lastText)) {
            return;
        }
        lastPct = pct;
        lastText = text;
        final float fr = fraction;
        ui.post(() -> setStatus(text + " " + pct + "%", fr));
    }

    private void postError(final String message) {
        ui.post(() -> {
            busy = false;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            btnPick.setEnabled(true);
            setControlsEnabled(false);
            progress.setProgress(0);
            txtStatus.setText(message);
        });
    }

    private void setStatus(String text, float fraction) {
        txtStatus.setText(text);
        progress.setProgress((int) (fraction * 1000f));
    }

    private void setControlsEnabled(boolean on) {
        btnPlay.setEnabled(on);
        seek.setEnabled(on);
        for (int i = 0; i < modeGroup.getChildCount(); i++) {
            modeGroup.getChildAt(i).setEnabled(on);
        }
    }

    private void updatePlayButton() {
        boolean playing = engine != null && engine.isPlaying();
        btnPlay.setText(playing ? R.string.btn_pause : R.string.btn_play);
    }

    private int currentMode() {
        int id = modeGroup.getCheckedRadioButtonId();
        if (id == R.id.modeInstrumental) {
            return PlayerEngine.MODE_INSTRUMENTAL;
        }
        if (id == R.id.modeVocals) {
            return PlayerEngine.MODE_VOCALS;
        }
        return PlayerEngine.MODE_ALL;
    }

    private void releaseEngine() {
        ui.removeCallbacks(ticker);
        if (engine != null) {
            engine.release();
            engine = null;
        }
        updatePlayButton();
    }

    private String displayName(Uri uri) {
        String name = null;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return name != null ? name : getString(R.string.default_song_name);
    }

    private static String timeText(int ms, int totalMs) {
        return fmt(ms) + " / " + fmt(totalMs);
    }

    private static String fmt(int ms) {
        int s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
