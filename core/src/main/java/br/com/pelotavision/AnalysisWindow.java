package br.com.pelotavision;
import java.util.*;
import org.opencv.core.*;
/** ROI input pixels at 1:1 scale. Results are translated back to full upright preview coordinates. */
public final class AnalysisWindow {
 public final int fullWidth,fullHeight,left,top,width,height;
 public final InterestRegion region,localRegion;
 public AnalysisWindow(int w,int h,InterestRegion roi){
  if(w<1||h<1||roi!=null&&(roi.right>w+1e-7||roi.bottom>h+1e-7))throw new IllegalArgumentException("Área fora da imagem.");
  fullWidth=w;fullHeight=h;region=roi;
  left=roi==null?0:(int)Math.floor(roi.left);top=roi==null?0:(int)Math.floor(roi.top);
  width=(roi==null?w:(int)Math.ceil(roi.right))-left;height=(roi==null?h:(int)Math.ceil(roi.bottom))-top;
  localRegion=roi==null?null:new InterestRegion(roi.shape,roi.left-left,roi.top-top,roi.right-left,roi.bottom-top);
 }
 public boolean sameScope(AnalysisWindow other){return other!=null&&fullWidth==other.fullWidth&&fullHeight==other.fullHeight&&sameRegion(region,other.region);}
 public static boolean sameRegion(InterestRegion a,InterestRegion b){return a==b||a!=null&&b!=null&&a.shape==b.shape&&Math.abs(a.left-b.left)<1e-7&&Math.abs(a.top-b.top)<1e-7&&Math.abs(a.right-b.right)<1e-7&&Math.abs(a.bottom-b.bottom)<1e-7;}
 public void requireDetectable(){if(width<32||height<32)throw new IllegalArgumentException("Aumente a ROI: largura e altura devem ter pelo menos 32 pixels.");}
 /** Outside a circular/fractional ROI is filled only from its own median, never from excluded pixels. */
 public Mat cropGray(Mat full){
  if(full.cols()!=fullWidth||full.rows()!=fullHeight||full.type()!=CvType.CV_8UC1)throw new IllegalArgumentException("Foto e ROI incompatíveis.");
  Mat sub=full.submat(top,top+height,left,left+width),crop;try{crop=sub.clone();}finally{sub.release();}
  if(localRegion!=null){byte[] pixels=new byte[width*height];crop.get(0,0,pixels);int[] counts=new int[256];int n=0;
   for(int y=0;y<height;y++)for(int x=0;x<width;x++)if(localRegion.contains(x+.5,y+.5)){counts[pixels[y*width+x]&255]++;n++;}
   int median=0,sum=0;for(;median<255;median++){sum+=counts[median];if(sum>=(n+1)/2)break;}
   for(int y=0;y<height;y++)for(int x=0;x<width;x++)if(!localRegion.contains(x+.5,y+.5))pixels[y*width+x]=(byte)median;
   crop.put(0,0,pixels);
  }return crop;
 }
 public PelletDetector.Result filterLocal(PelletDetector.Result input){
  if(input.width!=width||input.height!=height)throw new IllegalArgumentException("Resultado fora da ROI.");
  if(localRegion==null)return input;List<PelletDetector.Detection> ds=new ArrayList<>();
  for(PelletDetector.Detection d:input.circles)if(localRegion.includes(d)){
   // RF features use a square crop. A square crossing the circular ROI must remain inconclusive.
   double r=d.crackRadius;boolean safe=localRegion.contains(d.crackX-r,d.crackY-r)&&localRegion.contains(d.crackX+r,d.crackY-r)&&localRegion.contains(d.crackX-r,d.crackY+r)&&localRegion.contains(d.crackX+r,d.crackY+r);
   ds.add(d.translated(0,0,!safe));
  }return new PelletDetector.Result(ds,width,height,input.candidates,input.durationMs,input.candidateLimit,input.method);
 }
 public PelletDetector.Result toGlobal(PelletDetector.Result input){
  List<PelletDetector.Detection> ds=new ArrayList<>();for(PelletDetector.Detection d:input.circles)ds.add(d.translated(left,top,false));
  return new PelletDetector.Result(ds,fullWidth,fullHeight,input.candidates,input.durationMs,input.candidateLimit,input.method+(region==null?"":"+active_roi_crop_v1"));
 }
 public CrackAnalysis toGlobal(CrackAnalysis input){
  List<CrackAnalysis.Entry> out=new ArrayList<>();for(CrackAnalysis.Entry e:input.entries){float[] runs=e.markedRuns();for(int i=0;i<runs.length;i+=4){runs[i]+=left;runs[i+1]+=top;runs[i+2]+=left;runs[i+3]+=top;}CrackAnalysis.Entry mapped=new CrackAnalysis.Entry(e.pelletId,e.automatic,runs,e.modelScore,e.method);out.add(e.manuallyReviewed?mapped.reviewed(e.status):mapped);}
  return new CrackAnalysis(fullWidth,fullHeight,input.sensitivity,input.durationMs,out);
 }
 public boolean keepsReference(ObjectCalibration.Reference ref,PelletDetector.Result mapped,int ow,int oh){
  if(ref==null)return true;if(ref.width!=fullWidth||ref.height!=fullHeight||ref.originalWidth!=ow||ref.originalHeight!=oh)return false;
  // A measured reference outside the analyzed crop remains an archived, user-selected scale reference.
  if(region!=null&&!region.includes(ref.geometry))return true;
  for(PelletDetector.Detection d:mapped.circles)if(ref.sameGeometry(d))return true;return false;
 }
}
