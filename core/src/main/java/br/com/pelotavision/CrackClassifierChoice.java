package br.com.pelotavision;

/**
 * Crack classifier selected in "Modelos de análise", with explicit fallback.
 * 0.15.5: MobileNetV3 is the default. In two independent tests it beat the forest: the old holdout
 * (AUC 0.96 vs 0.49) and the bench photos of 26/10/2026, never seen by either (AUC 0.97 vs 0.76;
 * verification/cracks-bench-20261026). It only runs when its asset loaded and matched its SHA-256;
 * otherwise the request falls back to the forest (and says so).
 * The comparison mode keeps the forest status (production behavior) and records the network score
 * next to it, without mixing them.
 */
public enum CrackClassifierChoice {
    RANDOM_FOREST("Random Forest (reserva)"),
    MOBILENET("MobileNetV3 (padrão)"),
    COMPARISON("Comparação RF × MobileNetV3");

    /** Escolha de quem nunca mudou o classificador (e de valores salvos ilegíveis). */
    public static final CrackClassifierChoice DEFAULT = MOBILENET;

    public final String label;

    CrackClassifierChoice(String label) { this.label = label; }

    public static CrackClassifierChoice parse(String name) {
        for (CrackClassifierChoice c : values()) if (c.name().equals(name)) return c;
        return DEFAULT;
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
        if (requested == null) requested = DEFAULT;
        if (requested.needsNetwork() && !networkAvailable)
            return new Resolution(requested, RANDOM_FOREST, "MobileNetV3 indisponível (" + (networkError == null ? "modelo ausente" : networkError)
                    + "). Usando " + (forestAvailable ? "o Random Forest." : "a heurística de reserva."));
        if (requested == COMPARISON && !forestAvailable)
            return new Resolution(requested, MOBILENET, "Random Forest indisponível: comparação impossível. Usando só a MobileNetV3 (experimental).");
        return new Resolution(requested, requested, null);
    }
}
