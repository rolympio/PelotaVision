package br.com.pelotavision;

/**
 * Detector de movimento do ao vivo (0.15.5), Java puro: compara miniaturas em tons de cinza de quadros
 * sucessivos (diferença absoluta média, 0..255) e decide, com histerese, se a cena está parada.
 * Parada = diferença abaixo de {@link #STILL} em {@link #STILL_TICKS} medições seguidas; volta a
 * "em movimento" só acima de {@link #MOVING} (o ruído do sensor fica entre os dois e não alterna o estado).
 */
public final class LiveMotion {
    public enum State { MOVING, SETTLING, STILL }

    /** Limiares em níveis de cinza médios por pixel da miniatura (após desfoque). */
    public static final double STILL = 1.6, MOVING = 3.5;
    public static final int STILL_TICKS = 4;

    private byte[] previous;
    private int quiet;
    private State state = State.MOVING;
    private double last = Double.NaN;
    private long stillSince = -1;

    /** Diferença média entre duas miniaturas do mesmo tamanho; NaN se incomparáveis. */
    public static double meanAbsDiff(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return Double.NaN;
        long sum = 0;
        for (int i = 0; i < a.length; i++) sum += Math.abs((a[i] & 255) - (b[i] & 255));
        return sum / (double) a.length;
    }

    /** Alimenta uma miniatura (mesmo tamanho a cada chamada) e devolve o estado. */
    public synchronized State update(byte[] thumb, long nowMs) {
        double d = meanAbsDiff(previous, thumb);
        previous = thumb == null ? null : thumb.clone();
        last = d;
        if (!Double.isFinite(d) || d > MOVING) {
            quiet = 0;
            state = State.MOVING;
            stillSince = -1;
        } else if (d < STILL) {
            quiet++;
            if (quiet >= STILL_TICKS) {
                if (state != State.STILL) stillSince = nowMs;
                state = State.STILL;
            } else if (state == State.MOVING) state = State.SETTLING;
        }
        // Entre STILL e MOVING: mantém o estado (histerese).
        return state;
    }

    public synchronized State state() { return state; }
    public synchronized double lastDifference() { return last; }
    /** Instante em que a cena ficou parada (ms), ou -1. */
    public synchronized long stillSince() { return stillSince; }

    public synchronized void reset() { previous = null; quiet = 0; state = State.MOVING; last = Double.NaN; stillSince = -1; }
}
