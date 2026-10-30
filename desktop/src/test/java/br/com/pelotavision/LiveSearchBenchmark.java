package br.com.pelotavision;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;

/**
 * 0.16.0: genético (0.5.5) × busca rápida nos mesmos quadros reais. Não é teste automático (demora); rode à mão:
 *   java ... br.com.pelotavision.LiveSearchBenchmark <pasta com *_original.png> [threads]
 * Cada foto vira 3 "quadros parados": a original e duas com ruído gaussiano σ=2 (ruído típico de webcam).
 * Mesmo avaliador da 0.5.5 para o genético (DesktopAnalysis.live por quadro) e o avaliador com cache para a rápida.
 */
public final class LiveSearchBenchmark {
    public static void main(String[] args) throws Exception {
        DesktopApp.loadOpenCv();
        File dir = new File(args[0]);
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : LiveSearch.defaultThreads();
        File[] files = dir.listFiles((d, n) -> n.endsWith("_original.png"));
        java.util.Arrays.sort(files);
        System.out.println("foto;metodo;ms;avaliacoes;melhor;aptidao");
        double[] tot = new double[6];
        for (File f : files) {
            Mat bgr = Mats.readUpright(f), gray = Mats.gray(bgr);
            bgr.release();
            List<Mat> frames = new ArrayList<>();
            frames.add(gray);
            for (int k = 0; k < 2; k++) {
                Mat noise = new Mat(gray.size(), CvType.CV_16S), g16 = new Mat(), out = new Mat();
                Core.randn(noise, 0, 2);
                gray.convertTo(g16, CvType.CV_16S);
                Core.add(g16, noise, g16);
                g16.convertTo(out, CvType.CV_8U);
                noise.release(); g16.release();
                frames.add(out);
            }
            DetectionTuner.Option start = new DetectionTuner.Option(3, 75, DetectionTuner.GRADIENT, false);
            DetectionAssessment.Context ctx = new DetectionAssessment.Context(null, gray.cols(), gray.rows(), null, 5);
            long t0 = System.nanoTime();
            LiveTuner.Run ga = LiveTuner.evolve(start, ctx, o -> { List<PelletDetector.Result> r = new ArrayList<>(); for (Mat m : frames) r.add(DesktopAnalysis.live(m, o, null)); return r; },
                    () -> false, null, 20261027L + gray.cols());
            long gaMs = (System.nanoTime() - t0) / 1_000_000;
            t0 = System.nanoTime();
            LiveTuner.Run fast1;
            try (LiveSearch.Frames fr = new LiveSearch.Frames(frames, null)) { fast1 = LiveSearch.search(start, ctx, fr::run, () -> false, null, 1, 0); }
            long f1 = (System.nanoTime() - t0) / 1_000_000;
            t0 = System.nanoTime();
            LiveTuner.Run fastN;
            try (LiveSearch.Frames fr = new LiveSearch.Frames(frames, null)) { fastN = LiveSearch.search(start, ctx, fr::run, () -> false, null, threads, 0); }
            long fN = (System.nanoTime() - t0) / 1_000_000;
            String n = f.getName().replace("_original.png", "");
            System.out.println(String.format(Locale.ROOT, "%s;genetico;%d;%d;%s;%.1f", n, gaMs, ga.evaluated.size(), ga.best.option.id, ga.best.fitness()));
            System.out.println(String.format(Locale.ROOT, "%s;rapida_1thread;%d;%d;%s;%.1f", n, f1, fast1.evaluated.size(), fast1.best.option.id, fast1.best.fitness()));
            System.out.println(String.format(Locale.ROOT, "%s;rapida_%dthreads;%d;%d;%s;%.1f", n, threads, fN, fastN.evaluated.size(), fastN.best.option.id, fastN.best.fitness()));
            tot[0] += gaMs; tot[1] += f1; tot[2] += fN; tot[3] += ga.best.fitness(); tot[4] += fast1.best.fitness(); tot[5] += fastN.best.fitness();
            for (Mat m : frames) m.release();
        }
        int k = files.length;
        System.out.println(String.format(Locale.ROOT, "MEDIA;genetico %.0f ms (apt %.1f);rapida1 %.0f ms (apt %.1f);rapida%d %.0f ms (apt %.1f)",
                tot[0] / k, tot[3] / k, tot[1] / k, tot[4] / k, threads, tot[2] / k, tot[5] / k));
    }
}
