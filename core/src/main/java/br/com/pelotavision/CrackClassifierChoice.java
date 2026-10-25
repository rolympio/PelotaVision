package br.com.pelotavision;

/**
 * Crack classifier selected in "Modelos de análise", with explicit fallback.
 * The Random Forest stays the default. MobileNetV3 is experimental and only runs when its asset
 * loaded and matched its SHA-256; otherwise the request falls back to the forest (and says so).
 * The comparison mode keeps the forest status (production behavior) and records the network score
 * next to it, without mixing them.
 */
public enum CrackClassifierChoice {
    RANDOM_FOREST("Random Forest (padrão)"),
    MOBILENET("MobileNetV3 (experimental)"),
    COMPARISON("Comparação RF × MobileNetV3 (experimental)");

    public final String label;

    CrackClassifierChoice(String label) { this.label = label; }

    public static CrackClassifierChoice parse(String name) {
        for (CrackClassifierChoice c : values()) if (c.name().equals(name)) return c;
        return RANDOM_FOREST;
    }

    public boolean needsNetwork() { return this != RANDOM_FOREST; }

    /** Effective choice and a user message (null when nothing was replaced). */
    public static final class Resolution {
        public final CrackClassifierChoice requested, effective;
        public final String message;

        Resolution(CrackClassifierChoice requested, CrackClassifierChoice effective, String message) {
            this.requested = requested;
            this.effective = effective;
            this.message = message;
        }

        public boolean fellBack() { return requested != effective; }
    }

    public static Resolution resolve(CrackClassifierChoice requested, boolean forestAvailable, boolean networkAvailable, String networkError) {
        if (requested == null) requested = RANDOM_FOREST;
        if (requested.needsNetwork() && !networkAvailable)
            return new Resolution(requested, RANDOM_FOREST, "MobileNetV3 indisponível (" + (networkError == null ? "modelo ausente" : networkError)
                    + "). Usando " + (forestAvailable ? "o Random Forest." : "a heurística de reserva."));
        if (requested == COMPARISON && !forestAvailable)
            return new Resolution(requested, MOBILENET, "Random Forest indisponível: comparação impossível. Usando só a MobileNetV3 (experimental).");
        return new Resolution(requested, requested, null);
    }
}
