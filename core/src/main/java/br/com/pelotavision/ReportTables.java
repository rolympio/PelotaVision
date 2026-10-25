package br.com.pelotavision;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tabelas de saida comuns a Android e Windows (CSV por pelota com 32 colunas e CSV
 * do histograma). Extraido de ResultSaver 0.11.0 sem alterar formato, ordem de
 * colunas, separador (;), BOM UTF-8 ou numero de casas decimais.
 */
public final class ReportTables {
    private ReportTables() {}

    public static final String PELLET_HEADER = "id;x_px;y_px;radius_px;equivalent_diameter_px;area_px2;roundness;partial_edge;needs_review;equivalent_diameter_mm;area_mm2;included_in_histogram;crack_status;crack_manually_reviewed;crack_score;crack_method;ellipse_major_axis_px;ellipse_minor_axis_px;ellipse_angle_deg;apparent_sphericity_2d;ellipse_fitted;included_in_shape_mean;ellipse_major_axis_mm;ellipse_minor_axis_mm;ellipse_fit_support;ellipse_fit_rms;calibration_reference_id;calibration_measure;calibration_real_mm;calibration_reference_excluded;calibration_original_px_per_mm;calibration_full_image_id";

    /** Histograma em pixels (classe de 10 px) usado quando nao ha escala fisica. */
    public static Granulometry.Distribution pixelHistogram(PelletDetector.Result result) {
        List<Granulometry.Item> items = new ArrayList<>();
        for (PelletDetector.Detection d : result.circles) items.add(new Granulometry.Item(2 * d.radius, d.partial, d.needsReview));
        return Granulometry.compute(items, Granulometry.Settings.pixels(10), result.width, result.height, result.width, result.height);
    }

    public static String histogramCsv(Granulometry.Distribution distribution) {
        String suffix = distribution.settings.pixelsOnly() ? "_px" : "_mm";
        StringBuilder grainCsv = new StringBuilder("\uFEFFlower" + suffix + ";upper" + suffix + ";count;percent_by_number\n");
        for (int i = 0; i < distribution.binCount(); i++)
            grainCsv.append(String.format(Locale.ROOT, "%.6f;%.6f;%d;%.6f\n", distribution.lower(i), distribution.upper(i), distribution.count(i), distribution.percent(i)));
        return grainCsv.toString();
    }

    /** Histogram CSV with the cracked series appended as the last columns (older readers keep working). */
    public static String histogramCsv(CrackHistogram h) {
        Granulometry.Distribution distribution = h.distribution;
        String suffix = distribution.settings.pixelsOnly() ? "_px" : "_mm";
        StringBuilder csv = new StringBuilder("\uFEFFlower" + suffix + ";upper" + suffix + ";count;percent_by_number;count_cracked;percent_cracked_of_all\n");
        for (int i = 0; i < distribution.binCount(); i++)
            csv.append(String.format(Locale.ROOT, "%.6f;%.6f;%d;%.6f;%s;%s\n", distribution.lower(i), distribution.upper(i), distribution.count(i), distribution.percent(i),
                    h.available ? Integer.toString(h.cracked(i)) : "", h.available ? String.format(Locale.ROOT, "%.6f", h.crackedValue(i, CrackHistogram.Normalization.PERCENT_OF_ALL)) : ""));
        return csv.toString();
    }

    public static String pelletCsv(PelletDetector.Result result, CrackAnalysis cracks, Granulometry.Distribution distribution) {
        if (distribution == null) distribution = pixelHistogram(result);
        boolean physical = !distribution.settings.pixelsOnly();
        StringBuilder csv = new StringBuilder("\uFEFF" + PELLET_HEADER + "\n");
        int id = 0;
        for (PelletDetector.Detection d : result.circles) {
            id++;
            double diameter = !physical ? Double.NaN : distribution.diameterMm(2 * d.radius);
            double area = !physical ? Double.NaN : d.area / (distribution.analyzedPixelsPerMm * distribution.analyzedPixelsPerMm);
            CrackAnalysis.Entry crack = cracks == null ? null : cracks.entries.get(id - 1);
            ObjectCalibration.Axes originalAxes = physical && d.ellipseFitted ? ObjectCalibration.originalAxes(d, distribution.originalWidth, distribution.originalHeight, result.width, result.height) : null;
            boolean included = distribution.includes(new Granulometry.Item(2 * d.radius, d.partial, d.needsReview));
            csv.append(String.format(Locale.ROOT, "%d;%.3f;%.3f;%.3f;%.3f;%.3f;%.4f;%s;%s;%s;%s;%s;%s;%s;%s;%s", id, d.x, d.y, d.radius, 2 * d.radius, d.area, d.roundness, d.partial, d.needsReview,
                    Double.isFinite(diameter) ? String.format(Locale.ROOT, "%.6f", diameter) : "", Double.isFinite(area) ? String.format(Locale.ROOT, "%.6f", area) : "", included, crack == null ? "not_analyzed" : crack.status.name(), crack != null && crack.manuallyReviewed, crack != null && Double.isFinite(crack.modelScore) ? String.format(Locale.ROOT, "%.6f", crack.modelScore) : "", crack == null ? "not_analyzed" : crack.method));
            csv.append(String.format(Locale.ROOT, ";%s;%s;%s;%s;%s;%s;%s;%s;%s;%s", number(d.ellipseFitted ? 2 * d.semiMajor : Double.NaN), number(d.ellipseFitted ? 2 * d.semiMinor : Double.NaN), number(d.ellipseFitted ? d.angleDeg : Double.NaN), number(d.aspectRatio()), d.ellipseFitted, ShapeStatistics.includes(d), number(originalAxes != null ? originalAxes.major / distribution.settings.originalPixelsPerMm : Double.NaN), number(originalAxes != null ? originalAxes.minor / distribution.settings.originalPixelsPerMm : Double.NaN), number(d.fitSupport), number(d.fitRms)));
            ObjectCalibration.Reference ref = distribution.settings.selectedObject;
            csv.append(String.format(Locale.ROOT, ";%s;%s;%s;%s;%s;%s\n", ref == null ? "" : ref.id, ref == null ? "" : ref.measure.name(), number(ref == null ? Double.NaN : ref.realMm), ref == null ? "" : Boolean.toString(ref.excludeFromStatistics), number(physical ? distribution.settings.originalPixelsPerMm : Double.NaN), ref == null ? "" : Integer.toString(ref.fullImageId)));
        }
        return csv.toString();
    }

    public static String number(double v) { return Double.isFinite(v) ? String.format(Locale.ROOT, "%.6f", v) : ""; }
}
