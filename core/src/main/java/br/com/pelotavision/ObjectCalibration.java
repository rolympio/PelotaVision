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
  /** 0.17.0: outras esferas padrão (mesmo diâmetro) marcadas na mesma foto; a escala usa todas. */
  public final List<PelletDetector.Detection> extraSpheres;
  /** Correção de perspectiva: o tamanho aparente cresce (1 + k·ρ²) do centro (ρ=0) ao canto (ρ=1) da foto.
   *  originalPixelsPerMm é a escala no CENTRO; cada pelota usa a escala local. k=0 desliga. */
  public final double perspectiveK;
  public final String perspectiveSource;
  /** Desvio de cada esfera em relação ao modelo ajustado (%), na ordem de {@link #spheres()}; vazio sem esferas. */
  public final double[] sphereDeviationPct;
  private Reference(PelletDetector.Detection d,int fullId,int displayId,Measure measure,double realMm,boolean exclude,int ow,int oh,int w,int h,List<PelletDetector.Detection> extras,double defaultK){
   eligible(d);dimensions(ow,oh,w,h);if(fullId<1||displayId<1||measure==null||!Double.isFinite(realMm)||realMm<=0)throw new IllegalArgumentException("Informe uma medida real positiva em milímetros.");
   if(!Double.isFinite(defaultK)||defaultK<MIN_K||defaultK>MAX_K)throw new IllegalArgumentException(String.format(Locale.ROOT,"Correção de perspectiva k deve ficar entre %.2f e %.2f.",MIN_K,MAX_K));
   geometry=d;fullImageId=fullId;displayIdAtSelection=displayId;this.measure=measure;this.realMm=realMm;excludeFromStatistics=exclude;originalWidth=ow;originalHeight=oh;width=w;height=h;
   originalAxes=originalAxes(d,ow,oh,w,h);originalReferencePixels=measure==Measure.MAJOR_AXIS?originalAxes.major:measure==Measure.MINOR_AXIS?originalAxes.minor:Math.sqrt(originalAxes.major*originalAxes.minor);
   List<PelletDetector.Detection> ex=new ArrayList<>();
   if(extras!=null)for(PelletDetector.Detection e:extras){
    if(measure!=Measure.STANDARD_SPHERE)throw new IllegalArgumentException("Várias referências só valem para esferas padrão.");
    eligible(e);if(sameGeometryOf(d,e))continue;boolean dup=false;for(PelletDetector.Detection o:ex)if(sameGeometryOf(o,e))dup=true;if(!dup)ex.add(e);
   }
   extraSpheres=Collections.unmodifiableList(ex);
   // ppm_i = a·(1 + k·ρ_i²): com ≥3 esferas, ou 2 bem separadas do centro à borda, ajusta a e k; senão usa k informado.
   List<PelletDetector.Detection> all=spheres();int n=all.size();double[] r2=new double[n],ppm=new double[n];
   for(int i=0;i<n;i++){PelletDetector.Detection s=all.get(i);r2[i]=rho2(s.x,s.y,w,h);ppm[i]=(i==0?originalReferencePixels:equivalentPixels(s,ow,oh,w,h))/realMm;}
   double k=defaultK,a;String source=defaultK==0?"off":"user_k";
   double minR=Double.MAX_VALUE,maxR=-1;for(double v:r2){minR=Math.min(minR,v);maxR=Math.max(maxR,v);}
   if(n>=3||(n==2&&maxR-minR>=.15)){
    double sx=0,sy=0,sxx=0,sxy=0;for(int i=0;i<n;i++){sx+=r2[i];sy+=ppm[i];sxx+=r2[i]*r2[i];sxy+=r2[i]*ppm[i];}
    double den=n*sxx-sx*sx;
    if(den>1e-9){double b=(n*sxy-sx*sy)/den,a0=(sy-b*sx)/n;if(a0>0){k=Math.max(MIN_K,Math.min(MAX_K,b/a0));source=String.format(Locale.ROOT,"fitted_%d_spheres",n);}}
   }
   double sum=0;for(int i=0;i<n;i++)sum+=ppm[i]/(1+k*r2[i]);a=sum/n;
   perspectiveK=k;perspectiveSource=n>1&&source.equals("user_k")?String.format(Locale.ROOT,"mean_%d_spheres_user_k",n):n>1&&source.equals("off")?String.format(Locale.ROOT,"mean_%d_spheres",n):source;
   sphereDeviationPct=new double[measure==Measure.STANDARD_SPHERE?n:0];
   for(int i=0;i<sphereDeviationPct.length;i++)sphereDeviationPct[i]=100*(ppm[i]/(a*(1+k*r2[i]))-1);
   originalPixelsPerMm=a;if(!Double.isFinite(originalPixelsPerMm)||originalPixelsPerMm<=0)throw new IllegalArgumentException("A medida produz uma escala inválida.");
   id=UUID.randomUUID().toString();selectedAt=System.currentTimeMillis();
  }
  /** Primeira referência + esferas extras. */
  public List<PelletDetector.Detection> spheres(){List<PelletDetector.Detection> l=new ArrayList<>();l.add(geometry);l.addAll(extraSpheres);return l;}
  /** Escala local (px originais por mm) na posição (x, y) da imagem analisada. */
  public double localPixelsPerMm(double x,double y){return originalPixelsPerMm*(1+perspectiveK*rho2(x,y,width,height));}
  /** A detecção é uma das referências marcadas (para excluir das estatísticas). */
  public boolean covers(PelletDetector.Detection d){if(sameGeometry(d))return true;for(PelletDetector.Detection e:extraSpheres)if(sameGeometryOf(e,d))return true;return false;}
  /** Nova referência com mais uma esfera (mesmo diâmetro, mesmo k informado). */
  public Reference plusSphere(PelletDetector.Detection extra,double defaultK){List<PelletDetector.Detection> l=new ArrayList<>(extraSpheres);l.add(extra);return new Reference(geometry,fullImageId,displayIdAtSelection,measure,realMm,excludeFromStatistics,originalWidth,originalHeight,width,height,l,defaultK);}
  /** Resumo para a tela: k, origem, nº de esferas e concordância entre elas. */
  public String perspectiveSummary(){
   String src=perspectiveSource.startsWith("fitted")?"ajustado pelas esferas":perspectiveK==0?"desligada":"informado";
   String dev=Double.isFinite(maxSphereDeviationPct())?String.format(Locale.ROOT," · esferas concordam em ±%.1f%%",maxSphereDeviationPct()):"";
   return String.format(Locale.ROOT,"Perspectiva k=%.3f (%s) · %d esfera(s) · canto %+.1f%% vs centro%s",perspectiveK,src,spheres().size(),100*perspectiveK,dev);
  }
  /** Desvio máximo absoluto entre as esferas e o modelo (%), NaN com uma só. */
  public double maxSphereDeviationPct(){if(sphereDeviationPct.length<2)return Double.NaN;double m=0;for(double v:sphereDeviationPct)m=Math.max(m,Math.abs(v));return m;}
  public boolean matches(PelletDetector.Result all,int ow,int oh){
   if(all==null||all.width!=width||all.height!=height||ow!=originalWidth||oh!=originalHeight||fullImageId>all.circles.size())return false;
   PelletDetector.Detection d=all.circles.get(fullImageId-1);if(!d.ellipseFitted||d.partial||d.needsReview)return false;
   // Never search or bind to another candidate after reanalysis.
   return sameGeometry(d);
  }
  public boolean sameGeometry(PelletDetector.Detection d){return sameGeometryOf(geometry,d);}
  public String summary(){return measure.label+" = "+String.format(Locale.getDefault(),"%.3f",realMm)+" mm";}
  public double majorMm(){return originalAxes.major/localPixelsPerMm(geometry.x,geometry.y);}
  public double minorMm(){return originalAxes.minor/localPixelsPerMm(geometry.x,geometry.y);}
  public double equivalentMm(){return Math.sqrt(originalAxes.major*originalAxes.minor)/localPixelsPerMm(geometry.x,geometry.y);}
 }
 /** Limites da correção de perspectiva (por ρ² do centro ao canto). Testes de 11/11/2026: k≈0,06–0,09 com o celular a ~12 cm. */
 public static final double MIN_K=-.05,MAX_K=.3;
 /** ρ² = (distância ao centro / meia diagonal)²: 0 no centro, 1 no canto. */
 public static double rho2(double x,double y,int w,int h){double dx=x-w/2.0,dy=y-h/2.0;return (dx*dx+dy*dy)/((w*(double)w+h*(double)h)/4.0);}
 static double equivalentPixels(PelletDetector.Detection d,int ow,int oh,int w,int h){Axes a=originalAxes(d,ow,oh,w,h);return Math.sqrt(a.major*a.minor);}
 static boolean sameGeometryOf(PelletDetector.Detection geometry,PelletDetector.Detection d){
  if(!d.ellipseFitted||d.partial||d.needsReview)return false;
  double da=Math.abs(d.angleDeg-geometry.angleDeg)%180;da=Math.min(da,180-da);
  return Math.abs(d.x-geometry.x)<1e-6&&Math.abs(d.y-geometry.y)<1e-6&&Math.abs(d.semiMajor-geometry.semiMajor)<1e-6&&Math.abs(d.semiMinor-geometry.semiMinor)<1e-6&&(da<1e-6||Math.abs(d.semiMajor-d.semiMinor)<1e-6);
 }
 public static Reference select(PelletDetector.Detection d,int fullId,int displayId,Measure measure,double realMm,boolean exclude,int ow,int oh,int w,int h){return new Reference(d,fullId,displayId,measure,realMm,exclude,ow,oh,w,h,null,0);}
 /** 0.17.0: com esferas extras (mesmo diâmetro) e correção de perspectiva k (0 = desligada; ajustada se houver esferas suficientes). */
 public static Reference select(PelletDetector.Detection d,int fullId,int displayId,Measure measure,double realMm,boolean exclude,int ow,int oh,int w,int h,List<PelletDetector.Detection> extraSpheres,double perspectiveK){return new Reference(d,fullId,displayId,measure,realMm,exclude,ow,oh,w,h,extraSpheres,perspectiveK);}
 public static List<Integer> sampleIndices(PelletDetector.Result all,InterestRegion roi,Reference reference){
  List<Integer> ids=new ArrayList<>();for(int i=0;i<all.circles.size();i++)if((roi==null||roi.includes(all.circles.get(i)))&&!(reference!=null&&reference.excludeFromStatistics&&reference.covers(all.circles.get(i))))ids.add(i);return ids;
 }
 public static int excludedByReference(PelletDetector.Result all,InterestRegion roi,Reference ref){if(ref==null||!ref.excludeFromStatistics)return 0;int n=0;for(PelletDetector.Detection d:all.circles)if((roi==null||roi.includes(d))&&ref.covers(d))n++;return n;}
}
