package br.com.pelotavision;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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

/**
 * Telas e exportações da 0.14.0 renderizadas pela própria Activity (Robolectric, gráficos nativos) a partir
 * de fotos reais do repositório: bancada com trincas (crack_example) e correia com 404 pelotas.
 * Sem escala física informada nas fixtures: tudo em pixels, sem inventar mm.
 * As imagens vão para verification/visuals-0.14.0.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE, qualifiers = "w400dp-h820dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class VisualsDemoTest {
    private static File dir() {
        File d = new File(System.getProperty("pelotavision.validationDir"), "visuals-0.14.0");
        d.mkdirs();
        return d;
    }

    private static void screen(MainActivity a, File path) throws Exception {
        View root = a.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1640, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 800, 1640);
        Bitmap b = Bitmap.createBitmap(800, 1640, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        BeltDemoTest.png(b, path);
        b.recycle();
    }

    private static void dialogShot(AlertDialog d, File path) throws Exception {
        View root = d.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1640, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, 800, Math.max(1, root.getMeasuredHeight()));
        Bitmap b = Bitmap.createBitmap(800, Math.max(1, root.getMeasuredHeight()), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        BeltDemoTest.png(b, path);
        b.recycle();
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

    @Test public void benchPhotoWithCracksShowsOverlaidHistogramMapAndModelsSection() throws Exception {
        File root = new File(System.getProperty("pelotavision.validationDir"));
        Bitmap photo = BitmapFactory.decodeFile(new File(root, "fixtures/crack_example.png").toString());
        assertNotNull(photo);
        JSONObject input = new JSONObject(new String(Files.readAllBytes(new File(root, "fixtures/crack_example_analysis.json").toPath()), StandardCharsets.UTF_8));
        JSONArray cs = input.getJSONArray("circles"), ks = input.getJSONArray("cracks");
        List<PelletDetector.Detection> objects = new ArrayList<>();
        List<CrackAnalysis.Entry> entries = new ArrayList<>();
        for (int i = 0; i < cs.length(); i++) {
            JSONObject c = cs.getJSONObject(i);
            double r = c.getDouble("radius");
            objects.add(new PelletDetector.Detection(c.getDouble("x"), c.getDouble("y"), r, c.getDouble("area"), c.getDouble("roundness"), c.getBoolean("partial"), c.getBoolean("review")));
            entries.add(new CrackAnalysis.Entry(i + 1, CrackAnalysis.Status.valueOf(ks.getJSONObject(i).getString("status")), new float[0], 0.5, CrackModel.ID));
        }
        PelletDetector.Result all = new PelletDetector.Result(objects, photo.getWidth(), photo.getHeight(), objects.size(), 0, false, "fixture_circles");
        CrackAnalysis cracks = new CrackAnalysis(all.width, all.height, 50, 0, entries);
        File dir = dir();
        try (var ctl = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = ctl.get();
            load(a, photo, all, cracks);
            CrackHistogram h = (CrackHistogram) ObjectCalibrationTest.get(a, "crackHistogram");
            assertNotNull(h);
            assertTrue(h.available);
            assertTrue(h.crackedTotal <= cracks.count(CrackAnalysis.Status.POSSIBLE));
            for (int i = 0; i < h.binCount(); i++) assertTrue(h.cracked(i) <= h.all(i));
            screen(a, new File(dir, "Tela_1_segmentacao_e_histograma_comparativo.png"));
            ObjectCalibrationTest.call(a, "setViewMode", new Class[]{int.class}, 2);
            assertEquals(View.VISIBLE, ((View) ObjectCalibrationTest.get(a, "colorMap")).getVisibility());
            assertNotEquals(View.VISIBLE, ((View) ObjectCalibrationTest.get(a, "overlay")).getVisibility());
            screen(a, new File(dir, "Tela_2_mapa_granulometrico.png"));
            ObjectCalibrationTest.call(a, "setViewMode", new Class[]{int.class}, 0);
            assertNotEquals(View.VISIBLE, ((View) ObjectCalibrationTest.get(a, "colorMap")).getVisibility());
            screen(a, new File(dir, "Tela_3_original.png"));
            ObjectCalibrationTest.call(a, "setViewMode", new Class[]{int.class}, 1);

            ObjectCalibrationTest.call(a, "showAnalysisModels", new Class[]{});
            AlertDialog models = ObjectCalibrationTest.dialog();
            assertEquals("Modelos de análise", org.robolectric.Shadows.shadowOf(models).getTitle().toString());
            List<RadioButton> radios = new ArrayList<>();
            collect(models.getWindow().getDecorView(), radios);
            boolean yolo = false, hybrid = false;
            for (RadioButton r : radios) {
                String t = r.getText().toString();
                if (t.contains("YOLO11")) { yolo = true; assertFalse("YOLO sem modelo validado: desabilitado", r.isEnabled()); }
                if (t.startsWith("Híbrida")) { hybrid = true; assertFalse(r.isEnabled()); }
            }
            assertTrue(yolo && hybrid);
            dialogShot(models, new File(dir, "Tela_4_modelos_de_analise.png"));
            models.dismiss();
            ObjectCalibrationTest.call(a, "showMapOptions", new Class[]{});
            AlertDialog map = ObjectCalibrationTest.dialog();
            android.widget.SeekBar opacity = ObjectCalibrationTest.tag(map, "map_opacity");
            assertEquals(70, opacity.getMax());
            assertEquals(25, opacity.getProgress());
            dialogShot(map, new File(dir, "Tela_5_opcoes_do_mapa.png"));
            map.dismiss();
            ObjectCalibrationTest.call(a, "showHistogramOptions", new Class[]{});
            dialogShot(ObjectCalibrationTest.dialog(), new File(dir, "Tela_6_opcoes_do_grafico.png"));
            ObjectCalibrationTest.dialog().dismiss();

            Granulometry.Distribution d = (Granulometry.Distribution) ObjectCalibrationTest.get(a, "distribution");
            ReportVisuals v = ReportVisuals.defaults();
            Bitmap report = PhotoComposer.compose(photo, all, cracks, d, null, 0, false, h, v);
            BeltDemoTest.png(report, new File(dir, "Exportacao_relatorio_histograma_comparativo.png"));
            report.recycle();
            Bitmap mapped = MapComposer.compose(photo, all, d, v, null, false);
            assertNotNull(mapped);
            // fundo da foto preservado fora das pelotas; dentro, cor a 25%
            PelletDetector.Detection first = null;
            for (PelletDetector.Detection p : all.circles) if (GranulometryColorScale.colored(d, new Granulometry.Item(2 * p.radius, p.partial, p.needsReview))) { first = p; break; }
            assertNotNull(first);
            int inside = mapped.getPixel((int) first.x, (int) first.y), orig = photo.getPixel((int) first.x, (int) first.y);
            int expected = GranulometryColorScale.blend(orig & 0xFFFFFF, GranulometryColorScale.relative(d).rgb(d.diameterMm(2 * first.radius)), .25);
            assertEquals(expected, inside & 0xFFFFFF);
            assertEquals(photo.getPixel(2, 2), mapped.getPixel(2, 2));
            BeltDemoTest.png(mapped, new File(dir, "Exportacao_mapa_granulometrico.png"));
            mapped.recycle();
            String json = ReportJson.crackHistogramJson(h, v).toString(1) + "\n" + ReportJson.mapJson(d, v);
            Files.write(new File(dir, "bancada_blocos_json.txt").toPath(), json.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test public void beltPhotoWith404PelletsKeepsMapLegible() throws Exception {
        File root = new File(System.getProperty("pelotavision.validationDir"), "belt-0.9.0");
        Bitmap photo = BitmapFactory.decodeFile(new File(root, "input_correia.jpg").toString());
        assertNotNull(photo);
        JSONObject input = new JSONObject(new String(Files.readAllBytes(new File(root, "analysis.json").toPath()), StandardCharsets.UTF_8));
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
        CrackAnalysis cracks = new CrackAnalysis(all.width, all.height, 50, 0, entries);
        File dir = dir();
        try (var ctl = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = ctl.get();
            load(a, photo, all, cracks);
            ObjectCalibrationTest.set(a, "visuals", ReportVisuals.defaults().withMap(.35, true, false, 0, 0));
            ObjectCalibrationTest.call(a, "refreshVisuals", new Class[]{});
            ObjectCalibrationTest.call(a, "setViewMode", new Class[]{int.class}, 2);
            screen(a, new File(dir, "Tela_7_mapa_correia_404_pelotas_rotulos.png"));
            Granulometry.Distribution d = (Granulometry.Distribution) ObjectCalibrationTest.get(a, "distribution");
            ReportVisuals v = (ReportVisuals) ObjectCalibrationTest.get(a, "visuals");
            Bitmap mapped = MapComposer.compose(photo, all, d, v, null, false);
            BeltDemoTest.png(mapped, new File(dir, "Exportacao_mapa_correia_404_pelotas.png"));
            mapped.recycle();
            CrackHistogram h = (CrackHistogram) ObjectCalibrationTest.get(a, "crackHistogram");
            Files.write(new File(dir, "correia_indicadores.txt").toPath(), (HistogramView.indicators(h) + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void collect(View v, List<RadioButton> out) {
        if (v instanceof RadioButton) out.add((RadioButton) v);
        if (v instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) collect(((android.view.ViewGroup) v).getChildAt(i), out);
    }
}
