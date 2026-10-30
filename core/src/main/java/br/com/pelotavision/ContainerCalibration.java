package br.com.pelotavision;

/**
 * Scale from the known INNER diameter of a round container (sieve pan, tray, bucket) in upright
 * analysis coordinates. The circle is proposed automatically or placed by the user, and only the
 * user confirms it. The scale is valid only in the plane where the circle was measured: the rim of
 * a deep container is closer to the camera than the pellets, so the rim scale overstates px/mm at
 * the pellet level (pellets would be measured too small). The pellet-level plane uses the wall
 * contact of the outermost pellets, which assumes a cylindrical wall and pellets touching it.
 */
public final class ContainerCalibration {
    private ContainerCalibration() {}
    /** Above this rim ovality the photo is too oblique; the calibration does not rectify perspective. */
    public static final double MAX_OVALITY=.06;
    /**
     * Fraction of the circumference that must lie inside the photo. 0.15.4: a container larger than the
     * frame (webcam 16:9 over a sieve pan) is accepted when the visible arcs surround the center: the
     * diameter across them is measured, not extrapolated.
     */
    public static final double MIN_VISIBLE=.55;
    /** Largest continuous stretch of rim outside the photo; one-sided views (big gap) stay blocked. */
    public static final double MAX_GAP_DEGREES=100;

    public enum Plane {
        RIM("Borda superior interna","rim_inner_edge"),
        PELLET_LEVEL("Nível das pelotas (contato com a parede)","pellet_wall_contact");
        public final String label,code;
        Plane(String label,String code){this.label=label;this.code=code;}
    }

    public static final class Circle {
        public final double cx,cy,r;
        public final boolean automatic;
        public Circle(double cx,double cy,double r,boolean automatic){
            if(!Double.isFinite(cx)||!Double.isFinite(cy)||!Double.isFinite(r)||r<=0)throw new IllegalArgumentException("Círculo inválido.");
            this.cx=cx;this.cy=cy;this.r=r;this.automatic=automatic;
        }
        public Circle moved(double dx,double dy,int w,int h){return new Circle(clamp(cx+dx,0,w-1),clamp(cy+dy,0,h-1),r,false);}
        /** Radius follows the pointer, keeping the center. */
        public Circle resizedTo(double x,double y,double minimum,int w,int h){return new Circle(cx,cy,Math.max(minimum,Math.min(Math.hypot(x-cx,y-cy),Math.max(w,h))),false);}
        /** Handles 0..3 = E,S,W,N on the circle. */
        public double handleX(int i){return cx+(i==0?r:i==2?-r:0);}
        public double handleY(int i){return cy+(i==1?r:i==3?-r:0);}
        /** Equivalent diameter in ORIGINAL pixels; same geometric-mean resize as Granulometry. */
        public double originalDiameter(int ow,int oh,int w,int h){return 2*r*Math.sqrt((double)ow/w*((double)oh/h));}
        /** Fraction of the circumference inside the analyzed photo. */
        public double visibleFraction(int w,int h){int in=0,n=360;for(int i=0;i<n;i++){double t=2*Math.PI*i/n,x=cx+r*Math.cos(t),y=cy+r*Math.sin(t);if(x>=0&&y>=0&&x<=w-1&&y<=h-1)in++;}return in/(double)n;}
        /** Largest continuous arc (degrees) of the circle outside the photo; 0 when fully inside. */
        public double largestGapDegrees(int w,int h){
            int n=360;boolean[] in=new boolean[n];int count=0;
            for(int i=0;i<n;i++){double t=2*Math.PI*i/n,x=cx+r*Math.cos(t),y=cy+r*Math.sin(t);in[i]=x>=0&&y>=0&&x<=w-1&&y<=h-1;if(in[i])count++;}
            if(count==0)return 360;if(count==n)return 0;
            int first=0;while(!in[first])first++;
            int best=0,run=0;for(int k=0;k<n;k++){int i=(first+k)%n;if(in[i]){best=Math.max(best,run);run=0;}else run++;}
            return Math.max(best,run)*360./n;
        }
        /** Null when enough of the rim is inside the photo and on both sides; otherwise the reason. */
        public String framingProblem(int w,int h){
            double v=visibleFraction(w,h),gap=largestGapDegrees(w,h);
            if(v>=MIN_VISIBLE&&gap<=MAX_GAP_DEGREES)return null;
            return String.format(java.util.Locale.ROOT,"O círculo sai da foto: %.0f%% da borda visível, maior trecho fora %.0f°. A borda precisa aparecer em pelo menos %.0f%% e em lados opostos (nenhum trecho fora maior que %.0f°).",100*v,gap,100*MIN_VISIBLE,MAX_GAP_DEGREES);
        }
        public Circle scaled(double sx,double sy){return new Circle(cx*sx,cy*sy,r*Math.sqrt(sx*sy),automatic);}
        public Circle manual(){return new Circle(cx,cy,r,false);}
    }

    /** Automatic proposal; never a confirmed reference. */
    public static final class Detection {
        public final Circle rim,pelletLevel;
        /** (r_max-r_min)/r of the rim, from its second harmonic; NaN if unknown. */
        public final double rimOvality,rimCoverage,pelletCoverage;
        public final String pelletNote;
        Detection(Circle rim,double ovality,double rimCoverage,Circle pelletLevel,double pelletCoverage,String pelletNote){
            this.rim=rim;rimOvality=ovality;this.rimCoverage=rimCoverage;this.pelletLevel=pelletLevel;this.pelletCoverage=pelletCoverage;this.pelletNote=pelletNote;
        }
        public Circle circle(Plane p){return p==Plane.PELLET_LEVEL?pelletLevel:rim;}
        /** How much larger px/mm is at the rim than at the pellet level (0.18 = 18%). NaN if unknown. */
        public double rimOverPelletScale(){return pelletLevel==null?Double.NaN:rim.r/pelletLevel.r-1;}
        /** Maps a detection made on a resized scan back to analysis coordinates. */
        public Detection scaled(double sx,double sy){return new Detection(rim.scaled(sx,sy),rimOvality,rimCoverage,pelletLevel==null?null:pelletLevel.scaled(sx,sy),pelletCoverage,pelletNote);}
    }

