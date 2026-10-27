package br.com.pelotavision;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Ajuste Rosin–Rammler (RRSB / Weibull de 2 parâmetros) da amostra medida, comum a Android e Windows.
 *
 * <p>F(d) = 100·[1 − exp(−(d/d′)ⁿ)] = % passante (acumulado abaixo de d).</p>
 *
 * <ul>
 *   <li><b>Ajuste:</b> máxima verossimilhança sobre os diâmetros individuais (não sobre as classes), com
 *   peso 1 (base número) ou d³ (base massa estimada). Não depende da largura de classe escolhida.</li>
 *   <li><b>Pontos:</b> % passante acumulado nos limites superiores das classes do histograma, como os
 *   pontos de peneira dos gráficos clássicos. R² e RMSE são calculados nesses pontos, só para comparação
 *   com a literatura; R² de curva acumulada é sempre alto e não prova bom ajuste. KS usa as pelotas
 *   individuais.</li>
 *   <li><b>Massa estimada:</b> supõe esferas de mesma densidade (massa ∝ d³). Não é peneiramento: d vem da
 *   projeção 2D e erros de diâmetro são elevados ao cubo.</li>
 * </ul>
 */
public final class RosinRammler {
    private RosinRammler() {}

    public static final int MIN_PELLETS = 12;

    public enum Basis {
        NUMBER("Número de pelotas", "number", "% passante em número"),
        MASS("Massa estimada (∝ d³)", "estimated_mass_d3", "% passante em massa estimada");
        public final String label, id, axis;
        Basis(String label, String id, String axis) { this.label = label; this.id = id; this.axis = axis; }
        public static Basis fromId(String id) { for (Basis b : values()) if (b.id.equals(id) || b.name().equals(id)) return b; return NUMBER; }
    }

    /** Ponto experimental: % passante acumulado no limite superior de uma classe. */
    public static final class Point {
        public final double size, percentFiner;
        Point(double size, double percentFiner) { this.size = size; this.percentFiner = percentFiner; }
    }

    public static final class Fit {
        public final Basis basis;
        public final String unit;
        public final boolean available;
        public final String reason;
        public final int pellets;
        /** n (uniformidade) e d′ (tamanho característico, 63,2% passante). */
        public final double n, characteristic, d50, r2, rmse, ks;
        public final List<Point> points;

        Fit(Basis basis, String unit, String reason, int pellets, List<Point> points) {
            this.basis = basis; this.unit = unit; this.available = false; this.reason = reason; this.pellets = pellets;
            n = characteristic = d50 = r2 = rmse = ks = Double.NaN; this.points = Collections.unmodifiableList(points);
        }
        Fit(Basis basis, String unit, int pellets, double n, double characteristic, double r2, double rmse, double ks, List<Point> points) {
            this.basis = basis; this.unit = unit; this.available = true; this.reason = null; this.pellets = pellets;
            this.n = n; this.characteristic = characteristic; this.d50 = characteristic * Math.pow(Math.log(2), 1 / n);
            this.r2 = r2; this.rmse = rmse; this.ks = ks; this.points = Collections.unmodifiableList(points);
        }

        /** % passante previsto pelo ajuste. */
        public double finer(double d) {
            if (!available || !(d > 0)) return 0;
            return 100 * (1 - Math.exp(-Math.pow(d / characteristic, n)));
        }

        /** Diâmetro em que o ajuste prevê {@code percent}% passante. */
        public double sizeAt(double percent) {
            if (!available || !(percent > 0) || !(percent < 100)) return Double.NaN;
            return characteristic * Math.pow(-Math.log(1 - percent / 100), 1 / n);
        }

        /** Fórmula ajustada, por exemplo {@code F(d) = 100·[1 − exp(−(d / 12,4 mm)^4,87)]}. */
        public String formula() {
            if (!available) return "Ajuste indisponível: " + reason;
            return "F(d) = 100·[1 − exp(−(d / " + fmt(characteristic) + " " + unit + ")^" + fmt(n) + ")]";
        }

