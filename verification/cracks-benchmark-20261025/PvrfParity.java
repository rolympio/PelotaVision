import br.com.pelotavision.CrackModel;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Paridade Python x Java do formato PVF1: java PvrfParity modelo.pvrf vetores.csv (72 atributos + escore esperado por linha). */
public class PvrfParity {
    public static void main(String[] a) throws Exception {
        CrackModel m;
        try (InputStream in = Files.newInputStream(Path.of(a[0]))) { m = new CrackModel(in); }
        double max = 0; int n = 0; long t0 = System.nanoTime();
        for (String line : Files.readAllLines(Path.of(a[1]))) {
            String[] p = line.split(",");
            float[] f = new float[CrackModel.FEATURES];
            for (int i = 0; i < f.length; i++) f[i] = Float.parseFloat(p[i]);
            max = Math.max(max, Math.abs(m.score(f) - Double.parseDouble(p[f.length])));
            n++;
        }
        double us = (System.nanoTime() - t0) / 1e3 / n;
        System.out.printf(Locale.ROOT, "{\"model\":\"%s\",\"vectors\":%d,\"max_abs_diff\":%.3e,\"java_us_per_score_incl_parse\":%.1f}%n", a[0], n, max, us);
        if (max > 1e-9) System.exit(1);
    }
}
