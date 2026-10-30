package br.com.pelotavision;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;

/** Regression for the 11 mm sphere suggestion and manual rejection core. */
public class SphereCalibrationAssistantAndroidTest {
    @Test public void candidatesScaleAndRejection() {
        PelletDetector.Detection seed = new PelletDetector.Detection(
                20, 20, 36, Math.PI*36*36, 0.99, false, false);
        PelletDetector.Detection sphere = new PelletDetector.Detection(
                seed,20,20,36,36,0,true,0.99,0.1,false,false);
        PelletDetector.Result result = new PelletDetector.Result(
                Arrays.asList(sphere,seed),200,100,2,0,false);
        java.util.List<SphereCalibrationAssistant.Candidate> ranked =
                SphereCalibrationAssistant.suggest(result,200,100,11,5);
        assertEquals(1,ranked.size());
        assertEquals(0,ranked.get(0).detectionIndex);
        assertEquals(72.0/11.0,ranked.get(0).pixelsPerMm,1e-12);
        assertEquals(Collections.singletonList(1),
                SphereCalibrationAssistant.acceptedIndices(result,null,null,
                        Collections.singleton(0)));
    }
    @Test(expected=IllegalArgumentException.class)
    public void rejectsInvalidDiameter() {
        SphereCalibrationAssistant.suggest(new PelletDetector.Result(
                Collections.emptyList(),100,100,0,0,false),100,100,0,1);
    }
}