        /** Linha de parâmetros: n, d′, d50 do ajuste e qualidade. */
        public String parameters() {
            if (!available) return reason;
            return "n = " + fmt(n) + " · d′ (63,2%) = " + fmt(characteristic) + " " + unit + " · d50 ajustado = " + fmt(d50) + " " + unit;
        }

        public String quality() {
            if (!available) return "";
            return "R² = " + fmt4(r2) + " · RMSE = " + fmt(rmse) + " p.p. · KS = " + fmt4(ks) + " · " + pellets + " pelotas";
        }

        public JSONObject toJson() throws Exception {
            JSONObject j = new JSONObject();
            j.put("model", "rosin_rammler_rrsb");
            j.put("equation", "F(d) = 100*(1 - exp(-(d/d_prime)^n)), F = cumulative % finer");
            j.put("basis", basis.id);
            j.put("unit", unit);
            j.put("available", available);
            j.put("reason", reason == null ? JSONObject.NULL : reason);
            j.put("pellets", pellets);
            j.put("fit_method", basis == Basis.MASS
                    ? "weighted maximum likelihood on individual diameters, weight d^3 (spheres of equal density)"
                    : "maximum likelihood on individual diameters (number basis)");
            j.put("n", finite(n));
            j.put("d_prime", finite(characteristic));
            j.put("d50_fit", finite(d50));
            j.put("formula", formula());
            j.put("r2_at_class_limits", finite(r2));
            j.put("rmse_percent_points", finite(rmse));
            j.put("ks_individual", finite(ks));
            j.put("note", "R2 and RMSE are computed at histogram class upper limits for comparison with sieve-style plots; R2 of cumulative curves is inflated. "
                    + (basis == Basis.MASS ? "Estimated mass assumes d^3 from 2D image diameters; not a sieve analysis." : "Number basis is not comparable to mass-based sieve RR parameters."));
            JSONArray pts = new JSONArray();
            for (Point p : points) { JSONObject o = new JSONObject(); o.put("size", p.size); o.put("percent_finer", p.percentFiner); pts.put(o); }
            j.put("points", pts);
            return j;
        }
    }

    /** Bloco "rosin_rammler" do JSON: as duas bases, com a escolhida na tela destacada no relatório. */
    public static Object reportJson(Granulometry.Distribution d, Basis selected) throws Exception {
        if (d == null) return JSONObject.NULL;
        JSONObject j = new JSONObject();
        j.put("selected_basis", (selected == null ? Basis.NUMBER : selected).id);
        for (Basis b : Basis.values()) j.put(b.id, fit(d, b).toJson());
        return j;
    }

    /** Ajuste a partir da distribuição exibida (mesmas pelotas incluídas e mesmas classes do histograma). */
    public static Fit fit(Granulometry.Distribution d, Basis basis) {
        if (basis == null) basis = Basis.NUMBER;
        if (d == null) return new Fit(basis, "px", "sem distribuição", 0, new ArrayList<>());
        double[] sizes = d.sizes();
        double[] uppers = new double[d.binCount()];
        for (int i = 0; i < uppers.length; i++) uppers[i] = d.upper(i);
        int[] bin = new int[sizes.length];
        for (int i = 0; i < sizes.length; i++) bin[i] = d.binIndex(sizes[i]);
        return fit(sizes, bin, uppers, d.settings.unit(), basis);
    }

