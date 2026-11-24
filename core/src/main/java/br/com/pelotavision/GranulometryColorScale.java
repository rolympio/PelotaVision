package br.com.pelotavision;

/**
 * Continuous diameter → color scale of the granulometric map, shared by Android, Windows and the
 * exported reports. Blue (smallest) → cyan → green → yellow → red (largest), by HSV hue 240°→0°.
 *
 * <p>Relative scale: between the smallest and largest diameter INCLUDED in the distribution
 * of this photo. Fixed scale: user range in mm (or px without physical scale), so that photos
 * can be compared; values outside the range are clamped to the end colors. When the range is
 * empty (all diameters equal) every pellet gets the middle color.
 */
public final class GranulometryColorScale {
    /** 0.15.1: 55% por padrão e até 90% (antes 25% / 70%): sobre correia clara a cor a 25% ficava quase invisível. */
    public static final double DEFAULT_OPACITY = 0.55, MAX_OPACITY = 0.90;
    /** Escala relativa robusta (D10–D90) a partir deste número de pelotas incluídas; abaixo, mínimo–máximo. */
    public static final int ROBUST_MIN_COUNT = 20;
    private static final float SATURATION = 0.85f, VALUE = 0.95f;

    public final double min, max;
    public final boolean fixed;
    public final String unit;
    /** "min_max", "d10_d90" (relativa robusta) ou "fixed". */
    public final String basis;

    private GranulometryColorScale(double min, double max, boolean fixed, String unit) {
        this(min, max, fixed, unit, fixed ? "fixed" : "min_max");
    }
    private GranulometryColorScale(double min, double max, boolean fixed, String unit, String basis) {
        this.min = min;
        this.max = max;
        this.fixed = fixed;
        this.unit = unit;
        this.basis = basis;
    }

    /** Null when the distribution has no included pellet. */
    public static GranulometryColorScale relative(Granulometry.Distribution d) {
        if (d == null || d.included == 0 || !Double.isFinite(d.minMm) || !Double.isFinite(d.maxMm)) return null;
        // 0.15.1: com muitas pelotas, poucos fragmentos/extremos esticavam a escala e quase tudo ficava
        // verde-amarelo. D10–D90 espalha as cores sobre a massa central; abaixo de D10 = azul, acima de D90 = vermelho.
        if (d.included >= ROBUST_MIN_COUNT && Double.isFinite(d.d10) && Double.isFinite(d.d90) && d.d90 > d.d10)
            return new GranulometryColorScale(d.d10, d.d90, false, d.settings.unit(), "d10_d90");
        return new GranulometryColorScale(d.minMm, d.maxMm, false, d.settings.unit());
    }

    public static GranulometryColorScale fixed(double min, double max, String unit) {
        if (!Double.isFinite(min) || !Double.isFinite(max) || min < 0 || max <= min)
            throw new IllegalArgumentException("Escala fixa: informe mínimo e máximo com mínimo < máximo.");
        return new GranulometryColorScale(min, max, true, unit);
    }

    public boolean equalRange() { return !(max > min); }

    /** Position in [0,1]; 0.5 for an empty range. */
    public double fraction(double value) {
        if (equalRange()) return 0.5;
        return Math.max(0, Math.min(1, (value - min) / (max - min)));
    }

    /** Opaque 0xRRGGBB for a diameter. */
    public int rgb(double value) { return colorAt(fraction(value)); }

    public String mode() { return fixed ? "fixa" : "relativa"; }
    /** Texto da legenda, incluindo a base da escala relativa. */
    public String label() { return fixed ? "fixa" : "d10_d90".equals(basis) ? "relativa D10–D90" : "relativa mín–máx"; }
    /** Legenda: valores além das pontas também recebem a cor da ponta. */
    public boolean clipsEnds() { return "d10_d90".equals(basis); }

    /** n evenly spaced legend values from min to max (n ≥ 2). */
    public double[] ticks(int n) {
        n = Math.max(2, n);
        double[] t = new double[n];
        for (int i = 0; i < n; i++) t[i] = equalRange() ? min : min + (max - min) * i / (n - 1.0);
        return t;
    }

    public static double clampOpacity(double opacity) {
        return Double.isFinite(opacity) ? Math.max(0, Math.min(MAX_OPACITY, opacity)) : DEFAULT_OPACITY;
    }

