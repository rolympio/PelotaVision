package br.com.pelotavision;
import java.util.*;
/** Pixel-edge affine mapping from an upright original-source crop to the retained preview. */
public final class DetailGeometry {
 public final int width,height,previewWidth,previewHeight,sample;public final double sx,sy,tx,ty;public final InterestRegion region;
 public DetailGeometry(int w,int h,int pw,int ph,double sx,double sy,double tx,double ty,int sample,InterestRegion roi){if(w<1||h<1||pw<1||ph<1||!(sx>0&&sy>0)||!Double.isFinite(sx+sy+tx+ty))throw new IllegalArgumentException("Mapeamento inválido.");width=w;height=h;previewWidth=pw;previewHeight=ph;this.sx=sx;this.sy=sy;this.tx=tx;this.ty=ty;this.sample=sample;region=roi;}
 public boolean inside(double x,double y){double px=x*sx+tx,py=y*sy+ty;return px>=0&&py>=0&&px<=previewWidth&&py<=previewHeight&&(region==null||region.contains(px,py));}
 public PelletDetector.Detection toPreview(PelletDetector.Detection d){return d.affine(sx,sy,tx,ty);}
 public PelletDetector.Result toPreview(PelletDetector.Result result){List<PelletDetector.Detection> ds=new ArrayList<>();for(PelletDetector.Detection d:result.circles){PelletDetector.Detection p=toPreview(d);if(region==null||region.includes(p))ds.add(p);}return new PelletDetector.Result(ds,previewWidth,previewHeight,result.candidates,result.durationMs,result.candidateLimit,result.method);}
 public CrackAnalysis toPreview(CrackAnalysis input){List<CrackAnalysis.Entry> out=new ArrayList<>();for(CrackAnalysis.Entry e:input.entries){float[] runs=e.markedRuns();for(int i=0;i<runs.length;i+=2){runs[i]=(float)(runs[i]*sx+tx);runs[i+1]=(float)(runs[i+1]*sy+ty);}CrackAnalysis.Entry mapped=new CrackAnalysis.Entry(e.pelletId,e.automatic,runs,e.modelScore,e.method);out.add(e.manuallyReviewed?mapped.reviewed(e.status):mapped);}return new CrackAnalysis(previewWidth,previewHeight,input.sensitivity,input.durationMs,out);}
 /** Only selected source pixels contribute to the fill value. */
 public void mask(org.opencv.core.Mat gray){if(region==null)return;byte[] p=new byte[width*height];gray.get(0,0,p);int[] counts=new int[256];int n=0;for(int y=0;y<height;y++)for(int x=0;x<width;x++)if(inside(x+.5,y+.5)){counts[p[y*width+x]&255]++;n++;}int median=0,sum=0;for(;median<255;median++){sum+=counts[median];if(sum>=(n+1)/2)break;}for(int y=0;y<height;y++)for(int x=0;x<width;x++)if(!inside(x+.5,y+.5))p[y*width+x]=(byte)median;gray.put(0,0,p);}
}
