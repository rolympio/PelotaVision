package br.com.pelotavision;

import org.json.JSONObject;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Rajada em lote (0.19.0), sem interface: base dos ensaios A/B/C de {@code tools/burst_validate/}.
 *
 * <pre>
 * java -cp "pelotavision-desktop.jar;libs/*" br.com.pelotavision.BurstCli --saida PASTA [--roi x,y,w,h]
 *      [--sr] [--forcar-sr] [--iteracoes 10] [--sr-lado 512] FOTO1 FOTO2 ... | PASTA_DE_FOTOS
 * </pre>
 *
 * Grava {@code laudo.txt}, {@code rajada.json} (bloco "burst"), {@code rajada_empilhada.png} (mesma
 * grade da base) e, se a SR for aceita, {@code rajada_sr2x.png} (derivado; não é medida). As fotos de
 * entrada não são tocadas. Código de saída 0 em sucesso, 2 em erro de uso.
 */
public final class BurstCli {
    private BurstCli() {}

    public static void main(String[] args) throws Exception {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        File out = null;
        BurstStack.Options o = new BurstStack.Options();
        List<File> inputs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--saida": out = new File(args[++i]); break;
                case "--roi": {
                    int[] v = Arrays.stream(args[++i].split(",")).mapToInt(s -> Integer.parseInt(s.trim())).toArray();
                    o.roi = new Rect(v[0], v[1], v[2], v[3]);
                    break;
                }
                case "--sr": o.sr = true; break;
                case "--forcar-sr": o.sr = true; o.forceSrForTesting = true; break;
                case "--iteracoes": o.srIterations = Integer.parseInt(args[++i]); break;
                case "--sr-lado": o.srMaxSide = Integer.parseInt(args[++i]); break;
                case "--parada": o.liveStill = Boolean.TRUE; break;
                default:
                    File f = new File(a);
                    if (f.isDirectory()) inputs.addAll(images(f)); else inputs.add(f);
            }
        }
        if (out == null || inputs.size() < 1) {
            System.err.println("Uso: BurstCli --saida PASTA [--roi x,y,w,h] [--sr] [--forcar-sr] [--iteracoes N] [--sr-lado PX] FOTOS... | PASTA");
            System.exit(2);
        }
        if (inputs.size() > BurstStack.MAX_FRAMES) {
            System.err.println("Usando as primeiras " + BurstStack.MAX_FRAMES + " de " + inputs.size() + " fotos.");
            inputs = inputs.subList(0, BurstStack.MAX_FRAMES);
        }
        if (!out.isDirectory() && !out.mkdirs()) throw new IOException("Não foi possível criar " + out);
        List<Mat> frames = new ArrayList<>();
        try {
            for (File f : inputs) frames.add(Mats.readUpright(f));
            BurstStack.Result r = BurstStack.run(frames, o);
            try {
                List<String> names = new ArrayList<>();
                for (File f : inputs) names.add(f.getName());
                String fusedName = null, srName = null;
                if (r.fused != null) { fusedName = "rajada_empilhada.png"; write(new File(out, fusedName), r.fused); }
                if (r.sr != null) { srName = "rajada_sr2x.png"; write(new File(out, srName), r.sr); }
                JSONObject j = new JSONObject();
                j.put("app", "PelotaVision Windows " + DesktopApp.VERSION + " (nucleo " + DesktopApp.CORE_VERSION + ") BurstCli");
                j.put("opencv_version", Core.VERSION);
                j.put("burst", r.toJson(names, fusedName, srName, false));
                Files.write(new File(out, "rajada.json").toPath(), j.toString(2).getBytes(StandardCharsets.UTF_8));
                String text = r.describe();
                Files.write(new File(out, "laudo.txt").toPath(), text.getBytes(StandardCharsets.UTF_8));
                System.out.println(text);
            } finally { r.release(); }
        } finally { for (Mat m : frames) m.release(); }
    }

    static List<File> images(File dir) {
        File[] all = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|png|bmp|tif|tiff|webp)$"));
        List<File> l = new ArrayList<>(all == null ? List.of() : Arrays.asList(all));
        l.sort((a, b) -> a.getName().compareTo(b.getName()));
        return l;
    }

    /** PNG sem perdas, gravado pelos bytes (funciona com acentos no caminho do Windows). */
    static void write(File f, Mat m) throws IOException {
        MatOfByte buf = new MatOfByte();
        try {
            if (!Imgcodecs.imencode(".png", m, buf)) throw new IOException("Falha ao codificar " + f.getName());
            Files.write(f.toPath(), buf.toArray());
        } finally { buf.release(); }
    }
}
