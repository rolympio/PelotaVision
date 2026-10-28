package br.com.pelotavision;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.*;

/** Opção neural no fluxo de ajuste: identidade, compatibilidade e opt-in na busca. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class NeuralOptionTest {
    @Test public void legacyConstructorKeepsIdsAndNeuralHasItsOwn() {
        assertEquals("log_p1_s75", new DetectionTuner.Option(1, 75, true).id);
        assertEquals("clahe_p3_s75", new DetectionTuner.Option(3, 75, false).id);
        DetectionTuner.Option nn = new DetectionTuner.Option(3, 75, DetectionTuner.NEURAL);
        assertEquals("nn_p3_s75", nn.id);
        assertTrue(nn.neural());
        assertFalse(nn.legacy);
        assertEquals("neural_stardist", nn.methodName());
        assertTrue(nn.label().startsWith("Rede neural"));
        assertTrue(new DetectionTuner.Option(0, 50, DetectionTuner.LOG).legacy);
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsUnknownMethod() { new DetectionTuner.Option(1, 75, 3); }

    @Test public void searchIsUnchangedUnlessNeuralChosenAndInstalled() {
        List<DetectionTuner.Option> standard = DetectionTuner.options(new DetectionTuner.Option(3, 75, false));
        for (DetectionTuner.Option o : standard) assertFalse(o.neural());
        assertEquals(24, standard.size());
        // Sem modelo instalado (JVM de teste), escolher a rede não acrescenta opções neurais além da atual.
        List<DetectionTuner.Option> chosen = DetectionTuner.options(new DetectionTuner.Option(3, 75, DetectionTuner.NEURAL));
        int neural = 0; for (DetectionTuner.Option o : chosen) if (o.neural()) neural++;
        assertEquals(NeuralPelletDetector.available() ? 4 : 1, neural);
    }

    @Test public void thresholdAndGeometry() {
        assertEquals(.5, NeuralPelletDetector.threshold(50), 1e-12);
        assertEquals(.75, NeuralPelletDetector.threshold(0), 1e-12);
        assertEquals(.25, NeuralPelletDetector.threshold(100), 1e-12);
        float[] ax = {0, 4, 4, 0}, ay = {0, 0, 4, 4}, bx = {2, 6, 6, 2}, by = {2, 2, 6, 6};
        double[][] a = NeuralPelletDetector.hull(ax, ay), b = NeuralPelletDetector.hull(bx, by);
        assertEquals(4, NeuralPelletDetector.intersectionArea(a, b), 1e-12);
        assertEquals(16, NeuralPelletDetector.intersectionArea(a, a), 1e-12);
        assertEquals(0, NeuralPelletDetector.intersectionArea(a, NeuralPelletDetector.hull(new float[]{10, 11, 11}, new float[]{0, 0, 1})), 0);
        double[] p = NeuralPelletDetector.props(ax, ay);
        assertEquals(16, p[0], 1e-12); assertEquals(2, p[1], 1e-12); assertEquals(2, p[2], 1e-12); assertEquals(16, p[3], 1e-12);
        int[] st = NeuralPelletDetector.normStats(new byte[]{10, 10, 10, 10}, .01, .998);
        assertEquals(10, st[0]); assertEquals(18, st[1]); assertEquals(10, st[2]);
    }

    @Test public void beltSceneHasOwnSizesLimitsAndIds() {
        DetectionTuner.Option bench = new DetectionTuner.Option(3, 75, DetectionTuner.GRADIENT), belt = new DetectionTuner.Option(3, 75, DetectionTuner.GRADIENT, true);
        assertEquals("clahe_p3_s75", bench.id);
        assertEquals("belt_clahe_p3_s75", belt.id);
        assertFalse(bench.parameters.belt);
        assertEquals(600, bench.parameters.maxObjects);
        assertEquals(1024, bench.parameters.workSide(1024));
        assertEquals(4096, bench.parameters.workSide(4096));
        assertTrue(belt.parameters.belt);
        assertEquals(5000, belt.parameters.maxObjects);
        assertEquals(1600, belt.parameters.workSide(1024));
        assertEquals(1600, belt.parameters.workSide(4096));
        assertEquals(.004, belt.parameters.minRadiusFraction, 0);
        assertEquals(.016, belt.parameters.maxRadiusFraction, 0);
        assertEquals(.012, bench.parameters.minRadiusFraction, 0);
        assertTrue(belt.label().startsWith("Correia"));
        for (DetectionTuner.Option o : DetectionTuner.options(belt)) assertTrue(o.id, o.belt && o.id.startsWith("belt_"));
        assertEquals(24, DetectionTuner.options(belt).size());
    }

    @Test public void benchSearchListIsUnchanged() {
        List<DetectionTuner.Option> o = DetectionTuner.options(new DetectionTuner.Option(1, 75, true));
        assertEquals("log_p1_s75", o.get(0).id);
        assertEquals("clahe_p1_s75", o.get(1).id);
        assertEquals("log_p1_s55", o.get(8).id);
        assertEquals("clahe_p2_s90", o.get(23).id);
    }

    @Test public void sceneBlock() throws Exception {
        JSONObject belt = (JSONObject) ReportJson.sceneJson(new DetectionTuner.Option(0, 75, DetectionTuner.LOG, true));
        assertEquals("belt", belt.getString("mode"));
        assertEquals(5000, belt.getInt("max_objects"));
        assertFalse(belt.getBoolean("detailed_mode_available"));
        assertEquals("bench", ((JSONObject) ReportJson.sceneJson(new DetectionTuner.Option(0, 75, false))).getString("mode"));
    }

    @Test public void reportBlockOnlyForNeuralOption() throws Exception {
        assertSame(JSONObject.NULL, ReportJson.neuralModelJson(new DetectionTuner.Option(1, 75, false)));
        assertSame(JSONObject.NULL, ReportJson.neuralModelJson(null));
    }
}
