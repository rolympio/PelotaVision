package br.com.pelotavision;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class ResultSaver {
    private ResultSaver() {}
    static final String APP_VERSION="0.15.2";
    static final class SavedResult {
        final String base;final Uri image;
        /** 0.15.0: relatório PDF; null quando a geração falhou (os demais arquivos continuam válidos). */
        final Uri pdf;
        SavedResult(String base,Uri image){this(base,image,null);}
        SavedResult(String base,Uri image,Uri pdf){this.base=base;this.image=image;this.pdf=pdf;}
    }
    static SavedResult save(Context context,Bitmap photo,File original,String originalMime,
                       PelletDetector.Result result,PelletDetector.Parameters parameters,
                       Granulometry.Distribution distribution) throws Exception {
        return save(context,photo,original,originalMime,result,parameters,null,distribution,null,0);
    }
    static SavedResult save(Context context,Bitmap photo,File original,String originalMime,
                       PelletDetector.Result result,PelletDetector.Parameters parameters,CrackAnalysis cracks,
                       Granulometry.Distribution distribution) throws Exception {
        return save(context,photo,original,originalMime,result,parameters,cracks,distribution,null,0);
    }
    static SavedResult save(Context context,Bitmap photo,File original,String originalMime,
                       PelletDetector.Result result,PelletDetector.Parameters parameters,CrackAnalysis cracks,
                       Granulometry.Distribution distribution,InterestRegion region,int excludedByRegion) throws Exception {
        return save(context,photo,original,originalMime,result,parameters,cracks,distribution,region,excludedByRegion,null,null);
    }
    static SavedResult save(Context context,Bitmap photo,File original,String originalMime,
                       PelletDetector.Result result,PelletDetector.Parameters parameters,CrackAnalysis cracks,
                       Granulometry.Distribution distribution,InterestRegion region,int excludedByRegion,DetectionTuner.Report suggestions,DetectionTuner.Option applied) throws Exception {
        return save(context,photo,original,originalMime,result,parameters,cracks,distribution,region,excludedByRegion,suggestions,applied,false);
    }
    static SavedResult save(Context context,Bitmap photo,File original,String originalMime,
                       PelletDetector.Result result,PelletDetector.Parameters parameters,CrackAnalysis cracks,
                       Granulometry.Distribution distribution,InterestRegion region,int excludedByRegion,DetectionTuner.Report suggestions,DetectionTuner.Option applied,boolean cropRoi) throws Exception {return save(context,photo,original,originalMime,result,parameters,cracks,distribution,region,excludedByRegion,suggestions,applied,cropRoi,null);}
    static SavedResult save(Context context,Bitmap photo,File original,String originalMime,PelletDetector.Result result,PelletDetector.Parameters parameters,CrackAnalysis cracks,Granulometry.Distribution distribution,InterestRegion region,int excludedByRegion,DetectionTuner.Report suggestions,DetectionTuner.Option applied,boolean cropRoi,DetailedAnalysis.Report detailed) throws Exception {
        return save(context,photo,original,originalMime,result,parameters,cracks,distribution,region,excludedByRegion,suggestions,applied,cropRoi,detailed,ReportVisuals.defaults(),null,null);
    }
    /** 0.14.0: also the comparative histogram, the granulometric map PNG and the analysis models (JSON schema 2). */
    static SavedResult save(Context context,Bitmap photo,File original,String originalMime,PelletDetector.Result result,PelletDetector.Parameters parameters,CrackAnalysis cracks,Granulometry.Distribution distribution,InterestRegion region,int excludedByRegion,DetectionTuner.Report suggestions,DetectionTuner.Option applied,boolean cropRoi,DetailedAnalysis.Report detailed,ReportVisuals visuals,CrackClassifierChoice.Resolution crackChoice,String networkId) throws Exception {
        return save(context,photo,original,originalMime,result,parameters,cracks,distribution,region,excludedByRegion,suggestions,applied,cropRoi,detailed,visuals,crackChoice,networkId,null);
    }
    /** 0.15.0: also the PDF report and the low-light treatment block (JSON schema unchanged, additive key low_light). */
    static SavedResult save(Context context,Bitmap photo,File original,String originalMime,PelletDetector.Result result,PelletDetector.Parameters parameters,CrackAnalysis cracks,Granulometry.Distribution distribution,InterestRegion region,int excludedByRegion,DetectionTuner.Report suggestions,DetectionTuner.Option applied,boolean cropRoi,DetailedAnalysis.Report detailed,ReportVisuals visuals,CrackClassifierChoice.Resolution crackChoice,String networkId,LowLightInfo lowLight) throws Exception {
        return save(context,photo,original,originalMime,result,parameters,cracks,distribution,region,excludedByRegion,suggestions,applied,cropRoi,detailed,visuals,crackChoice,networkId,lowLight,null);
    }
    /**
     * 0.15.2: filtered = foto com o filtro de iluminação aplicado (null sem realce). Quando presente, a imagem
     * segmentada e o mapa são desenhados sobre ela, o arquivo _filtrada.png é gravado e o PDF traz original,
     * filtrada, segmentada e mapa como figuras separadas. As medidas continuam nos pixels originais.
     */
    static SavedResult save(Context context,Bitmap photo,File original,String originalMime,PelletDetector.Result result,PelletDetector.Parameters parameters,CrackAnalysis cracks,Granulometry.Distribution distribution,InterestRegion region,int excludedByRegion,DetectionTuner.Report suggestions,DetectionTuner.Option applied,boolean cropRoi,DetailedAnalysis.Report detailed,ReportVisuals visuals,CrackClassifierChoice.Resolution crackChoice,String networkId,LowLightInfo lowLight,Bitmap filtered) throws Exception {
        if(filtered!=null&&(lowLight==null||!lowLight.enhanced()))filtered=null;
        if(filtered!=null&&(filtered.getWidth()!=photo.getWidth()||filtered.getHeight()!=photo.getHeight()))throw new IllegalArgumentException("Imagem filtrada incompatível com a foto.");
        Bitmap shown=filtered!=null?filtered:photo;
        if(distribution==null)distribution=PhotoComposer.pixelHistogram(result);
        if(visuals==null)visuals=ReportVisuals.defaults();
        java.util.List<Granulometry.Item> histogramItems=new ArrayList<>();for(PelletDetector.Detection d:result.circles)histogramItems.add(new Granulometry.Item(2*d.radius,d.partial,d.needsReview));
        CrackHistogram crackHistogram=CrackHistogram.compute(distribution,histogramItems,cracks);
        boolean physical=!distribution.settings.pixelsOnly();
        ContentResolver resolver=context.getContentResolver();List<Uri> pending=new ArrayList<>();
        String base="PelotaVision_"+new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.ROOT).format(new Date());
        Bitmap annotated=null,map=null,filteredPanel=null;
        try {
            String extension="image/png".equals(originalMime)?"png":"image/webp".equals(originalMime)?"webp":"jpg";
            Uri source=create(resolver,MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    base+"_original."+extension,originalMime,"Pictures/PelotaVision");pending.add(source);
            try(InputStream input=new FileInputStream(original);OutputStream output=stream(resolver,source)) {
                byte[] bytes=new byte[65536];int count;while((count=input.read(bytes))!=-1)output.write(bytes,0,count);
            }
            annotated=PhotoComposer.compose(photo,result,cracks,distribution,region,excludedByRegion,cropRoi,crackHistogram,visuals,filtered);
            Uri image=create(resolver,MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    base+"_resultado.png","image/png","Pictures/PelotaVision");pending.add(image);
            try(OutputStream output=stream(resolver,image)) {if(!annotated.compress(Bitmap.CompressFormat.PNG,100,output))throw new IOException("Falha ao gravar a imagem.");}
            String filteredFile=null;
            if(filtered!=null){
                try{filteredPanel=PhotoComposer.panel(filtered,result,cracks,distribution,region,cropRoi,false);
                    Uri filteredUri=create(resolver,MediaStore.Images.Media.EXTERNAL_CONTENT_URI,base+"_filtrada.png","image/png","Pictures/PelotaVision");pending.add(filteredUri);
                    try(OutputStream output=stream(resolver,filteredUri)){if(!filteredPanel.compress(Bitmap.CompressFormat.PNG,100,output))throw new IOException("Falha ao gravar a imagem filtrada.");}filteredFile=base+"_filtrada.png";
                }catch(OutOfMemoryError e){if(filteredPanel!=null){filteredPanel.recycle();filteredPanel=null;}}
            }
            String mapFile=null;
            try{map=MapComposer.compose(shown,result,distribution,visuals,region,cropRoi);
                if(map!=null){Uri mapUri=create(resolver,MediaStore.Images.Media.EXTERNAL_CONTENT_URI,base+"_mapa.png","image/png","Pictures/PelotaVision");pending.add(mapUri);
                    try(OutputStream output=stream(resolver,mapUri)){if(!map.compress(Bitmap.CompressFormat.PNG,100,output))throw new IOException("Falha ao gravar o mapa.");}mapFile=base+"_mapa.png";}
            }catch(OutOfMemoryError e){mapFile=null;if(map!=null){map.recycle();map=null;}}
            JSONObject json=new JSONObject();json.put("app","PelotaVision "+APP_VERSION);json.put("report_schema_version",ReportJson.SCHEMA_VERSION);json.put("created_at_epoch_ms",System.currentTimeMillis());
            json.put("analysis_models",ReportJson.analysisModelsJson(applied,crackChoice,networkId));
            json.put("crack_histogram",ReportJson.crackHistogramJson(crackHistogram,visuals));
            Object mapJson=ReportJson.mapJson(distribution,visuals);if(mapJson instanceof JSONObject)((JSONObject)mapJson).put("file",mapFile==null?JSONObject.NULL:mapFile);json.put("granulometric_map",mapJson);
            JSONObject display=new JSONObject();display.put("histogram_show_all",visuals.showAll);display.put("histogram_show_cracked",visuals.showCracked);display.put("histogram_values_on_bars",visuals.showValues);display.put("histogram_normalization",visuals.normalization.name());display.put("all_color","#268CAB alpha 0.55");display.put("cracked_color","#FF8C1A alpha 0.65");json.put("visualization",display);
            json.put("region_of_interest",ReportJson.regionJson(region,excludedByRegion));json.put("detection_suggestion",ReportJson.suggestionJson(suggestions,applied));json.put("neural_model",ReportJson.neuralModelJson(applied));json.put("scene",ReportJson.sceneJson(applied));json.put("low_light",lowLight==null?JSONObject.NULL:lowLight.toJson());
            JSONObject imagery=new JSONObject();imagery.put("filtered_file",filteredFile==null?JSONObject.NULL:filteredFile);imagery.put("segmented_base",filtered!=null?"filtered":"original");imagery.put("map_base",filtered!=null?"filtered":"original");
            imagery.put("note","filtered image is for display and object proposal only; ellipses, diameters and crack crops use ORIGINAL pixels");json.put("report_images",imagery);
            JSONObject processing=ReportJson.processingScopeJson(new AnalysisWindow(result.width,result.height,result.method.contains("active_roi_crop_v1")?region:null));
            if(detailed!=null){processing.put("input_width",detailed.geometry.width);processing.put("input_height",detailed.geometry.height);processing.put("crop_resizes_input",detailed.geometry.sample>1);processing.put("coordinate_mapping","detailed_analysis.local_to_preview; detections retain preview coordinates");processing.put("outside_roi_original_pixels_analyzed",region==null);}
            json.put("processing_scope",processing);json.put("detailed_analysis",ReportJson.detailJson(detailed));
            json.put("method",result.method);json.put("shape_analysis",ReportJson.shapeJson(result));json.put("count",result.circles.size());
            json.put("image_width",annotated.getWidth());json.put("image_height",annotated.getHeight());
            json.put("analysis_photo_width",result.width);json.put("analysis_photo_height",result.height);
            ReportViewport viewport=new ReportViewport(photo.getWidth(),photo.getHeight(),region,cropRoi);
            PhotoComposer.Layout layout=new PhotoComposer.Layout(viewport.width,viewport.height);
            json.put("report_viewport",ReportJson.viewportJson(viewport));json.put("photo_panel_width",viewport.width);json.put("photo_panel_height",viewport.height);
            json.put("coordinates","detections in full upright analyzed photo pixels; composite x=(x-crop_left)*render_scale; composite y=annotated_panel_top+(y-crop_top)*render_scale");
            json.put("original_panel_top",layout.originalTop);json.put("annotated_panel_top",layout.annotatedTop);json.put("histogram_top",layout.histogramTop);
            json.put("cracks",ReportJson.crackJson(cracks));
            json.put("original_file",base+"_original."+extension);json.put("annotated_file",base+"_resultado.png");
            json.put("analysis_ms",result.durationMs);json.put("reviewed",false);json.put("candidate_limit",result.candidateLimit);
            json.put("physical_scale",JSONObject.NULL);json.put("histogram",JSONObject.NULL);
            json.put("note","Experimental 2D ellipse fit: area=pi*a*b, equivalent diameter=2*sqrt(a*b). Unfitted objects retain seed estimates and require review. Aspect ratio b/a is apparent 2D shape, not 3D sphericity. Roundness on fitted objects uses ellipse perimeter, not measured contour perimeter. Distribution by number, not validated sieve granulometry.");
            if(distribution!=null) {
                JSONObject scale=new JSONObject();scale.put("original_pixels_per_mm",distribution.settings.originalPixelsPerMm);
                scale.put("analyzed_pixels_per_mm",distribution.analyzedPixelsPerMm);
                scale.put("original_upright_width",distribution.originalWidth);scale.put("original_upright_height",distribution.originalHeight);
                scale.put("source",distribution.settings.calibrationSource);
                scale.put("estimated",distribution.settings.estimated());
                scale.put("belt_reference",ReportJson.beltReferenceJson(distribution.settings.beltReference));
                scale.put("container_reference",ReportJson.containerReferenceJson(distribution.settings.containerReference));
                scale.put("selected_object",ReportJson.objectReferenceJson(distribution.settings.selectedObject,region));
                scale.put("assumed_mean_mm",Double.isFinite(distribution.settings.knownMeanMm)?distribution.settings.knownMeanMm:JSONObject.NULL);
                scale.put("reference_pixels",Double.isFinite(distribution.settings.referencePixels)?distribution.settings.referencePixels:JSONObject.NULL);
                scale.put("reference_mm",Double.isFinite(distribution.settings.referenceMm)?distribution.settings.referenceMm:JSONObject.NULL);if(physical)json.put("physical_scale",scale);
                String suffix=physical?"_mm":"_px";
                JSONObject histogram=new JSONObject();histogram.put("unit",physical?"mm":"px");histogram.put("basis","number");histogram.put("diameter_method",result.method.contains("ellipse_boundary")?"ellipse_equivalent_area_diameter=2*sqrt(semiMajor*semiMinor); unfitted_seed_area_fallback":result.method.startsWith("gradient_circle")?"fitted_circle_diameter; area=pi*r^2":"2*sqrt(projected_area/pi)");
                histogram.put("mean"+suffix,Double.isFinite(distribution.meanMm)?distribution.meanMm:JSONObject.NULL);
                histogram.put("min"+suffix,Double.isFinite(distribution.minMm)?distribution.minMm:JSONObject.NULL);
                histogram.put("max"+suffix,Double.isFinite(distribution.maxMm)?distribution.maxMm:JSONObject.NULL);
                histogram.put("bin_width"+suffix,distribution.settings.binWidthMm);histogram.put("included",distribution.included);histogram.put("excluded",distribution.excluded);
                histogram.put("requested_bin_width"+suffix,distribution.requestedBinWidthMm);
                histogram.put("bin_width_auto_adjusted",distribution.settings.binWidthMm!=distribution.requestedBinWidthMm);
                histogram.put("exclude_partial_edge",distribution.settings.excludePartial);histogram.put("exclude_needs_review",distribution.settings.excludeReview);
                histogram.put("class_convention","lower inclusive, upper exclusive except inclusive final upper edge; displayed classes span observed diameters; boundaries are multiples of bin width");
                histogram.put("percentile_method","linear interpolation at (n-1)*q; distribution by number");
                histogram.put("d10"+suffix,Double.isFinite(distribution.d10)?distribution.d10:JSONObject.NULL);
                histogram.put("d50"+suffix,Double.isFinite(distribution.d50)?distribution.d50:JSONObject.NULL);
                histogram.put("d90"+suffix,Double.isFinite(distribution.d90)?distribution.d90:JSONObject.NULL);
                JSONArray bins=new JSONArray();String grainCsv=ReportTables.histogramCsv(crackHistogram);
                for(int i=0;i<distribution.binCount();i++) {
                    JSONObject bin=new JSONObject();bin.put("lower"+suffix,distribution.lower(i));bin.put("upper"+suffix,distribution.upper(i));
                    bin.put("count",distribution.count(i));bin.put("percent_by_number",distribution.percent(i));bins.put(bin);
                }
                histogram.put("bins",bins);json.put("histogram",histogram);
                Uri grainUri=create(resolver,MediaStore.Downloads.EXTERNAL_CONTENT_URI,base+"_granulometria.csv","text/csv","Download/PelotaVision");pending.add(grainUri);
                try(OutputStream output=stream(resolver,grainUri)){output.write(grainCsv.getBytes(StandardCharsets.UTF_8));}
            }
            JSONObject cfg=new JSONObject();cfg.put("min_radius_fraction",parameters.minRadiusFraction);cfg.put("max_radius_fraction",parameters.maxRadiusFraction);cfg.put("log_threshold",parameters.threshold);json.put("parameters",cfg);
            JSONArray detections=new JSONArray();String csv=ReportTables.pelletCsv(result,cracks,distribution);
            int id=0;for(PelletDetector.Detection d:result.circles) {
                id++;JSONObject item=new JSONObject();item.put("id",id);item.put("x_px",d.x);item.put("y_px",d.y);item.put("radius_px",d.radius);
                item.put("equivalent_diameter_px",d.radius*2);item.put("area_px2",d.area);item.put("roundness",d.roundness);item.put("partial_edge",d.partial);item.put("needs_review",d.needsReview);detections.put(item);
                double diameter=!physical?Double.NaN:distribution.diameterMm(2*d.radius);
                double area=!physical?Double.NaN:d.area/(distribution.analyzedPixelsPerMm*distribution.analyzedPixelsPerMm);
                CrackAnalysis.Entry crack=cracks==null?null:cracks.entries.get(id-1);
                item.put("crack_status",crack==null?"not_analyzed":crack.status.name());item.put("crack_manually_reviewed",crack!=null&&crack.manuallyReviewed);
                item.put("ellipse_major_axis_px",d.ellipseFitted?2*d.semiMajor:JSONObject.NULL);item.put("ellipse_minor_axis_px",d.ellipseFitted?2*d.semiMinor:JSONObject.NULL);item.put("ellipse_angle_deg",d.ellipseFitted?d.angleDeg:JSONObject.NULL);item.put("apparent_sphericity_2d",finite(d.aspectRatio()));item.put("ellipse_fitted",d.ellipseFitted);item.put("included_in_shape_mean",ShapeStatistics.includes(d));
                item.put("ellipse_fit_support",finite(d.fitSupport));item.put("ellipse_fit_rms",finite(d.fitRms));item.put("area_method",d.ellipseFitted?"ellipse_pi_a_b":"seed_area_fallback");item.put("crack_crop_x_px",d.crackX);item.put("crack_crop_y_px",d.crackY);item.put("crack_crop_radius_px",d.crackRadius);
                ObjectCalibration.Axes originalAxes=physical&&d.ellipseFitted?ObjectCalibration.originalAxes(d,distribution.originalWidth,distribution.originalHeight,result.width,result.height):null;
                item.put("ellipse_major_axis_mm",originalAxes!=null?originalAxes.major/distribution.settings.originalPixelsPerMm:JSONObject.NULL);item.put("ellipse_minor_axis_mm",originalAxes!=null?originalAxes.minor/distribution.settings.originalPixelsPerMm:JSONObject.NULL);
                item.put("crack_score",crack!=null&&Double.isFinite(crack.modelScore)?crack.modelScore:JSONObject.NULL);item.put("crack_method",crack==null?"not_analyzed":crack.method);
                boolean included=distribution!=null&&distribution.includes(new Granulometry.Item(2*d.radius,d.partial,d.needsReview));
                item.put("equivalent_diameter_mm",Double.isFinite(diameter)?diameter:JSONObject.NULL);
                item.put("area_mm2",Double.isFinite(area)?area:JSONObject.NULL);item.put("included_in_histogram",included);
            }
            json.put("detections",detections);
            // 0.15.0: relatório PDF montado a partir do mesmo JSON. Falha no PDF não invalida os demais arquivos.
            Uri pdfUri=null,candidate=null;
            try{
                json.put("pdf_file",base+"_relatorio.pdf");
                List<Bitmap> owned=new ArrayList<>();byte[] pdf;
                try{pdf=PdfReport.render(json,PdfReport.figures(json,annotated,filteredPanel,map,owned));}finally{for(Bitmap b:owned)b.recycle();}
                candidate=create(resolver,MediaStore.Downloads.EXTERNAL_CONTENT_URI,base+"_relatorio.pdf","application/pdf","Download/PelotaVision");
                try(OutputStream output=stream(resolver,candidate)){output.write(pdf);}
                pending.add(candidate);pdfUri=candidate;
            }catch(Exception|OutOfMemoryError|LinkageError e){
                if(candidate!=null&&pdfUri==null)try{resolver.delete(candidate,null,null);}catch(Exception ignored){}
                json.put("pdf_file",JSONObject.NULL);json.put("pdf_error",e.getClass().getSimpleName()+": "+e.getMessage());}
            Uri jsonUri=create(resolver,MediaStore.Downloads.EXTERNAL_CONTENT_URI,base+".json","application/json","Download/PelotaVision");pending.add(jsonUri);
            try(OutputStream output=stream(resolver,jsonUri)){output.write(json.toString(2).getBytes(StandardCharsets.UTF_8));}
            Uri csvUri=create(resolver,MediaStore.Downloads.EXTERNAL_CONTENT_URI,base+".csv","text/csv","Download/PelotaVision");pending.add(csvUri);
            try(OutputStream output=stream(resolver,csvUri)){output.write(csv.getBytes(StandardCharsets.UTF_8));}
            ContentValues visible=new ContentValues();visible.put(MediaStore.MediaColumns.IS_PENDING,0);
            for(Uri uri:pending)if(resolver.update(uri,visible,null,null)!=1)throw new IOException("Não foi possível concluir a gravação.");
            return new SavedResult(base,image,pdfUri);
        } catch(Exception|OutOfMemoryError e) {for(Uri uri:pending)try{resolver.delete(uri,null,null);}catch(Exception ignored){}throw e;}
        finally {if(annotated!=null)annotated.recycle();if(map!=null)map.recycle();if(filteredPanel!=null)filteredPanel.recycle();}
    }
    // Repasses: os blocos do JSON ficam em core/ReportJson (comum a Android e Windows).
    static Object detailJson(DetailedAnalysis.Report d)throws Exception{return ReportJson.detailJson(d);}
    static JSONObject processingScopeJson(AnalysisWindow w)throws Exception{return ReportJson.processingScopeJson(w);}
    static JSONObject viewportJson(ReportViewport v)throws Exception{return ReportJson.viewportJson(v);}
    static Object beltReferenceJson(BeltCalibration.Reference ref)throws Exception{return ReportJson.beltReferenceJson(ref);}
    static Object containerReferenceJson(ContainerCalibration.Reference ref)throws Exception{return ReportJson.containerReferenceJson(ref);}
    static Object suggestionScopeJson(DetectionTuner.Report report)throws Exception{return ReportJson.suggestionScopeJson(report);}
    static Object suggestionJson(DetectionTuner.Report report,DetectionTuner.Option applied)throws Exception{return ReportJson.suggestionJson(report,applied);}
    static Object objectReferenceJson(ObjectCalibration.Reference ref,InterestRegion region)throws Exception{return ReportJson.objectReferenceJson(ref,region);}
    static JSONObject shapeJson(PelletDetector.Result result)throws Exception{return ReportJson.shapeJson(result);}
    static JSONObject crackJson(CrackAnalysis cracks)throws Exception{return ReportJson.crackJson(cracks);}
    static Object regionJson(InterestRegion region,int excluded)throws Exception{return ReportJson.regionJson(region,excluded);}
    private static Object finite(double v){return Double.isFinite(v)?v:JSONObject.NULL;}
    private static OutputStream stream(ContentResolver resolver,Uri uri)throws IOException {
        OutputStream output=resolver.openOutputStream(uri,"w");if(output==null)throw new IOException("Não foi possível abrir o destino.");return output;
    }
    private static Uri create(ContentResolver resolver,Uri collection,String name,String mime,String path)throws IOException {
        ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,path);values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=resolver.insert(collection,values);if(uri==null)throw new IOException("Não foi possível criar o arquivo.");return uri;
    }
}
