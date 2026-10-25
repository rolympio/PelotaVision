package br.com.pelotavision;

import org.json.JSONObject;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;

/**
 * EXPERIMENTAL neural crack classifier: MobileNetV3-Small (ONNX via OpenCV DNN, the same engine as
 * StarDist, on Android and Windows). Mirror of tools/cracks/train_mobilenet.py#prep:
 * square of side ceil(2*1.15*r) around the pellet, taken from the ORIGINAL analyzed grayscale pixels
 * (never the super-resolution image), BORDER_REFLECT_101 at the photo edge, INTER_AREA to 128x128,
 * outside the 1.08*r circle filled with 128. Normalization and softmax are inside the ONNX.
 * The score is not a calibrated probability.
 */
public final class MobileNetCrackModel {
    public static final String MODEL = "crack_mobilenetv3.onnx", METADATA = "crack_mobilenetv3.json";

    public final String id, sha256, status;
    public final JSONObject metadata;
    final int size;
    final double half, maskRadius, fill;
    private final Net net;

    private MobileNetCrackModel(File onnx, String metadataJson) throws Exception {
        metadata = new JSONObject(metadataJson);
        byte[] bytes = Files.readAllBytes(onnx.toPath());
        sha256 = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!sha256.equals(metadata.getString("sha256")))
            throw new IllegalArgumentException("SHA-256 do modelo de trincas não confere com o metadado.");
        id = metadata.getString("id");
        status = metadata.optString("status", "experimental_not_validated");
        size = metadata.getInt("input_size");
        half = metadata.getDouble("crop_half_radius");
        maskRadius = metadata.getDouble("mask_radius");
        fill = metadata.getDouble("fill");
        if (size < 32 || size > 512 || half < 1 || maskRadius <= 0) throw new IllegalArgumentException("Metadado de trincas inválido.");
        net = Dnn.readNetFromONNX(onnx.getAbsolutePath());
        if (net.empty()) throw new IllegalArgumentException("ONNX de trincas não carregado.");
        net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
        net.setPreferableTarget(Dnn.DNN_TARGET_CPU);
    }

    /** Returns null (and a reason through {@link #lastError}) when the asset is missing or inconsistent. */
    public static MobileNetCrackModel load(File onnx, String metadataJson) {
        try {
            if (onnx == null || !onnx.isFile() || metadataJson == null || metadataJson.isEmpty())
                throw new IllegalArgumentException("modelo MobileNetV3 ausente");
            MobileNetCrackModel m = new MobileNetCrackModel(onnx, metadataJson);
            lastError = null;
            return m;
        } catch (Throwable e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return null;
        }
    }

    public static volatile String lastError = "modelo MobileNetV3 não carregado";

    public boolean validated() { return !status.startsWith("experimental"); }

    /** Network input (float32 size x size, 0..255), exactly as in the training script. */
    public Mat input(Mat gray, double cx, double cy, double r) {
        if (gray.empty() || gray.type() != CvType.CV_8UC1 || !(r > 0)) throw new IllegalArgumentException("Recorte inválido");
        double h = half * r;
        int side = Math.max(2, (int) Math.ceil(2 * h));
        int x0 = (int) Math.floor(cx - h), y0 = (int) Math.floor(cy - h);
        int W = gray.cols(), H = gray.rows();
        int pl = Math.max(0, -x0), pt = Math.max(0, -y0), pr = Math.max(0, x0 + side - W), pb = Math.max(0, y0 + side - H);
        Mat crop = new Mat(), small = new Mat(), out = new Mat();
        try {
            Mat inner = gray.submat(new Rect(x0 + pl, y0 + pt, side - pl - pr, side - pt - pb));
            if (pl > 0 || pt > 0 || pr > 0 || pb > 0)
                Core.copyMakeBorder(inner, crop, pt, pb, pl, pr, Core.BORDER_REFLECT_101 | Core.BORDER_ISOLATED);
            else inner.copyTo(crop);
            inner.release();
            Imgproc.resize(crop, small, new Size(size, size), 0, 0, Imgproc.INTER_AREA);
            small.convertTo(out, CvType.CV_32F);
            float[] px = new float[size * size];
            out.get(0, 0, px);
            double k = size / (double) side;
            float ccx = (float) ((cx - x0) * k - 0.5), ccy = (float) ((cy - y0) * k - 0.5);
            double rr = maskRadius * r * k;
            float limit = (float) (rr * rr), f = (float) fill;
            for (int y = 0; y < size; y++) {
                float dy = y - ccy;
                for (int x = 0; x < size; x++) {
                    float dx = x - ccx;
                    if (dx * dx + dy * dy > limit) px[y * size + x] = f;
                }
            }
            out.put(0, 0, px);
            return out;
        } finally {
            crop.release();
            small.release();
        }
    }

    /** Uncalibrated crack score in [0,1]. */
    public synchronized double score(Mat gray, double cx, double cy, double r) {
        Mat x = input(gray, cx, cy, r);
        Mat blob = null, y = null;
        try {
            blob = Dnn.blobFromImage(x, 1.0, new Size(size, size), new org.opencv.core.Scalar(0), false, false, CvType.CV_32F);
            net.setInput(blob);
            y = net.forward();
            float[] p = new float[2];
            y.reshape(1, 1).get(0, 0, p);
            return p[1];
        } finally {
            x.release();
            if (blob != null) blob.release();
            if (y != null) y.release();
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte v : b) s.append(String.format("%02x", v));
        return s.toString();
    }
}
