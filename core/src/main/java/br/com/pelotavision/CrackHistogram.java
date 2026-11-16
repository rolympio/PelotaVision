package br.com.pelotavision;

import java.util.List;

/**
 * Second granulometric series: pellets with a crack indication, in exactly the classes of the
 * total distribution. It is a subset of the total, never added to it.
 *
 * <p>Mapping of the screening states: {@link CrackAnalysis.Status#POSSIBLE} = "trincada"
 * (indicação do classificador ou revisão manual), {@link CrackAnalysis.Status#NO_INDICATION} =
 * "sem trinca visível", {@link CrackAnalysis.Status#INCONCLUSIVE} = "incerta / não avaliável".
 * When no classification is available the series is reported as unavailable, never as zero.
 */
public final class CrackHistogram {
    public enum Normalization {
        /** Number of pellets per class (both series). */
        ABSOLUTE,
        /** Both series divided by the number of pellets in the distribution: the subset relation is preserved. */
        PERCENT_OF_ALL,
        /** Each series divided by its own total (sums 100% each): distribution conditional on the class. */
        CONDITIONAL
    }

    public final Granulometry.Distribution distribution;
    /** False when the classifier is off, the classification does not match the pellets, or none is evaluable. */
    public final boolean available;
    public final String unavailableReason;
    private final int[] cracked;
    /** Counts among pellets INCLUDED in the distribution. */
    public final int total, crackedTotal, intactTotal, uncertainTotal;

    private CrackHistogram(Granulometry.Distribution d, boolean available, String reason, int[] cracked,
                           int crackedTotal, int intactTotal, int uncertainTotal) {
        this.distribution = d;
        this.available = available;
        this.unavailableReason = reason;
        this.cracked = cracked;
        this.total = d == null ? 0 : d.included;
        this.crackedTotal = crackedTotal;
        this.intactTotal = intactTotal;
        this.uncertainTotal = uncertainTotal;
    }

    /**
     * @param items  the same list (same order) used to compute {@code d}
     * @param cracks entries aligned with {@code items} (entry i = pellet i), or null when the classifier is off
     */
    public static CrackHistogram compute(Granulometry.Distribution d, List<Granulometry.Item> items, CrackAnalysis cracks) {
        if (d == null) throw new IllegalArgumentException("Distribuição ausente.");
        int bins = d.binCount();
        if (cracks == null)
            return new CrackHistogram(d, false, "classificação indisponível", new int[bins], 0, 0, 0);
        if (items == null || cracks.entries.size() != items.size())
            return new CrackHistogram(d, false, "classificação indisponível (pelotas e classificação não correspondem)", new int[bins], 0, 0, 0);
        int[] series = new int[bins];
        int c = 0, ok = 0, unc = 0, included = 0;
        for (int i = 0; i < items.size(); i++) {
            Granulometry.Item item = items.get(i);
            if (!d.includes(item)) continue;
            int bin = d.binIndex(d.diameterMm(item));
            if (bin < 0) continue;
            included++;
            CrackAnalysis.Status s = cracks.entries.get(i).status;
            if (s == CrackAnalysis.Status.POSSIBLE) { series[bin]++; c++; }
            else if (s == CrackAnalysis.Status.NO_INDICATION) ok++;
            else unc++;
        }
        if (included != d.included)
            return new CrackHistogram(d, false, "classificação indisponível (contagem divergente da distribuição)", new int[bins], 0, 0, 0);
        if (c + ok == 0)
            return new CrackHistogram(d, false, "classificação indisponível (nenhuma pelota avaliável)", new int[bins], 0, 0, unc);
        return new CrackHistogram(d, true, null, series, c, ok, unc);
    }

    public int binCount() { return cracked.length; }
    public int cracked(int bin) { return cracked[bin]; }
    public int all(int bin) { return distribution.count(bin); }
    public int evaluable() { return crackedTotal + intactTotal; }

    /** Cracked pellets among the evaluable ones (incertas fora do denominador); NaN if unavailable. */
    public double crackedPercentOfEvaluable() {
        return !available || evaluable() == 0 ? Double.NaN : 100.0 * crackedTotal / evaluable();
    }

    public double allValue(int bin, Normalization n) {
        int v = all(bin);
        if (n == Normalization.ABSOLUTE) return v;
        return total == 0 ? 0 : 100.0 * v / total;
    }

    public double crackedValue(int bin, Normalization n) {
        if (!available) return Double.NaN;
        int v = cracked[bin];
        switch (n) {
            case ABSOLUTE: return v;
            case PERCENT_OF_ALL: return total == 0 ? 0 : 100.0 * v / total;
            default: return crackedTotal == 0 ? 0 : 100.0 * v / crackedTotal;
        }
    }

    /** Short legend text for the second series. */
    public String crackedLegend(Normalization n) {
        if (!available) return "Pelotas trincadas: " + unavailableReason;
        return n == Normalization.CONDITIONAL ? "Pelotas trincadas (distribuição condicional: soma 100% entre as trincadas)"
                : "Pelotas trincadas (subconjunto)";
    }

    public static String axisLabel(Normalization n) {
        return n == Normalization.ABSOLUTE ? "pelotas" : n == Normalization.PERCENT_OF_ALL ? "% do total" : "% da própria série";
    }
}
