package br.com.pelotavision;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Encanamento do refino SAM 2 sem modelo real: um decoder sintético devolve, para cada prompt, os logits de um
 * disco com {@code factor} × o raio da detecção. O refino precisa recuperar centro e raio desse disco em px da
 * foto, em qualquer bloco e em qualquer escala. (O teste com o modelo real fica em Sam2ModelTest.)
 */
public class Sam2RefinerTest {
    static JSONObject meta() throws Exception {
        JSONObject j = new JSONObject();
        j.put("id", "teste"); j.put("input_size", 1024); j.put("batch", 16); j.put("mask_size", 256);
        j.put("mean", new JSONArray(new double[]{.485, .456, .406})); j.put("std", new JSONArray(new double[]{.229, .224, .225}));
        return j;
    }

    /** Encoder sintético: só confere a forma da entrada. */
    static final class FakeEncoder implements Sam2Refiner.Engine {
        int calls;
        public Map<String, float[]> run(Map<String, float[]> in, Map<String, long[]> shapes, String[] outs) {
            calls++;
            assertEquals(3 * 1024 * 1024, in.get("image").length);
            Map<String, float[]> m = new HashMap<>();
            m.put("image_embed", new float[256 * 64 * 64]); m.put("high_res_feats_0", new float[32 * 256 * 256]); m.put("high_res_feats_1", new float[64 * 128 * 128]);
            return m;
        }
        public String description() { return "falso"; }
        public void close() {}
    }

    /** Decoder sintético: disco de raio factor·(meia caixa / 1,15), centrado no ponto positivo. */
    static final class DiskDecoder implements Sam2Refiner.Engine {
        final double factor; final float iou;
        DiskDecoder(double factor, float iou) { this.factor = factor; this.iou = iou; }
        public Map<String, float[]> run(Map<String, float[]> in, Map<String, long[]> shapes, String[] outs) {
            float[] c = in.get("point_coords"), l = in.get("point_labels");
            int b = (int) shapes.get("point_coords")[0];
            float[] logits = new float[b * 256 * 256], q = new float[b];
            for (int k = 0; k < b; k++) {
                assertEquals(2f, l[k * 3], 0); assertEquals(3f, l[k * 3 + 1], 0); assertEquals(1f, l[k * 3 + 2], 0);
                double cx = c[k * 6 + 4], cy = c[k * 6 + 5], r = (c[k * 6 + 2] - c[k * 6]) / 2 / Sam2Refiner.BOX * factor;
                for (int gy = 0; gy < 256; gy++) for (int gx = 0; gx < 256; gx++) {
                    double u = (gx + .5) * 4 - .5, v = (gy + .5) * 4 - .5;
                    logits[k * 65536 + gy * 256 + gx] = (float) (r - Math.hypot(u - cx, v - cy));
                }
                q[k] = iou;
            }
            Map<String, float[]> m = new HashMap<>();
            m.put("low_res_masks", logits); m.put("iou_predictions", q);
            return m;
        }
        public String description() { return "falso"; }
        public void close() {}
    }

    static PelletDetector.Result grid(int w, int h, double radius, double step) {
        List<PelletDetector.Detection> d = new ArrayList<>();
        for (double y = step / 2; y < h; y += step) for (double x = step / 2; x < w; x += step)
            d.add(new PelletDetector.Detection(x + .3, y - .2, radius, Math.PI * radius * radius, .9, false, false));
        return new PelletDetector.Result(d, w, h, d.size(), 1, false, "teste");
    }

    static PelletDetector.Result run(Sam2Refiner r, PelletDetector.Result seeds, int w, int h) {
        double radius = seeds.circles.get(0).radius, s = Sam2Refiner.chooseScale(radius, w, h);
        int sw = (int) Math.round(w * s), sh = (int) Math.round(h * s);
        return r.refinePixels(new byte[sw * sh], sw, sh, sw / (double) w, sh / (double) h, s, 256, seeds, () -> false, System.nanoTime());
    }

    @Test public void recoversDiskRadiusAndCenterAcrossTilesAndScales() throws Exception {
        for (double radius : new double[]{6, 18, 45, 120}) {
            int w = (int) (radius * 60), h = (int) (radius * 40);
            FakeEncoder enc = new FakeEncoder();
            Sam2Refiner r = Sam2Refiner.forTest(enc, new DiskDecoder(.9, .95f), meta());
            PelletDetector.Result seeds = grid(w, h, radius, radius * 3.1);
            PelletDetector.Result out = run(r, seeds, w, h);
            assertEquals(seeds.circles.size(), out.circles.size());
            Sam2Refiner.Report rep = Sam2Refiner.lastReport();
            assertEquals("raio " + radius + ": " + rep.summary(), seeds.circles.size(), rep.refined);
            assertTrue(rep.tiles >= 1 && enc.calls == rep.tiles);
            for (int i = 0; i < out.circles.size(); i++) {
                PelletDetector.Detection a = seeds.circles.get(i), b = out.circles.get(i);
                assertEquals("raio " + radius, .9 * radius, b.radius, .03 * radius + .35);
                assertEquals(a.x, b.x, .03 * radius + .35);
                assertEquals(a.y, b.y, .03 * radius + .35);
                assertTrue(b.ellipseFitted);
            }
            assertTrue(out.method.contains("+sam2_teste"));
        }
    }

