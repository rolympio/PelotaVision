package br.com.pelotavision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sugestões conservadoras para localizar uma esfera-padrão já segmentada.
 * Não confirma a identidade da esfera: exige confirmação do operador.
 * Sem uma referência independente, circularidade não distingue esfera de pelota.
 */
public final class SphereCalibrationAssistant {
    private SphereCalibrationAssistant() {}

    public static final class Candidate {
        public final int detectionIndex;
        public final double diameterPixels;
        public final double pixelsPerMm;
        public final double aspectRatio;
        public final double score;
        Candidate(int index, double diameter, double realMm, double aspect, double score) {
            detectionIndex=index;
            diameterPixels=diameter;
            pixelsPerMm=diameter/realMm;
            aspectRatio=aspect;
            this.score=score;
        }
    }

    /**
     * Ordena candidatos por circularidade e qualidade geométrica, sem assumir
     * que a maior ou a mais circular detecção seja necessariamente a esfera.
     * originalWidth/Height: foto original; result.width/height: imagem de detecção.
     */
    public static List<Candidate> suggest(PelletDetector.Result result,
            int originalWidth, int originalHeight, double sphereDiameterMm, int limit) {
        if(result==null || originalWidth<=0 || originalHeight<=0 ||
                result.width<=0 || result.height<=0 ||
                !Double.isFinite(sphereDiameterMm) || sphereDiameterMm<=0 || limit<1)
            throw new IllegalArgumentException("Parâmetros de calibração inválidos");
        List<Candidate> ranked=new ArrayList<>();
        for(int i=0;i<result.circles.size();i++) {
            PelletDetector.Detection d=result.circles.get(i);
            if(!d.ellipseFitted || d.partial || d.needsReview) continue;
            ObjectCalibration.Axes axes=ObjectCalibration.originalAxes(
                    d,originalWidth,originalHeight,result.width,result.height);
            if(!(axes.major>0 && axes.minor>0))continue;
            double aspect=axes.minor/axes.major;
            if(aspect<0.85)continue;
            double diameter=Math.sqrt(axes.major*axes.minor);
            double quality=Math.max(0,Math.min(1,d.roundness));
            double score=0.65*aspect+0.35*quality;
            ranked.add(new Candidate(i,diameter,sphereDiameterMm,aspect,score));
        }
        ranked.sort(Comparator.comparingDouble((Candidate c)->c.score).reversed()
                .thenComparingInt(c->c.detectionIndex));
        return Collections.unmodifiableList(new ArrayList<>(
                ranked.subList(0,Math.min(limit,ranked.size()))));
    }


    /**
     * Escala robusta das esferas CONFIRMADAS pelo operador. A mediana evita
     * que uma esfera mal segmentada distorca toda a granulometria.
     * Nao estima profundidade nem aplica correcao de altura nao identificavel.
     */
    public static double robustPixelsPerMm(List<Candidate> confirmed) {
        if (confirmed == null || confirmed.isEmpty())
            throw new IllegalArgumentException("Nenhuma esfera confirmada");
        List<Double> values = new ArrayList<>();
        for (Candidate c : confirmed) {
            if (c == null || !Double.isFinite(c.pixelsPerMm) || c.pixelsPerMm <= 0)
                throw new IllegalArgumentException("Escala de esfera invalida");
            values.add(c.pixelsPerMm);
        }
        Collections.sort(values);
        double median = median(values);
        List<Double> deviations = new ArrayList<>();
        for (double v : values) deviations.add(Math.abs(v - median));
        Collections.sort(deviations);
        double cutoff = Math.max(0.04 * median, 3.0 * 1.4826 * median(deviations));
        List<Double> kept = new ArrayList<>();
        for (double v : values) if (Math.abs(v - median) <= cutoff) kept.add(v);
        return median(kept);
    }

    private static double median(List<Double> sorted) {
        int n = sorted.size();
        if (n == 0) throw new IllegalArgumentException("Sem medidas validas");
        return n % 2 == 1 ? sorted.get(n / 2) :
                (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }


    /**
     * Amostra metrologica conservadora. Nao elimina deteccoes por tamanho
     * em mm ou por aderencia a Rosin-Rammler: isso causaria vies.
     * fitSupport e fitRms sao avaliados somente quando finitos.
     */
    public static boolean isCompleteMeasurement(PelletDetector.Detection d) {
        if (d == null || !d.ellipseFitted || d.partial || d.needsReview) return false;
        if (!(d.semiMajor > 0 && d.semiMinor > 0)) return false;
        if (d.semiMinor / d.semiMajor < 0.72) return false;
        if (Double.isFinite(d.fitSupport) && d.fitSupport < 0.65) return false;
        if (Double.isFinite(d.fitRms) && d.fitRms > 0.20 * d.semiMinor) return false;
        return true;
    }

    /** Nao inclui as esferas confirmadas nem objetos com geometria inconclusiva. */
    public static List<Integer> validatedPelletIndices(PelletDetector.Result result,
            InterestRegion roi, Set<Integer> confirmedSphereIndices) {
        if (result == null) throw new IllegalArgumentException("Resultado ausente");
        Set<Integer> spheres = confirmedSphereIndices == null
                ? Collections.emptySet() : new HashSet<>(confirmedSphereIndices);
        for (Integer i : spheres)
            if (i == null || i < 0 || i >= result.circles.size())
                throw new IllegalArgumentException("Indice de esfera invalido");
        List<Integer> valid = new ArrayList<>();
        for (int i = 0; i < result.circles.size(); i++) {
            PelletDetector.Detection d = result.circles.get(i);
            if (!spheres.contains(i) && (roi == null || roi.includes(d))
                    && isCompleteMeasurement(d)) valid.add(i);
        }
        return Collections.unmodifiableList(valid);
    }

    /**
     * Compara calibracoes sem substituir silenciosamente a escala da esfera.
     * A diferenca de planos torna a escala da borda inadequada como substituta.
     */
    public static double borderRelativeBias(double spherePxPerMm, double borderPxPerMm) {
        if (!Double.isFinite(spherePxPerMm) || spherePxPerMm <= 0
                || !Double.isFinite(borderPxPerMm) || borderPxPerMm <= 0)
            throw new IllegalArgumentException("Escalas invalidas");
        return spherePxPerMm / borderPxPerMm - 1.0;
    }

    /** Índices estáveis somente para o resultado de segmentação informado. */
    public static List<Integer> acceptedIndices(PelletDetector.Result result,
            InterestRegion roi, ObjectCalibration.Reference reference,
            Set<Integer> rejectedIndices) {
        if(result==null)throw new IllegalArgumentException("Resultado ausente");
        Set<Integer> rejected=rejectedIndices==null
                ?Collections.emptySet():new HashSet<>(rejectedIndices);
        for(Integer index:rejected)
            if(index==null || index<0 || index>=result.circles.size())
                throw new IllegalArgumentException("Índice excluído inválido");
        List<Integer> accepted=new ArrayList<>();
        for(int i=0;i<result.circles.size();i++) {
            PelletDetector.Detection d=result.circles.get(i);
            if(rejected.contains(i))continue;
            if(roi!=null && !roi.includes(d))continue;
            if(reference!=null && reference.excludeFromStatistics &&
                    reference.sameGeometry(d))continue;
            accepted.add(i);
        }
        return Collections.unmodifiableList(accepted);
    }
}
