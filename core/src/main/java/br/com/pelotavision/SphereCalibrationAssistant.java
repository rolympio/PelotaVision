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
