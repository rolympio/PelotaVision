package br.com.pelotavision;

import static org.junit.Assert.*;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Componentes Swing do Windows (sem OpenCV nativo): grafico todas × trincadas e mapa granulometrico
 * sobre a foto real crack_example.png, em pixels (a fixture nao tem escala fisica).
 * Grava em build/test-artifacts/visuals-0.14.0/windows_*.png.
 */
public class DesktopVisualsTest {
    @Test public void histogramAndMapComponentsRenderOnRealPhoto() throws Exception {
        File root = new File(System.getProperty("pelotavision.validationDir", "../verification"));
        BufferedImage photo = ImageIO.read(new File(root, "fixtures/crack_example.png"));
        assertNotNull(photo);
        JSONObject fx = new JSONObject(new String(Files.readAllBytes(new File(root, "fixtures/crack_example_analysis.json").toPath()), StandardCharsets.UTF_8));
        JSONArray cs = fx.getJSONArray("circles"), ks = fx.getJSONArray("cracks");
        List<PelletDetector.Detection> ds = new ArrayList<>();
        List<CrackAnalysis.Entry> es = new ArrayList<>();
        for (int i = 0; i < cs.length(); i++) {
            JSONObject c = cs.getJSONObject(i);
            ds.add(new PelletDetector.Detection(c.getDouble("x"), c.getDouble("y"), c.getDouble("radius"), c.getDouble("area"), c.getDouble("roundness"), c.getBoolean("partial"), c.getBoolean("review")));
            es.add(new CrackAnalysis.Entry(i + 1, CrackAnalysis.Status.valueOf(ks.getJSONObject(i).getString("status")), new float[0], .5, CrackModel.ID));
        }
        PelletDetector.Result r = new PelletDetector.Result(ds, photo.getWidth(), photo.getHeight(), ds.size(), 0, false, "fixture");
        CrackAnalysis cracks = new CrackAnalysis(r.width, r.height, 50, 0, es);
        Granulometry.Distribution d = ReportTables.pixelHistogram(r);
        CrackHistogram h = CrackHistogram.compute(d, PhotoSession.items(r), cracks);
        assertTrue(h.available);
        File dir = TestPaths.artifacts("visuals-0.14.0");
        dir.mkdirs();
        for (CrackHistogram.Normalization n : CrackHistogram.Normalization.values()) {
            BufferedImage img = new BufferedImage(520, 260, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            HistogramView.paint(g, d, h, HistogramView.DEFAULTS.withHistogram(true, true, true, n), 520, 260);
            g.dispose();
            ImageIO.write(img, "png", new File(dir, "windows_histograma_" + n.name().toLowerCase() + ".png"));
        }
        ImageView view = new ImageView();
        view.setSize(1100, 640);
        view.setImage(photo, false);
        view.setOverlay(r, cracks);
        view.setMap(d, ReportVisuals.defaults().withMap(.25, true, false, 0, 0));
        view.setViewMode(2);
        BufferedImage screen = new BufferedImage(1100, 640, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = screen.createGraphics();
        view.paint(g);
        g.dispose();
        ImageIO.write(screen, "png", new File(dir, "windows_mapa_granulometrico.png"));
        // fundo da foto (sem pelota) igual ao original na tela; centro de uma pelota colorido
        int[] px = photo.getRGB(0, 0, photo.getWidth(), photo.getHeight(), null, 0, photo.getWidth());
        int[] orig = px.clone();
        int n = GranulometryColorScale.paintMap(px, photo.getWidth(), photo.getHeight(), r, d, GranulometryColorScale.relative(d), .25);
        assertEquals(d.included, n);
        assertEquals(orig[0], px[0]);
    }
}
