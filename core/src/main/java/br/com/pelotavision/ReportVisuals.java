package br.com.pelotavision;

/** Display options of the granulometric map and of the comparative histogram, recorded in exports. */
public final class ReportVisuals {
    public final double mapOpacity;
    public final boolean mapLabels, mapFixed;
    public final double mapFixedMin, mapFixedMax;
    public final boolean showAll, showCracked, showValues;
    public final CrackHistogram.Normalization normalization;

    public ReportVisuals(double mapOpacity, boolean mapLabels, boolean mapFixed, double mapFixedMin, double mapFixedMax,
                         boolean showAll, boolean showCracked, boolean showValues, CrackHistogram.Normalization normalization) {
        this.mapOpacity = GranulometryColorScale.clampOpacity(mapOpacity);
        this.mapLabels = mapLabels;
        this.mapFixed = mapFixed;
        this.mapFixedMin = mapFixedMin;
        this.mapFixedMax = mapFixedMax;
        this.showAll = showAll;
        this.showCracked = showCracked;
        this.showValues = showValues;
        this.normalization = normalization == null ? CrackHistogram.Normalization.PERCENT_OF_ALL : normalization;
    }

    /** Defaults: 25% color over 75% photo, relative scale, both series, percent of all pellets (the original chart). */
    public static ReportVisuals defaults() {
        return new ReportVisuals(GranulometryColorScale.DEFAULT_OPACITY, false, false, 8, 16, true, true, false,
                CrackHistogram.Normalization.PERCENT_OF_ALL);
    }

    /** Scale for this distribution: fixed when chosen and valid, otherwise relative; null if nothing is included. */
    public GranulometryColorScale scale(Granulometry.Distribution d) {
        if (d == null || d.included == 0) return null;
        if (mapFixed && Double.isFinite(mapFixedMin) && Double.isFinite(mapFixedMax) && mapFixedMax > mapFixedMin && mapFixedMin >= 0)
            return GranulometryColorScale.fixed(mapFixedMin, mapFixedMax, d.settings.unit());
        return GranulometryColorScale.relative(d);
    }

    public ReportVisuals withMap(double opacity, boolean labels, boolean fixed, double min, double max) {
        return new ReportVisuals(opacity, labels, fixed, min, max, showAll, showCracked, showValues, normalization);
    }

    public ReportVisuals withHistogram(boolean all, boolean cracked, boolean values, CrackHistogram.Normalization n) {
        return new ReportVisuals(mapOpacity, mapLabels, mapFixed, mapFixedMin, mapFixedMax, all, cracked, values, n);
    }
}
