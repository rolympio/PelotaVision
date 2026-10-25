package br.com.pelotavision;
import java.util.*;
/** Candidate indications and user review; never a certificate of crack absence. */
public final class CrackAnalysis {
 public enum Status { POSSIBLE, NO_INDICATION, INCONCLUSIVE }
 public static final class Entry {
  public final double modelScore; public final String method;
  /** Comparison mode only: score of the other classifier (NaN/null otherwise). Never changes the status. */
  public final double comparisonScore; public final String comparisonMethod;
  public final int pelletId;public final Status automatic,status;public final boolean manuallyReviewed;
  /** Horizontal marked runs in upright photo coordinates: x1,y1,x2,y2. */
  private final float[] runs;
  public Entry(int id,Status status,float[] runs){this(id,status,status,false,runs,Double.NaN,"experimental_blackhat_dark_line_geometry",Double.NaN,null);}
  public Entry(int id,Status status,float[] runs,double score,String method){this(id,status,status,false,runs,score,method,Double.NaN,null);}
  public Entry(int id,Status status,float[] runs,double score,String method,double comparisonScore,String comparisonMethod){this(id,status,status,false,runs,score,method,comparisonScore,comparisonMethod);}
  private Entry(int id,Status automatic,Status status,boolean reviewed,float[] runs,double score,String method,double comparisonScore,String comparisonMethod){this.comparisonScore=comparisonScore;this.comparisonMethod=comparisonMethod;this.modelScore=score;this.method=method;this.pelletId=id;this.automatic=automatic;this.status=status;this.manuallyReviewed=reviewed;this.runs=runs.clone();}
  public float[] markedRuns(){return runs.clone();}
  public Entry reviewed(Status value){return new Entry(pelletId,automatic,value,true,runs,modelScore,method,comparisonScore,comparisonMethod);}
  public Entry restore(){return new Entry(pelletId,automatic,automatic,false,runs,modelScore,method,comparisonScore,comparisonMethod);}
  public Entry numbered(int id){return new Entry(id,automatic,status,manuallyReviewed,runs,modelScore,method,comparisonScore,comparisonMethod);}
 }
 public final int width,height,sensitivity;public final long durationMs;
 public final List<Entry> entries;
 public CrackAnalysis(int width,int height,int sensitivity,long ms,List<Entry> entries){
  this.width=width;this.height=height;this.sensitivity=sensitivity;this.durationMs=ms;this.entries=Collections.unmodifiableList(new ArrayList<>(entries));
 }
 public int count(Status value){int n=0;for(Entry e:entries)if(e.status==value)n++;return n;}
 public int reviewedCount(){int n=0;for(Entry e:entries)if(e.manuallyReviewed)n++;return n;}
 public CrackAnalysis review(int index,Status status){List<Entry> copy=new ArrayList<>(entries);copy.set(index,status==null?copy.get(index).restore():copy.get(index).reviewed(status));return new CrackAnalysis(width,height,sensitivity,durationMs,copy);}
 public CrackAnalysis select(List<Integer> ids){List<Entry> copy=new ArrayList<>();for(int i:ids)copy.add(entries.get(i).numbered(copy.size()+1));return new CrackAnalysis(width,height,sensitivity,durationMs,copy);}
}
