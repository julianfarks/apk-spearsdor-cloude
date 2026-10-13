package com.julian.separador;

/**
 * Separa voz e instrumental con técnicas clásicas (no es una IA):
 *
 *  1. Estéreo: la voz suele ir al centro (igual en izquierda y derecha) y muchos
 *     instrumentos van repartidos a los lados.
 *  2. Armónico vs percusivo: la voz es "armónica" (tonos sostenidos) y el bombo,
 *     la caja y los platillos son "percusivos" (golpes). Aunque estén al centro,
 *     los golpes se dejan fuera de la voz.
 *  3. Rango de frecuencias: bajos y agudos extremos se quedan en la instrumental.
 *
 * Se calculan DOS máscaras distintas: una estricta para la pista de voz (para que
 * no se cuele instrumental) y otra más generosa para quitar la voz de la
 * instrumental (para que no se cuele voz).
 */
final class Separator {

    interface Progress {
        void onProgress(float fraction);
    }

    static final class Params {
        /** Pista de voz: similitud L/R a partir de la cual una banda empieza a contar como voz. */
        double voiceSimLow = 0.65;
        /** Pista de voz: similitud L/R a partir de la cual es voz al 100 %. */
        double voiceSimHigh = 0.97;
        /** Instrumental: similitud a partir de la cual se empieza a quitar. */
        double cutSimLow = 0.15;
        /** Instrumental: similitud a partir de la cual se quita al 100 %. */
        double cutSimHigh = 0.75;

        double voiceBassLowHz = 110;
        double voiceBassHighHz = 220;
        double voiceTopStartHz = 7000;
        double voiceTopEndHz = 13000;
        double voiceTopFloor = 0.0;

        double cutBassLowHz = 80;
        double cutBassHighHz = 160;
        double cutTopStartHz = 6000;
        double cutTopEndHz = 11000;
        double cutTopFloor = 0.3;

        /** 0 = ignora la parte percusiva, 1 = la aplica por completo (pista de voz). */
        double voiceHarmonicWeight = 1.0;
        /** Igual, para lo que se quita de la instrumental. */
        double cutHarmonicWeight = 0.15;

        /** Ventana (en cuadros, impar) del filtro de mediana en el tiempo. */
        int timeMedian = 5;
        /** Ventana (en bandas, impar) del filtro de mediana en frecuencia. */
        int freqMedian = 13;
    }

    static final int N = 4096;
    static final int H = 1024;

    private Separator() {
    }

    static void extract(short[] left, short[] right, int frames, int sampleRate,
                        short[] voiceL, short[] voiceR, short[] instL, short[] instR,
                        Progress progress) {
        extract(left, right, frames, sampleRate, voiceL, voiceR, instL, instR,
                new Params(), progress);
    }

