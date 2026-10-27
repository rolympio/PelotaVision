package br.com.pelotavision;

/**
 * Motor que executa uma rede neural embarcada (StarDist ou MobileNetV3).
 * Entrada e saída em float32, ordem NCHW (a mesma do ONNX), qualquer que seja o motor por baixo.
 * Implementações: {@link OpenCvBackend} (padrão, CPU), ONNX Runtime no Windows e LiteRT no Android.
 */
public interface InferenceBackend extends AutoCloseable {
    /** Executa a rede; {@code shape} é a forma NCHW da entrada. Devolve a saída em NCHW. */
    float[] run(float[] input, int[] shape) throws Exception;

    /** Ex.: "OpenCV DNN · CPU", "ONNX Runtime · CUDA", "LiteRT · GPU (fp16)". */
    String description();

    /** Verdadeiro quando roda num acelerador (GPU/NPU), não na CPU. */
    boolean accelerated();

    @Override void close();
}
