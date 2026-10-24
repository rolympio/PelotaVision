package br.com.pelotavision;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exportacao do Windows. CSV por pelota e CSV do histograma sao gerados pelo nucleo
 * comum (ReportTables), no mesmo formato do Android. A imagem original e preservada
 * byte a byte (arquivo aberto) ou em PNG sem perdas (quadro da webcam).
 */
final class DesktopExport {
    private DesktopExport() {}

    static final class Source {
        final File file;            // arquivo aberto (copiado byte a byte), ou null
        final BufferedImage frame;   // quadro da webcam (salvo em PNG), ou null
        final CameraSource.Info camera;
        Source(File file, BufferedImage frame, CameraSource.Info camera) { this.file = file; this.frame = frame; this.camera = camera; }
    }

    static List<File> save(File folder, Source source, BufferedImage analyzedImage, DesktopAnalysis.Output out, DesktopAnalysis.Config cfg) throws IOException {
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Nao foi possivel criar a pasta " + folder);
        String base = "PelotaVision_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
        List<File> written = new ArrayList<>();
        File original;
        if (source.file != null) {
            String name = source.file.getName();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "img";
            original = new File(folder, base + "_original." + ext);
            Files.copy(source.file.toPath(), original.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        } else {
            original = new File(folder, base + "_original.png");
            if (!ImageIO.write(source.frame, "png", original)) throw new IOException("Falha ao gravar o quadro original.");
        }
        written.add(original);

        File marked = new File(folder, base + "_resultado.png");
        if (!ImageIO.write(compose(analyzedImage, out), "png", marked)) throw new IOException("Falha ao gravar a imagem marcada.");
        written.add(marked);

        File csv = new File(folder, base + ".csv");
        Files.write(csv.toPath(), ReportTables.pelletCsv(out.result, out.cracks, out.distribution).getBytes(StandardCharsets.UTF_8));
        written.add(csv);
        File grain = new File(folder, base + "_granulometria.csv");
        Files.write(grain.toPath(), ReportTables.histogramCsv(out.distribution).getBytes(StandardCharsets.UTF_8));
        written.add(grain);

        File json = new File(folder, base + ".json");
        Files.write(json.toPath(), Json.write(report(source, original, out, cfg)).getBytes(StandardCharsets.UTF_8));
        written.add(json);
        return written;
    }

    static BufferedImage compose(BufferedImage image, DesktopAnalysis.Output out) {
        int panel = 420;
        int h = Math.max(image.getHeight(), 520);
        BufferedImage composite = new BufferedImage(image.getWidth() + panel, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = composite.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE); g.fillRect(0, 0, composite.getWidth(), h);
        g.drawImage(image, 0, 0, null);
        Graphics2D o = (Graphics2D) g.create();
        o.scale(image.getWidth() / (double) out.result.width, image.getHeight() / (double) out.result.height);
        ImageView.drawDetections(o, out.result, out.cracks, (float) Math.max(1.5, out.result.width / 900.0), true);
        o.dispose();
        Graphics2D p = (Graphics2D) g.create();
        p.translate(image.getWidth(), 0);
        p.setColor(Color.BLACK);
        p.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        int y = 22;
        for (String line : summaryLines(out)) { p.drawString(line, 12, y); y += 18; }
        Graphics2D hg = (Graphics2D) p.create();
        hg.translate(10, y + 6);
        HistogramView.paint(hg, out.distribution, panel - 20, 230);
        hg.dispose();
        p.dispose();
        g.dispose();
        return composite;
    }

    static List<String> summaryLines(DesktopAnalysis.Output out) {
        List<String> l = new ArrayList<>();
        Granulometry.Distribution d = out.distribution;
        String u = d.settings.unit();
        l.add(DesktopApp.NAME + " " + DesktopApp.VERSION + " (nucleo " + DesktopApp.CORE_VERSION + ")");
        l.add("Propostas: " + out.result.circles.size() + " · ajuste: " + out.applied.label());
        l.add(String.format(Locale.ROOT, "Media %s %s · D10 %s · D50 %s · D90 %s", HistogramView.fmt(d.meanMm), u, HistogramView.fmt(d.d10), HistogramView.fmt(d.d50), HistogramView.fmt(d.d90)));
        l.add(d.settings.pixelsOnly() ? "Sem escala fisica: valores em pixels." : String.format(Locale.ROOT, "Escala: %.4f px/mm (%s)", d.settings.originalPixelsPerMm, d.settings.calibrationSource));
        l.add(String.format(Locale.ROOT, "Forma 2D b/a: %s (n=%d)", Double.isFinite(out.shape.mean) ? String.format(Locale.ROOT, "%.3f", out.shape.mean) : "—", out.shape.included));
        if (out.cracks != null) l.add(String.format(Locale.ROOT, "Trincas (triagem): possivel %d · inconclusivo %d · sem indicacao %d",
                out.cracks.count(CrackAnalysis.Status.POSSIBLE), out.cracks.count(CrackAnalysis.Status.INCONCLUSIVE), out.cracks.count(CrackAnalysis.Status.NO_INDICATION)));
        l.add("Distribuicao por numero, nao por massa; foto 2D nao equivale a peneiramento.");
        return l;
    }

    private static Map<String, Object> report(Source source, File original, DesktopAnalysis.Output out, DesktopAnalysis.Config cfg) {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("app", DesktopApp.NAME + " " + DesktopApp.VERSION);
        j.put("core_version", DesktopApp.CORE_VERSION);
        j.put("platform", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        j.put("opencv_version", org.opencv.core.Core.VERSION);
        j.put("created_at_epoch_ms", System.currentTimeMillis());
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("type", source.file != null ? "file" : "webcam");
        src.put("original_saved_as", original.getName());
        if (source.file != null) src.put("source_file_name", source.file.getName());
        if (source.camera != null) src.put("camera", source.camera.asMap());
        src.put("original_width", out.originalWidth); src.put("original_height", out.originalHeight);
        src.put("analysis_sample_power_of_two", out.sample);
        j.put("source", src);
        j.put("method", out.result.method);
        j.put("count", out.result.circles.size());
        j.put("analysis_photo_width", out.result.width); j.put("analysis_photo_height", out.result.height);
        j.put("duration_ms", out.durationMs);
        Map<String, Object> opt = new LinkedHashMap<>();
        opt.put("applied_id", out.applied.id); opt.put("applied_label", out.applied.label());
        opt.put("auto_suggest", cfg.autoSuggest); opt.put("minimum_expected_mm", cfg.minimumExpectedMm);
        if (out.suggestions != null) {
            DetectionTuner.Trial best = out.suggestions.best();
            opt.put("search_method", DetectionTuner.METHOD); opt.put("planned", out.suggestions.planned); opt.put("evaluated", out.suggestions.trials.size());
            opt.put("cancelled", out.suggestions.cancelled); opt.put("time_limited", out.suggestions.timeLimited); opt.put("search_duration_ms", out.suggestions.durationMs);
            opt.put("best_score_points_not_probability", best == null ? null : best.score);
        }
        j.put("detection_setting", opt);
        Map<String, Object> cfgJ = new LinkedHashMap<>();
        cfgJ.put("min_radius_fraction", out.applied.parameters.minRadiusFraction); cfgJ.put("max_radius_fraction", out.applied.parameters.maxRadiusFraction); cfgJ.put("log_threshold", out.applied.parameters.threshold);
        j.put("parameters", cfgJ);
        Granulometry.Distribution d = out.distribution;
        boolean physical = !d.settings.pixelsOnly();
        String sfx = physical ? "_mm" : "_px";
        if (physical) {
            Map<String, Object> scale = new LinkedHashMap<>();
            scale.put("source", d.settings.calibrationSource); scale.put("original_pixels_per_mm", d.settings.originalPixelsPerMm);
            scale.put("analyzed_pixels_per_mm", d.analyzedPixelsPerMm);
            scale.put("reference_pixels", d.settings.referencePixels); scale.put("reference_mm", d.settings.referenceMm);
            scale.put("note", "Referencia no mesmo plano das pelotas; sem correcao de perspectiva ou distorcao de lente.");
            j.put("physical_scale", scale);
        }
        Map<String, Object> hist = new LinkedHashMap<>();
        hist.put("unit", physical ? "mm" : "px"); hist.put("basis", "number");
        hist.put("mean" + sfx, d.meanMm); hist.put("min" + sfx, d.minMm); hist.put("max" + sfx, d.maxMm);
        hist.put("d10" + sfx, d.d10); hist.put("d50" + sfx, d.d50); hist.put("d90" + sfx, d.d90);
        hist.put("bin_width" + sfx, d.settings.binWidthMm); hist.put("requested_bin_width" + sfx, d.requestedBinWidthMm);
        hist.put("included", d.included); hist.put("excluded", d.excluded);
        hist.put("exclude_partial_edge", d.settings.excludePartial); hist.put("exclude_needs_review", d.settings.excludeReview);
        List<Object> bins = new ArrayList<>();
        for (int i = 0; i < d.binCount(); i++) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("lower" + sfx, d.lower(i)); b.put("upper" + sfx, d.upper(i)); b.put("count", d.count(i)); b.put("percent_by_number", d.percent(i));
            bins.add(b);
        }
        hist.put("bins", bins);
        j.put("histogram", hist);
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("apparent_sphericity_2d_mean_b_over_a", out.shape.mean); shape.put("included", out.shape.included); shape.put("excluded", out.shape.excluded);
        shape.put("note", "Forma aparente 2D; nao mede esfericidade 3D.");
        j.put("shape_analysis", shape);
        if (out.cracks != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("sensitivity", out.cracks.sensitivity); c.put("possible", out.cracks.count(CrackAnalysis.Status.POSSIBLE));
            c.put("inconclusive", out.cracks.count(CrackAnalysis.Status.INCONCLUSIVE)); c.put("no_indication", out.cracks.count(CrackAnalysis.Status.NO_INDICATION));
            c.put("model", out.crackModelNote);
            c.put("note", "Triagem experimental. Ausencia de indicacao nao comprova ausencia de defeito.");
            j.put("crack_screening", c);
        }
        List<Object> dets = new ArrayList<>();
        int id = 0;
        for (PelletDetector.Detection det : out.result.circles) {
            id++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("x_px", det.x); m.put("y_px", det.y); m.put("radius_px", det.radius);
            m.put("equivalent_diameter_px", 2 * det.radius); m.put("area_px2", det.area); m.put("roundness", det.roundness);
            m.put("partial_edge", det.partial); m.put("needs_review", det.needsReview); m.put("ellipse_fitted", det.ellipseFitted);
            m.put("ellipse_major_axis_px", det.ellipseFitted ? 2 * det.semiMajor : null); m.put("ellipse_minor_axis_px", det.ellipseFitted ? 2 * det.semiMinor : null);
            m.put("ellipse_angle_deg", det.ellipseFitted ? det.angleDeg : null);
            m.put("equivalent_diameter" + sfx, physical ? d.diameterMm(2 * det.radius) : 2 * det.radius);
            CrackAnalysis.Entry e = out.cracks == null ? null : out.cracks.entries.get(id - 1);
            m.put("crack_status", e == null ? "not_analyzed" : e.status.name());
            m.put("crack_score", e == null ? null : e.modelScore);
            dets.add(m);
        }
        j.put("detections", dets);
        j.put("coordinates", "pixels da imagem analisada (orientada); multiplique por analysis_sample_power_of_two para o original");
        return j;
    }

    /** JSON minimo (sem dependencias). NaN/infinito viram null. */
    static final class Json {
        static String write(Object v) { StringBuilder s = new StringBuilder(); write(s, v, 0); s.append('\n'); return s.toString(); }
        @SuppressWarnings("unchecked")
        private static void write(StringBuilder s, Object v, int ind) {
            if (v == null) s.append("null");
            else if (v instanceof String) str(s, (String) v);
            else if (v instanceof Boolean) s.append(v);
            else if (v instanceof Double || v instanceof Float) { double x = ((Number) v).doubleValue(); s.append(Double.isFinite(x) ? Double.toString(x) : "null"); }
            else if (v instanceof Number) s.append(v);
            else if (v instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) v;
                s.append("{");
                boolean first = true;
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    s.append(first ? "\n" : ",\n"); first = false;
                    pad(s, ind + 1); str(s, e.getKey()); s.append(": "); write(s, e.getValue(), ind + 1);
                }
                if (!first) { s.append('\n'); pad(s, ind); }
                s.append("}");
            } else if (v instanceof Collection) {
                s.append("[");
                boolean first = true;
                for (Object o : (Collection<Object>) v) {
                    s.append(first ? "\n" : ",\n"); first = false;
                    pad(s, ind + 1); write(s, o, ind + 1);
                }
                if (!first) { s.append('\n'); pad(s, ind); }
                s.append("]");
            } else str(s, v.toString());
        }
        private static void pad(StringBuilder s, int n) { for (int i = 0; i < n; i++) s.append("  "); }
        private static void str(StringBuilder s, String v) {
            s.append('"');
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                switch (c) {
                    case '"': s.append("\\\""); break;
                    case '\\': s.append("\\\\"); break;
                    case '\n': s.append("\\n"); break;
                    case '\r': s.append("\\r"); break;
                    case '\t': s.append("\\t"); break;
                    default: if (c < 0x20) s.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else s.append(c);
                }
            }
            s.append('"');
        }
    }
}
