package br.com.pelotavision;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** Opção neural no fluxo de ajuste: identidade, compatibilidade e opt-in na busca. */
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

    @Test public void reportBlockOnlyForNeuralOption() throws Exception {
        assertSame(JSONObject.NULL, ReportJson.neuralModelJson(new DetectionTuner.Option(1, 75, false)));
        assertSame(JSONObject.NULL, ReportJson.neuralModelJson(null));
    }
}
