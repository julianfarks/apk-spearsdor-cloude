package com.julian.separador;

/** Segundo barrido: umbrales más estrictos y tamaños de mediana. */
public class SceneSweep2 {

    static final int SR = SceneBench.SR;

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

        System.out.println("PISTA VOZ: umbrales estrictos (voz dB | resto colado dB | diferencia)");
        double[][] vs = {{0.55, 0.95}, {0.65, 0.97}, {0.75, 0.98}, {0.80, 0.99}};
        for (double[] v : vs) {
            Separator.Params p = new Separator.Params();
            p.voiceSimLow = v[0];
            p.voiceSimHigh = v[1];
            double[] m = SceneSweep.run(st, L, R, n, p, from, to);
            System.out.printf("  sim %.2f-%.2f  ->  voz %+5.1f | colado %+6.1f | dif %+5.1f%n",
                    v[0], v[1], m[0], m[1], m[0] - m[1]);
        }

        System.out.println("\nPISTA VOZ: tamaño de medianas con sim 0.65-0.97");
        int[][] meds = {{5, 9}, {5, 13}, {7, 9}, {7, 13}, {7, 21}, {11, 13}, {11, 21}, {15, 21}};
        for (int[] md : meds) {
            Separator.Params p = new Separator.Params();
            p.voiceSimLow = 0.65;
            p.voiceSimHigh = 0.97;
            p.timeMedian = md[0];
            p.freqMedian = md[1];
            double[] m = SceneSweep.run(st, L, R, n, p, from, to);
            System.out.printf("  tiempo %2d freq %2d  ->  voz %+5.1f | colado %+6.1f | dif %+5.1f   (instr: voz que queda %+6.1f | conservada %+5.1f)%n",
                    md[0], md[1], m[0], m[1], m[0] - m[1], m[2], m[3]);
        }

        System.out.println("\nINSTRUMENTAL: poco armónico (voz que queda dB | instrumental conservada dB)");
        double[][] cs = {{0.15, 0.75}, {0.05, 0.65}, {-0.05, 0.55}};
        double[] cw = {0.0, 0.15, 0.3, 0.45};
        for (double[] c : cs) {
            for (double w : cw) {
                Separator.Params p = new Separator.Params();
                p.cutSimLow = c[0];
                p.cutSimHigh = c[1];
                p.cutHarmonicWeight = w;
                double[] m = SceneSweep.run(st, L, R, n, p, from, to);
                System.out.printf("  sim %+.2f-%.2f  armónico %.2f  ->  voz que queda %+6.1f | conservada %+5.1f%n",
                        c[0], c[1], w, m[2], m[3]);
            }
        }

        System.out.println("\nINSTRUMENTAL: efecto de la banda de recorte (sim 0.05-0.65, armónico 0.3)");
        double[][] bands = {{80, 160, 6000, 11000, 0.3}, {80, 160, 9000, 15000, 0.3}, {80, 160, 9000, 15000, 0.6}, {60, 120, 12000, 18000, 0.5}};
        for (double[] bd : bands) {
            Separator.Params p = new Separator.Params();
            p.cutSimLow = 0.05;
            p.cutSimHigh = 0.65;
            p.cutHarmonicWeight = 0.3;
            p.cutBassLowHz = bd[0];
            p.cutBassHighHz = bd[1];
            p.cutTopStartHz = bd[2];
            p.cutTopEndHz = bd[3];
            p.cutTopFloor = bd[4];
            double[] m = SceneSweep.run(st, L, R, n, p, from, to);
            System.out.printf("  grave %.0f-%.0f  agudo %.0f-%.0f piso %.1f  ->  voz que queda %+6.1f | conservada %+5.1f%n",
                    bd[0], bd[1], bd[2], bd[3], bd[4], m[2], m[3]);
        }
    }
}
