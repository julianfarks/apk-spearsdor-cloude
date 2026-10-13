package com.julian.separador;

/** FFT compleja radix-2 en sitio (el tamaño debe ser potencia de 2). */
final class Fft {
    private final int n;
    private final double[] cos;
    private final double[] sin;
    private final int[] rev;

    Fft(int n) {
        if (n < 2 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException("n debe ser potencia de 2");
        }
        this.n = n;
        cos = new double[n / 2];
        sin = new double[n / 2];
        for (int i = 0; i < n / 2; i++) {
            double a = 2.0 * Math.PI * i / n;
            cos[i] = Math.cos(a);
            sin[i] = Math.sin(a);
        }
        rev = new int[n];
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) {
            rev[i] = Integer.reverse(i) >>> (32 - bits);
        }
    }

    void forward(double[] re, double[] im) {
        transform(re, im, false);
    }

    /** Inversa, ya escalada por 1/n. */
    void inverse(double[] re, double[] im) {
        transform(re, im, true);
        double s = 1.0 / n;
        for (int i = 0; i < n; i++) {
            re[i] *= s;
            im[i] *= s;
        }
    }

    private void transform(double[] re, double[] im, boolean inv) {
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int halfLen = len >> 1;
            int step = n / len;
            for (int i = 0; i < n; i += len) {
                for (int j = 0, k = 0; j < halfLen; j++, k += step) {
                    double wr = cos[k];
                    double wi = inv ? sin[k] : -sin[k];
                    int a = i + j;
                    int b = a + halfLen;
                    double tr = re[b] * wr - im[b] * wi;
                    double ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }
    }
}
