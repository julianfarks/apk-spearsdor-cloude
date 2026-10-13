package com.julian.separador;

import java.util.Random;

/**
 * Escena sintética más parecida a una canción: voz con vibrato y algo de reverb estéreo,
 * batería y bajo al centro, guitarras a los lados, pad ancho y un órgano al centro
 * (el caso difícil: es armónico y está al centro, igual que la voz).
 * Mide cuánto de cada instrumento pasa a cada pista (regresión por mínimos cuadrados).
 */
public class SceneBench {

    static final int SR = 44100;
    static final String[] NAMES = {"voz", "bateria(centro)", "bajo(centro)", "guitarras(lados)",
            "pad(ancho)", "organo(centro)"};

    static double sq(double x) {
        return x * x;
    }

    static double[][][] scene(int secs) {
        int n = SR * secs;
        Random rnd = new Random(7);
        double[] noise = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            noise[i] = rnd.nextGaussian();
        }

        // --- voz ---
        double[] v = new double[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double f0 = 200 + 40 * Math.sin(2 * Math.PI * 0.35 * t) + 25 * Math.sin(2 * Math.PI * 0.9 * t)
                    + 3 * Math.sin(2 * Math.PI * 5.5 * t);
            ph += 2 * Math.PI * f0 / SR;
            double gate = Math.sqrt(Math.max(0, Math.sin(2 * Math.PI * 1.6 * t)));
            double s = 0;
            for (int h = 1; h <= 30; h++) {
                double fh = h * f0;
                if (fh > 9000) {
                    break;
                }
                double form = 1 + 2 * Math.exp(-sq((fh - 700) / 250)) + 1.5 * Math.exp(-sq((fh - 1800) / 400));
                s += form / Math.pow(h, 1.1) * Math.sin(h * ph);
            }
            double sib = Math.pow(Math.max(0, Math.sin(2 * Math.PI * 1.6 * t - 2.4)), 8)
                    * (noise[i + 1] - noise[i]) * 0.5;
            v[i] = gate * s * 0.12 + sib * 0.05;
        }
        double[] vL = new double[n];
        double[] vR = new double[n];
        int dL = (int) (0.011 * SR), dR = (int) (0.017 * SR);
        for (int i = 0; i < n; i++) {
            vL[i] = v[i] + 0.25 * (i >= dL ? v[i - dL] : 0);
            vR[i] = v[i] + 0.25 * (i >= dR ? v[i - dR] : 0);
        }

        // --- batería (centro) ---
        double[] drums = new double[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double tk = t % 0.5;
            double kick = Math.sin(2 * Math.PI * (50 * tk + 70 * 0.05 * (1 - Math.exp(-tk / 0.05))))
                    * Math.exp(-tk / 0.12);
            double ts = (t + 0.25) % 1.0;
            double snare = (noise[i + 1] - 0.5 * noise[i]) * Math.exp(-ts / 0.08) * 0.6;
            double th = t % 0.125;
            double hat = (noise[i + 1] - noise[i]) * Math.exp(-th / 0.02) * 0.25;
            drums[i] = kick * 1.2 + snare + hat;
        }

