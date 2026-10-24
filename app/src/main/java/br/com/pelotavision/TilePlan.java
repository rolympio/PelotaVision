package br.com.pelotavision;
import java.util.*;
/** Fixed source-pixel tiles; overlap exceeds twice the largest configured radius. No tile is resized. */
public final class TilePlan {
 public static final int NORMAL_SIDE=640,SR_SIDE=320;
 public static final class Tile {public final int left,top,width,height,index;final double ownL,ownT,ownR,ownB;Tile(int l,int t,int w,int h,int i,double a,double b,double c,double d){left=l;top=t;width=w;height=h;index=i;ownL=a;ownT=b;ownR=c;ownB=d;}public boolean owns(double x,double y){return x>=ownL&&y>=ownT&&x<ownR&&y<ownB;}}
 static List<Integer> starts(int length,int side){List<Integer> a=new ArrayList<>();a.add(0);if(length<=side)return a;int stride=(int)(side*.65);for(int x=stride;x<length-side;x+=stride)a.add(x);if(a.get(a.size()-1)!=length-side)a.add(length-side);return a;}
 public static List<Tile> create(int width,int height,boolean sr){int side=sr?SR_SIDE:NORMAL_SIDE,w=Math.min(width,side),h=Math.min(height,side);List<Integer> xs=starts(width,side),ys=starts(height,side);List<Tile> out=new ArrayList<>();for(int y=0;y<ys.size();y++)for(int x=0;x<xs.size();x++){int l=xs.get(x),t=ys.get(y);out.add(new Tile(l,t,w,h,out.size(),x==0?0:(xs.get(x-1)+l+w)/2.,y==0?0:(ys.get(y-1)+t+h)/2.,x==xs.size()-1?width:(l+xs.get(x+1)+w)/2.,y==ys.size()-1?height:(t+ys.get(y+1)+h)/2.));}return out;}
 public static boolean duplicate(PelletDetector.Detection a,PelletDetector.Detection b){double ratio=a.radius/b.radius;return ratio>.65&&ratio<1/.65&&Math.hypot(a.x-b.x,a.y-b.y)<.28*(a.radius+b.radius);}
 public static List<PelletDetector.Detection> merge(List<PelletDetector.Detection> candidates){List<PelletDetector.Detection> sorted=new ArrayList<>(candidates);sorted.sort((a,b)->Double.compare(quality(b),quality(a)));List<PelletDetector.Detection> kept=new ArrayList<>();for(PelletDetector.Detection d:sorted){boolean duplicate=false;for(PelletDetector.Detection k:kept)if(duplicate(d,k)){duplicate=true;break;}if(!duplicate)kept.add(d);}kept.sort(Comparator.comparingDouble((PelletDetector.Detection d)->d.y).thenComparingDouble(d->d.x));return kept;}
 static double quality(PelletDetector.Detection d){return (d.ellipseFitted?10:0)+(d.needsReview?0:5)+(d.partial?0:3)+(Double.isFinite(d.fitSupport)?d.fitSupport:0);}
}
