import br.com.pelotavision.*;
import java.nio.file.*;import java.io.*;import java.util.*;import org.opencv.core.*;import org.opencv.imgcodecs.Imgcodecs;
public class ModelCheck {
 public static void main(String[] args)throws Exception{
  nu.pattern.OpenCV.loadLocally();Core.setNumThreads(2);CrackModel model;try(InputStream in=new FileInputStream(args[0])){model=new CrackModel(in);}
  List<String> vectors=Files.readAllLines(Path.of(args[1])),samples=Files.readAllLines(Path.of(args[2]));double maxForest=0,maxFeature=0,maxScore=0;int changes=0;CrackFeatures extractor=new CrackFeatures();Mat image=null;String last="";
  for(int i=0;i<vectors.size();i++){
   String[] values=vectors.get(i).split(","),s=samples.get(i).split(";");float[] f=new float[72];for(int j=0;j<72;j++)f[j]=Float.parseFloat(values[j]);double expected=Double.parseDouble(values[72]);maxForest=Math.max(maxForest,Math.abs(model.score(f)-expected));
   if(!last.equals(s[0])){if(image!=null)image.release();image=Imgcodecs.imread(s[0],0);last=s[0];}
   CrackFeatures.Value v=extractor.extract(image,Double.parseDouble(s[1]),Double.parseDouble(s[2]),Double.parseDouble(s[3]));for(int j=0;j<72;j++)maxFeature=Math.max(maxFeature,Math.abs(v.features[j]-f[j]));if(s[4].equals("gray20")&&s[6].equals("1")){double x=Double.parseDouble(s[1]),y=Double.parseDouble(s[2]),r=Double.parseDouble(s[3]);CrackAnalysis traced=new CrackDetector(model).detect(image,new PelletDetector.Result(Arrays.asList(new PelletDetector.Detection(x,y,r,Math.PI*r*r,1,false,false)),image.cols(),image.rows(),1,0,false),50);if(traced.entries.get(0).status!=CrackAnalysis.Status.POSSIBLE||traced.entries.get(0).markedRuns().length==0)throw new AssertionError("Visible reference crack lost its trace");}
   double score=model.score(v.features);maxScore=Math.max(maxScore,Math.abs(score-expected));if((score>=.5)!=(expected>=.5))changes++;
  }
  if(image!=null)image.release();System.out.println("samples="+vectors.size()+" forest_max_error="+maxForest+" feature_max_error="+maxFeature+" end_to_end_score_max_error="+maxScore+" classification_changes="+changes);
  if(maxForest>1e-12||maxFeature>1e-5||maxScore>1e-6)throw new AssertionError("Python/Java inference mismatch");
  try{new CrackModel(new ByteArrayInputStream(new byte[16]));throw new AssertionError("Malformed model accepted");}catch(IOException expected){}
  Mat blank=new Mat(300,400,CvType.CV_8UC1,new Scalar(180));PelletDetector.Detection d=new PelletDetector.Detection(150,150,50,Math.PI*2500,1,false,false);
  CrackAnalysis a=new CrackDetector(model).detect(blank,new PelletDetector.Result(Arrays.asList(d),400,300,1,0,false),50);if(a.entries.get(0).status!=CrackAnalysis.Status.INCONCLUSIVE)throw new AssertionError("Uniform surface must be inconclusive");blank.release();
  CrackAnalysis.Entry e=a.entries.get(0).reviewed(CrackAnalysis.Status.POSSIBLE).numbered(4);if(e.modelScore!=a.entries.get(0).modelScore||!e.manuallyReviewed||e.restore().status!=a.entries.get(0).status)throw new AssertionError("Review lost provenance");
  System.out.println("PASS malformed model, low contrast abstention, review provenance");
 }
}