    /**
     * @param sizes diâmetros incluídos
     * @param bin   classe de cada diâmetro (regra do histograma); -1 = fora
     * @param upper limite superior de cada classe
     */
    static Fit fit(double[] sizes, int[] bin, double[] upper, String unit, Basis basis) {
        int m = sizes.length;
        List<Point> points = points(sizes, bin, upper, basis);
        if (m < MIN_PELLETS) return new Fit(basis, unit, "menos de " + MIN_PELLETS + " pelotas incluídas (" + m + ")", m, points);
        double min = Double.POSITIVE_INFINITY, max = 0, logSum = 0;
        for (double v : sizes) {
            if (!(v > 0) || !Double.isFinite(v)) return new Fit(basis, unit, "diâmetro inválido", m, points);
            min = Math.min(min, v); max = Math.max(max, v); logSum += Math.log(v);
        }
        if (max / min < 1.0001) return new Fit(basis, unit, "diâmetros sem variação", m, points);
        // Normaliza pela média geométrica para manter x^k e d³ em faixa numérica segura.
        double g = Math.exp(logSum / m);
        double[] x = new double[m], lx = new double[m], w = new double[m];
        double sw = 0, swl = 0;
        for (int i = 0; i < m; i++) {
            x[i] = sizes[i] / g; lx[i] = Math.log(x[i]);
            w[i] = basis == Basis.MASS ? x[i] * x[i] * x[i] : 1;
            sw += w[i]; swl += w[i] * lx[i];
        }
        double meanLog = swl / sw;
        // Equação de verossimilhança em k é crescente: bissecção em escala log.
        double lo = 0.05, hi = 200;
        if (score(lo, x, lx, w, meanLog) > 0 || score(hi, x, lx, w, meanLog) < 0) return new Fit(basis, unit, "ajuste não convergiu", m, points);
        for (int it = 0; it < 200; it++) {
            double mid = Math.sqrt(lo * hi);
            if (score(mid, x, lx, w, meanLog) < 0) lo = mid; else hi = mid;
            if (hi / lo < 1 + 1e-12) break;
        }
        double k = Math.sqrt(lo * hi), swk = 0;
        for (int i = 0; i < m; i++) swk += w[i] * Math.pow(x[i], k);
        double scale = g * Math.pow(swk / sw, 1 / k);
        if (!Double.isFinite(k) || !Double.isFinite(scale) || scale <= 0) return new Fit(basis, unit, "ajuste não convergiu", m, points);

        // KS sobre a FDA empírica ponderada das pelotas individuais.
        Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(sizes[a], sizes[b]));
        double acc = 0, ks = 0;
        for (int idx = 0; idx < m; idx++) {
            int i = order[idx];
            double model = 1 - Math.exp(-Math.pow(sizes[i] / scale, k));
            ks = Math.max(ks, Math.abs(model - acc / sw));
            acc += w[i];
            ks = Math.max(ks, Math.abs(model - acc / sw));
        }
        // R² e RMSE nos pontos de classe (como nos gráficos de peneira).
        double r2 = Double.NaN, rmse = Double.NaN;
        if (points.size() >= 3) {
            double mean = 0;
            for (Point p : points) mean += p.percentFiner / points.size();
            double ssr = 0, sst = 0;
            for (Point p : points) {
                double e = p.percentFiner - 100 * (1 - Math.exp(-Math.pow(p.size / scale, k)));
                ssr += e * e; sst += (p.percentFiner - mean) * (p.percentFiner - mean);
            }
            rmse = Math.sqrt(ssr / points.size());
            r2 = sst > 0 ? 1 - ssr / sst : Double.NaN;
        }
        return new Fit(basis, unit, m, k, scale, r2, rmse, ks, points);
    }

    private static double score(double k, double[] x, double[] lx, double[] w, double meanLog) {
        double a = 0, b = 0;
        for (int i = 0; i < x.length; i++) { double t = w[i] * Math.pow(x[i], k); a += t * lx[i]; b += t; }
        return a / b - 1 / k - meanLog;
    }

    private static List<Point> points(double[] sizes, int[] bin, double[] upper, Basis basis) {
        List<Point> out = new ArrayList<>();
        if (upper.length == 0 || sizes.length == 0) return out;
        double[] mass = new double[upper.length];
        double total = 0;
        for (int i = 0; i < sizes.length; i++) {
            if (bin[i] < 0 || bin[i] >= upper.length) continue;
            double wgt = basis == Basis.MASS ? sizes[i] * sizes[i] * sizes[i] : 1;
            mass[bin[i]] += wgt; total += wgt;
        }
        if (total <= 0) return out;
        double acc = 0;
        for (int j = 0; j < upper.length; j++) {
            acc += mass[j];
            if (upper[j] > 0) out.add(new Point(upper[j], Math.min(100, 100 * acc / total)));
        }
        return out;
    }

    // ---------------- Gráfico (geometria comum; cada plataforma só desenha) ----------------

    /** Geometria do gráfico em pixels, eixo x logarítmico, y 0–100% passante. */
    public static final class Chart {
        public final float left, top, right, bottom;
        public final double xMin, xMax;
        public final double[] xTicks;
        public final double[] yTicks = {0, 20, 40, 60, 80, 100};
        public final Fit fit;

        public Chart(Fit fit, float left, float top, float right, float bottom) {
            this.fit = fit; this.left = left; this.top = top; this.right = right; this.bottom = bottom;
            double lo = Double.POSITIVE_INFINITY, hi = 0;
            for (Point p : fit.points) { lo = Math.min(lo, p.size); hi = Math.max(hi, p.size); }
            if (fit.available) { lo = Math.min(lo, fit.sizeAt(2)); hi = Math.max(hi, fit.sizeAt(99.5)); }
            if (!(lo > 0) || !Double.isFinite(hi) || hi <= lo) { lo = 1; hi = 10; }
            // Margem de ~12% em log de cada lado.
            double span = Math.log10(hi / lo), pad = Math.max(.05, span * .12);
            xMin = Math.pow(10, Math.log10(lo) - pad); xMax = Math.pow(10, Math.log10(hi) + pad);
            xTicks = ticks(xMin, xMax);
        }

        public float x(double size) { return (float) (left + (right - left) * (Math.log10(size) - Math.log10(xMin)) / (Math.log10(xMax) - Math.log10(xMin))); }
        public float y(double percent) { return (float) (bottom - (bottom - top) * percent / 100); }

        /** Polilinha da curva ajustada, em pixels (x0,y0,x1,y1,…); vazia se indisponível. */
        public float[] curve(int samples) {
            if (!fit.available) return new float[0];
            float[] out = new float[2 * samples];
            for (int i = 0; i < samples; i++) {
                double size = xMin * Math.pow(xMax / xMin, i / (double) (samples - 1));
                out[2 * i] = x(size); out[2 * i + 1] = y(fit.finer(size));
            }
            return out;
        }

        /** Marcas "redondas" do eixo log: 1-2-5 por década, ou todos os inteiros×10^k em faixa estreita. */
        static double[] ticks(double lo, double hi) {
            List<Double> fine = new ArrayList<>(), coarse = new ArrayList<>();
            int e0 = (int) Math.floor(Math.log10(lo)) - 1, e1 = (int) Math.ceil(Math.log10(hi)) + 1;
            for (int e = e0; e <= e1; e++) for (int mnt = 1; mnt <= 9; mnt++) {
                double v = mnt * Math.pow(10, e);
                if (v < lo * (1 - 1e-9) || v > hi * (1 + 1e-9)) continue;
                fine.add(v); if (mnt == 1 || mnt == 2 || mnt == 5) coarse.add(v);
            }
            List<Double> pick = fine.size() <= 9 ? fine : coarse;
            if (pick.size() < 2) pick = fine;
            double[] out = new double[pick.size()];
            for (int i = 0; i < out.length; i++) out[i] = pick.get(i);
            return out;
        }
    }

    public static String tick(double v) {
        if (v >= 100 || Math.abs(v - Math.rint(v)) < 1e-9) return String.format(Locale.ROOT, "%.0f", v);
        return String.format(new Locale("pt", "BR"), v >= 1 ? "%.1f" : "%.2g", v);
    }

    static String fmt(double v) {
        if (!Double.isFinite(v)) return "—";
        if (Math.abs(v) >= 100) return String.format(new Locale("pt", "BR"), "%.1f", v);
        if (Math.abs(v) >= 10) return String.format(new Locale("pt", "BR"), "%.2f", v);
        return String.format(new Locale("pt", "BR"), "%.3f", v);
    }

    static String fmt4(double v) { return Double.isFinite(v) ? String.format(new Locale("pt", "BR"), "%.4f", v) : "—"; }

    private static Object finite(double v) { return Double.isFinite(v) ? v : JSONObject.NULL; }
}
