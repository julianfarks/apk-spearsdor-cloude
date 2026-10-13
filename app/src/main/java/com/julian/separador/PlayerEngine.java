package com.julian.separador;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * Reproduce la canción desde memoria. Puede sonar todo, solo la instrumental
 * (original - voz) o solo la voz, y se puede cambiar en pleno reproducción
 * (con un pequeño fundido para que no haya "clics").
 */
final class PlayerEngine {

    static final int MODE_ALL = 0;
    static final int MODE_INSTRUMENTAL = 1;
    static final int MODE_VOCALS = 2;

    interface Listener {
        void onFinished();
    }

    private static final int CHUNK_FRAMES = 2048;

    private final short[] origL;
    private final short[] origR;
    private final short[] vocL;
    private final short[] vocR;
    private final int length;
    private final int sampleRate;
    private final Listener listener;
    private final AudioTrack track;
    private final Thread thread;
    private final Object lock = new Object();

    private volatile int mode = MODE_ALL;
    private volatile boolean playing = false;
    private volatile boolean released = false;

    private int pos = 0;      // siguiente muestra a escribir (protegido por lock)
    private int seekGen = 0;  // cambia cada vez que se hace seek (protegido por lock)

    // Ganancias actuales (solo las toca el hilo de reproducción)
    private float gainVoice = 1f;
    private float gainInstr = 1f;

    PlayerEngine(short[] origL, short[] origR, short[] vocL, short[] vocR,
                 int length, int sampleRate, Listener listener) {
        this.origL = origL;
        this.origR = origR;
        this.vocL = vocL;
        this.vocR = vocR;
        this.length = length;
        this.sampleRate = sampleRate;
        this.listener = listener;

        int minBuf = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int bufBytes = Math.max(minBuf, sampleRate); // ~0,25 s de audio estéreo 16 bit

        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        thread = new Thread(this::loop, "reproductor");
        thread.start();
    }

    void setMode(int newMode) {
        mode = newMode;
    }

    boolean isPlaying() {
        return playing;
    }

    int getPositionMs() {
        synchronized (lock) {
            return (int) ((long) pos * 1000L / sampleRate);
        }
    }

    void play() {
        if (released) {
            return;
        }
        synchronized (lock) {
            if (pos >= length) {
                pos = 0;
                seekGen++;
            }
            playing = true;
            try {
                track.play();
            } catch (IllegalStateException ignored) {
            }
            lock.notifyAll();
        }
    }

    void pause() {
        if (released) {
            return;
        }
        playing = false;
        try {
            track.pause();
        } catch (IllegalStateException ignored) {
        }
    }

    void seekToMs(int ms) {
        if (released) {
            return;
        }
        int frame = (int) Math.max(0, Math.min((long) length, (long) ms * sampleRate / 1000L));
        synchronized (lock) {
            seekGen++;
            pos = frame;
        }
        boolean was = playing;
        try {
            track.pause();
            track.flush();
            if (was) {
                track.play();
            }
        } catch (IllegalStateException ignored) {
        }
    }

    void release() {
        released = true;
        playing = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        try {
            track.pause();
            track.flush();
        } catch (IllegalStateException ignored) {
        }
        try {
            thread.join(500);
        } catch (InterruptedException ignored) {
        }
        track.release();
    }

    private void loop() {
        short[] buf = new short[CHUNK_FRAMES * 2];
        while (!released) {
            if (!playing) {
                synchronized (lock) {
                    if (!playing && !released) {
                        try {
                            lock.wait(200);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
                continue;
            }

            int start;
            int gen;
            synchronized (lock) {
                start = pos;
                gen = seekGen;
            }
            if (start >= length) {
                finishPlayback();
                continue;
            }

            int n = Math.min(CHUNK_FRAMES, length - start);
            fill(buf, start, n);
            int written = track.write(buf, 0, n * 2);
            if (written < 0) {
                playing = false;
                continue;
            }
            synchronized (lock) {
                if (gen == seekGen) {
                    pos = start + n;
                }
            }
        }
    }

    private void finishPlayback() {
        playing = false;
        try {
            track.stop(); // deja que termine de sonar lo que ya está en el buffer
        } catch (IllegalStateException ignored) {
        }
        if (!released && listener != null) {
            listener.onFinished();
        }
    }

    private void fill(short[] out, int start, int n) {
        int m = mode;
        float targetVoice = (m == MODE_INSTRUMENTAL) ? 0f : 1f;
        float targetInstr = (m == MODE_VOCALS) ? 0f : 1f;
        float gv = gainVoice;
        float gi = gainInstr;
        for (int i = 0; i < n; i++) {
            gv += (targetVoice - gv) * 0.0015f;
            gi += (targetInstr - gi) * 0.0015f;
            if (Math.abs(targetVoice - gv) < 1e-3f) {
                gv = targetVoice;
            }
            if (Math.abs(targetInstr - gi) < 1e-3f) {
                gi = targetInstr;
            }
            int idx = start + i;
            int vl = vocL[idx];
            int vr = vocR[idx];
            float l = gv * vl + gi * (origL[idx] - vl);
            float r = gv * vr + gi * (origR[idx] - vr);
            out[2 * i] = clip(l);
            out[2 * i + 1] = clip(r);
        }
        gainVoice = gv;
        gainInstr = gi;
    }

    private static short clip(float v) {
        int s = Math.round(v);
        if (s > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (s < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) s;
    }
}