    static void extract(short[] left, short[] right, int frames, int sampleRate,
                        short[] voiceL, short[] voiceR, short[] instL, short[] instR,
                        Params p, Progress progress) {
        new Worker(left, right, frames, sampleRate, voiceL, voiceR, instL, instR, p, progress).run();
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

    // ------------------------------------------------------------------------------------

    private static final class Worker {
        private final short[] left;
        private final short[] right;
        private final int frames;
        private final short[] voiceL;
        private final short[] voiceR;
        private final short[] instL;
        private final short[] instR;
        private final Params p;
        private final Progress progress;

        private final int half = N / 2;
        private final int overlap = N - H;
        private final int d;
        private final int ring;
        private final int hf;
        private final int total;
        private final Fft fft = new Fft(N);
        private final double[] win = new double[N];
        private final double norm;

        private final double[] bandV;
        private final double[] bandC;

        private final double[][] zRe;
        private final double[][] zIm;
        private final double[][] mag;
        private final double[][] cen;

        private final double[] cs;
        private final double[] gv;
        private final double[] gc;
        private final double[] medBuf;
        private final double[] re = new double[N];
        private final double[] im = new double[N];
        private final double[] accVL = new double[N];
        private final double[] accVR = new double[N];
        private final double[] accIL = new double[N];
        private final double[] accIR = new double[N];

        Worker(short[] left, short[] right, int frames, int sampleRate,
               short[] voiceL, short[] voiceR, short[] instL, short[] instR,
               Params p, Progress progress) {
            this.left = left;
            this.right = right;
            this.frames = frames;
            this.voiceL = voiceL;
            this.voiceR = voiceR;
            this.instL = instL;
            this.instR = instR;
            this.p = p;
            this.progress = progress;

            d = Math.max(0, p.timeMedian / 2);
            ring = 2 * d + 1;
            hf = Math.max(0, p.freqMedian / 2);
            total = (frames + overlap + H - 1) / H;

            for (int i = 0; i < N; i++) {
                win[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / N);
            }
            double nn = 0;
            for (int m = 0; m < N / H; m++) {
                nn += win[m * H] * win[m * H];
            }
            norm = nn;

            bandV = new double[half + 1];
            bandC = new double[half + 1];
            for (int k = 0; k <= half; k++) {
                double hz = (double) k * sampleRate / N;
                bandV[k] = band(hz, p.voiceBassLowHz, p.voiceBassHighHz,
                        p.voiceTopStartHz, p.voiceTopEndHz, p.voiceTopFloor);
                bandC[k] = band(hz, p.cutBassLowHz, p.cutBassHighHz,
                        p.cutTopStartHz, p.cutTopEndHz, p.cutTopFloor);
            }

            zRe = new double[ring][N];
            zIm = new double[ring][N];
            mag = new double[ring][half + 1];
            cen = new double[ring][half + 1];
            cs = new double[half + 1];
            gv = new double[half + 1];
            gc = new double[half + 1];
            medBuf = new double[Math.max(p.timeMedian, p.freqMedian) + 2];
        }

        void run() {
            for (int f = 0; f < total; f++) {
                analyze(f);
                if (f - d >= 0) {
                    processCenter(f - d, f);
                }
                if (progress != null && (f & 31) == 0) {
                    progress.onProgress((float) f / total);
                }
            }
            // Los últimos cuadros se procesan con los vecinos que existan.
            for (int cf = Math.max(0, total - d); cf < total; cf++) {
                processCenter(cf, total - 1);
            }
            if (progress != null) {
                progress.onProgress(1f);
            }
        }

        /** FFT del cuadro f (L en la parte real, R en la imaginaria) y medidas por banda. */
        private void analyze(int f) {
            final int slot = f % ring;
            final double[] a = zRe[slot];
            final double[] b = zIm[slot];
            final int start = f * H - overlap;
            for (int i = 0; i < N; i++) {
                int idx = start + i;
                if (idx >= 0 && idx < frames) {
                    a[i] = left[idx] * win[i];
                    b[i] = right[idx] * win[i];
                } else {
                    a[i] = 0;
                    b[i] = 0;
                }
            }
            fft.forward(a, b);

            final double[] mg = mag[slot];
            final double[] cn = cen[slot];
            for (int k = 0; k <= half; k++) {
                int nk = (N - k) & (N - 1);
                double ar = a[k];
                double ai = b[k];
                double br = a[nk];
                double bi = -b[nk];
                // XL = (a + b) / 2 ; XR = (a - b) / (2i)
                double lr = 0.5 * (ar + br);
                double li = 0.5 * (ai + bi);
                double rr = 0.5 * (ai - bi);
                double ri = -0.5 * (ar - br);

                double pl = lr * lr + li * li;
                double pr = rr * rr + ri * ri;
                double cross = lr * rr + li * ri;
                cn[k] = 2.0 * cross / (pl + pr + 1e-3);

                double mr = 0.5 * (lr + rr);
                double mi = 0.5 * (li + ri);
                mg[k] = Math.sqrt(mr * mr + mi * mi);
            }
        }

        /** Calcula las máscaras del cuadro central cf (ya hay cuadros analizados hasta 'latest'). */
        private void processCenter(int cf, int latest) {
            final int sc = cf % ring;
            final int sPrev = (cf > 0 ? cf - 1 : cf) % ring;
            final int sNext = (cf < latest ? cf + 1 : cf) % ring;
            final int lo = Math.max(0, cf - d);
            final int hi = Math.min(latest, cf + d);

            final double[] cPrev = cen[sPrev];
            final double[] cCur = cen[sc];
            final double[] cNext = cen[sNext];
            for (int k = 0; k <= half; k++) {
                cs[k] = 0.25 * cPrev[k] + 0.5 * cCur[k] + 0.25 * cNext[k];
            }

            final boolean harm = p.voiceHarmonicWeight > 0 || p.cutHarmonicWeight > 0;
            final double wv = p.voiceHarmonicWeight;
            final double wc = p.cutHarmonicWeight;
            final double[] magCur = mag[sc];
            for (int k = 0; k <= half; k++) {
                double c = 0.25 * cs[k > 0 ? k - 1 : k] + 0.5 * cs[k]
                        + 0.25 * cs[k < half ? k + 1 : k];

                double hm = 1.0;
                if (harm) {
                    int n = 0;
                    for (int t = lo; t <= hi; t++) {
                        medBuf[n++] = mag[t % ring][k];
                    }
                    double hv = median(medBuf, n);
                    n = 0;
                    int k0 = Math.max(0, k - hf);
                    int k1 = Math.min(half, k + hf);
                    for (int kk = k0; kk <= k1; kk++) {
                        medBuf[n++] = magCur[kk];
                    }
                    double pv = median(medBuf, n);
                    hm = (hv * hv) / (hv * hv + pv * pv + 1e-9);
                }
                gv[k] = smooth(c, p.voiceSimLow, p.voiceSimHigh) * bandV[k]
                        * ((1.0 - wv) + wv * hm);
                gc[k] = smooth(c, p.cutSimLow, p.cutSimHigh) * bandC[k]
                        * ((1.0 - wc) + wc * hm);
            }

            // Pista de voz: original * máscara de voz
            applyMask(zRe[sc], zIm[sc], gv, false);
            fft.inverse(re, im);
            for (int i = 0; i < N; i++) {
                accVL[i] += re[i] * win[i];
                accVR[i] += im[i] * win[i];
            }
            // Instrumental: original * (1 - máscara de recorte)
            applyMask(zRe[sc], zIm[sc], gc, true);
            fft.inverse(re, im);
            for (int i = 0; i < N; i++) {
                accIL[i] += re[i] * win[i];
                accIR[i] += im[i] * win[i];
            }

            final int start = cf * H - overlap;
            for (int i = 0; i < H; i++) {
                int pos = start + i;
                if (pos >= 0 && pos < frames) {
                    voiceL[pos] = toShort(accVL[i] / norm);
                    voiceR[pos] = toShort(accVR[i] / norm);
                    instL[pos] = toShort(accIL[i] / norm);
                    instR[pos] = toShort(accIR[i] / norm);
                }
            }
            shift(accVL);
            shift(accVR);
            shift(accIL);
            shift(accIR);
        }

        /** La máscara es simétrica en frecuencia, así que se aplica directo a Z = L + iR. */
        private void applyMask(double[] srcRe, double[] srcIm, double[] g, boolean invert) {
            for (int k = 0; k <= half; k++) {
                double gk = invert ? 1.0 - g[k] : g[k];
                re[k] = srcRe[k] * gk;
                im[k] = srcIm[k] * gk;
                if (k > 0 && k < half) {
                    int nk = N - k;
                    re[nk] = srcRe[nk] * gk;
                    im[nk] = srcIm[nk] * gk;
                }
            }
        }

        private static void shift(double[] acc) {
            System.arraycopy(acc, H, acc, 0, N - H);
            for (int i = N - H; i < N; i++) {
                acc[i] = 0;
            }
        }
    }

    private static double band(double hz, double bassLow, double bassHigh,
                               double topStart, double topEnd, double topFloor) {
        double up = (hz - bassLow) / Math.max(1e-9, bassHigh - bassLow);
        up = up < 0 ? 0 : (up > 1 ? 1 : up);
        double down;
        if (hz <= topStart) {
            down = 1.0;
        } else if (hz >= topEnd) {
            down = topFloor;
        } else {
            down = 1.0 - (1.0 - topFloor) * (hz - topStart) / Math.max(1e-9, topEnd - topStart);
        }
        return up * down;
    }

    private static double smooth(double x, double lo, double hi) {
        double t = (x - lo) / Math.max(1e-9, hi - lo);
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }

    /** Mediana de los primeros n valores (ordena el arreglo en el sitio). */
    private static double median(double[] buf, int n) {
        for (int i = 1; i < n; i++) {
            double v = buf[i];
            int j = i - 1;
            while (j >= 0 && buf[j] > v) {
                buf[j + 1] = buf[j];
                j--;
            }
            buf[j + 1] = v;
        }
        return buf[n >> 1];
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
