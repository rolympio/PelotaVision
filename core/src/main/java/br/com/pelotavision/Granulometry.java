package br.com.pelotavision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Fixed-width classes over the observed range; bin width never scales diameters. */
public final class Granulometry {
    public static final int MAX_BINS=5000;
    public static final class Settings {
        public final double originalPixelsPerMm,binWidthMm,knownMeanMm,referencePixels,referenceMm;
        public final boolean excludePartial,excludeReview;
        public final String calibrationSource;
        public final ObjectCalibration.Reference selectedObject;
        public final BeltCalibration.Reference beltReference;
        public final ContainerCalibration.Reference containerReference;
        public Settings(double pixelsPerMm,double binWidth,boolean partial,boolean review) {
            this(pixelsPerMm,binWidth,partial,review,"pixels_per_mm",Double.NaN,Double.NaN,Double.NaN);
        }
        private Settings(double scale,double binWidth,boolean partial,boolean review,String source,double mean,double pixels,double mm) {this(scale,binWidth,partial,review,source,mean,pixels,mm,null);}
        private Settings(double scale,double binWidth,boolean partial,boolean review,String source,double mean,double pixels,double mm,ObjectCalibration.Reference selectedObject) {
            this(scale,binWidth,partial,review,source,mean,pixels,mm,selectedObject,null);
        }
        private Settings(double scale,double binWidth,boolean partial,boolean review,String source,double mean,double pixels,double mm,ObjectCalibration.Reference selectedObject,BeltCalibration.Reference beltReference) {
            this(scale,binWidth,partial,review,source,mean,pixels,mm,selectedObject,beltReference,null);
        }
        private Settings(double scale,double binWidth,boolean partial,boolean review,String source,double mean,double pixels,double mm,ObjectCalibration.Reference selectedObject,BeltCalibration.Reference beltReference,ContainerCalibration.Reference containerReference) {
            this.selectedObject=selectedObject;this.beltReference=beltReference;this.containerReference=containerReference;
            positive(scale);positive(binWidth);
            originalPixelsPerMm=scale;binWidthMm=binWidth;excludePartial=partial;excludeReview=review;
            calibrationSource=source;knownMeanMm=mean;referencePixels=pixels;referenceMm=mm;
        }
        public static Settings selectedObject(ObjectCalibration.Reference reference,double binWidth,boolean partial,boolean review){
            if(reference==null)throw new IllegalArgumentException("Selecione a referência na foto.");
            return new Settings(reference.originalPixelsPerMm,binWidth,partial,review,"user_selected_object",Double.NaN,reference.originalReferencePixels,reference.realMm,reference);
        }
        public Settings withHistogram(double binWidth,boolean partial,boolean review){return new Settings(originalPixelsPerMm,binWidth,partial,review,calibrationSource,knownMeanMm,referencePixels,referenceMm,selectedObject,beltReference,containerReference);}
        public static Settings belt(BeltCalibration.Reference reference,double binWidth,boolean partial,boolean review){
            if(reference==null)throw new IllegalArgumentException("Marque as bordas da correia.");
            return new Settings(reference.originalPixelsPerMm,binWidth,partial,review,"belt_width_user_confirmed",Double.NaN,reference.originalWidthPixels,reference.realWidthMm,null,reference);
        }
        public static Settings container(ContainerCalibration.Reference reference,double binWidth,boolean partial,boolean review){
            if(reference==null)throw new IllegalArgumentException("Marque a borda do recipiente.");
            return new Settings(reference.originalPixelsPerMm,binWidth,partial,review,"container_inner_diameter_user_confirmed",Double.NaN,reference.originalDiameterPixels,reference.realInnerDiameterMm,null,null,reference);
        }
        public static Settings reference(double pixels,double mm,double binWidth,boolean partial,boolean review) {
            positive(pixels);positive(mm);
            return new Settings(pixels/mm,binWidth,partial,review,"reference_length",Double.NaN,pixels,mm);
        }
        public static Settings estimatedMean(double mean,double binWidth,boolean partial,boolean review) {
            positive(mean);
            return new Settings(1,binWidth,partial,review,"assumed_mean",mean,Double.NaN,Double.NaN);
        }
        public static Settings pixels(double binWidth){return new Settings(1,binWidth,true,false,"pixels_only",Double.NaN,Double.NaN,Double.NaN);}
        public boolean pixelsOnly(){return "pixels_only".equals(calibrationSource);}
        public String unit(){return pixelsOnly()?"px":"mm";}
        public boolean estimated(){return calibrationSource.startsWith("assumed_mean");}
        public double analyzedPixelsPerMm(int ow,int oh,int width,int height) {
            if(beltReference!=null&&!beltReference.matches(ow,oh,width,height))throw new IllegalArgumentException("A referência da correia pertence a outra imagem. Recalibre.");
            if(containerReference!=null&&!containerReference.matches(ow,oh,width,height))throw new IllegalArgumentException("A borda do recipiente pertence a outra imagem. Recalibre.");
            double value=pixelsOnly()?1:originalPixelsPerMm*resizeRatio(ow,oh,width,height);positive(value);return value;
        }
    }
    private static void positive(double v) {
        if(!Double.isFinite(v)||v<=0)throw new IllegalArgumentException("Escala, referência e classe devem ser números positivos.");
    }
    private static double resizeRatio(int ow,int oh,int width,int height) {
        if(ow<=0||oh<=0||width<=0||height<=0)throw new IllegalArgumentException("Dimensões inválidas.");
        return Math.sqrt((double)width/ow*((double)height/oh));
    }
    public static final class Item {
        public final double diameterPx;
        public final boolean partial,review;
        /** Posição na imagem analisada (para a correção de perspectiva); NaN = desconhecida (sem correção). */
        public final double x,y;
        public Item(double diameter,boolean partial,boolean review) {this(diameter,partial,review,Double.NaN,Double.NaN);}
        public Item(double diameter,boolean partial,boolean review,double x,double y) {diameterPx=diameter;this.partial=partial;this.review=review;this.x=x;this.y=y;}
        public static Item of(PelletDetector.Detection d){return new Item(2*d.radius,d.partial,d.needsReview,d.x,d.y);}
    }
    /** Fator de perspectiva da escala de referência (1 sem correção ou sem posição). */
    static double perspectiveFactor(Settings s,double x,double y,int width,int height){
        double k=s.selectedObject==null?0:s.selectedObject.perspectiveK;
        if(k==0||!Double.isFinite(x)||!Double.isFinite(y)||width<=0||height<=0)return 1;
        return 1+k*ObjectCalibration.rho2(x,y,width,height);
    }
    public static final class Distribution {
        public final Settings settings;
        public final double analyzedPixelsPerMm,d10,d50,d90,requestedBinWidthMm,meanMm,minMm,maxMm;
        public final int included,excluded,originalWidth,originalHeight;
        /** Dimensões da imagem analisada (posição das pelotas para a correção de perspectiva). */
        public final int analyzedWidth,analyzedHeight;
        private final int[] counts;
        private final double firstIndex;
        /** Diâmetros incluídos, ordenados (mesma unidade de d10/d50). Base do ajuste Rosin–Rammler. */
        private final double[] sizes;
        private Distribution(Settings cfg,double scale,double first,int[] bins,List<Double> diameters,int excluded,int ow,int oh,int w,int h) {
            analyzedWidth=w;analyzedHeight=h;settings=cfg;analyzedPixelsPerMm=scale;counts=bins.clone();included=diameters.size();this.excluded=excluded;
            requestedBinWidthMm=cfg.binWidthMm;firstIndex=first;
            originalWidth=ow;originalHeight=oh;Collections.sort(diameters);
            sizes=new double[diameters.size()];for(int i=0;i<sizes.length;i++)sizes[i]=diameters.get(i);
            d10=percentile(diameters,.10);d50=percentile(diameters,.50);d90=percentile(diameters,.90);
            minMm=included==0?Double.NaN:diameters.get(0);maxMm=included==0?Double.NaN:diameters.get(included-1);
            double mean=0;for(double v:diameters)mean+=v/included;meanMm=included==0?Double.NaN:mean;
        }
        public int binCount(){return counts.length;}
        /** Cópia dos diâmetros incluídos, em ordem crescente. */
        public double[] sizes(){return sizes.clone();}
        public int count(int index){return counts[index];}
        public double lower(int index){return (firstIndex+index)*settings.binWidthMm;}
        public double upper(int index){return lower(index+1);}
        public double percent(int index){return included==0?0:100.*counts[index]/included;}
        public boolean includes(Item item) {return accepted(item,settings);}
        /** Sem posição: escala do centro (sem correção de perspectiva). Prefira {@link #diameterMm(Item)}. */
        public double diameterMm(double pixels){return pixels/analyzedPixelsPerMm;}
        /** Diâmetro em mm com a escala local (perspectiva) na posição da pelota. */
        public double diameterMm(Item item){return item.diameterPx/(analyzedPixelsPerMm*perspectiveFactor(settings,item.x,item.y,analyzedWidth,analyzedHeight));}
        public double diameterMm(PelletDetector.Detection d){return diameterMm(Item.of(d));}
        /** Área em mm² com a escala local. */
        public double areaMm2(PelletDetector.Detection d){double s=analyzedPixelsPerMm*perspectiveFactor(settings,d.x,d.y,analyzedWidth,analyzedHeight);return d.area/(s*s);}
        /** Class of an included diameter, with exactly the rule used to fill {@link #count(int)}; -1 if outside. */
        public int binIndex(double mm) {
            if(!Double.isFinite(mm)||mm<=0||counts.length==0)return -1;
            int index=(int)Math.min(counts.length-1,classIndex(mm,settings.binWidthMm)-firstIndex);
            return index<0?-1:index;
        }
    }
    private static boolean accepted(Item item,Settings settings) {
        return Double.isFinite(item.diameterPx)&&item.diameterPx>0&&
            !(settings.excludePartial&&item.partial)&&!(settings.excludeReview&&item.review);
    }
    public static Distribution compute(List<Item> items,Settings settings,int ow,int oh,int width,int height) {
        double ratio=resizeRatio(ow,oh,width,height);
        List<Double> pixels=new ArrayList<>();List<Item> kept=new ArrayList<>();int excluded=0;
        for(Item item:items){if(accepted(item,settings)){pixels.add(item.diameterPx);kept.add(item);}else excluded++;}
        if("assumed_mean".equals(settings.calibrationSource)) {
            if(pixels.isEmpty())throw new IllegalArgumentException("Não há pelotas incluídas para estimar a escala pela média.");
            double meanPixels=0;for(double v:pixels)meanPixels+=v/pixels.size();
            settings=new Settings(meanPixels/settings.knownMeanMm/ratio,settings.binWidthMm,settings.excludePartial,settings.excludeReview,
                "assumed_mean_locked",settings.knownMeanMm,Double.NaN,Double.NaN);
        }
        double scale=settings.analyzedPixelsPerMm(ow,oh,width,height),min=Double.POSITIVE_INFINITY,max=0;
        List<Double> diameters=new ArrayList<>();
        for(Item it:kept){double mm=it.diameterPx/(scale*perspectiveFactor(settings,it.x,it.y,width,height));positive(mm);diameters.add(mm);min=Math.min(min,mm);max=Math.max(max,mm);}
        double first=diameters.isEmpty()?0:classIndex(min,settings.binWidthMm);
        double last=diameters.isEmpty()?-1:Math.max(first,Math.ceil(boundaryQuotient(max,settings.binWidthMm))-1),number=last-first+1;
        if(!Double.isFinite(first)||!Double.isFinite(last)||number>MAX_BINS||number<0||
                (!diameters.isEmpty()&&(first+1)*settings.binWidthMm<=first*settings.binWidthMm))
            throw new IllegalArgumentException("Mais de 5000 classes ou largura muito pequena. Aumente a classe; ela não será alterada automaticamente.");
        int[] bins=new int[(int)number];
        // The final upper edge is inclusive, as in standard numeric histograms.
        for(double mm:diameters)bins[Math.min(bins.length-1,(int)(classIndex(mm,settings.binWidthMm)-first))]++;
        return new Distribution(settings,scale,first,bins,diameters,excluded,ow,oh,width,height);
    }
    private static double boundaryQuotient(double diameter,double width) {
        double quotient=diameter/width,nearest=Math.rint(quotient);
        // Correct only floating point roundoff at a boundary (e.g. 0.3/0.1).
        if(Math.abs(quotient-nearest)<=4*Math.ulp(quotient))quotient=nearest;
        return quotient;
    }
    private static double classIndex(double diameter,double width){return Math.floor(boundaryQuotient(diameter,width));}
    private static double percentile(List<Double> sorted,double q) {
        if(sorted.isEmpty())return Double.NaN;
        double position=(sorted.size()-1)*q;int lo=(int)Math.floor(position),hi=(int)Math.ceil(position);
        return sorted.get(lo)+(sorted.get(hi)-sorted.get(lo))*(position-lo);
    }
}
