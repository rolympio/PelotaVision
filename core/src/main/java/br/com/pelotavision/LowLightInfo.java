package br.com.pelotavision;

import org.json.JSONObject;

/**
 * Rastreabilidade do tratamento de baixa iluminação aplicado a uma análise (0.15.0).
 * Registra a avaliação de iluminação da foto, como o modo foi escolhido e qual foi usado.
 * Os escores são heurísticos de cobertura, NÃO estimativas de acurácia ou confiança.
 */
public final class LowLightInfo {
    /** automatic = LowLightRecovery escolheu; manual = usuário escolheu em "Luz"; not_applied = sem ROI ou sem necessidade. */
    public final String selection;
    public final LowLightPreprocessor.Mode mode;
    public final LowLightPreprocessor.Quality quality;
    public final double originalScore, chosenScore;
    public final String reason;

    private LowLightInfo(String selection,LowLightPreprocessor.Mode mode,LowLightPreprocessor.Quality quality,double originalScore,double chosenScore,String reason) {
        this.selection=selection;this.mode=mode;this.quality=quality;this.originalScore=originalScore;this.chosenScore=chosenScore;this.reason=reason;
    }
    public static LowLightInfo notApplied(LowLightPreprocessor.Quality quality,String reason) {
        return new LowLightInfo("not_applied",LowLightPreprocessor.Mode.ORIGINAL,quality,Double.NaN,Double.NaN,reason);
    }
    public static LowLightInfo automatic(LowLightRecovery.Result r) {
        return new LowLightInfo("automatic",r.mode,r.quality,r.originalScore,r.chosenScore,
                r.mode==LowLightPreprocessor.Mode.ORIGINAL?"no treatment improved detection evidence materially; original kept":"treatment improved detection evidence over original");
    }
    public static LowLightInfo manual(LowLightPreprocessor.Mode mode,LowLightPreprocessor.Quality quality) {
        return new LowLightInfo("manual",mode,quality,Double.NaN,Double.NaN,"chosen by the user in the Luz dialog");
    }
    public boolean enhanced(){return mode!=LowLightPreprocessor.Mode.ORIGINAL;}

    /** Rótulo em português para relatórios. */
    public static String label(LowLightPreprocessor.Mode m) {
        switch(m) {
            case GAMMA_CLAHE: return "Gama + CLAHE";
            case DENOISE_CLAHE: return "Redução de ruído + CLAHE";
            case RETINEX: return "Retinex";
            default: return "Original (sem realce)";
        }
    }
    public static String selectionLabel(String s) {
        switch(s) {
            case "automatic": return "Automática (recuperação adaptativa)";
            case "manual": return "Manual (escolhida pelo usuário)";
            default: return "Não aplicada";
        }
    }

    public JSONObject toJson() throws Exception {
        JSONObject j=new JSONObject();
        j.put("selection",selection);j.put("mode",mode.name());j.put("enhanced",enhanced());j.put("reason",reason==null?JSONObject.NULL:reason);
        if(quality!=null) {
            JSONObject q=new JSONObject();
            q.put("mean_gray",quality.mean);q.put("std_gray",quality.standardDeviation);
            q.put("clipped_black_fraction",quality.clippedBlackFraction);q.put("clipped_white_fraction",quality.clippedWhiteFraction);
            q.put("needs_correction",quality.needsCorrection());q.put("rule","mean<75 or std<22 or black_fraction>0.08");
            j.put("illumination",q);
        } else j.put("illumination",JSONObject.NULL);
        j.put("original_score",Double.isFinite(originalScore)?originalScore:JSONObject.NULL);
        j.put("chosen_score",Double.isFinite(chosenScore)?chosenScore:JSONObject.NULL);
        j.put("score_type","heuristic coverage score; not accuracy or confidence");
        j.put("measurement_pixels","enhanced image only proposes objects; ellipse fitting and crack crops use ORIGINAL pixels");
        return j;
    }
}
