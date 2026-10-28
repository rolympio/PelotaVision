package br.com.pelotavision;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Native LoG filters and a 4-connected bucket-queue watershed.
 * No Android types: this detector can also be exercised on desktop JNI.
 * Results describe visible projected areas, not physical sphere diameters.
 */
public final class PelletDetector {
    public static final class Parameters {
        public final double minRadiusFraction, maxRadiusFraction, threshold;
        /** Cena de correia: pelotas pequenas e numerosas; muda resolução de trabalho, bandas e limite de objetos. */
        public final boolean belt;
        public final int maxObjects;
        public static final int BENCH_MAX_OBJECTS = 600, BELT_MAX_OBJECTS = 5000;
        public Parameters(double min, double max, double threshold) { this(min, max, threshold, false); }
        public Parameters(double min, double max, double threshold, boolean belt) {
            if (!(min > 0 && max > min && max < .3 && threshold > 0 && threshold < 1))
                throw new IllegalArgumentException("Invalid detection parameters");
            minRadiusFraction = min; maxRadiusFraction = max; this.threshold = threshold;
            this.belt = belt; maxObjects = belt ? BELT_MAX_OBJECTS : BENCH_MAX_OBJECTS;
        }
        /** Bancada respeita o pedido; correia limita o lado de trabalho a 1600 px, sem ampliar fotos menores. */
        public int workSide(int requested) { return belt ? 1600 : requested; }
        public static Parameters automatic() { return new Parameters(.012, .15, .045); }
        public static Parameters medium() { return new Parameters(.021, .060, .045); }
    }
    public static final class Detection {
        /** radius is the equal-area radius; axes below are SEMI-axes in upright photo pixels. */
        public final double x,y,radius,area,roundness,semiMajor,semiMinor,angleDeg;
        public final boolean partial,needsReview,ellipseFitted;
        public final double fitSupport,fitRms;
        /** Preserve the 0.5.0 RF crop to avoid silently changing its feature distribution. */
        public final double crackX,crackY,crackRadius;
        public final boolean crackNeedsReview;
        public Detection(double x,double y,double radius,double area,double roundness,boolean partial,boolean needsReview){
            this.x=x;this.y=y;this.radius=radius;this.area=area;this.roundness=roundness;this.partial=partial;this.needsReview=needsReview;
            semiMajor=radius;semiMinor=radius;angleDeg=0;ellipseFitted=false;fitSupport=Double.NaN;fitRms=Double.NaN;
            crackX=x;crackY=y;crackRadius=radius;crackNeedsReview=needsReview;
        }
        public Detection(Detection seed,double x,double y,double a,double b,double angle,boolean fitted,double support,double rms,boolean partial,boolean needsReview){
            if(!(Double.isFinite(x+y+a+b+angle)&&a>=b&&b>0))throw new IllegalArgumentException("Elipse inválida");
            this.x=x;this.y=y;semiMajor=a;semiMinor=b;angleDeg=((angle%180)+180)%180;ellipseFitted=fitted;
            radius=Math.sqrt(a*b);area=Math.PI*a*b;double h=Math.pow((a-b)/(a+b),2);double perimeter=Math.PI*(a+b)*(1+3*h/(10+Math.sqrt(4-3*h)));
            roundness=fitted?4*Math.PI*area/(perimeter*perimeter):seed.roundness;this.partial=partial;this.needsReview=needsReview;fitSupport=support;fitRms=rms;
            crackX=seed.crackX;crackY=seed.crackY;crackRadius=seed.crackRadius;crackNeedsReview=seed.crackNeedsReview;
        }
        private Detection(Detection d,double dx,double dy,boolean reviewCrack){
            x=d.x+dx;y=d.y+dy;radius=d.radius;area=d.area;roundness=d.roundness;semiMajor=d.semiMajor;semiMinor=d.semiMinor;angleDeg=d.angleDeg;
            partial=d.partial;needsReview=d.needsReview;ellipseFitted=d.ellipseFitted;fitSupport=d.fitSupport;fitRms=d.fitRms;
            crackX=d.crackX+dx;crackY=d.crackY+dy;crackRadius=d.crackRadius;crackNeedsReview=d.crackNeedsReview||reviewCrack;
        }
        public Detection translated(double dx,double dy,boolean reviewCrack){return new Detection(this,dx,dy,reviewCrack);}
        public Detection affine(double sx,double sy,double tx,double ty){return new Detection(this,sx,sy,tx,ty,false);}
        public Detection flagged(){return new Detection(this,1,1,0,0,true);}
        private Detection(Detection d,double sx,double sy,double tx,double ty,boolean review){
            x=d.x*sx+tx;y=d.y*sy+ty;double t=Math.toRadians(d.angleDeg),c=Math.cos(t),s=Math.sin(t),aa=d.semiMajor*d.semiMajor,bb=d.semiMinor*d.semiMinor;
            double xx=(aa*c*c+bb*s*s)*sx*sx,yy=(aa*s*s+bb*c*c)*sy*sy,xy=(aa-bb)*c*s*sx*sy,z=Math.hypot(xx-yy,2*xy);
            semiMajor=Math.sqrt(Math.max(0,(xx+yy+z)/2));semiMinor=Math.sqrt(Math.max(0,(xx+yy-z)/2));angleDeg=((Math.toDegrees(.5*Math.atan2(2*xy,xx-yy))%180)+180)%180;
            radius=d.radius*Math.sqrt(Math.abs(sx*sy));area=d.area*Math.abs(sx*sy);double h=Math.pow((semiMajor-semiMinor)/(semiMajor+semiMinor),2);double perimeter=Math.PI*(semiMajor+semiMinor)*(1+3*h/(10+Math.sqrt(4-3*h)));roundness=d.ellipseFitted?4*Math.PI*area/(perimeter*perimeter):d.roundness;ellipseFitted=d.ellipseFitted;fitSupport=d.fitSupport;fitRms=d.fitRms;partial=d.partial;needsReview=d.needsReview||review;
            crackX=d.crackX*sx+tx;crackY=d.crackY*sy+ty;crackRadius=d.crackRadius*Math.sqrt(sx*sy);crackNeedsReview=d.crackNeedsReview||review;
        }
        public double aspectRatio(){return ellipseFitted?semiMinor/semiMajor:Double.NaN;}
        public double extentX(){double t=Math.toRadians(angleDeg);return Math.hypot(semiMajor*Math.cos(t),semiMinor*Math.sin(t));}
        public double extentY(){double t=Math.toRadians(angleDeg);return Math.hypot(semiMajor*Math.sin(t),semiMinor*Math.cos(t));}
        public double normalizedDistance(double px,double py){double t=Math.toRadians(angleDeg),dx=px-x,dy=py-y;return Math.hypot((dx*Math.cos(t)+dy*Math.sin(t))/semiMajor,(-dx*Math.sin(t)+dy*Math.cos(t))/semiMinor);}
        public double boundaryX(double t){double angle=Math.toRadians(angleDeg);return x+semiMajor*Math.cos(t)*Math.cos(angle)-semiMinor*Math.sin(t)*Math.sin(angle);}
        public double boundaryY(double t){double angle=Math.toRadians(angleDeg);return y+semiMajor*Math.cos(t)*Math.sin(angle)+semiMinor*Math.sin(t)*Math.cos(angle);}
        private double distance2(double t,double cx,double cy){double dx=boundaryX(t)-cx,dy=boundaryY(t)-cy;return dx*dx+dy*dy;}
        /** Maximum distance from an arbitrary point, optimizing all local maxima of a degree-2 trigonometric polynomial. */
        public double farthestDistance(double cx,double cy){
            if(Math.abs(semiMajor-semiMinor)<1e-10)return Math.hypot(x-cx,y-cy)+semiMajor;
            double best=0,step=2*Math.PI/64;
            for(int i=0;i<64;i++){double t=i*step,v=distance2(t,cx,cy);best=Math.max(best,v);if(v<distance2(t-step,cx,cy)||v<distance2(t+step,cx,cy))continue;
                double lo=t-step,hi=t+step;for(int k=0;k<45;k++){double u=lo+(hi-lo)/3,z=hi-(hi-lo)/3;if(distance2(u,cx,cy)<distance2(z,cx,cy))lo=u;else hi=z;}best=Math.max(best,distance2((lo+hi)/2,cx,cy));
            }return Math.sqrt(best);
        }
    }
    public static final class Result {
        public final List<Detection> circles;
        public final int width, height, candidates;
        public final long durationMs;
        public final boolean candidateLimit;
        public final String method;
        public Result(List<Detection> circles, int width, int height, int candidates,
                      long durationMs, boolean limit) {this(circles,width,height,candidates,durationMs,limit,"native_multiscale_LoG_bucket_watershed");}
        public Result(List<Detection> circles,int width,int height,int candidates,long durationMs,boolean limit,String method) {
            this.method=method;this.circles=Collections.unmodifiableList(new ArrayList<>(circles));
            this.width=width; this.height=height; this.candidates=candidates;
            this.durationMs=durationMs; this.candidateLimit=limit;
        }
    }
    private static final class Seed {
        final int x, y; final double radius; final float score;
        Seed(int x, int y, double radius, float score) {this.x=x; this.y=y; this.radius=radius; this.score=score;}
    }
    public Result detect(Mat gray, Parameters config, int maximumSide, int scales) {
        if (gray.empty() || gray.type()!=CvType.CV_8UC1)
            throw new IllegalArgumentException("An 8-bit gray image is required");
        if (maximumSide<128 || scales<2) throw new IllegalArgumentException("Invalid work resolution");
        long start=System.nanoTime();
        int originalW=gray.cols(), originalH=gray.rows();
        double scale=Math.min(1., maximumSide/(double)Math.max(originalW,originalH));
        int w=Math.max(1,(int)Math.round(originalW*scale)), h=Math.max(1,(int)Math.round(originalH*scale));
        double sx=w/(double)originalW, sy=h/(double)originalH, isotropic=Math.sqrt(sx*sy);
        double rmin=originalW*config.minRadiusFraction*isotropic;
        double rmax=originalW*config.maxRadiusFraction*isotropic;
        int pad=Math.max(1,(int)Math.round(rmax*1.2));
        List<Mat> nativeMats=new ArrayList<>();
        try {
            Mat small=own(nativeMats), normalized=own(nativeMats), extended=own(nativeMats);
            Mat blurred=own(nativeMats), response=own(nativeMats), smooth=own(nativeMats), binary=own(nativeMats);
            Imgproc.resize(gray,small,new Size(w,h),0,0,Imgproc.INTER_AREA);
            byte[] raw=new byte[w*h]; small.get(0,0,raw);
            int[] histogram=new int[256]; for(byte value:raw) histogram[value&255]++;
            int total=0, dark=0; for(;dark<255;dark++){total+=histogram[dark];if(total>=raw.length/10)break;}
            small.convertTo(normalized,CvType.CV_32F,1./255);
            Core.copyMakeBorder(normalized,extended,pad,pad,pad,pad,Core.BORDER_CONSTANT,new Scalar(dark/255.));
            int ew=extended.cols(), eh=extended.rows();
            float[] values=new float[ew*eh], best=new float[w*h], radii=new float[w*h];
            double sigmaMin=Math.max(1.,rmin/Math.sqrt(2)), sigmaMax=Math.max(sigmaMin+.1,rmax/Math.sqrt(2));
            for(int si=0;si<scales;si++) {
                double sigma=sigmaMin+(sigmaMax-sigmaMin)*si/(scales-1.);
                Imgproc.GaussianBlur(extended,blurred,new Size(0,0),sigma,sigma,Core.BORDER_REFLECT_101);
                Imgproc.Laplacian(blurred,response,CvType.CV_32F,1,-sigma*sigma,0,Core.BORDER_REFLECT_101);
                response.get(0,0,values);
                for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
                    int p=y*w+x;float v=values[(y+pad)*ew+x+pad];
                    if(v>best[p]) {best[p]=v;radii[p]=(float)(sigma*Math.sqrt(2));}
                }
            }
            List<Seed> possible=new ArrayList<>();
            for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
                int p=y*w+x;float v=best[p]; if(v<config.threshold)continue;
                boolean peak=true;
                for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1)&&peak;yy++)
                    for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++)
                        if(best[yy*w+xx]>v) {peak=false;break;}
                if(peak)possible.add(new Seed(x,y,radii[p],v));
            }
            possible.sort((a,b)->Float.compare(b.score,a.score));
            List<Seed> seeds=new ArrayList<>(); boolean limit=false;
            for(Seed candidate:possible) {
                boolean duplicate=false;
                for(Seed accepted:seeds) {
                    double distance=Math.hypot(candidate.x-accepted.x,candidate.y-accepted.y);
                    if(distance<.65*Math.min(candidate.radius,accepted.radius)
                            || overlap(candidate.radius,accepted.radius,distance)>.30) {duplicate=true;break;}
                }
                if(!duplicate) {
                    if(seeds.size()==config.maxObjects) {limit=true;break;}
                    seeds.add(candidate);
                }
            }
            Imgproc.GaussianBlur(small,smooth,new Size(0,0),1.5);
            double otsu=Imgproc.threshold(smooth,binary,0,255,Imgproc.THRESH_BINARY|Imgproc.THRESH_OTSU);
            byte[] light=new byte[w*h];smooth.get(0,0,light);
            boolean[] mask=new boolean[w*h];for(int p=0;p<mask.length;p++)mask[p]=(light[p]&255)>otsu*.72;
            fillHoles(mask,w,h);
            int[] labels=watershed(light,mask,seeds,w,h);
            int n=seeds.size();
            int[] kept=new int[w*h], area=new int[n+1], perimeter=new int[n+1];
            double[] sumX=new double[n+1],sumY=new double[n+1]; boolean[] partial=new boolean[n+1];
            for(int p=0;p<labels.length;p++) {
                int label=labels[p]; if(label==0)continue;
                Seed seed=seeds.get(label-1);int x=p%w,y=p/w;
                double dx=x-seed.x,dy=y-seed.y;
                if(dx*dx+dy*dy>seed.radius*seed.radius*1.9*1.9)continue;
                kept[p]=label;area[label]++;sumX[label]+=x;sumY[label]+=y;
                if(x==0||y==0||x==w-1||y==h-1)partial[label]=true;
            }
            for(int p=0;p<kept.length;p++) {
                int label=kept[p];if(label==0)continue;int x=p%w,y=p/w;
                if(x==0||kept[p-1]!=label)perimeter[label]++;
                if(x==w-1||kept[p+1]!=label)perimeter[label]++;
                if(y==0||kept[p-w]!=label)perimeter[label]++;
                if(y==h-1||kept[p+w]!=label)perimeter[label]++;
            }
            List<Detection> circles=new ArrayList<>();
            for(int label=1;label<=n;label++) {
                double minArea=Math.PI*Math.pow(rmin*.70,2)*(partial[label]?.4:1);
                if(area[label]<minArea||area[label]==0)continue;
                double cx=sumX[label]/area[label],cy=sumY[label]/area[label];
                double sourceArea=area[label]/(sx*sy),radius=Math.sqrt(sourceArea/Math.PI);
                // The lattice perimeter is orientation-corrected; this is a heuristic, not a confidence score.
                double correctedPerimeter=perimeter[label]*Math.PI/4.;
                double roundness=Math.min(1.,4*Math.PI*area[label]/(correctedPerimeter*correctedPerimeter));
                Seed seed=seeds.get(label-1);
                double displacement=Math.hypot(cx-seed.x,cy-seed.y)/Math.max(1,seed.radius);
                boolean review=radius<originalW*config.minRadiusFraction || radius>originalW*config.maxRadiusFraction
                        || roundness<.62 || displacement>.55;
                circles.add(new Detection(cx/sx,cy/sy,radius,sourceArea,roundness,partial[label],review));
            }
            circles.sort(Comparator.comparingDouble((Detection d)->d.y).thenComparingDouble(d->d.x));
            return new Result(circles,originalW,originalH,seeds.size(),(System.nanoTime()-start)/1_000_000,limit);
        } finally {for(Mat mat:nativeMats)mat.release();}
    }
    private static Mat own(List<Mat> mats){Mat m=new Mat();mats.add(m);return m;}
    private static double overlap(double a,double b,double d) {
        if(d>=a+b)return 0;
        if(d<=Math.abs(a-b))return 1;
        double aa=Math.acos(clamp((d*d+a*a-b*b)/(2*d*a)));
        double bb=Math.acos(clamp((d*d+b*b-a*a)/(2*d*b)));
        double lens=a*a*aa+b*b*bb-.5*Math.sqrt(Math.max(0,(-d+a+b)*(d+a-b)*(d-a+b)*(d+a+b)));
        return lens/(Math.PI*Math.pow(Math.min(a,b),2));
    }
    private static double clamp(double x){return Math.max(-1,Math.min(1,x));}
    private static void fillHoles(boolean[] mask,int w,int h) {
        boolean[] outside=new boolean[mask.length];int[] queue=new int[mask.length];int tail=0;
        for(int p=0;p<mask.length;p++) if(!mask[p]&&(p<w||p>=w*(h-1)||p%w==0||p%w==w-1)){outside[p]=true;queue[tail++]=p;}
        for(int head=0;head<tail;head++) {
            int p=queue[head],x=p%w,y=p/w;
            if(x>0)tail=fillNeighbor(p-1,mask,outside,queue,tail);
            if(x<w-1)tail=fillNeighbor(p+1,mask,outside,queue,tail);
            if(y>0)tail=fillNeighbor(p-w,mask,outside,queue,tail);
            if(y<h-1)tail=fillNeighbor(p+w,mask,outside,queue,tail);
        }
        for(int p=0;p<mask.length;p++)if(!outside[p])mask[p]=true;
    }
    private static int fillNeighbor(int p,boolean[] mask,boolean[] outside,int[] q,int tail) {
        if(!mask[p]&&!outside[p]){outside[p]=true;q[tail++]=p;}return tail;
    }
    private static int[] watershed(byte[] gray,boolean[] mask,List<Seed> seeds,int w,int h) {
        int[] labels=new int[gray.length],next=new int[gray.length],heads=new int[256],tails=new int[256];
        Arrays.fill(next,-1);Arrays.fill(heads,-1);Arrays.fill(tails,-1);
        for(int i=0;i<seeds.size();i++) {
            Seed seed=seeds.get(i);int p=seed.y*w+seed.x;
            if(mask[p]&&labels[p]==0){labels[p]=i+1;enqueue(p,gray[p]&255,next,heads,tails);}
        }
        // Highest brightness first, FIFO within a level. Every pixel enters at most once.
        for(int level=255;level>=0;level--) while(heads[level]!=-1) {
            int p=heads[level];heads[level]=next[p];if(heads[level]==-1)tails[level]=-1;
            int x=p%w,y=p/w,label=labels[p];
            if(x>0)flood(p-1,label,level,gray,mask,labels,next,heads,tails);
            if(x<w-1)flood(p+1,label,level,gray,mask,labels,next,heads,tails);
            if(y>0)flood(p-w,label,level,gray,mask,labels,next,heads,tails);
            if(y<h-1)flood(p+w,label,level,gray,mask,labels,next,heads,tails);
        }
        return labels;
    }
    private static void flood(int p,int label,int level,byte[] gray,boolean[] mask,int[] labels,
                              int[] next,int[] heads,int[] tails) {
        if(!mask[p]||labels[p]!=0)return;
        labels[p]=label;enqueue(p,Math.min(level,gray[p]&255),next,heads,tails);
    }
    private static void enqueue(int p,int level,int[] next,int[] heads,int[] tails) {
        if(tails[level]!=-1)next[tails[level]]=p;else heads[level]=p;
        tails[level]=p;next[p]=-1;
    }
}
