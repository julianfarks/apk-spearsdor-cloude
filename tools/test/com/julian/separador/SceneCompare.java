package com.julian.separador;

/** Compara pocos candidatos de parámetros mostrando la tabla completa por instrumento. */
public class SceneCompare {
    public static void main(String[] args) {
        int secs = 16;
        int n = SceneBench.SR * secs;
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
        int from = SceneBench.SR, to = n - SceneBench.SR;
        double[][] cands = {{0.05, 0.65}, {0.15, 0.75}, {0.25, 0.80}, {0.35, 0.85}};
        for (double[] c : cands) {
            Separator.Params p = new Separator.Params();
            p.cutSimLow = c[0];
            p.cutSimHigh = c[1];
            short[] vl = new short[n], vr = new short[n], il = new short[n], ir = new short[n];
            Separator.extract(L, R, n, SceneBench.SR, vl, vr, il, ir, p, null);
            double[] ci = SceneBench.project(st, il, ir, from, to);
            System.out.printf("cut sim %.2f-%.2f:", c[0], c[1]);
            for (int s = 0; s < 6; s++) {
                System.out.printf("  %s %s", SceneBench.NAMES[s].replaceAll("\\(.*\\)", ""), SceneBench.db(ci[s]));
            }
            System.out.println();
        }
    }
}
