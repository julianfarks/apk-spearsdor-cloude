package com.julian.separador;

/**
 * Separa la voz del resto usando el "estéreo": la voz suele ir al centro (igual en
 * izquierda y derecha) y los instrumentos repartidos. Se hace por bandas de
 * frecuencia (STFT) con una máscara suave, así que no es una IA: funciona bien con
 * canciones estéreo de estudio y deja algo de voz/instrumento mezclado en otras.
 *
 * Solo se calcula la voz; la instrumental es (original - voz).
 */
final class SeparatorV1 {

    interface Progress {
        void onProgress(float fraction);
    }

    static final class Params {
        /** Similitud L/R a partir de la cual una banda empieza a contar como "voz". */
        double simLow = 0.35;
        /** Similitud L/R a partir de la cual una banda es "voz" al 100 %. */
        double simHigh = 0.92;
        /** Suavizado en el tiempo de la máscara (0 = nada, 1 = congelada). */
        double timeSmooth = 0.4;
        /** Por debajo de esta frecuencia todo se queda en la instrumental (bombo, bajo). */
        double bassLowHz = 90;
        /** A partir de esta frecuencia la máscara actúa al 100 %. */
        double bassHighHz = 180;
    }

    static final int N = 4096;
    static final int H = 1024;
    private static final double EPS = 1e-3;

    private SeparatorV1() {
    }

    static void extractVoice(short[] left, short[] right, int frames, int sampleRate,
                             short[] outL, short[] outR, Progress progress) {
        extractVoice(left, right, frames, sampleRate, outL, outR, new Params(), progress);
    }

    static void extractVoice(short[] left, short[] right, int frames, int sampleRate,
                             short[] outL, short[] outR, Params p, Progress progress) {
        final int half = N / 2;
        final int overlap = N - H;
        final Fft fft = new Fft(N);

        final double[] win = new double[N];
        for (int i = 0; i < N; i++) {
            win[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / N);
        }
        double norm = 0;
        for (int m = 0; m < N / H; m++) {
            norm += win[m * H] * win[m * H];
        }

        final double[] bass = new double[half + 1];
        for (int k = 0; k <= half; k++) {
            double hz = (double) k * sampleRate / N;
            double t = (hz - p.bassLowHz) / Math.max(1e-9, p.bassHighHz - p.bassLowHz);
            bass[k] = t < 0 ? 0 : (t > 1 ? 1 : t);
        }

        final double[] re = new double[N];
        final double[] im = new double[N];
        final double[] accL = new double[N];
        final double[] accR = new double[N];
        final double[] raw = new double[half + 1];
        final double[] mask = new double[half + 1];
        final double[] prev = new double[half + 1];

        final int totalFrames = (frames + overlap + H - 1) / H;

        for (int f = 0; f < totalFrames; f++) {
            final int start = f * H - overlap;

            // L en la parte real, R en la imaginaria: una sola FFT para los dos canales.
            for (int i = 0; i < N; i++) {
                int idx = start + i;
                if (idx >= 0 && idx < frames) {
                    re[i] = left[idx] * win[i];
                    im[i] = right[idx] * win[i];
                } else {
                    re[i] = 0;
                    im[i] = 0;
                }
            }
            fft.forward(re, im);

            for (int k = 0; k <= half; k++) {
                int nk = (N - k) & (N - 1);
                double ar = re[k];
                double ai = im[k];
                double br = re[nk];
                double bi = -im[nk];
                // XL = (a + b) / 2 ; XR = (a - b) / (2i)
                double lr = 0.5 * (ar + br);
                double li = 0.5 * (ai + bi);
                double dr = ar - br;
                double di = ai - bi;
                double rr = 0.5 * di;
                double ri = -0.5 * dr;

                double pl = lr * lr + li * li;
                double pr = rr * rr + ri * ri;
                double cross = lr * rr + li * ri;
                double sim = 2.0 * cross / (pl + pr + EPS);

                double t = (sim - p.simLow) / Math.max(1e-9, p.simHigh - p.simLow);
                t = t < 0 ? 0 : (t > 1 ? 1 : t);
                raw[k] = t * t * (3 - 2 * t) * bass[k];
            }

            // Suavizado en frecuencia y en el tiempo para evitar el "burbujeo".
            for (int k = 0; k <= half; k++) {
                double a = raw[k > 0 ? k - 1 : k];
                double b = raw[k < half ? k + 1 : k];
                double sm = 0.25 * a + 0.5 * raw[k] + 0.25 * b;
                double m = p.timeSmooth * prev[k] + (1.0 - p.timeSmooth) * sm;
                prev[k] = m;
                mask[k] = m;
            }

            // La máscara es simétrica, así que se puede aplicar directo a Z = L + iR:
            // al invertir, la parte real es la voz de L y la imaginaria la voz de R.
            for (int k = 0; k <= half; k++) {
                double g = mask[k];
                re[k] *= g;
                im[k] *= g;
                if (k > 0 && k < half) {
                    int nk = N - k;
                    re[nk] *= g;
                    im[nk] *= g;
                }
            }
            fft.inverse(re, im);

            for (int i = 0; i < N; i++) {
                accL[i] += re[i] * win[i];
                accR[i] += im[i] * win[i];
            }

            // Las primeras H muestras ya están completas: se escriben y se desplaza el buffer.
            for (int i = 0; i < H; i++) {
                int pos = start + i;
                if (pos >= 0 && pos < frames) {
                    outL[pos] = toShort(accL[i] / norm);
                    outR[pos] = toShort(accR[i] / norm);
                }
            }
            System.arraycopy(accL, H, accL, 0, N - H);
            System.arraycopy(accR, H, accR, 0, N - H);
            for (int i = N - H; i < N; i++) {
                accL[i] = 0;
                accR[i] = 0;
            }

            if (progress != null && (f & 31) == 0) {
                progress.onProgress((float) f / totalFrames);
            }
        }
        if (progress != null) {
            progress.onProgress(1f);
        }
    }

    /** True si izquierda y derecha son (casi) iguales: no hay nada que separar. */
    static boolean isNearlyMono(short[] l, short[] r, int frames) {
        double mid = 0;
        double side = 0;
        for (int i = 0; i < frames; i += 7) {
            double a = l[i];
            double b = r[i];
            double m = a + b;
            double s = a - b;
            mid += m * m;
            side += s * s;
        }
        if (mid < 1) {
            return true;
        }
        return side < mid * 1e-4;
    }

    private static short toShort(double v) {
        long r = Math.round(v);
        if (r > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (r < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) r;
    }
}
