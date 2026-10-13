package com.julian.separador;

/** Barrido de parámetros sobre la escena sintética (herramienta de ajuste, no es parte de la app). */
public class SceneSweep {

    static final int SR = SceneBench.SR;

    static double[] metrics(double[][][] st, short[] vl, short[] vr, short[] il, short[] ir, int from, int to) {
        double[] cv = SceneBench.project(st, vl, vr, from, to);
        double[] ci = SceneBench.project(st, il, ir, from, to);
        double eOthers = 0, leakVoice = 0, keptInst = 0;
        for (int s = 1; s < st.length; s++) {
            double e = SceneBench.energy(st[s], from, to);
            eOthers += e;
            leakVoice += cv[s] * cv[s] * e;
            keptInst += ci[s] * ci[s] * e;
        }
        return new double[]{
                20 * Math.log10(Math.max(1e-6, Math.abs(cv[0]))),   // voz que se conserva en pista voz
                10 * Math.log10(Math.max(1e-12, leakVoice / eOthers)), // resto que se cuela en pista voz
                20 * Math.log10(Math.max(1e-6, Math.abs(ci[0]))),   // voz que queda en instrumental
                10 * Math.log10(Math.max(1e-12, keptInst / eOthers))   // instrumental que se conserva
        };
    }

    static double[] run(double[][][] st, short[] L, short[] R, int n, Separator.Params p, int from, int to) {
        short[] vl = new short[n], vr = new short[n], il = new short[n], ir = new short[n];
        Separator.extract(L, R, n, SR, vl, vr, il, ir, p, null);
        return metrics(st, vl, vr, il, ir, from, to);
    }

    public static void main(String[] args) {
        int secs = 16;
        int n = SR * secs;
        double[][][] st = SceneBench.scene(secs);
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

        System.out.println("PISTA VOZ  (voz conservada dB: más cerca de 0 mejor | resto colado dB: más negativo mejor)");
        double[][] vs = {{0.55, 0.95}, {0.45, 0.90}, {0.35, 0.85}, {0.25, 0.80}, {0.15, 0.75}};
        double[] vw = {0.5, 0.8, 1.0};
        for (double[] v : vs) {
            for (double w : vw) {
                Separator.Params p = new Separator.Params();
                p.voiceSimLow = v[0];
                p.voiceSimHigh = v[1];
                p.voiceHarmonicWeight = w;
                double[] m = run(st, L, R, n, p, from, to);
                System.out.printf("  sim %.2f-%.2f  armónico %.1f  ->  voz %+5.1f dB | resto colado %+6.1f dB%n",
                        v[0], v[1], w, m[0], m[1]);
            }
        }

        System.out.println("\nPISTA INSTRUMENTAL  (voz que queda dB: más negativo mejor | instrumental conservada dB: más cerca de 0 mejor)");
        double[][] cs = {{0.30, 0.85}, {0.15, 0.75}, {0.05, 0.65}, {-0.05, 0.55}, {-0.15, 0.45}};
        double[] cw = {0.3, 0.6, 1.0};
        for (double[] c : cs) {
            for (double w : cw) {
                Separator.Params p = new Separator.Params();
                p.cutSimLow = c[0];
                p.cutSimHigh = c[1];
                p.cutHarmonicWeight = w;
                double[] m = run(st, L, R, n, p, from, to);
                System.out.printf("  sim %+.2f-%.2f  armónico %.1f  ->  voz que queda %+6.1f dB | instrumental conservada %+5.1f dB%n",
                        c[0], c[1], w, m[2], m[3]);
            }
        }
    }
}
