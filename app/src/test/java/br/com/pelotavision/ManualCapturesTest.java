package br.com.pelotavision;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

/**
 * Capturas do manual 0.15.2: telas e diálogos renderizados pela própria Activity (Robolectric, gráficos nativos),
 * com fotos reais do repositório (bancada crack_example, correia input_correia) e as detecções registradas delas.
 * Percorre o fluxo do usuário: tela inicial → foto → área → segmentação → luz → escala → trincas → mapa → salvar → PDF.
 * Cada captura é independente: uma falha vira linha em capturas.txt e não impede as outras.
 * Grava em verification/manual-0.15.2/android.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE, qualifiers = "w400dp-h820dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ManualCapturesTest {
    interface Step { void run() throws Exception; }

    private final List<String> log = new ArrayList<>();
    private File dir;

    private void shot(String name, Step s) {
        try { s.run(); log.add("ok    " + name); }
        catch (Throwable t) { log.add("FALHA " + name + " :: " + t); }
        finally {
            try { Dialog d = ShadowDialog.getLatestDialog(); if (d != null && d.isShowing()) d.dismiss(); } catch (Throwable ignored) {}
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
    }

    private void screen(MainActivity a, String name) throws Exception {
        View root = a.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1640, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 800, 1640);
        Bitmap b = Bitmap.createBitmap(800, 1640, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        BeltDemoTest.png(b, new File(dir, name + ".png"));
        b.recycle();
    }

    /** Último diálogo aberto (AlertDialog ou Dialog); full = diálogo de tela cheia. */
    private void dialog(String name, boolean full) throws Exception {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("nenhum diálogo aberto", d);
        View root = d.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1640, full ? View.MeasureSpec.EXACTLY : View.MeasureSpec.AT_MOST));
        int h = Math.max(1, full ? 1640 : root.getMeasuredHeight());
        root.layout(0, 0, 800, h);
        Bitmap b = Bitmap.createBitmap(800, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.rgb(16, 26, 35));
        root.draw(new Canvas(b));
        BeltDemoTest.png(b, new File(dir, name + ".png"));
        b.recycle();
        d.dismiss();
    }

    private static void load(MainActivity a, Bitmap photo, PelletDetector.Result all, CrackAnalysis cracks) throws Exception {
        ObjectCalibrationTest.setup(a);
        Bitmap dummy = (Bitmap) ObjectCalibrationTest.get(a, "photoBitmap");
        ObjectCalibrationTest.set(a, "photoBitmap", photo);
        dummy.recycle();
        ObjectCalibrationTest.set(a, "photoAllResult", all);
        ObjectCalibrationTest.set(a, "photoAllCracks", cracks);
        ObjectCalibrationTest.set(a, "originalWidth", photo.getWidth());
        ObjectCalibrationTest.set(a, "originalHeight", photo.getHeight());
        ObjectCalibrationTest.set(a, "scaleSettings", null);
        ((View) ObjectCalibrationTest.get(a, "frozen")).setVisibility(View.VISIBLE);
        ObjectCalibrationTest.call(a, "applyRegion", new Class[]{InterestRegion.class}, (Object) null);
    }

    private static Object[] bench(File root) throws Exception {
        Bitmap photo = BitmapFactory.decodeFile(new File(root, "fixtures/crack_example.png").toString());
        JSONObject input = new JSONObject(new String(Files.readAllBytes(new File(root, "fixtures/crack_example_analysis.json").toPath()), StandardCharsets.UTF_8));
        JSONArray cs = input.getJSONArray("circles"), ks = input.getJSONArray("cracks");
        List<PelletDetector.Detection> objects = new ArrayList<>();
        List<CrackAnalysis.Entry> entries = new ArrayList<>();
        for (int i = 0; i < cs.length(); i++) {
            JSONObject c = cs.getJSONObject(i);
            objects.add(new PelletDetector.Detection(c.getDouble("x"), c.getDouble("y"), c.getDouble("radius"), c.getDouble("area"), c.getDouble("roundness"), c.getBoolean("partial"), c.getBoolean("review")));
            entries.add(new CrackAnalysis.Entry(i + 1, CrackAnalysis.Status.valueOf(ks.getJSONObject(i).getString("status")), new float[0], 0.5, CrackModel.ID));
        }
        PelletDetector.Result all = new PelletDetector.Result(objects, photo.getWidth(), photo.getHeight(), objects.size(), 0, false, "fixture_circles");
        return new Object[]{photo, all, new CrackAnalysis(all.width, all.height, 50, 0, entries)};
    }

    private static Object[] belt(File root) throws Exception {
        File b = new File(root, "belt-0.9.0");
        Bitmap photo = BitmapFactory.decodeFile(new File(b, "input_correia.jpg").toString());
        JSONObject input = new JSONObject(new String(Files.readAllBytes(new File(b, "analysis.json").toPath()), StandardCharsets.UTF_8));
        JSONArray ds = input.getJSONArray("objects");
        List<PelletDetector.Detection> objects = new ArrayList<>();
        List<CrackAnalysis.Entry> entries = new ArrayList<>();
        for (int i = 0; i < ds.length(); i++) {
            JSONObject d = ds.getJSONObject(i);
            PelletDetector.Detection seed = new PelletDetector.Detection(d.getDouble("crack_x"), d.getDouble("crack_y"), d.getDouble("crack_radius"), Math.PI * Math.pow(d.getDouble("crack_radius"), 2), d.getDouble("roundness"), d.getBoolean("partial"), d.getBoolean("crack_review"));
            objects.add(new PelletDetector.Detection(seed, d.getDouble("x"), d.getDouble("y"), d.getDouble("a"), d.getDouble("b"), d.getDouble("angle"), d.getBoolean("fitted"),
                    d.isNull("support") ? Double.NaN : d.getDouble("support"), d.isNull("rms") ? Double.NaN : d.getDouble("rms"), d.getBoolean("partial"), d.getBoolean("review")));
            entries.add(new CrackAnalysis.Entry(i + 1, CrackAnalysis.Status.valueOf(d.getString("crack_status")), new float[0], 0.5, CrackModel.ID));
        }
        PelletDetector.Result all = new PelletDetector.Result(objects, photo.getWidth(), photo.getHeight(), objects.size(), 0, false, input.getString("method"));
        return new Object[]{photo, all, new CrackAnalysis(all.width, all.height, 50, 0, entries)};
    }

    @Test public void walkthroughFromPhotoToPdf() throws Exception {
        File root = new File(System.getProperty("pelotavision.validationDir"));
        dir = new File(root, "manual-0.15.2/android");
        dir.mkdirs();
        Object[] b = bench(root);
        Bitmap photo = (Bitmap) b[0];
        PelletDetector.Result all = (PelletDetector.Result) b[1];
        CrackAnalysis cracks = (CrackAnalysis) b[2];

        try (var ctl = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = ctl.get();
            shot("01_tela_inicial_camera", () -> screen(a, "01_tela_inicial_camera"));
            shot("02_foto_segmentada", () -> { load(a, photo, all, cracks); screen(a, "02_foto_segmentada"); });
            shot("03_mapa_granulometrico", () -> { ObjectCalibrationTest.call(a, "setViewMode", new Class[]{int.class}, 2); screen(a, "03_mapa_granulometrico"); });
            shot("04_somente_original", () -> { ObjectCalibrationTest.call(a, "setViewMode", new Class[]{int.class}, 0); screen(a, "04_somente_original"); ObjectCalibrationTest.call(a, "setViewMode", new Class[]{int.class}, 1); });
            shot("05_ajustes_deteccao", () -> { ObjectCalibrationTest.call(a, "showAdjustments", new Class[]{}); dialog("05_ajustes_deteccao", false); });
            shot("06_modelos_de_analise", () -> { ObjectCalibrationTest.call(a, "showAnalysisModels", new Class[]{}); dialog("06_modelos_de_analise", false); });

            // Pelota de referência (ajustada, inteira) e toque nela: revisão de trinca e calibração.
            int pick = -1;
            for (int i = 0; i < all.circles.size(); i++) { PelletDetector.Detection d = all.circles.get(i); if (!d.partial && !d.needsReview && d.ellipseFitted) { pick = i; break; } }
            if (pick < 0) for (int i = 0; i < all.circles.size(); i++) if (!all.circles.get(i).partial) { pick = i; break; }
            final int chosen = Math.max(0, pick);
            shot("07_toque_na_pelota_revisao", () -> {
                screen(a, "02_foto_segmentada");
                CircleOverlay overlay = (CircleOverlay) ObjectCalibrationTest.get(a, "overlay");
                PelletDetector.Detection d = all.circles.get(chosen);
                float s = Math.min(overlay.getWidth() / (float) all.width, overlay.getHeight() / (float) all.height);
                float vx = (overlay.getWidth() - all.width * s) / 2 + (float) d.x * s, vy = (overlay.getHeight() - all.height * s) / 2 + (float) d.y * s;
                ObjectCalibrationTest.call(a, "reviewCrackAt", new Class[]{float.class, float.class}, vx, vy);
                dialog("07_toque_na_pelota_revisao", false);
            });
            shot("08_sensibilidade_trincas", () -> { ObjectCalibrationTest.call(a, "showCrackSettings", new Class[]{}); dialog("08_sensibilidade_trincas", false); });
            shot("09_escala_mm", () -> { ObjectCalibrationTest.call(a, "showCalibration", new Class[]{}); dialog("09_escala_mm", false); });
            shot("10_calibrar_pela_pelota", () -> { ObjectCalibrationTest.call(a, "showObjectCalibration", new Class[]{int.class}, chosen); dialog("10_calibrar_pela_pelota", false); });
            shot("11_opcoes_do_mapa", () -> { ObjectCalibrationTest.call(a, "showMapOptions", new Class[]{}); dialog("11_opcoes_do_mapa", false); });
            shot("12_opcoes_do_grafico", () -> { ObjectCalibrationTest.call(a, "showHistogramOptions", new Class[]{}); dialog("12_opcoes_do_grafico", false); });

            // Área de interesse: editor, recorte aguardando análise, ROI segmentada, zoom.
            InterestRegion roi = new InterestRegion(InterestRegion.Shape.RECTANGLE, all.width * .12, all.height * .12, all.width * .88, all.height * .88);
            shot("13_editor_de_area", () -> { ObjectCalibrationTest.call(a, "showRegionEditor", new Class[]{}); dialog("13_editor_de_area", true); });
            shot("14_area_aguardando_segmentacao", () -> { ObjectCalibrationTest.call(a, "activateRoi", new Class[]{InterestRegion.class}, roi); screen(a, "14_area_aguardando_segmentacao"); });
            shot("15_area_segmentada", () -> {
                ObjectCalibrationTest.set(a, "photoAnalysisWindow", new AnalysisWindow(all.width, all.height, roi));
                ObjectCalibrationTest.set(a, "roiNeedsAnalysis", false);
                ObjectCalibrationTest.call(a, "applyRegion", new Class[]{InterestRegion.class}, roi);
                screen(a, "15_area_segmentada");
            });
            shot("16_zoom_da_area", () -> { ObjectCalibrationTest.call(a, "showRoiZoom", new Class[]{InterestRegion.class}, roi); dialog("16_zoom_da_area", true); });
            shot("17_como_segmentar", () -> { ObjectCalibrationTest.call(a, "showSegmentationOptions", new Class[]{}); dialog("17_como_segmentar", false); });
            shot("18_luz_imagem_escura", () -> { ObjectCalibrationTest.call(a, "showLowLightDialog", new Class[]{}); dialog("18_luz_imagem_escura", false); });
        }

        // Correia: foto real com 404 pelotas, calibração pela largura e mapa.
        Object[] c = belt(root);
        try (var ctl = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = ctl.get();
            shot("19_correia_segmentada", () -> { load(a, (Bitmap) c[0], (PelletDetector.Result) c[1], (CrackAnalysis) c[2]); screen(a, "19_correia_segmentada"); });
            shot("20_correia_mapa", () -> { ObjectCalibrationTest.call(a, "setViewMode", new Class[]{int.class}, 2); screen(a, "20_correia_mapa"); ObjectCalibrationTest.call(a, "setViewMode", new Class[]{int.class}, 1); });
            shot("21_calibrar_pela_correia", () -> { ObjectCalibrationTest.call(a, "showBeltCalibration", new Class[]{}); dialog("21_calibrar_pela_correia", true); });
        }

        // Salvar: os mesmos arquivos do aparelho (ResultSaver) e as páginas do PDF desenhadas por PdfReport.
        shot("22_relatorio_pdf", () -> {
            // A Activity recicla a foto ao fechar: o relatório parte de uma cópia nova da mesma foto e detecções.
            Object[] fresh = bench(root);
            Bitmap sp = (Bitmap) fresh[0];
            PelletDetector.Result sr = (PelletDetector.Result) fresh[1];
            CrackAnalysis sc = (CrackAnalysis) fresh[2];
            android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
            File out = new File(context.getCacheDir(), "manual_saida");
            out.mkdirs();
            CrackExportTest.FakeMediaStore provider = new CrackExportTest.FakeMediaStore(out);
            android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
            info.authority = "media";
            provider.attachInfo(context, info);
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", provider);
            File source = new File(out, "entrada.png");
            try (java.io.FileOutputStream f = new java.io.FileOutputStream(source)) { assertTrue(sp.compress(Bitmap.CompressFormat.PNG, 100, f)); }
            ResultSaver.SavedResult saved = ResultSaver.save(context, sp, source, "image/png", sr, PelletDetector.Parameters.medium(), sc, null);
            JSONObject json = new JSONObject(new String(Files.readAllBytes(new File(out, saved.base + ".json").toPath()), StandardCharsets.UTF_8));
            json.put("pdf_file", saved.base + "_relatorio.pdf");
            Bitmap composite = BitmapFactory.decodeFile(new File(out, saved.base + "_resultado.png").toString());
            Bitmap map = BitmapFactory.decodeFile(new File(out, saved.base + "_mapa.png").toString());
            BeltDemoTest.png(composite, new File(dir, "23_arquivo_resultado.png"));
            BeltDemoTest.png(map, new File(dir, "24_arquivo_mapa.png"));
            List<Bitmap> owned = new ArrayList<>();
            List<PdfReport.Figure> figures = PdfReport.figures(json, composite, null, map, owned);
            final List<Bitmap> pages = new ArrayList<>();
            int n = PdfReport.draw(new PdfReport.Sink() {
                Bitmap p;
                public Canvas begin(int k) { p = Bitmap.createBitmap(PdfReport.W * 2, PdfReport.H * 2, Bitmap.Config.ARGB_8888); p.eraseColor(Color.WHITE); Canvas cv = new Canvas(p); cv.scale(2, 2); return cv; }
                public void end() { pages.add(p); }
            }, json, figures);
            for (int i = 0; i < pages.size(); i++) BeltDemoTest.png(pages.get(i), new File(dir, "22_relatorio_pdf_pagina_" + (i + 1) + ".png"));
            for (Bitmap p : pages) p.recycle();
            for (Bitmap o : owned) o.recycle();
            assertTrue(n >= 2);
        });

        Files.write(new File(dir, "capturas.txt").toPath(), String.join("\n", log).concat("\n").getBytes(StandardCharsets.UTF_8));
        long ok = log.stream().filter(l -> l.startsWith("ok")).count();
        assertTrue("capturas: " + log, ok >= 15);
    }
}
