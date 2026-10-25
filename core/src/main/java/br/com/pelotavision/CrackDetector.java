package br.com.pelotavision;
import org.opencv.core.*;
import java.util.*;
/** Local learned screening; crack traces are morphology-based, not a trained segmentation. */
public final class CrackDetector {
 private final CrackModel model;
 private final MobileNetCrackModel network;
 private final CrackClassifierChoice choice;
 /** Explicit legacy heuristic fallback, retained for old projects and native regression checks. */
 public CrackDetector(){this(null);}
 public CrackDetector(CrackModel model){this(model,null,CrackClassifierChoice.RANDOM_FOREST);}
 /** choice must already be resolved (CrackClassifierChoice.resolve): a network choice without network is refused. */
 public CrackDetector(CrackModel model,MobileNetCrackModel network,CrackClassifierChoice choice){
  if(choice!=null&&choice.needsNetwork()&&network==null)throw new IllegalArgumentException("MobileNetV3 indisponível; resolva a escolha antes (fallback para o RF).");
  if(choice==CrackClassifierChoice.COMPARISON&&model==null)throw new IllegalArgumentException("Comparação exige o Random Forest.");
  this.model=model;this.network=network;this.choice=choice==null?CrackClassifierChoice.RANDOM_FOREST:choice;
 }
 public CrackClassifierChoice choice(){return choice;}
 public CrackAnalysis detect(Mat gray,PelletDetector.Result pellets,int sensitivity){
  boolean useNet=choice==CrackClassifierChoice.MOBILENET,compare=choice==CrackClassifierChoice.COMPARISON;
  if(model==null&&!useNet){List<PelletDetector.Detection> crops=new ArrayList<>();for(PelletDetector.Detection d:pellets.circles)crops.add(new PelletDetector.Detection(d.crackX,d.crackY,d.crackRadius,Math.PI*d.crackRadius*d.crackRadius,1,d.partial,d.crackNeedsReview));return new HeuristicCrackDetector().detect(gray,new PelletDetector.Result(crops,pellets.width,pellets.height,pellets.candidates,pellets.durationMs,pellets.candidateLimit),sensitivity);}
  if(gray.empty()||gray.type()!=CvType.CV_8UC1||gray.cols()!=pellets.width||gray.rows()!=pellets.height)throw new IllegalArgumentException("Foto e detecções incompatíveis.");
  long start=System.nanoTime();sensitivity=Math.max(0,Math.min(100,sensitivity));double center=.5+(50-sensitivity)*.004;
  List<CrackAnalysis.Entry> entries=new ArrayList<>();CrackFeatures extractor=new CrackFeatures();int id=0;
  for(PelletDetector.Detection d:pellets.circles){
   id++;CrackFeatures.Value value=extractor.extract(gray,d.crackX,d.crackY,d.crackRadius);
   double score=useNet?network.score(gray,d.crackX,d.crackY,d.crackRadius):model.score(value.features);
   double other=compare?network.score(gray,d.crackX,d.crackY,d.crackRadius):Double.NaN;
   boolean poor=(d.ellipseFitted&&d.aspectRatio()<.65)||d.partial||d.crackNeedsReview||d.crackRadius<20||value.spread<10||value.mean<15||value.mean>242;
   CrackAnalysis.Status status=poor?CrackAnalysis.Status.INCONCLUSIVE:score>=center+.1?CrackAnalysis.Status.POSSIBLE:score<=center-.1?CrackAnalysis.Status.NO_INDICATION:CrackAnalysis.Status.INCONCLUSIVE;
   byte[] localized=status==CrackAnalysis.Status.POSSIBLE?new CrackTrace().locate(value.normalized):new byte[CrackFeatures.N*CrackFeatures.N];
   List<Float> runs=new ArrayList<>();double step=2*d.crackRadius/(CrackFeatures.N-1.);
   for(int yy=0;yy<CrackFeatures.N;yy++)for(int xx=0;xx<CrackFeatures.N;xx++)if(d.normalizedDistance(d.crackX-d.crackRadius+xx*step,d.crackY-d.crackRadius+yy*step)>1)localized[yy*CrackFeatures.N+xx]=0;
   if(status==CrackAnalysis.Status.POSSIBLE)for(int y=0;y<CrackFeatures.N;y++)for(int x=0;x<CrackFeatures.N;x++)if(localized[y*CrackFeatures.N+x]!=0){int first=x;while(x+1<CrackFeatures.N&&localized[y*CrackFeatures.N+x+1]!=0)x++;runs.add((float)(d.crackX-d.crackRadius+first*step));runs.add((float)(d.crackY-d.crackRadius+y*step));runs.add((float)(d.crackX-d.crackRadius+(x+1)*step));runs.add((float)(d.crackY-d.crackRadius+y*step));}
   float[] marks=new float[runs.size()];for(int i=0;i<marks.length;i++)marks[i]=runs.get(i);
   entries.add(compare?new CrackAnalysis.Entry(id,status,marks,score,CrackModel.ID,other,network.id):new CrackAnalysis.Entry(id,status,marks,score,useNet?network.id:CrackModel.ID));
  }
  return new CrackAnalysis(pellets.width,pellets.height,sensitivity,(System.nanoTime()-start)/1_000_000,entries);
 }
}