    /** 0xRRGGBB for t in [0,1] (hue 240° → 0°). Same formula as android.graphics.Color.HSVToColor. */
    public static int colorAt(double t) {
        t = Double.isFinite(t) ? Math.max(0, Math.min(1, t)) : 0.5;
        float h = (float) (240 * (1 - t)), s = SATURATION, v = VALUE;
        float c = v * s, hp = h / 60f, x = c * (1 - Math.abs(hp % 2 - 1)), m = v - c;
        float r, g, b;
        if (hp < 1) { r = c; g = x; b = 0; }
        else if (hp < 2) { r = x; g = c; b = 0; }
        else if (hp < 3) { r = 0; g = c; b = x; }
        else if (hp < 4) { r = 0; g = x; b = c; }
        else { r = x; g = 0; b = c; }
        return (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }

    /** Source-over blend of an opaque color over a photo pixel: opacity of color, rest of the photo. */
    public static int blend(int photoRgb, int colorRgb, double opacity) {
        double a = clampOpacity(opacity);
        int r = (int) Math.round(((photoRgb >> 16) & 255) * (1 - a) + ((colorRgb >> 16) & 255) * a);
        int g = (int) Math.round(((photoRgb >> 8) & 255) * (1 - a) + ((colorRgb >> 8) & 255) * a);
        int b = (int) Math.round((photoRgb & 255) * (1 - a) + (colorRgb & 255) * a);
        return (r << 16) | (g << 8) | b;
    }

    /** Opacidade do contorno: zero quando o preenchimento é zero (foto original preservada). */
    public static double rimOpacity(double opacity) {
        double a = clampOpacity(opacity);
        return a <= 0 ? 0 : Math.min(MAX_OPACITY, a + 0.30);
    }

    /** Alpha byte for drawing APIs (Android Paint / AWT Color). */
    public static int alpha(double opacity) { return (int) Math.round(clampOpacity(opacity) * 255); }

    /**
     * Blends {@code color} into the pixels whose center lies inside the rotated ellipse (pixel-exact, used by
     * the exported map on Android and Windows so both produce the same image). Pixels outside are untouched.
     * @return number of pixels changed
     */
    public static int paintEllipse(int[] rgb, int width, int height, double cx, double cy, double a, double b, double angleDeg,
                                   int color, double opacity) {
        if (!(a > 0) || !(b > 0) || !Double.isFinite(cx) || !Double.isFinite(cy)) return 0;
        double t = Math.toRadians(angleDeg), c = Math.cos(t), s = Math.sin(t), ext = Math.max(a, b);
        // 0.15.1: contorno mais forte (≈1,5 px ou 20% do raio) para separar pelotas encostadas sobre fundo claro.
        double ring = Math.min(0.35, Math.max(0.20, 1.5 / Math.min(a, b))), inner = 1 - ring, rimStart = inner * inner;
        double rim = rimOpacity(opacity);
        int x0 = Math.max(0, (int) Math.floor(cx - ext)), x1 = Math.min(width - 1, (int) Math.ceil(cx + ext));
        int y0 = Math.max(0, (int) Math.floor(cy - ext)), y1 = Math.min(height - 1, (int) Math.ceil(cy + ext));
        int n = 0;
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                double dx = x + 0.5 - cx, dy = y + 0.5 - cy, u = (dx * c + dy * s) / a, v = (-dx * s + dy * c) / b;
                double q = u * u + v * v;
                if (q <= 1) {
                    int i = y * width + x;
                    rgb[i] = (rgb[i] & 0xFF000000) | blend(rgb[i] & 0xFFFFFF, color, q >= rimStart ? rim : opacity);
                    n++;
                }
            }
        return n;
    }

    /** Paints every pellet included in the distribution; returns how many were colored. */
    public static int paintMap(int[] rgb, int width, int height, PelletDetector.Result result, Granulometry.Distribution d,
                               GranulometryColorScale scale, double opacity) {
        if (scale == null) return 0;
        int n = 0;
        for (PelletDetector.Detection p : result.circles) {
            Granulometry.Item item = Granulometry.Item.of(p);
            if (!colored(d, item)) continue;
            int color = scale.rgb(d.diameterMm(item));
            double a = p.semiMajor > 0 ? p.semiMajor : p.radius, b = p.semiMinor > 0 ? p.semiMinor : p.radius;
            paintEllipse(rgb, width, height, p.x, p.y, a, b, p.semiMajor > 0 ? p.angleDeg : 0, color, opacity);
            n++;
        }
        return n;
    }

    /** True when the pellet is part of the distribution (and therefore colored): same rule as the histogram. */
    public static boolean colored(Granulometry.Distribution d, Granulometry.Item item) {
        return d != null && d.includes(item) && d.binIndex(d.diameterMm(item)) >= 0;
    }
}
