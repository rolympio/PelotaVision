package br.com.pelotavision;
import java.util.Locale;
/** Number-weighted arithmetic mean of projected ellipse minor/major ratios; never 3-D sphericity. */
public final class ShapeStatistics {
 public final int included,excluded;public final double mean;
 private ShapeStatistics(int n,int ex,double mean){included=n;excluded=ex;this.mean=mean;}
 public static boolean includes(PelletDetector.Detection d){return d.ellipseFitted&&!d.partial&&!d.needsReview&&Double.isFinite(d.aspectRatio())&&d.aspectRatio()>0&&d.aspectRatio()<=1;}
 public static ShapeStatistics of(PelletDetector.Result result){int n=0;double sum=0;for(PelletDetector.Detection d:result.circles)if(includes(d)){n++;sum+=d.aspectRatio();}return new ShapeStatistics(n,result.circles.size()-n,n==0?Double.NaN:sum/n);}
 public String label(){return String.format(Locale.getDefault(),"Esfericidade aparente 2D (b/a): %s · n=%d",Double.isFinite(mean)?String.format(Locale.getDefault(),"%.3f",mean):"—",included);}
}
