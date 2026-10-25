package br.com.pelotavision;

import org.opencv.core.Mat;
import java.util.ArrayList;
import java.util.List;

/**
 * Conservative low-light proposal recovery on the selected ROI.
 * Every candidate is evaluated against evidence in ORIGINAL pixels; final
 * ellipse fitting and crack inference never use the enhanced image.
 * Heuristic scores are NOT accuracy or confidence estimates.
 */
public final class LowLightRecovery {
    private LowLightRecovery() {}
    public static final class Result {
        public final PelletDetector.Result pellets;
        public final LowLightPreprocessor.Mode mode;
        public final LowLightPreprocessor.Quality quality;
        public final double originalScore, chosenScore;
        Result(PelletDetector.Result r, LowLightPreprocessor.Mode m,LowLightPreprocessor.Quality q,double a,double b) {
            pellets=r;mode=m;quality=q;originalScore=a;chosenScore=b;
        }
    }
    public static Result analyze(Mat original,DetectionTuner.Option option,AnalysisWindow window,
                                 DetectionAssessment.Context context) {
        LowLightPreprocessor.Quality quality=LowLightPreprocessor.assess(original);
        PelletDetector.Result baseline=DetectionTuner.detect(original,option,window);
        if(!quality.needsCorrection())return new Result(baseline,LowLightPreprocessor.Mode.ORIGINAL,quality,0,0);
        List<PelletDetector.Result> results=new ArrayList<>();
        List<LowLightPreprocessor.Mode> modes=new ArrayList<>();
        results.add(baseline);modes.add(LowLightPreprocessor.Mode.ORIGINAL);
        for(LowLightPreprocessor.Mode mode:new LowLightPreprocessor.Mode[]{
                LowLightPreprocessor.Mode.GAMMA_CLAHE,
                LowLightPreprocessor.Mode.DENOISE_CLAHE,
                LowLightPreprocessor.Mode.RETINEX}) {
            Mat enhanced=null;
            try {
                enhanced=LowLightPreprocessor.apply(original,mode);
                PelletDetector.Result seeds=DetectionTuner.seeds(enhanced,option,option.parameters.workSide(1024),12);
                // Refine in original image to avoid measuring enhanced/sharpened borders.
                PelletDetector.Result refined=new EllipseFitter().refine(original,seeds,option.belt);
                results.add(window==null?refined:window.filterLocal(refined));
                modes.add(mode);
            } catch(RuntimeException ignored) {
                // A failing optional treatment must never invalidate the original analysis.
            } finally {if(enhanced!=null)enhanced.release();}
        }
        DetectionEvidence evidence=DetectionEvidence.create(original,results);
        List<DetectionAssessment.Metrics> metrics=new ArrayList<>();
        int max=0;
        for(PelletDetector.Result r:results) {
            DetectionAssessment.Metrics m=DetectionAssessment.measure(r,context);
            metrics.add(m);max=Math.max(max,m.valid);
        }
        int best=0;double baselineScore=0,bestScore=Double.NEGATIVE_INFINITY;
        for(int i=0;i<results.size();i++) {
            PelletDetector.Result r=results.get(i);
            DetectionEvidence.Coverage c=evidence.coverage(evidence.map(r),context);
            double score=c.available?
                    DetectionAssessment.coverageScore(metrics.get(i),c,0,r.candidateLimit):
                    DetectionAssessment.score(metrics.get(i),max,0,r.candidateLimit);
            if(i==0){baselineScore=score;bestScore=score;}
            // Require a material improvement over original, not merely more objects.
            else if(score>bestScore+5 && !r.candidateLimit) {best=i;bestScore=score;}
        }
        return new Result(results.get(best),modes.get(best),quality,baselineScore,bestScore);
    }
}
