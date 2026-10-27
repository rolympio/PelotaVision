package br.com.pelotavision;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;

import java.io.File;

/** OpenCV DNN na CPU: o motor de referência, validado nas versões anteriores e sempre disponível. */
public final class OpenCvBackend implements InferenceBackend {
    private Net net;

    public OpenCvBackend(File onnx) {
        net = Dnn.readNetFromONNX(onnx.getAbsolutePath());
        if (net.empty()) throw new IllegalArgumentException("ONNX não carregado: " + onnx.getName());
        net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
        net.setPreferableTarget(Dnn.DNN_TARGET_CPU);
    }

    @Override public synchronized float[] run(float[] input, int[] shape) {
        if (net == null) throw new IllegalStateException("Motor OpenCV já liberado.");
        Mat blob = new Mat(shape, CvType.CV_32F), out = null;
        try {
            blob.put(new int[shape.length], input);
            net.setInput(blob);
            out = net.forward();
            int n = 1;
            for (int d = 0; d < out.dims(); d++) n *= out.size(d);
            float[] y = new float[n];
            if (out.dims() > 2) out.get(new int[out.dims()], y);
            else out.reshape(1, 1).get(0, 0, y);
            return y;
        } finally {
            blob.release();
            if (out != null) out.release();
        }
    }

    @Override public String description() { return "OpenCV DNN · CPU"; }
    @Override public boolean accelerated() { return false; }
    @Override public synchronized void close() { net = null; }
}
