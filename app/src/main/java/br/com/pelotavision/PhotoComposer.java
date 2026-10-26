package br.com.pelotavision;
import android.graphics.*;
import java.util.*;
/** Original, annotated photo, candidate-count summary, then histogram. */
final class PhotoComposer {
 private PhotoComposer(){}
 static final class Layout {
  final int titleHeight,originalTop,annotatedTop,summaryTop,histogramTop,height;
  Layout(int w,int h){titleHeight=Math.max(1,Math.round(w*24f/380));originalTop=titleHeight;annotatedTop=originalTop+h+titleHeight;summaryTop=annotatedTop+h;histogramTop=summaryTop+Math.round(w*152f/380);height=histogramTop+Math.round(w*216f/380);}
 }
 static Granulometry.Distribution pixelHistogram(PelletDetector.Result result){return ReportTables.pixelHistogram(result);}
 static Bitmap compose(Bitmap photo,PelletDetector.Result result,Granulometry.Distribution distribution){return compose(photo,result,null,distribution);}
 static Bitmap compose(Bitmap photo,PelletDetector.Result result,CrackAnalysis cracks,Granulometry.Distribution distribution){
  return compose(photo,result,cracks,distribution,null,0);
 }
 static Bitmap compose(Bitmap photo,PelletDetector.Result result,CrackAnalysis cracks,Granulometry.Distribution distribution,InterestRegion region,int excludedByRegion){
  return compose(photo,result,cracks,distribution,region,excludedByRegion,false);
 }
 static Bitmap compose(Bitmap photo,PelletDetector.Result result,CrackAnalysis cracks,Granulometry.Distribution distribution,InterestRegion region,int excludedByRegion,boolean cropRoi){
  return compose(photo,result,cracks,distribution,region,excludedByRegion,cropRoi,null,null);
 }
 /** With the comparative histogram (all × cracked) when crackHistogram is not null. Layout unchanged. */
 static Bitmap compose(Bitmap photo,PelletDetector.Result result,CrackAnalysis cracks,Granulometry.Distribution distribution,InterestRegion region,int excludedByRegion,boolean cropRoi,CrackHistogram crackHistogram,ReportVisuals visuals){
  return compose(photo,result,cracks,distribution,region,excludedByRegion,cropRoi,crackHistogram,visuals,null);
 }
 /** 0.15.2: quando filtered não é null, o painel 2 (segmentado) é desenhado sobre a imagem filtrada; o painel 1 continua original. */
 static Bitmap compose(Bitmap photo,PelletDetector.Result result,CrackAnalysis cracks,Granulometry.Distribution distribution,InterestRegion region,int excludedByRegion,boolean cropRoi,CrackHistogram crackHistogram,ReportVisuals visuals,Bitmap filtered){
  if(filtered!=null&&(filtered.getWidth()!=photo.getWidth()||filtered.getHeight()!=photo.getHeight()))throw new IllegalArgumentException("Imagem filtrada incompatível.");
  if(photo.getWidth()!=result.width||photo.getHeight()!=result.height)throw new IllegalArgumentException("Foto e detecções incompatíveis.");
  if(cracks!=null&&(cracks.width!=result.width||cracks.height!=result.height||cracks.entries.size()!=result.circles.size()))throw new IllegalArgumentException("Trincas e pelotas incompatíveis.");
  if(distribution==null)distribution=pixelHistogram(result);
  if(distribution.settings.selectedObject!=null&&(distribution.settings.selectedObject.width!=result.width||distribution.settings.selectedObject.height!=result.height))throw new IllegalArgumentException("Referência pertence a outra imagem.");
  ReportViewport viewport=new ReportViewport(photo.getWidth(),photo.getHeight(),region,cropRoi);
  Layout layout=new Layout(viewport.width,viewport.height);Bitmap output=Bitmap.createBitmap(viewport.width,layout.height,Bitmap.Config.ARGB_8888);
  try{
   Canvas canvas=new Canvas(output);canvas.drawColor(Color.rgb(16,26,35));float s=viewport.width/380f;
   Paint label=new Paint(Paint.ANTI_ALIAS_FLAG);label.setColor(Color.WHITE);label.setTextSize(12*s);label.setFakeBoldText(true);
   canvas.drawText(viewport.cropped?"1 · ROI AMPLIADA — sem marcações":"1 · ORIGINAL — sem marcações",12*s,16*s,label);
   drawPanel(canvas,photo,result,cracks,distribution,region,viewport,layout.originalTop,false);
   canvas.drawText(filtered!=null?"2 · PELOTAS E POSSÍVEIS TRINCAS · FILTRADA":"2 · PELOTAS E POSSÍVEIS TRINCAS",12*s,layout.annotatedTop-8*s,label);
   drawPanel(canvas,filtered!=null?filtered:photo,result,cracks,distribution,region,viewport,layout.annotatedTop,true);
   label.setFakeBoldText(false);label.setTextSize(11*s);
   if(cracks!=null){
    canvas.drawText("Possíveis trincas: "+cracks.count(CrackAnalysis.Status.POSSIBLE)+" · sem indicação: "+cracks.count(CrackAnalysis.Status.NO_INDICATION),12*s,layout.summaryTop+18*s,label);
    canvas.drawText("Inconclusivas: "+cracks.count(CrackAnalysis.Status.INCONCLUSIVE)+" · pelotas: "+result.circles.size()+" · revisadas: "+cracks.reviewedCount(),12*s,layout.summaryTop+35*s,label);
   }else canvas.drawText("Trincas não analisadas nesta imagem",12*s,layout.summaryTop+18*s,label);
   label.setColor(Color.rgb(181,198,208));label.setTextSize(9*s);
   boolean trained=false;if(cracks!=null)for(CrackAnalysis.Entry e:cracks.entries)if(Double.isFinite(e.modelScore)){trained=true;break;}
   canvas.drawText(trained?"Modelo RF experimental · confirmar por revisão":"Triagem heurística experimental · confirmar por revisão",12*s,layout.summaryTop+50*s,label);
   ShapeStatistics shape=ShapeStatistics.of(result);canvas.drawText(shape.label(),12*s,layout.summaryTop+65*s,label);
   canvas.drawText("Excluídas da forma: "+shape.excluded+" · média por número · não mede esfericidade 3D",12*s,layout.summaryTop+79*s,label);
   canvas.drawText("Vermelho: possível trinca · amarelo: revisão · tracejado: forma sem ajuste",12*s,layout.summaryTop+93*s,label);
   canvas.drawText(region==null?"Sem indicação não garante ausência de trinca.":result.method.contains("active_roi_crop_v1")?result.method.contains("detail_original_tiles_v1")?"ROI detalhada · medidas na original · exige revisão":"ROI ativa · somente recorte analisado · exige revisão":"ROI "+(region.shape==InterestRegion.Shape.CIRCLE?"circular":"retangular")+" · fora/limite: "+excludedByRegion+" · exige revisão",12*s,layout.summaryTop+108*s,label);
   ObjectCalibration.Reference reference=distribution.settings.selectedObject;
   if(reference!=null){
    canvas.drawText("REF do usuário · "+reference.summary(),12*s,layout.summaryTop+123*s,label);
    canvas.drawText("Escala: "+HistogramView.number(distribution.settings.originalPixelsPerMm)+" px/mm (original) · "+(reference.excludeFromStatistics?"REF fora da amostra":"REF incluída na amostra"),12*s,layout.summaryTop+138*s,label);
   }else if(distribution.settings.beltReference!=null){
    BeltCalibration.Reference belt=distribution.settings.beltReference;
    canvas.drawText("Correia: "+HistogramView.number(belt.realWidthMm)+" mm · bordas confirmadas pelo usuário",12*s,layout.summaryTop+123*s,label);
    canvas.drawText("Escala original: "+HistogramView.number(belt.originalPixelsPerMm)+" px/mm · "+(viewport.cropped?"ROI ampliada; medidas preservadas":"imagem inteira"),12*s,layout.summaryTop+138*s,label);
   }else{
    canvas.drawText(distribution.settings.pixelsOnly()?"Calibre em Escala / mm: correia ou referência.":"Escala informada: "+HistogramView.number(distribution.settings.originalPixelsPerMm)+" px/mm (original)",12*s,layout.summaryTop+123*s,label);
    canvas.drawText(distribution.settings.pixelsOnly()?"Sem referência física; histograma em pixels.":distribution.settings.estimated()?"Estimativa pela média informada; não é referência independente.":"Sem referência de objeto selecionada nesta foto.",12*s,layout.summaryTop+138*s,label);
   }
   HistogramView.draw(canvas,new RectF(0,layout.histogramTop,viewport.width,output.getHeight()),distribution,crackHistogram,visuals);
   return output;
  }catch(RuntimeException|OutOfMemoryError e){output.recycle();throw e;}
 }
 /**
  * 0.15.2: um único painel (sem títulos), recortado como no relatório (mesmo ReportViewport).
  * base = foto original ou filtrada; marked = desenhar elipses, trincas, ROI e referências.
  */
 static Bitmap panel(Bitmap base,PelletDetector.Result result,CrackAnalysis cracks,Granulometry.Distribution distribution,InterestRegion region,boolean cropRoi,boolean marked){
  if(base.getWidth()!=result.width||base.getHeight()!=result.height)throw new IllegalArgumentException("Foto e detecções incompatíveis.");
  if(distribution==null)distribution=pixelHistogram(result);
  ReportViewport viewport=new ReportViewport(base.getWidth(),base.getHeight(),region,cropRoi);
  Bitmap output=Bitmap.createBitmap(viewport.width,viewport.height,Bitmap.Config.ARGB_8888);
  try{Canvas canvas=new Canvas(output);canvas.drawColor(Color.rgb(16,26,35));drawPanel(canvas,base,result,cracks,distribution,region,viewport,0,marked);return output;}
  catch(RuntimeException|OutOfMemoryError e){output.recycle();throw e;}
 }
 private static void drawPanel(Canvas canvas,Bitmap photo,PelletDetector.Result result,CrackAnalysis cracks,Granulometry.Distribution distribution,InterestRegion region,ReportViewport viewport,int panelTop,boolean marked){
  canvas.save();canvas.clipRect(0,panelTop,viewport.width,panelTop+viewport.height);canvas.translate(-viewport.left*viewport.scale,panelTop-viewport.top*viewport.scale);canvas.scale(viewport.scale,viewport.scale);ReportClip.clip(canvas,viewport,region);canvas.drawBitmap(photo,0,0,null);
  if(marked){
   Paint stroke=new Paint(Paint.ANTI_ALIAS_FLAG),text=new Paint(Paint.ANTI_ALIAS_FLAG),outline=new Paint(Paint.ANTI_ALIAS_FLAG);stroke.setStrokeWidth(Math.max(2,photo.getWidth()/600f));
   CircleOverlay.draw(canvas,result,0,0,1,true,stroke,text,outline);CrackOverlay.draw(canvas,result,cracks,0,0,1);
   RegionOverlay.draw(canvas,region,0,0,1,result.width,result.height,false);ReferenceOverlay.draw(canvas,distribution.settings.selectedObject,0,0,1);
   if(!viewport.cropped&&distribution.settings.beltReference!=null)BeltEditorView.drawEdges(canvas,distribution.settings.beltReference.edges,0,0,1,false,1);
  }canvas.restore();
 }

}
