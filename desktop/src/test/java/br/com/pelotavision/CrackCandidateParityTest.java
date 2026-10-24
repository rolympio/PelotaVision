package br.com.pelotavision;

import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Paridade de ponta a ponta Python x Java do modelo CANDIDATO de trincas (sintético) e do atual:
 * OpenCV nativo 4.12 extrai os 72 atributos (CrackFeatures) dos mesmos recortes usados no
 * treino em Python e as duas florestas pontuam. Referências: ml_training/training_synth/.
 * Não muda o modelo usado pelo app.
 */
public class CrackCandidateParityTest {
    private static File repo;

    @BeforeClass public static void load() {
        DesktopApp.loadOpenCv();
        repo = new File(System.getProperty("pelotavision.validationDir")).getAbsoluteFile().getParentFile();
    }

    private static CrackModel model(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) { return new CrackModel(in); }
    }

    @Test public void candidateAndCurrentScoresMatchPython() throws IOException {
        File dir = new File(repo, "ml_training/training_synth");
        CrackModel candidate = model(new File(dir, "crack_forest_candidate.pvrf"));
        CrackModel current = model(new File(repo, "app/src/main/assets/crack_forest.pvrf"));
        List<String> vectors = Files.readAllLines(new File(dir, "reference_vectors_candidate.csv").toPath());
        List<String> samples = new ArrayList<>(Files.readAllLines(new File(dir, "reference_samples_candidate.csv").toPath()));
        samples.remove(0);
        assertEquals(vectors.size(), samples.size());
        Map<String, Mat> images = new HashMap<>();
        CrackFeatures extractor = new CrackFeatures();
        double maxFeature = 0, maxCand = 0, maxCur = 0, maxForest = 0;
        int changes = 0;
        try {
            for (int i = 0; i < vectors.size(); i++) {
                String[] v = vectors.get(i).split(","), s = samples.get(i).split(";");
                float[] expected = new float[72];
                for (int j = 0; j < 72; j++) expected[j] = Float.parseFloat(v[j]);
                double candExpected = Double.parseDouble(v[72]), curExpected = Double.parseDouble(s[4]);
                Mat gray = images.computeIfAbsent(s[0], p -> Imgcodecs.imread(new File(repo, p).getPath(), Imgcodecs.IMREAD_GRAYSCALE));
                assertTrue("imagem ausente: " + s[0], !gray.empty());
                CrackFeatures.Value value = extractor.extract(gray, Double.parseDouble(s[1]), Double.parseDouble(s[2]), Double.parseDouble(s[3]));
                for (int j = 0; j < 72; j++) maxFeature = Math.max(maxFeature, Math.abs(value.features[j] - expected[j]));
                maxForest = Math.max(maxForest, Math.abs(candidate.score(expected) - candExpected));
                double c = candidate.score(value.features), k = current.score(value.features);
                maxCand = Math.max(maxCand, Math.abs(c - candExpected));
                maxCur = Math.max(maxCur, Math.abs(k - curExpected));
                if ((c >= .5) != (candExpected >= .5)) changes++;
            }
        } finally { for (Mat m : images.values()) m.release(); }
        String line = String.format(Locale.ROOT,
                "Trincas candidato x Python: %d recortes | erro max atributos %.3g | floresta %.3g | escore candidato %.3g | escore atual %.3g | mudancas de classe %d | OpenCV %s",
                vectors.size(), maxFeature, maxForest, maxCand, maxCur, changes, Core.VERSION);
        System.out.println(line);
        File out = new File("build/parity/crack_candidate.txt");
        out.getParentFile().mkdirs();
        Files.write(out.toPath(), (line + "\n").getBytes(StandardCharsets.UTF_8));
        assertTrue("floresta candidata diverge do Python: " + maxForest, maxForest <= 1e-12);
        assertTrue("atributos divergem do Python: " + maxFeature, maxFeature <= 1e-5);
        assertTrue("escore candidato diverge: " + maxCand, maxCand <= 1e-6);
        assertTrue("escore atual diverge: " + maxCur, maxCur <= 1e-6);
        assertEquals(0, changes);
    }
}
