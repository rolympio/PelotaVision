package br.com.pelotavision;
import java.util.*;

/** Long, persistent grayscale edges; proposes geometry only, never confirms a physical reference. */
final class BeltEdgeDetector {
    private BeltEdgeDetector() {}
    static BeltCalibration.Edges suggest(byte[] gray,int w,int h) {
        if(gray.length!=w*h||Math.min(w,h)<32)return null;
        double best=0;BeltCalibration.Edges chosen=null;
        for(int deg=0;deg<180;deg+=5){
            double theta=Math.toRadians(deg),nx=Math.cos(theta),ny=Math.sin(theta),tx=-ny,ty=nx;
            double span=Math.abs(nx)*(w-1)+Math.abs(ny)*(h-1),along=Math.abs(tx)*(w-1)+Math.abs(ty)*(h-1),half=along*.34;
            int bins=(int)Math.ceil(span);double[] scores=new double[bins];
            for(int i=3;i<bins-3;i++){
                double u=i-span/2;double[] values=new double[48];int count=0;
                for(int j=0;j<48;j++){
                    double v=(j/47.-.5)*2*half,x=(w-1)/2.+u*nx+v*tx,y=(h-1)/2.+u*ny+v*ty;
                    if(x<5||y<5||x>w-6||y>h-6)continue;
                    // Local averages suppress pellet pores and sensor noise before the cross-edge gradient.
                    double left=0,right=0;for(int d=-1;d<=1;d++){left+=sample(gray,w,h,x-3*nx+d*tx,y-3*ny+d*ty);right+=sample(gray,w,h,x+3*nx+d*tx,y+3*ny+d*ty);}
                    values[count++]=Math.abs(right-left)/3;
                }
                if(count<40)continue;Arrays.sort(values,0,count);
                scores[i]=values[(int)(count*.30)]; // A gradient must persist along most of the line.
            }
            List<Integer> peaks=new ArrayList<>();for(int i=5;i<bins-5;i++){if(scores[i]<7)continue;boolean peak=true;for(int k=-4;k<=4;k++)if(scores[i+k]>scores[i]){peak=false;break;}if(peak)peaks.add(i);}
            peaks.sort((a,b)->Double.compare(scores[b],scores[a]));if(peaks.size()>20)peaks=peaks.subList(0,20);
            for(int a:peaks)for(int b:peaks){if(a>=bins/2||b<=bins/2||b-a<span*.38||b-a>span*.93)continue;
                double[] p=new double[8];boolean inside=true;int id=0;
                for(int edge:new int[]{a,b})for(int end:new int[]{-1,1}){double u=edge-span/2,x=(w-1)/2.+u*nx+end*half*tx,y=(h-1)/2.+u*ny+end*half*ty;p[id++]=x;p[id++]=y;if(x<0||y<0||x>w-1||y>h-1)inside=false;}
                if(!inside)continue;
                double score=Math.sqrt(scores[a]*scores[b])*(.6+.4*(b-a)/span);
                if(score>best){best=score;chosen=new BeltCalibration.Edges(p,true);}
            }
        }
        return chosen;
    }
    private static int sample(byte[] a,int w,int h,double x,double y){int ix=(int)Math.round(x),iy=(int)Math.round(y);return a[Math.max(0,Math.min(h-1,iy))*w+Math.max(0,Math.min(w-1,ix))]&255;}
}
