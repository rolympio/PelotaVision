package br.com.pelotavision;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exportacao do Windows no formato do Android 0.11.0 (ResultSaver/PhotoComposer):
 * original byte a byte (ou PNG sem perdas do quadro da webcam), PNG de relatorio
 * vertical (1 original/ROI sem marcacoes, 2 marcado, resumo, histograma), CSV por
 * pelota e do histograma (ReportTables) e JSON com as mesmas chaves (ReportJson),
 * acrescido do bloco "source" (camera/arquivo do Windows).
 */
final class DesktopExport {
    private DesktopExport() {}

    static List<File> save(File folder, PhotoSession s, DesktopAnalysis.Config cfg) throws Exception {
        if (!s.canSave()) throw new IllegalStateException("Segmente a area atual antes de salvar.");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Nao foi possivel criar a pasta " + folder);
        String base = "PelotaVision_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
        List<File> written = new ArrayList<>();
        File original;
        String extension;
        if (s.file != null) {
            String name = s.file.getName();
            extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "img";
            original = new File(folder, base + "_original." + extension);
            Files.copy(s.file.toPath(), original.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        } else {
            extension = "png";
            original = new File(folder, base + "_original.png");
            if (!ImageIO.write(s.frame, "png", original)) throw new IOException("Falha ao gravar o quadro original.");
        }
        written.add(original);

        boolean cropRoi = s.region() != null;
        ReportViewport viewport = new ReportViewport(s.width, s.height, s.region(), cropRoi);
        ReportVisuals visuals = cfg.visuals == null ? HistogramView.DEFAULTS : cfg.visuals;
        BufferedImage composite = compose(s, viewport, visuals);
        File marked = new File(folder, base + "_resultado.png");
        if (!ImageIO.write(composite, "png", marked)) throw new IOException("Falha ao gravar a imagem do relatorio.");
        written.add(marked);

        PelletDetector.Result result = s.active();
        CrackAnalysis cracks = s.activeCracks();
        Granulometry.Distribution d = s.distribution();
        File csv = new File(folder, base + ".csv");
        Files.write(csv.toPath(), ReportTables.pelletCsv(result, cracks, d).getBytes(StandardCharsets.UTF_8));
        written.add(csv);
        File grain = new File(folder, base + "_granulometria.csv");
        CrackHistogram ch = s.crackHistogram();
        Files.write(grain.toPath(), (ch != null ? ReportTables.histogramCsv(ch) : ReportTables.histogramCsv(d)).getBytes(StandardCharsets.UTF_8));
        written.add(grain);

        BufferedImage map = mapImage(s, viewport, visuals);
        String mapName = map == null ? null : base + "_mapa.png";
        File json = new File(folder, base + ".json");
        JSONObject report = report(s, cfg, viewport, composite, base, extension);
        report.put("report_schema_version", ReportJson.SCHEMA_VERSION);
        report.put("analysis_models", ReportJson.analysisModelsJson(s.output() == null ? null : s.output().applied, DesktopAnalysis.lastResolution, DesktopAnalysis.networkId()));
        if (ch != null) report.put("crack_histogram", ReportJson.crackHistogramJson(ch, visuals));
        Object mapJson = ReportJson.mapJson(d, visuals);
        if (mapJson instanceof JSONObject) ((JSONObject) mapJson).put("file", mapName == null ? JSONObject.NULL : mapName);
        report.put("granulometric_map", mapJson);
        JSONObject display = new JSONObject();
        display.put("histogram_show_all", visuals.showAll); display.put("histogram_show_cracked", visuals.showCracked); display.put("histogram_values_on_bars", visuals.showValues);
        display.put("histogram_normalization", visuals.normalization.name()); display.put("all_color", "#268CAB alpha 0.55"); display.put("cracked_color", "#FF8C1A alpha 0.65");
        report.put("visualization", display);
        Files.write(json.toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
        written.add(json);
        if (map != null) {
            File mapFile = new File(folder, mapName);
            if (!ImageIO.write(map, "png", mapFile)) throw new IOException("Falha ao gravar o mapa granulometrico.");
            written.add(mapFile);
        }
        return written;
    }

    /** Mapa exportado: pixels mesclados por GranulometryColorScale.paintMap (identico ao Android), recorte do relatorio e legenda. */
    static BufferedImage mapImage(PhotoSession s, ReportViewport v, ReportVisuals visuals) {
        Granulometry.Distribution d = s.distribution();
        GranulometryColorScale scale = visuals.scale(d);
        if (scale == null) return null;
        BufferedImage src = s.analyzedImage;
        int w = src.getWidth(), h = src.getHeight();
        int[] px = src.getRGB(0, 0, w, h, null, 0, w);
        GranulometryColorScale.paintMap(px, w, h, s.active(), d, scale, visuals.mapOpacity);
        BufferedImage painted = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        painted.setRGB(0, 0, w, h, px, 0, w);
        int band = Math.max(60, Math.round(v.width * 70f / 380));
        BufferedImage out = new BufferedImage(v.width, v.height + band, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(new Color(16, 26, 35)); g.fillRect(0, 0, out.getWidth(), out.getHeight());
        Graphics2D p = (Graphics2D) g.create();
        p.clip(new Rectangle2D.Double(0, 0, v.width, v.height));
        p.translate(-v.left * v.scale, -v.top * v.scale);
        p.scale(v.scale, v.scale);
        InterestRegion roi = s.region();
        if (v.cropped) p.clip(new Rectangle2D.Double(roi.left, roi.top, roi.width(), roi.height()));
        if (v.circular) p.clip(new Ellipse2D.Double(roi.left, roi.top, roi.width(), roi.height()));
        p.drawImage(painted, 0, 0, null);
        p.dispose();
        int lw = v.width - 24;
        HistogramView.legend(g, scale, visuals, 12, v.height + 24, Math.max(40, lw), Color.WHITE);
        g.dispose();
        return out;
    }

    /** Mesmo layout de PhotoComposer.Layout (proporcoes a partir de 380 px de largura). */
    static final class Layout {
        final int titleHeight, originalTop, annotatedTop, summaryTop, histogramTop, height;
        Layout(int w, int h) {
            titleHeight = Math.max(1, Math.round(w * 24f / 380)); originalTop = titleHeight; annotatedTop = originalTop + h + titleHeight;
            summaryTop = annotatedTop + h; histogramTop = summaryTop + Math.round(w * 152f / 380); height = histogramTop + Math.round(w * 216f / 380);
        }
    }

    static BufferedImage compose(PhotoSession s, ReportViewport v) { return compose(s, v, HistogramView.DEFAULTS); }

    static BufferedImage compose(PhotoSession s, ReportViewport v, ReportVisuals visuals) {
        PelletDetector.Result result = s.active();
        CrackAnalysis cracks = s.activeCracks();
        Granulometry.Distribution d = s.distribution();
        Layout layout = new Layout(v.width, v.height);
        BufferedImage out = new BufferedImage(v.width, layout.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(new Color(16, 26, 35)); g.fillRect(0, 0, out.getWidth(), out.getHeight());
        float sc = v.width / 380f;
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(8, Math.round(12 * sc))));
        g.drawString(v.cropped ? "1 · ROI AMPLIADA — sem marcacoes" : "1 · ORIGINAL — sem marcacoes", 12 * sc, 16 * sc);
        panel(g, s, v, layout.originalTop, false);
        g.setColor(Color.WHITE);
        g.drawString("2 · PELOTAS E POSSIVEIS TRINCAS", 12 * sc, layout.annotatedTop - 8 * sc);
        panel(g, s, v, layout.annotatedTop, true);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(8, Math.round(11 * sc))));
        g.setColor(Color.WHITE);
        float y = layout.summaryTop;
        if (cracks != null) {
            g.drawString("Possiveis trincas: " + cracks.count(CrackAnalysis.Status.POSSIBLE) + " · sem indicacao: " + cracks.count(CrackAnalysis.Status.NO_INDICATION), 12 * sc, y + 18 * sc);
            g.drawString("Inconclusivas: " + cracks.count(CrackAnalysis.Status.INCONCLUSIVE) + " · pelotas: " + result.circles.size() + " · revisadas: " + cracks.reviewedCount(), 12 * sc, y + 35 * sc);
        } else g.drawString("Trincas nao analisadas nesta imagem", 12 * sc, y + 18 * sc);
        g.setColor(new Color(181, 198, 208));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(7, Math.round(9 * sc))));
        boolean trained = false;
        if (cracks != null) for (CrackAnalysis.Entry e : cracks.entries) if (Double.isFinite(e.modelScore)) { trained = true; break; }
        ShapeStatistics shape = s.shape();
        String[] lines = {
                trained ? "Modelo RF experimental · confirmar por revisao" : "Triagem heuristica experimental · confirmar por revisao",
                String.format(Locale.ROOT, "Esfericidade aparente 2D (b/a): %s · n=%d", Double.isFinite(shape.mean) ? String.format(Locale.ROOT, "%.3f", shape.mean) : "—", shape.included),
                "Excluidas da forma: " + shape.excluded + " · media por numero · nao mede esfericidade 3D",
                "Vermelho: possivel trinca · amarelo: revisao · tracejado: forma sem ajuste",
                s.region() == null ? "Sem indicacao nao garante ausencia de trinca." : "ROI ativa · somente recorte analisado · exige revisao",
                scaleLine1(d), scaleLine2(d, v)};
        float[] offsets = {50, 65, 79, 93, 108, 123, 138};
        for (int i = 0; i < lines.length; i++) g.drawString(lines[i], 12 * sc, y + offsets[i] * sc);
        Graphics2D h = (Graphics2D) g.create();
        h.translate(0, layout.histogramTop);
        HistogramView.paint(h, d, s.crackHistogram(), visuals, v.width, layout.height - layout.histogramTop);
        h.dispose();
        g.dispose();
        return out;
    }

    private static String scaleLine1(Granulometry.Distribution d) {
        ObjectCalibration.Reference r = d.settings.selectedObject;
        if (r != null) return "REF do usuario · " + r.summary();
        if (d.settings.beltReference != null) return "Correia: " + HistogramView.fmt(d.settings.beltReference.realWidthMm) + " mm · bordas confirmadas pelo usuario";
        return d.settings.pixelsOnly() ? "Calibre: correia, referencia ou px/mm." : "Escala informada: " + HistogramView.fmt4(d.settings.originalPixelsPerMm) + " px/mm (original)";
    }

    private static String scaleLine2(Granulometry.Distribution d, ReportViewport v) {
        ObjectCalibration.Reference r = d.settings.selectedObject;
        if (r != null) return "Escala: " + HistogramView.fmt4(d.settings.originalPixelsPerMm) + " px/mm (original) · " + (r.excludeFromStatistics ? "REF fora da amostra" : "REF incluida na amostra");
        if (d.settings.beltReference != null) return "Escala original: " + HistogramView.fmt4(d.settings.beltReference.originalPixelsPerMm) + " px/mm · " + (v.cropped ? "ROI ampliada; medidas preservadas" : "imagem inteira");
        return d.settings.pixelsOnly() ? "Sem referencia fisica; histograma em pixels." : d.settings.estimated() ? "ESTIMATIVA pela media informada; nao e referencia independente." : "Sem referencia de objeto selecionada nesta foto.";
    }

    private static void panel(Graphics2D g0, PhotoSession s, ReportViewport v, int top, boolean marked) {
        Graphics2D g = (Graphics2D) g0.create();
        g.clip(new Rectangle2D.Double(0, top, v.width, v.height));
        g.translate(-v.left * v.scale, top - v.top * v.scale);
        g.scale(v.scale, v.scale);
        g.clip(new Rectangle2D.Double(v.left, v.top, v.right - v.left, v.bottom - v.top));
        InterestRegion roi = s.region();
        if (v.cropped) g.clip(new Rectangle2D.Double(roi.left, roi.top, roi.width(), roi.height()));
        if (v.circular) g.clip(new Ellipse2D.Double(roi.left, roi.top, roi.width(), roi.height()));
        g.drawImage(s.analyzedImage, 0, 0, null);
        if (marked) {
            float w = Math.max(2, s.width / 600f);
            ImageView.drawDetections(g, s.active(), s.activeCracks(), w, true);
            if (roi != null) {
                g.setColor(new Color(0, 200, 255));
                g.setStroke(new BasicStroke(w));
                g.draw(roi.shape == InterestRegion.Shape.CIRCLE ? new Ellipse2D.Double(roi.left, roi.top, roi.width(), roi.height())
                        : new Rectangle2D.Double(roi.left, roi.top, roi.width(), roi.height()));
            }
            Granulometry.Distribution d = s.distribution();
            if (d.settings.selectedObject != null) ImageView.drawReference(g, d.settings.selectedObject, w);
            if (!v.cropped && d.settings.beltReference != null) drawBelt(g, d.settings.beltReference.edges, w);
        }
        g.dispose();
    }

    static void drawBelt(Graphics2D g, BeltCalibration.Edges e, float w) {
        g.setColor(new Color(255, 160, 0));
        g.setStroke(new BasicStroke(w * 1.5f));
        g.draw(new Line2D.Double(e.x(0), e.y(0), e.x(1), e.y(1)));
        g.draw(new Line2D.Double(e.x(2), e.y(2), e.x(3), e.y(3)));
    }

    /** Linhas do resumo na tela. */
    static List<String> summaryLines(PhotoSession s) {
        List<String> l = new ArrayList<>();
        DesktopAnalysis.Output out = s.output();
        Granulometry.Distribution d = s.distribution();
        l.add("Propostas: " + s.active().circles.size() + " · ajuste: " + out.applied.label());
        if (d != null) {
            String u = d.settings.unit();
            l.add(String.format(Locale.ROOT, "Media %s %s · D10 %s · D50 %s · D90 %s", HistogramView.fmt(d.meanMm), u, HistogramView.fmt(d.d10), HistogramView.fmt(d.d50), HistogramView.fmt(d.d90)));
            l.add(String.format(Locale.ROOT, "Histograma: n=%d incluidas · %d excluidas", d.included, d.excluded));
            l.add(d.settings.pixelsOnly() ? "Sem escala fisica: valores em pixels."
                    : String.format(Locale.ROOT, "Escala: %s px/mm (%s)%s", HistogramView.fmt4(d.settings.originalPixelsPerMm), d.settings.calibrationSource, d.settings.estimated() ? " · HIPOTESE" : ""));
        }
        ShapeStatistics shape = s.shape();
        l.add(String.format(Locale.ROOT, "Forma 2D b/a: %s (n=%d, excl. %d)", Double.isFinite(shape.mean) ? String.format(Locale.ROOT, "%.3f", shape.mean) : "—", shape.included, shape.excluded));
        CrackAnalysis c = s.activeCracks();
        if (c != null) l.add(String.format(Locale.ROOT, "Trincas (triagem): possivel %d · inconclusivo %d · sem indicacao %d · revisadas %d",
                c.count(CrackAnalysis.Status.POSSIBLE), c.count(CrackAnalysis.Status.INCONCLUSIVE), c.count(CrackAnalysis.Status.NO_INDICATION), c.reviewedCount()));
        if (out.suggestions != null) {
            l.add(String.format(Locale.ROOT, "Busca: %d de %d opcoes em %.1f s%s. Pontos nao sao probabilidade.", out.suggestions.trials.size(), out.suggestions.planned,
                    out.suggestions.durationMs / 1000.0, out.suggestions.cancelled ? " (cancelada)" : out.suggestions.timeLimited ? " (limite de tempo)" : ""));
            if (out.suggestions.weak()) l.add("Atencao: sugestao fraca para esta imagem; confira as marcacoes.");
        }
        l.add("Distribuicao por numero, nao por massa; foto 2D nao equivale a peneiramento.");
        return l;
    }

    static JSONObject report(PhotoSession s, DesktopAnalysis.Config cfg, ReportViewport viewport, BufferedImage composite, String base, String extension) throws Exception {
        PelletDetector.Result result = s.active();
        CrackAnalysis cracks = s.activeCracks();
        Granulometry.Distribution distribution = s.distribution();
        DesktopAnalysis.Output out = s.output();
        InterestRegion region = s.region();
        boolean physical = !distribution.settings.pixelsOnly();
        JSONObject json = new JSONObject();
        json.put("app", "PelotaVision Windows " + DesktopApp.VERSION + " (nucleo " + DesktopApp.CORE_VERSION + ")");
        json.put("created_at_epoch_ms", System.currentTimeMillis());
        json.put("source", sourceJson(s));
        json.put("region_of_interest", ReportJson.regionJson(region, s.regionExcluded()));
        json.put("detection_suggestion", ReportJson.suggestionJson(s.rankedSuggestions(), out.applied));
        json.put("neural_model", ReportJson.neuralModelJson(out.applied));
        json.put("scene", ReportJson.sceneJson(out.applied));
        json.put("processing_scope", ReportJson.processingScopeJson(out.window));
        json.put("detailed_analysis", JSONObject.NULL);
        json.put("method", result.method);
        json.put("shape_analysis", ReportJson.shapeJson(result));
        json.put("count", result.circles.size());
        json.put("image_width", composite.getWidth()); json.put("image_height", composite.getHeight());
        json.put("analysis_photo_width", result.width); json.put("analysis_photo_height", result.height);
        Layout layout = new Layout(viewport.width, viewport.height);
        json.put("report_viewport", ReportJson.viewportJson(viewport));
        json.put("photo_panel_width", viewport.width); json.put("photo_panel_height", viewport.height);
        json.put("coordinates", "detections in full upright analyzed photo pixels; composite x=(x-crop_left)*render_scale; composite y=annotated_panel_top+(y-crop_top)*render_scale");
        json.put("original_panel_top", layout.originalTop); json.put("annotated_panel_top", layout.annotatedTop); json.put("histogram_top", layout.histogramTop);
        json.put("cracks", ReportJson.crackJson(cracks));
        json.put("original_file", base + "_original." + extension); json.put("annotated_file", base + "_resultado.png");
        json.put("analysis_ms", result.durationMs); json.put("reviewed", cracks != null && cracks.reviewedCount() > 0); json.put("candidate_limit", result.candidateLimit);
        json.put("note", "Experimental 2D ellipse fit: area=pi*a*b, equivalent diameter=2*sqrt(a*b). Unfitted objects retain seed estimates and require review. Aspect ratio b/a is apparent 2D shape, not 3D sphericity. Roundness on fitted objects uses ellipse perimeter, not measured contour perimeter. Distribution by number, not validated sieve granulometry.");
        if (physical) {
            JSONObject scale = new JSONObject();
            scale.put("original_pixels_per_mm", distribution.settings.originalPixelsPerMm);
            scale.put("analyzed_pixels_per_mm", distribution.analyzedPixelsPerMm);
            scale.put("original_upright_width", distribution.originalWidth); scale.put("original_upright_height", distribution.originalHeight);
            scale.put("source", distribution.settings.calibrationSource);
            scale.put("estimated", distribution.settings.estimated());
            scale.put("belt_reference", ReportJson.beltReferenceJson(distribution.settings.beltReference));
            scale.put("selected_object", ReportJson.objectReferenceJson(distribution.settings.selectedObject, region));
            scale.put("assumed_mean_mm", Double.isFinite(distribution.settings.knownMeanMm) ? distribution.settings.knownMeanMm : JSONObject.NULL);
            scale.put("reference_pixels", Double.isFinite(distribution.settings.referencePixels) ? distribution.settings.referencePixels : JSONObject.NULL);
            scale.put("reference_mm", Double.isFinite(distribution.settings.referenceMm) ? distribution.settings.referenceMm : JSONObject.NULL);
            json.put("physical_scale", scale);
        } else json.put("physical_scale", JSONObject.NULL);
        String suffix = physical ? "_mm" : "_px";
        JSONObject histogram = new JSONObject();
        histogram.put("unit", physical ? "mm" : "px"); histogram.put("basis", "number");
        histogram.put("mean" + suffix, finite(distribution.meanMm)); histogram.put("min" + suffix, finite(distribution.minMm)); histogram.put("max" + suffix, finite(distribution.maxMm));
        histogram.put("bin_width" + suffix, distribution.settings.binWidthMm); histogram.put("included", distribution.included); histogram.put("excluded", distribution.excluded);
        histogram.put("requested_bin_width" + suffix, distribution.requestedBinWidthMm);
        histogram.put("bin_width_auto_adjusted", distribution.settings.binWidthMm != distribution.requestedBinWidthMm);
        histogram.put("exclude_partial_edge", distribution.settings.excludePartial); histogram.put("exclude_needs_review", distribution.settings.excludeReview);
        histogram.put("class_convention", "lower inclusive, upper exclusive except inclusive final upper edge; displayed classes span observed diameters; boundaries are multiples of bin width");
        histogram.put("percentile_method", "linear interpolation at (n-1)*q; distribution by number");
        histogram.put("d10" + suffix, finite(distribution.d10)); histogram.put("d50" + suffix, finite(distribution.d50)); histogram.put("d90" + suffix, finite(distribution.d90));
        JSONArray bins = new JSONArray();
        for (int i = 0; i < distribution.binCount(); i++) {
            JSONObject bin = new JSONObject();
            bin.put("lower" + suffix, distribution.lower(i)); bin.put("upper" + suffix, distribution.upper(i));
            bin.put("count", distribution.count(i)); bin.put("percent_by_number", distribution.percent(i));
            bins.put(bin);
        }
        histogram.put("bins", bins);
        json.put("histogram", histogram);
        JSONObject parameters = new JSONObject();
        parameters.put("min_radius_fraction", out.applied.parameters.minRadiusFraction); parameters.put("max_radius_fraction", out.applied.parameters.maxRadiusFraction);
        parameters.put("log_threshold", out.applied.parameters.threshold);
        json.put("parameters", parameters);
        JSONArray detections = new JSONArray();
        int id = 0;
        for (PelletDetector.Detection d : result.circles) {
            id++;
            JSONObject item = new JSONObject();
            item.put("id", id); item.put("x_px", d.x); item.put("y_px", d.y); item.put("radius_px", d.radius);
            item.put("equivalent_diameter_px", d.radius * 2); item.put("area_px2", d.area); item.put("roundness", d.roundness);
            item.put("partial_edge", d.partial); item.put("needs_review", d.needsReview);
            double diameter = !physical ? Double.NaN : distribution.diameterMm(2 * d.radius);
            double area = !physical ? Double.NaN : d.area / (distribution.analyzedPixelsPerMm * distribution.analyzedPixelsPerMm);
            CrackAnalysis.Entry crack = cracks == null ? null : cracks.entries.get(id - 1);
            item.put("crack_status", crack == null ? "not_analyzed" : crack.status.name()); item.put("crack_manually_reviewed", crack != null && crack.manuallyReviewed);
            item.put("ellipse_major_axis_px", d.ellipseFitted ? 2 * d.semiMajor : JSONObject.NULL); item.put("ellipse_minor_axis_px", d.ellipseFitted ? 2 * d.semiMinor : JSONObject.NULL);
            item.put("ellipse_angle_deg", d.ellipseFitted ? d.angleDeg : JSONObject.NULL); item.put("apparent_sphericity_2d", finite(d.aspectRatio()));
            item.put("ellipse_fitted", d.ellipseFitted); item.put("included_in_shape_mean", ShapeStatistics.includes(d));
            item.put("ellipse_fit_support", finite(d.fitSupport)); item.put("ellipse_fit_rms", finite(d.fitRms));
            item.put("area_method", d.ellipseFitted ? "ellipse_pi_a_b" : "seed_area_fallback");
            item.put("crack_crop_x_px", d.crackX); item.put("crack_crop_y_px", d.crackY); item.put("crack_crop_radius_px", d.crackRadius);
            ObjectCalibration.Axes axes = physical && d.ellipseFitted ? ObjectCalibration.originalAxes(d, distribution.originalWidth, distribution.originalHeight, result.width, result.height) : null;
            item.put("ellipse_major_axis_mm", axes != null ? axes.major / distribution.settings.originalPixelsPerMm : JSONObject.NULL);
            item.put("ellipse_minor_axis_mm", axes != null ? axes.minor / distribution.settings.originalPixelsPerMm : JSONObject.NULL);
            item.put("crack_score", crack != null && Double.isFinite(crack.modelScore) ? crack.modelScore : JSONObject.NULL);
            item.put("crack_method", crack == null ? "not_analyzed" : crack.method);
            item.put("equivalent_diameter_mm", finite(diameter)); item.put("area_mm2", finite(area));
            item.put("included_in_histogram", distribution.includes(new Granulometry.Item(2 * d.radius, d.partial, d.needsReview)));
            detections.put(item);
        }
        json.put("detections", detections);
        return json;
    }

    /** Bloco exclusivo do Windows: origem da imagem e o que a webcam/driver informou. */
    static JSONObject sourceJson(PhotoSession s) {
        JSONObject j = new JSONObject();
        j.put("platform", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        j.put("opencv_version", org.opencv.core.Core.VERSION);
        j.put("type", s.file != null ? "file" : "webcam");
        if (s.file != null) j.put("source_file_name", s.file.getName());
        j.put("original_width", s.originalWidth); j.put("original_height", s.originalHeight);
        j.put("analysis_sample_power_of_two", s.sample);
        j.put("analyzed_width", s.width); j.put("analyzed_height", s.height);
        j.put("orientation", s.file != null ? "EXIF applied by OpenCV imdecode" : "webcam frame as delivered by driver");
        if (s.camera != null) {
            JSONObject cam = new JSONObject();
            for (Map.Entry<String, Object> e : s.camera.asMap().entrySet()) {
                Object v = e.getValue();
                cam.put(e.getKey(), v == null || (v instanceof Double && !Double.isFinite((Double) v)) ? JSONObject.NULL : v);
            }
            j.put("camera", cam);
        }
        return j;
    }

    private static Object finite(double v) { return Double.isFinite(v) ? v : JSONObject.NULL; }
}
