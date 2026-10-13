package com.julian.separador;

import java.util.Random;

/** Prueba de escritorio del algoritmo (no forma parte de la app). */
public class SeparatorTest {

    static final int SR = 44100;

    static double amp(short[] x, int from, int to, double hz) {
        double re = 0, im = 0;
        for (int n = from; n < to; n++) {
            double a = 2 * Math.PI * hz * n / SR;
            re += x[n] * Math.cos(a);
            im -= x[n] * Math.sin(a);
        }
        return 2.0 * Math.hypot(re, im) / (to - from);
    }

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "OK    " : "FALLO ") + name + "  " + detail);
        if (!ok) {
            failures++;
        }
    }

    static int failures = 0;

    public static void main(String[] args) {
        int frames = SR * 6;
        short[] L = new short[frames];
        short[] R = new short[frames];
        for (int n = 0; n < frames; n++) {
            double t = (double) n / SR;
            double env = 0.7 + 0.3 * Math.sin(2 * Math.PI * 3 * t);
            double voice = env * (0.30 * Math.sin(2 * Math.PI * 300 * t)
                    + 0.15 * Math.sin(2 * Math.PI * 600 * t)
                    + 0.08 * Math.sin(2 * Math.PI * 900 * t));
            double guitarL = 0.25 * Math.sin(2 * Math.PI * 440 * t);
            double guitarR = 0.25 * Math.sin(2 * Math.PI * 523.25 * t);
            double bass = 0.30 * Math.sin(2 * Math.PI * 60 * t);
            L[n] = (short) Math.round(16000 * (voice + guitarL + bass));
            R[n] = (short) Math.round(16000 * (voice + guitarR + bass));
        }

        // 1) Separación: la voz (centro) se queda, lo panoramizado y el bajo se van.
        short[] vl = new short[frames];
        short[] vr = new short[frames];
        Separator.extractVoice(L, R, frames, SR, vl, vr, null);
        int a = SR / 2;
        int b = frames - SR / 2;
        double v300 = amp(vl, a, b, 300) / amp(L, a, b, 300);
        double v900 = amp(vl, a, b, 900) / amp(L, a, b, 900);
        double g440 = amp(vl, a, b, 440) / amp(L, a, b, 440);
        double g523 = amp(vr, a, b, 523.25) / amp(R, a, b, 523.25);
        double b60 = amp(vl, a, b, 60) / amp(L, a, b, 60);
        check("voz 300 Hz se conserva en 'voz'", v300 > 0.85, String.format("%.3f", v300));
        check("voz 900 Hz se conserva en 'voz'", v900 > 0.85, String.format("%.3f", v900));
        check("guitarra izquierda (440 Hz) no está en 'voz'", g440 < 0.10, String.format("%.3f", g440));
        check("guitarra derecha (523 Hz) no está en 'voz'", g523 < 0.10, String.format("%.3f", g523));
        check("bajo central (60 Hz) no está en 'voz'", b60 < 0.10, String.format("%.3f", b60));

        // Instrumental = original - voz
        double i300 = 0, i440 = 0;
        short[] il = new short[frames];
        for (int n = 0; n < frames; n++) {
            il[n] = (short) (L[n] - vl[n]);
        }
        i300 = amp(il, a, b, 300) / amp(L, a, b, 300);
        i440 = amp(il, a, b, 440) / amp(L, a, b, 440);
        check("instrumental casi sin la voz (300 Hz)", i300 < 0.20, String.format("%.3f", i300));
        check("instrumental conserva la guitarra (440 Hz)", i440 > 0.90, String.format("%.3f", i440));

        // 2) Reconstrucción perfecta con máscara = 1 (comprueba ventanas, solape y FFT empaquetada).
        Separator.Params all = new Separator.Params();
        all.simLow = -3;
        all.simHigh = -2;
        all.bassLowHz = -2;
        all.bassHighHz = -1;
        all.timeSmooth = 0;
        short[] fl = new short[frames];
        short[] fr = new short[frames];
        Separator.extractVoice(L, R, frames, SR, fl, fr, all, null);
        int maxErr = 0;
        for (int n = 0; n < frames; n++) {
            maxErr = Math.max(maxErr, Math.abs(fl[n] - L[n]));
            maxErr = Math.max(maxErr, Math.abs(fr[n] - R[n]));
        }
        check("máscara=1 reconstruye la señal (error máx en muestras)", maxErr <= 2, "maxErr=" + maxErr);

        // 3) Detección de mono
        short[] m = L.clone();
        check("mono detectado", Separator.isNearlyMono(m, m, frames), "");
        check("estéreo no se marca como mono", !Separator.isNearlyMono(L, R, frames), "");

        // 4) Tamaños raros (más cortos que una ventana)
        short[] tiny = new short[500];
        short[] tl = new short[500];
        short[] tr = new short[500];
        Separator.extractVoice(tiny, tiny, 500, SR, tl, tr, null);
        check("audio muy corto no revienta", true, "");

        // 5) Velocidad con 4 minutos de ruido estéreo
        int big = SR * 240;
        Random rnd = new Random(1);
        short[] bl = new short[big];
        short[] br = new short[big];
        for (int n = 0; n < big; n++) {
            double c = rnd.nextGaussian() * 3000;
            bl[n] = (short) (c + rnd.nextGaussian() * 1500);
            br[n] = (short) (c + rnd.nextGaussian() * 1500);
        }
        short[] ol = new short[big];
        short[] or = new short[big];
        long t0 = System.nanoTime();
        Separator.extractVoice(bl, br, big, SR, ol, or, null);
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.printf("INFO  4 min de audio procesados en %.1f s (en este servidor)%n", secs);

        System.out.println(failures == 0 ? "\nTODO OK" : "\nFALLOS: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
