package com.julian.separador;

import java.util.Random;

/** Pruebas de escritorio del algoritmo (no forman parte de la app). */
public class SeparatorTest {

    static final int SR = 44100;
    static int failures = 0;

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

    static Separator.Params passAll() {
        Separator.Params q = new Separator.Params();
        q.voiceSimLow = -3;
        q.voiceSimHigh = -2;
        q.voiceBassLowHz = -2;
        q.voiceBassHighHz = -1;
        q.voiceTopStartHz = 1e9;
        q.voiceTopEndHz = 2e9;
        q.voiceHarmonicWeight = 0;
        q.cutSimLow = 3;
        q.cutSimHigh = 4;
        q.cutHarmonicWeight = 0;
        return q;
    }

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

        short[] vl = new short[frames], vr = new short[frames];
        short[] il = new short[frames], ir = new short[frames];
        Separator.extract(L, R, frames, SR, vl, vr, il, ir, null);
        int a = SR / 2;
        int b = frames - SR / 2;

        double v300 = amp(vl, a, b, 300) / amp(L, a, b, 300);
        double v900 = amp(vl, a, b, 900) / amp(L, a, b, 900);
        double g440 = amp(vl, a, b, 440) / amp(L, a, b, 440);
        double g523 = amp(vr, a, b, 523.25) / amp(R, a, b, 523.25);
        double b60 = amp(vl, a, b, 60) / amp(L, a, b, 60);
        check("voz 300 Hz se conserva en 'voz'", v300 > 0.85, String.format("%.3f", v300));
        check("voz 900 Hz se conserva en 'voz'", v900 > 0.85, String.format("%.3f", v900));
        check("guitarra izquierda no está en 'voz'", g440 < 0.10, String.format("%.3f", g440));
        check("guitarra derecha no está en 'voz'", g523 < 0.10, String.format("%.3f", g523));
        check("bajo central no está en 'voz'", b60 < 0.10, String.format("%.3f", b60));

        double i300 = amp(il, a, b, 300) / amp(L, a, b, 300);
        double i600 = amp(il, a, b, 600) / amp(L, a, b, 600);
        double i440 = amp(il, a, b, 440) / amp(L, a, b, 440);
        double i60 = amp(il, a, b, 60) / amp(L, a, b, 60);
        check("instrumental sin la voz (300 Hz)", i300 < 0.10, String.format("%.3f", i300));
        check("instrumental sin la voz (600 Hz)", i600 < 0.10, String.format("%.3f", i600));
        check("instrumental conserva la guitarra", i440 > 0.90, String.format("%.3f", i440));
        check("instrumental conserva el bajo", i60 > 0.90, String.format("%.3f", i60));

        // Reconstrucción exacta con máscaras "dejar pasar todo"
        short[] fvl = new short[frames], fvr = new short[frames];
        short[] fil = new short[frames], fir = new short[frames];
        Separator.extract(L, R, frames, SR, fvl, fvr, fil, fir, passAll(), null);
        int maxErr = 0;
        for (int n = 0; n < frames; n++) {
            maxErr = Math.max(maxErr, Math.abs(fvl[n] - L[n]));
            maxErr = Math.max(maxErr, Math.abs(fvr[n] - R[n]));
            maxErr = Math.max(maxErr, Math.abs(fil[n] - L[n]));
            maxErr = Math.max(maxErr, Math.abs(fir[n] - R[n]));
        }
        check("máscara=1 reconstruye la señal (error máx en muestras)", maxErr <= 2, "maxErr=" + maxErr);

        // Mono
        check("mono detectado", Separator.isNearlyMono(L.clone(), L.clone(), frames), "");
        check("estéreo no se marca como mono", !Separator.isNearlyMono(L, R, frames), "");

        // Tamaños raros
        for (int len : new int[]{1, 500, 1023, 1024, 1025, 4096, 5000}) {
            short[] x = new short[len];
            for (int i = 0; i < len; i++) {
                x[i] = (short) (1000 * Math.sin(i * 0.05));
            }
            short[] o1 = new short[len], o2 = new short[len], o3 = new short[len], o4 = new short[len];
            Separator.extract(x, x, len, SR, o1, o2, o3, o4, passAll(), null);
            int err = 0;
            for (int i = 0; i < len; i++) {
                err = Math.max(err, Math.abs(o1[i] - x[i]));
            }
            check("largo " + len + " no revienta y reconstruye", err <= 2, "err=" + err);
        }

        // Velocidad: 4 minutos de ruido estéreo
        int big = SR * 240;
        Random rnd = new Random(1);
        short[] bl = new short[big], br = new short[big];
        for (int n = 0; n < big; n++) {
            double c = rnd.nextGaussian() * 3000;
            bl[n] = (short) (c + rnd.nextGaussian() * 1500);
            br[n] = (short) (c + rnd.nextGaussian() * 1500);
        }
        short[] o1 = new short[big], o2 = new short[big], o3 = new short[big], o4 = new short[big];
        long t0 = System.nanoTime();
        Separator.extract(bl, br, big, SR, o1, o2, o3, o4, null);
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.printf("INFO  4 min de audio procesados en %.1f s (en este servidor)%n", secs);

        System.out.println(failures == 0 ? "\nTODO OK" : "\nFALLOS: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