    /** Pelotas claras (200) em fundo 60 com halo de 4 px que cai de 150 a 60; a máscara pega o halo. */
    static byte[] haloImage(int w, int h, PelletDetector.Result seeds, double trueR, double halo) {
        byte[] pix = new byte[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double best = 1e9;
            for (PelletDetector.Detection d : seeds.circles) best = Math.min(best, Math.hypot(x - d.x, y - d.y) - trueR);
            double v = best <= 0 ? 200 : best < halo ? 150 - 90 * best / halo : 60;
            pix[y * w + x] = (byte) Math.round(v);
        }
        return pix;
    }

    @Test public void edgeSnapRemovesHaloIncludedByMask() throws Exception {
        int w = 1100, h = 800; double trueR = 40, halo = 4;
        PelletDetector.Result seeds = grid(w, h, trueR, 130);
        byte[] pix = haloImage(w, h, seeds, trueR, halo);
        // escala 1 (raio = alvo): a máscara sintética cobre o halo (raio 43,5)
        Sam2Refiner r = Sam2Refiner.forTest(new FakeEncoder(), new DiskDecoder(43.5 / trueR, .95f), meta());
        PelletDetector.Result out = r.refinePixels(pix, w, h, 1, 1, 1, 256, seeds, () -> false, System.nanoTime());
        Sam2Refiner.Report rep = Sam2Refiner.lastReport();
        assertEquals(rep.summary(), seeds.circles.size(), rep.edgeSnapped);
        for (PelletDetector.Detection d : out.circles) assertEquals(trueR + .5, d.radius, 1.0);  // borda no degrau 200→150
        Sam2Refiner.edgeSnap = false;
        try {
            PelletDetector.Result raw = r.refinePixels(pix, w, h, 1, 1, 1, 256, seeds, () -> false, System.nanoTime());
            for (PelletDetector.Detection d : raw.circles) assertEquals(43.5, d.radius, 1.0);
        } finally { Sam2Refiner.edgeSnap = true; }
    }

    @Test public void keepsDetectorResultWhenMaskIsRejected() throws Exception {
        int w = 1200, h = 900;
        PelletDetector.Result seeds = grid(w, h, 30, 95);
        // IoU baixo: todas mantidas
        PelletDetector.Result low = run(Sam2Refiner.forTest(new FakeEncoder(), new DiskDecoder(.9, .3f), meta()), seeds, w, h);
        assertEquals(0, Sam2Refiner.lastReport().refined);
        for (int i = 0; i < low.circles.size(); i++) assertSame(seeds.circles.get(i), low.circles.get(i));
        // máscara 3x maior que a pelota (vaza da janela): mantidas
        run(Sam2Refiner.forTest(new FakeEncoder(), new DiskDecoder(3, .95f), meta()), seeds, w, h);
        assertEquals(0, Sam2Refiner.lastReport().refined);
        // IoU entre 0,5 e 0,8: aceitas, mas para revisão
        PelletDetector.Result mid = run(Sam2Refiner.forTest(new FakeEncoder(), new DiskDecoder(.9, .6f), meta()), seeds, w, h);
        for (PelletDetector.Detection d : mid.circles) assertTrue(d.needsReview);
    }

    @Test public void tilesCoverImageAndOwnersArePartition() {
        int[] s = Sam2Refiner.starts(3000, 1024, 256);
        assertEquals(0, s[0]); assertEquals(3000 - 1024, s[s.length - 1]);
        for (int i = 1; i < s.length; i++) assertTrue(s[i] - s[i - 1] <= 1024 - 256);
        for (double v = 0; v < 3000; v += 7.3) {
            int o = Sam2Refiner.owner(s, 1024, v);
            assertTrue(v >= s[o] && v < s[o] + 1024);
        }
        assertArrayEquals(new int[]{0}, Sam2Refiner.starts(800, 1024, 256));
    }

    @Test public void scaleTargetsMedianRadiusWithinLimits() {
        assertEquals(Sam2Refiner.TARGET_RADIUS / 20, Sam2Refiner.chooseScale(20, 2000, 1500), 1e-9);
        assertEquals(Sam2Refiner.MAX_SCALE, Sam2Refiner.chooseScale(2, 1000, 800), 1e-9);
        assertEquals(Sam2Refiner.MIN_SCALE, Sam2Refiner.chooseScale(1000, 3000, 2000), 1e-9);
        double s = Sam2Refiner.chooseScale(5, 6000, 4000);
        assertTrue(6000 * 4000 * s * s <= Sam2Refiner.MAX_SCALED_PIXELS * 1.0001);
    }
}