    public static final class Reference {
        public final Circle circle;
        public final Plane plane;
        public final double realInnerDiameterMm,originalDiameterPixels,originalPixelsPerMm;
        /** From the automatic rim detection of this photo; NaN when the circles were only placed by hand. */
        public final double rimOvality,rimOverPelletScale;
        /** Automatic proposal for this photo, kept so the dialog can reopen with both planes; null if placed by hand. */
        public final Detection detection;
        public final int originalWidth,originalHeight,width,height;
        public final long createdAt=System.currentTimeMillis();
        private Reference(Circle c,Plane plane,double mm,Detection detection,int ow,int oh,int w,int h){
            if(c==null)throw new IllegalArgumentException("Marque a borda do recipiente.");
            if(plane==null)throw new IllegalArgumentException("Escolha o plano da medida.");
            if(!Double.isFinite(mm)||mm<=0)throw new IllegalArgumentException("Informe o diâmetro interno real do recipiente em mm.");
            if(ow<=0||oh<=0||w<=0||h<=0)throw new IllegalArgumentException("Dimensões da foto indisponíveis.");
            String framing=c.framingProblem(w,h);if(framing!=null)throw new IllegalArgumentException(framing);
            double px=c.originalDiameter(ow,oh,w,h);
            if(px<40)throw new IllegalArgumentException("Recipiente pequeno demais na foto (menos de 40 px na original).");
            double ovality=detection==null?Double.NaN:detection.rimOvality;
            if(Double.isFinite(ovality)&&ovality>MAX_OVALITY)throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,"A borda aparece ovalizada (%.1f%%). Fotografe de cima, com a câmera paralela à boca do recipiente; esta calibração não corrige perspectiva.",100*ovality));
            circle=c;this.plane=plane;realInnerDiameterMm=mm;originalDiameterPixels=px;originalPixelsPerMm=px/mm;
            this.detection=detection;rimOvality=ovality;rimOverPelletScale=detection==null?Double.NaN:detection.rimOverPelletScale();
            originalWidth=ow;originalHeight=oh;width=w;height=h;
        }
        public boolean matches(int ow,int oh,int w,int h){return originalWidth==ow&&originalHeight==oh&&width==w&&height==h;}
        public String summary(){return "Recipiente Ø "+String.format(java.util.Locale.getDefault(),"%.1f",realInnerDiameterMm)+" mm · "+(plane==Plane.RIM?"borda":"nível das pelotas");}
    }

    public static Reference select(Circle c,Plane plane,double mm,Detection detection,int ow,int oh,int w,int h){return new Reference(c,plane,mm,detection,ow,oh,w,h);}
    public static Circle initial(int w,int h){return new Circle((w-1)/2.,(h-1)/2.,Math.min(w,h)*.42,false);}
    /** Plane to propose: the pellet level when it was found, because the rim is not in the pellets' plane. */
    public static Plane suggestedPlane(Detection d){return d!=null&&d.pelletLevel!=null?Plane.PELLET_LEVEL:Plane.RIM;}
    /** Circular ROI just inside the rim (2% margin), shrunk to stay inside the photo; null if too small. */
    public static InterestRegion interiorRegion(Circle rim,int w,int h){
        if(rim==null)return null;double r=Math.min(rim.r*.98,Math.min(Math.min(rim.cx,w-rim.cx),Math.min(rim.cy,h-rim.cy)));
        if(!(r>=8))return null;return new InterestRegion(InterestRegion.Shape.CIRCLE,rim.cx-r,rim.cy-r,rim.cx+r,rim.cy+r);
    }

    /**
     * Correcao empirica da borda ancorada em esferas certificadas na MESMA
     * fotografia e no plano de interesse. Nao extrapolar para outra altura,
     * distancia de camera ou recipiente sem nova verificacao.
     */
    public static final class LevelCorrection {
        public final double borderPxPerMm, spherePxPerMm, factor, discrepancy;
        public final int referenceCount;
        private LevelCorrection(double border, double sphere, int count) {
            if (!Double.isFinite(border) || border <= 0 ||
                    !Double.isFinite(sphere) || sphere <= 0 || count < 1)
                throw new IllegalArgumentException("Referencias de escala invalidas");
            borderPxPerMm=border; spherePxPerMm=sphere;
            factor=sphere/border; discrepancy=Math.abs(factor-1);
            referenceCount=count;
        }
        /** Escala medida no plano das esferas, nunca uma altura fisica inferida. */
        public double correctedPixelsPerMm(){return borderPxPerMm*factor;}
        /** Aviso de diferenca de planos, sem ocultar a divergencia. */
        public boolean requiresReview(){return discrepancy>0.05 || referenceCount<3;}
    }
    public static LevelCorrection calibrateLevel(double borderPxPerMm,
            double spherePxPerMm,int confirmedSphereCount) {
        return new LevelCorrection(borderPxPerMm,spherePxPerMm,confirmedSphereCount);
    }

    private static double clamp(double v,double a,double b){return Math.max(a,Math.min(b,v));}
}
