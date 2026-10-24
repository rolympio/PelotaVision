package br.com.pelotavision;
import java.util.*;
/** Scale comes only from a user-selected object and its externally supplied dimension. */
public final class ObjectCalibration {
 private ObjectCalibration(){}
 public enum Measure {
  MAJOR_AXIS("Pelota · eixo maior"),MINOR_AXIS("Pelota · eixo menor"),STANDARD_SPHERE("Esfera padrão · diâmetro");
  public final String label;Measure(String label){this.label=label;}
 }
 public static final class Axes {
  public final double major,minor,angleDeg;
  Axes(double major,double minor,double angle){this.major=major;this.minor=minor;angleDeg=((angle%180)+180)%180;}
 }
 /** Full DIAMETERS of the ellipse mapped back to original upright image coordinates. */
 public static Axes originalAxes(PelletDetector.Detection d,int ow,int oh,int w,int h){
  dimensions(ow,oh,w,h);double sx=ow/(double)w,sy=oh/(double)h,t=Math.toRadians(d.angleDeg),c=Math.cos(t),s=Math.sin(t),a=d.semiMajor*d.semiMajor,b=d.semiMinor*d.semiMinor;
  double xx=(a*c*c+b*s*s)*sx*sx,yy=(a*s*s+b*c*c)*sy*sy,xy=(a-b)*c*s*sx*sy,root=Math.hypot(xx-yy,2*xy);
  return new Axes(2*Math.sqrt(Math.max(0,(xx+yy+root)/2)),2*Math.sqrt(Math.max(0,(xx+yy-root)/2)),Math.toDegrees(.5*Math.atan2(2*xy,xx-yy)));
 }
 private static void dimensions(int ow,int oh,int w,int h){if(ow<=0||oh<=0||w<=0||h<=0)throw new IllegalArgumentException("Dimensões da foto indisponíveis.");}
 public static void eligible(PelletDetector.Detection d){if(!d.ellipseFitted||d.partial||d.needsReview)throw new IllegalArgumentException("Escolha uma elipse inteira, ajustada e sem alerta para calibrar.");}
 public static final class Reference {
  public final String id;public final long selectedAt;
  public final int fullImageId,displayIdAtSelection,originalWidth,originalHeight,width,height;
  public final PelletDetector.Detection geometry;
  public final Measure measure;public final double realMm,originalReferencePixels,originalPixelsPerMm;
  public final Axes originalAxes;
  public final boolean excludeFromStatistics;
  private Reference(PelletDetector.Detection d,int fullId,int displayId,Measure measure,double realMm,boolean exclude,int ow,int oh,int w,int h){
   eligible(d);dimensions(ow,oh,w,h);if(fullId<1||displayId<1||measure==null||!Double.isFinite(realMm)||realMm<=0)throw new IllegalArgumentException("Informe uma medida real positiva em milímetros.");
   geometry=d;fullImageId=fullId;displayIdAtSelection=displayId;this.measure=measure;this.realMm=realMm;excludeFromStatistics=exclude;originalWidth=ow;originalHeight=oh;width=w;height=h;
   originalAxes=originalAxes(d,ow,oh,w,h);originalReferencePixels=measure==Measure.MAJOR_AXIS?originalAxes.major:measure==Measure.MINOR_AXIS?originalAxes.minor:Math.sqrt(originalAxes.major*originalAxes.minor);
   originalPixelsPerMm=originalReferencePixels/realMm;if(!Double.isFinite(originalPixelsPerMm)||originalPixelsPerMm<=0)throw new IllegalArgumentException("A medida produz uma escala inválida.");
   id=UUID.randomUUID().toString();selectedAt=System.currentTimeMillis();
  }
  public boolean matches(PelletDetector.Result all,int ow,int oh){
   if(all==null||all.width!=width||all.height!=height||ow!=originalWidth||oh!=originalHeight||fullImageId>all.circles.size())return false;
   PelletDetector.Detection d=all.circles.get(fullImageId-1);if(!d.ellipseFitted||d.partial||d.needsReview)return false;
   // Never search or bind to another candidate after reanalysis.
   return sameGeometry(d);
  }
  public boolean sameGeometry(PelletDetector.Detection d){
   if(!d.ellipseFitted||d.partial||d.needsReview)return false;
   double da=Math.abs(d.angleDeg-geometry.angleDeg)%180;da=Math.min(da,180-da);
   return Math.abs(d.x-geometry.x)<1e-6&&Math.abs(d.y-geometry.y)<1e-6&&Math.abs(d.semiMajor-geometry.semiMajor)<1e-6&&Math.abs(d.semiMinor-geometry.semiMinor)<1e-6&&(da<1e-6||Math.abs(d.semiMajor-d.semiMinor)<1e-6);
  }
  public String summary(){return measure.label+" = "+String.format(Locale.getDefault(),"%.3f",realMm)+" mm";}
  public double majorMm(){return originalAxes.major/originalPixelsPerMm;}
  public double minorMm(){return originalAxes.minor/originalPixelsPerMm;}
  public double equivalentMm(){return Math.sqrt(originalAxes.major*originalAxes.minor)/originalPixelsPerMm;}
 }
 public static Reference select(PelletDetector.Detection d,int fullId,int displayId,Measure measure,double realMm,boolean exclude,int ow,int oh,int w,int h){return new Reference(d,fullId,displayId,measure,realMm,exclude,ow,oh,w,h);}
 public static List<Integer> sampleIndices(PelletDetector.Result all,InterestRegion roi,Reference reference){
  List<Integer> ids=new ArrayList<>();for(int i=0;i<all.circles.size();i++)if((roi==null||roi.includes(all.circles.get(i)))&&!(reference!=null&&reference.excludeFromStatistics&&reference.sameGeometry(all.circles.get(i))))ids.add(i);return ids;
 }
 public static int excludedByReference(PelletDetector.Result all,InterestRegion roi,Reference ref){if(ref==null||!ref.excludeFromStatistics)return 0;for(PelletDetector.Detection d:all.circles)if((roi==null||roi.includes(d))&&ref.sameGeometry(d))return 1;return 0;}
}