        // --- bajo (centro) ---
        double[] bass = new double[n];
        double[] bassNotes = {55, 65.4, 73.4, 49};
        double[] bassH = {1, 0.5, 0.3, 0.2};
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            int note = ((int) t) % 4;
            double tn = t % 1.0;
            double env = (1 - Math.exp(-tn / 0.01)) * Math.exp(-tn / 0.8);
            double s = 0;
            for (int h = 0; h < 4; h++) {
                s += bassH[h] * Math.sin(2 * Math.PI * bassNotes[note] * (h + 1) * t);
            }
            bass[i] = s * env;
        }

        // --- guitarras (lados) ---
        double[] gtrL = new double[n], gtrLr = new double[n];
        double[] gtrR = new double[n], gtrRl = new double[n];
        double[] chordA = {220, 277.2, 329.6};
        double[] chordB = {246.9, 311.1, 370};
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double tnA = t % 0.5;
            double tnB = (t + 0.25) % 0.5;
            double a = 0, b = 0;
            for (double f : chordA) {
                for (int h = 1; h <= 6; h++) {
                    a += Math.sin(2 * Math.PI * f * h * t) / h;
                }
            }
            for (double f : chordB) {
                for (int h = 1; h <= 6; h++) {
                    b += Math.sin(2 * Math.PI * f * h * t) / h;
                }
            }
            a *= Math.exp(-tnA / 0.7);
            b *= Math.exp(-tnB / 0.7);
            gtrL[i] = a;
            gtrLr[i] = 0.15 * a;
            gtrR[i] = b;
            gtrRl[i] = 0.15 * b;
        }
        double[] guitarL = new double[n], guitarR = new double[n];
        for (int i = 0; i < n; i++) {
            guitarL[i] = gtrL[i] + gtrRl[i];
            guitarR[i] = gtrLr[i] + gtrR[i];
        }

        // --- pad ancho ---
        double[] padL = new double[n], padR = new double[n];
        double[] padF = {196, 246.9, 293.7, 392};
        double[] phL = new double[4], phR = new double[4];
        for (int j = 0; j < 4; j++) {
            phL[j] = rnd.nextDouble() * 6.28;
            phR[j] = rnd.nextDouble() * 6.28;
        }
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double trem = 0.8 + 0.2 * Math.sin(2 * Math.PI * 0.3 * t);
            for (int j = 0; j < 4; j++) {
                padL[i] += Math.sin(2 * Math.PI * padF[j] * 1.002 * t + phL[j]) * trem;
                padR[i] += Math.sin(2 * Math.PI * padF[j] * 0.998 * t + phR[j]) * trem;
            }
        }

        // --- órgano (centro) ---
        double[] organ = new double[n];
        double[][] chords = {{261.6, 329.6, 392}, {220, 277.2, 329.6}, {246.9, 293.7, 370}, {196, 246.9, 293.7}};
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double[] ch = chords[((int) (t / 2)) % 4];
            double s = 0;
            for (double f : ch) {
                for (int h = 1; h <= 3; h++) {
                    s += Math.sin(2 * Math.PI * f * h * t) / h;
                }
            }
            organ[i] = s;
        }

        double[][][] st = new double[6][2][];
        st[0][0] = vL;
        st[0][1] = vR;
        st[1][0] = drums;
        st[1][1] = drums.clone();
        st[2][0] = bass;
        st[2][1] = bass.clone();
        st[3][0] = guitarL;
        st[3][1] = guitarR;
        st[4][0] = padL;
        st[4][1] = padR;
        st[5][0] = organ;
        st[5][1] = organ.clone();

        double[] target = {0.10, 0.12, 0.09, 0.07, 0.05, 0.05};
        for (int s = 0; s < 6; s++) {
            double e = 0;
            for (int c = 0; c < 2; c++) {
                for (int i = 0; i < n; i++) {
                    e += st[s][c][i] * st[s][c][i];
                }
            }
            double rms = Math.sqrt(e / (2.0 * n));
            double g = target[s] / rms;
            for (int c = 0; c < 2; c++) {
                for (int i = 0; i < n; i++) {
                    st[s][c][i] *= g;
                }
            }
        }
        double peak = 0;
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < n; i++) {
                double m = 0;
                for (int s = 0; s < 6; s++) {
                    m += st[s][c][i];
                }
                peak = Math.max(peak, Math.abs(m));
            }
        }
        double g = 0.8 * 32767 / peak;
        for (int s = 0; s < 6; s++) {
            for (int c = 0; c < 2; c++) {
                for (int i = 0; i < n; i++) {
                    st[s][c][i] *= g;
                }
            }
        }
        return st;
    }

    /** Mínimos cuadrados: o ~ sum coef_s * stem_s (canales apilados). */
    static double[] project(double[][][] st, short[] oL, short[] oR, int from, int to) {
        int m = st.length;
        double[][] G = new double[m][m + 1];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < m; j++) {
                double s = 0;
                for (int n = from; n < to; n++) {
                    s += st[i][0][n] * st[j][0][n] + st[i][1][n] * st[j][1][n];
                }
                G[i][j] = s;
            }
            double s = 0;
            for (int n = from; n < to; n++) {
                s += st[i][0][n] * oL[n] + st[i][1][n] * oR[n];
            }
            G[i][m] = s;
        }
        for (int c = 0; c < m; c++) {
            int piv = c;
            for (int r = c + 1; r < m; r++) {
                if (Math.abs(G[r][c]) > Math.abs(G[piv][c])) {
                    piv = r;
                }
            }
            double[] tmp = G[c];
            G[c] = G[piv];
            G[piv] = tmp;
            for (int r = 0; r < m; r++) {
                if (r != c) {
                    double f = G[r][c] / G[c][c];
                    for (int k = c; k <= m; k++) {
                        G[r][k] -= f * G[c][k];
                    }
                }
            }
        }
        double[] x = new double[m];
        for (int i = 0; i < m; i++) {
            x[i] = G[i][m] / G[i][i];
        }
        return x;
    }

    static double energy(double[][] s, int from, int to) {
        double e = 0;
        for (int n = from; n < to; n++) {
            e += s[0][n] * s[0][n] + s[1][n] * s[1][n];
        }
        return e;
    }

    static String db(double x) {
        double a = Math.abs(x);
        if (a < 1e-4) {
            return "  -inf";
        }
        return String.format("%+6.1f", 20 * Math.log10(a));
    }

    static void report(String title, double[][][] st, short[] vl, short[] vr, short[] il, short[] ir,
                       int from, int to) {
        double[] cv = project(st, vl, vr, from, to);
        double[] ci = project(st, il, ir, from, to);
        System.out.println("\n== " + title + " ==");
        System.out.printf("%-18s %12s %12s   (cuánto de cada instrumento pasa a cada pista, en dB; 0 = todo)%n",
                "", "PISTA VOZ", "PISTA INSTR");
        for (int s = 0; s < st.length; s++) {
            System.out.printf("%-18s %12s %12s%n", NAMES[s], db(cv[s]), db(ci[s]));
        }
        double eV = 0, eOthersInVoice = 0, eVinInst = 0, eOthersInInst = 0;
        for (int s = 0; s < st.length; s++) {
            double e = energy(st[s], from, to);
            if (s == 0) {
                eV = cv[s] * cv[s] * e;
                eVinInst = ci[s] * ci[s] * e;
            } else {
                eOthersInVoice += cv[s] * cv[s] * e;
                eOthersInInst += ci[s] * ci[s] * e;
            }
        }
        System.out.printf("Pista VOZ:   voz / resto           = %+.1f dB  (más alto = más limpia)%n",
                10 * Math.log10(eV / Math.max(1e-9, eOthersInVoice)));
        System.out.printf("Pista INSTR: instrumental / voz    = %+.1f dB  (más alto = menos voz)%n",
                10 * Math.log10(eOthersInInst / Math.max(1e-9, eVinInst)));
    }

    public static void main(String[] args) {
        int secs = 24;
        int n = SR * secs;
        double[][][] st = scene(secs);
        short[] L = new short[n], R = new short[n];
        for (int i = 0; i < n; i++) {
            double l = 0, r = 0;
            for (int s = 0; s < 6; s++) {
                l += st[s][0][i];
                r += st[s][1][i];
            }
            L[i] = (short) Math.round(l);
            R[i] = (short) Math.round(r);
        }
        int from = SR, to = n - SR;

        // Algoritmo anterior (v1): voz = máscara; instrumental = original - voz
        short[] ov1l = new short[n], ov1r = new short[n];
        SeparatorV1.extractVoice(L, R, n, SR, ov1l, ov1r, null);
        short[] i1l = new short[n], i1r = new short[n];
        for (int i = 0; i < n; i++) {
            i1l[i] = (short) (L[i] - ov1l[i]);
            i1r[i] = (short) (R[i] - ov1r[i]);
        }
        report("ANTES (v1)", st, ov1l, ov1r, i1l, i1r, from, to);

        // Algoritmo nuevo (v2) con los parámetros por defecto
        short[] vl = new short[n], vr = new short[n], il = new short[n], ir = new short[n];
        Separator.extract(L, R, n, SR, vl, vr, il, ir, null);
        report("AHORA (v2, parámetros por defecto)", st, vl, vr, il, ir, from, to);
    }
}
