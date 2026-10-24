import br.com.pelotavision.Granulometry;
import java.util.*;
public class GranulometryCheck {
    static void close(double e,double a){if(!Double.isFinite(a)||Math.abs(e-a)>1e-8)throw new AssertionError(e+" != "+a);}
    static void require(boolean b){if(!b)throw new AssertionError();}
    static Granulometry.Item item(double d){return new Granulometry.Item(d,false,false);}
    static Granulometry.Distribution measure(List<Granulometry.Item> items,Granulometry.Settings cfg){return Granulometry.compute(items,cfg,4000,3000,2000,1500);}
    public static void main(String[] args){
        Granulometry.Settings cfg=new Granulometry.Settings(20,1,true,false);
        List<Granulometry.Item> sample=Arrays.asList(item(82),item(92),item(102),item(112),item(128),item(138),item(148),item(158));
        Granulometry.Distribution d=measure(sample,cfg);
        close(10,d.analyzedPixelsPerMm);close(12,d.meanMm);close(8.2,d.minMm);close(15.8,d.maxMm);
        require(d.binCount()==8);close(8,d.lower(0));close(16,d.upper(7));
        double sum=0;for(int i=0;i<8;i++){require(d.count(i)==1);close(8+i,d.lower(i));close(1,d.upper(i)-d.lower(i));sum+=d.percent(i);}close(100,sum);
        Granulometry.Distribution half=measure(sample,new Granulometry.Settings(20,.5,true,false));
        close(d.d50,half.d50);close(d.meanMm,half.meanMm);close(d.minMm,half.minMm);require(half.binCount()>d.binCount());
        Granulometry.Distribution boundaries=measure(Arrays.asList(item(80),item(90),item(120),item(160)),cfg);
        require(boundaries.count(0)==1&&boundaries.count(1)==1&&boundaries.count(4)==1&&boundaries.count(7)==1&&boundaries.count(2)==0);
        Granulometry.Distribution ref=measure(sample,Granulometry.Settings.reference(240,12,1,true,false));close(d.d50,ref.d50);close(20,ref.settings.originalPixelsPerMm);
        Granulometry.Distribution estimated=measure(sample,Granulometry.Settings.estimatedMean(12,1,true,false));close(12,estimated.meanMm);close(20,estimated.settings.originalPixelsPerMm);require(estimated.settings.estimated());
        Granulometry.Distribution resized=Granulometry.compute(Arrays.asList(item(164),item(184),item(204),item(224),item(256),item(276),item(296),item(316)),cfg,4000,3000,4000,3000);close(d.meanMm,resized.meanMm);close(d.d90,resized.d90);
        Granulometry.Distribution excluded=measure(Arrays.asList(item(100),new Granulometry.Item(300,true,false),new Granulometry.Item(400,false,true),item(0),item(Double.NaN)),new Granulometry.Settings(20,1,true,true));require(excluded.included==1&&excluded.excluded==4);close(10,excluded.meanMm);
        Granulometry.Distribution empty=measure(Collections.emptyList(),cfg);require(empty.binCount()==0&&Double.isNaN(empty.meanMm));
        Granulometry.Distribution far=measure(Arrays.asList(item(15000)),new Granulometry.Settings(20,1,true,false));require(far.binCount()==1);close(1500,far.lower(0));close(1,far.settings.binWidthMm);
        Granulometry.Distribution wrongScale=measure(sample,new Granulometry.Settings(400,1,true,false));require(wrongScale.binCount()==1);close(.6,wrongScale.meanMm); // width never invents calibration
        Granulometry.Distribution decimal=Granulometry.compute(Arrays.asList(item(.3),item(.4)),new Granulometry.Settings(1,.1,true,false),100,100,100,100);require(decimal.binCount()==1&&decimal.count(0)==2);close(.3,decimal.lower(0));
        boolean rejected=false;try{measure(Arrays.asList(item(1),item(60000)),cfg);}catch(IllegalArgumentException e){rejected=true;}require(rejected);
        rejected=false;try{new Granulometry.Settings(0,1,true,false);}catch(IllegalArgumentException e){rejected=true;}require(rejected);
        System.out.println("PASS: 8–16 mm axis, mean 12 mm, eight fixed 1 mm classes, gaps, boundaries, percentiles independent of class width, resizing, reference calibration, explicitly estimated mean, exclusions, empty sample, large offset, wrong scale, decimal roundoff, bounded class count and invalid input.");
    }
}
