package br.com.pelotavision;
import org.opencv.core.Mat;import java.util.*;import java.util.function.BooleanSupplier;
/** Bounded search on one still photo. Only the selected result receives crack inference. */
public final class DetectionTuner {
 private DetectionTuner(){}
 public static final String METHOD="photo_setting_search_coverage_v2";
 public static final class Option {
  public final int profile,sensitivity;public final boolean legacy;public final PelletDetector.Parameters parameters;public final String id;
  public Option(int profile,int sensitivity,boolean legacy){if(profile<0||profile>3||sensitivity<0||sensitivity>100)throw new IllegalArgumentException("Ajuste inválido.");this.profile=profile;this.sensitivity=sensitivity;this.legacy=legacy;double[][] sizes={{.013,.038},{.021,.060},{.04,.105},{.012,.15}};parameters=new PelletDetector.Parameters(sizes[profile][0],sizes[profile][1],.12-sensitivity*.001);id=(legacy?"log":"clahe")+"_p"+profile+"_s"+sensitivity;}
  public String label(){return (legacy?"LoG · ":"Bordas · ")+new String[]{"Pequenas","Médias","Grandes","Auto"}[profile]+" · sens. "+sensitivity+"%";}
 }
 public static final class Trial {
  public final Option option;public final PelletDetector.Result result;public final String error;public final double stability,score;public final DetectionAssessment.Metrics metrics;public final DetectionEvidence.Map evidenceMap;public final DetectionEvidence.Coverage coverage;
  Trial(Option option,PelletDetector.Result result,String error,double stability,DetectionAssessment.Metrics metrics,double score){this(option,result,error,stability,metrics,score,null,null);}
  Trial(Option option,PelletDetector.Result result,String error,double stability,DetectionAssessment.Metrics metrics,double score,DetectionEvidence.Map map,DetectionEvidence.Coverage coverage){this.option=option;this.result=result;this.error=error;this.stability=stability;this.metrics=metrics;this.score=score;evidenceMap=map;this.coverage=coverage;}
 }
 public static final class Report {
  public final List<Trial> trials;public final int planned;public final long durationMs;public final boolean cancelled,timeLimited;public final DetectionAssessment.Context context;public final DetectionEvidence evidence;
  Report(List<Trial> trials,int planned,long duration,boolean cancelled,boolean limited,DetectionAssessment.Context context){this(trials,planned,duration,cancelled,limited,context,null);}
  Report(List<Trial> trials,int planned,long duration,boolean cancelled,boolean limited,DetectionAssessment.Context context,DetectionEvidence evidence){this.trials=Collections.unmodifiableList(new ArrayList<>(trials));this.planned=planned;durationMs=duration;this.cancelled=cancelled;timeLimited=limited;this.context=context;this.evidence=evidence;}
  public Report rank(DetectionAssessment.Context ctx){
   List<DetectionAssessment.Metrics> metrics=new ArrayList<>();int max=0;for(Trial t:trials){DetectionAssessment.Metrics m=t.result==null?null:DetectionAssessment.measure(t.result,ctx);metrics.add(m);if(m!=null)max=Math.max(max,m.valid);}
   List<Trial> out=new ArrayList<>();for(int i=0;i<trials.size();i++){Trial t=trials.get(i);DetectionAssessment.Metrics m=metrics.get(i);DetectionEvidence.Coverage coverage=evidence==null||t.evidenceMap==null?null:evidence.coverage(t.evidenceMap,ctx);double score=m==null?0:coverage!=null&&coverage.available?DetectionAssessment.coverageScore(m,coverage,t.stability,t.result.candidateLimit):DetectionAssessment.score(m,max,t.stability,t.result.candidateLimit);out.add(new Trial(t.option,t.result,t.error,t.stability,m,score,t.evidenceMap,coverage));}
   return new Report(out,planned,durationMs,cancelled,timeLimited,ctx,evidence);
  }
  public List<Trial> ordered(){List<Trial> out=new ArrayList<>(trials);out.sort((a,b)->Double.compare(b.score,a.score));return out;}
  public Trial best(){for(Trial t:ordered())if(t.result!=null)return t;return null;}
  public Trial find(String id){for(Trial t:trials)if(t.option.id.equals(id))return t;return null;}
  public boolean weak(){Trial t=best();return t==null||t.metrics.valid<3||t.metrics.validFraction<.5||t.score<55;}
 }
 public interface Progress {void update(int done,int total,String label);}
 public static List<Option> options(Option current){
  LinkedHashMap<String,Option> options=new LinkedHashMap<>();options.put(current.id,current);
  // Both localization methods are mandatory and interleaved: LoG is tested early even under a time budget.
  for(int profile:new int[]{1,3,0,2})for(boolean legacy:new boolean[]{true,false}){Option o=new Option(profile,75,legacy);options.put(o.id,o);}
  for(int sensitivity:new int[]{55,90})for(int profile:new int[]{1,3,0,2})for(boolean legacy:new boolean[]{true,false}){Option o=new Option(profile,sensitivity,legacy);options.put(o.id,o);}
  return new ArrayList<>(options.values());
 }
 public static PelletDetector.Result detect(Mat gray,Option option){PelletDetector.Result seeds=option.legacy?new PelletDetector().detect(gray,option.parameters,1024,12):new AdaptivePelletDetector().detect(gray,option.parameters,1024,12);return new EllipseFitter().refine(gray,seeds);}
 public static PelletDetector.Result detect(Mat gray,Option option,AnalysisWindow window){PelletDetector.Result result=detect(gray,option);return window==null?result:window.filterLocal(result);}
 public static Report search(Mat gray,Option current,DetectionAssessment.Context context,BooleanSupplier cancelled,Progress progress){return search(gray,current,context,cancelled,progress,30000);}
 public static Report search(Mat gray,Option current,DetectionAssessment.Context context,BooleanSupplier cancelled,Progress progress,long budgetMs){
  long start=System.nanoTime();List<Option> options=options(current);List<Trial> trials=new ArrayList<>();boolean stopped=false,limited=false;
  for(Option option:options){
   if(!trials.isEmpty()&&(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())){stopped=true;break;}
   if(!trials.isEmpty()&&(System.nanoTime()-start)/1_000_000>=budgetMs){limited=true;break;}
   progress.update(trials.size()+1,options.size(),option.label());
   try{trials.add(new Trial(option,context.filterScope(detect(gray,option,context.detectionWindow())),null,0,null,0));}catch(RuntimeException e){trials.add(new Trial(option,null,e.getClass().getSimpleName(),0,null,0));}
  }
  // Stability is computed once in worker context; scale changes only recalculate cheap metrics; a new ROI requires a new search.
  List<PelletDetector.Result> results=new ArrayList<>();for(Trial t:trials)if(t.result!=null)results.add(t.result);DetectionEvidence evidence=results.isEmpty()?null:DetectionEvidence.create(gray,results);
  List<Trial> stable=new ArrayList<>();for(Trial t:trials){double first=0,second=0;for(Trial other:trials)if(t!=other&&t.result!=null&&other.result!=null){double agree=DetectionAssessment.agreement(t.result,other.result);if(agree>first){second=first;first=agree;}else second=Math.max(second,agree);}stable.add(new Trial(t.option,t.result,t.error,trials.size()>2?(first+second)/2:first,null,0,t.result==null||evidence==null?null:evidence.map(t.result),null));}
  return new Report(stable,options.size(),(System.nanoTime()-start)/1_000_000,stopped||cancelled.getAsBoolean(),limited,context,evidence).rank(context);
 }
}
