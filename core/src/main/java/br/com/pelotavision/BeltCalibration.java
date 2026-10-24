package br.com.pelotavision;

/** Belt edges in upright analysis coordinates. Width is measured normal to the belt axis. */
public final class BeltCalibration {
    private BeltCalibration() {}
    public static final class Edges {
        // First edge start/end, then opposite edge start/end. Arrays are never exposed.
        private final double[] points;
        public final boolean automatic;
        public Edges(double[] points,boolean automatic) {
            if(points==null||points.length!=8)throw new IllegalArgumentException("Marque as duas bordas da correia.");
            this.points=points.clone();this.automatic=automatic;
            for(double v:points)if(!Double.isFinite(v))throw new IllegalArgumentException("Bordas inválidas.");
        }
        public double x(int i){return points[i*2];} public double y(int i){return points[i*2+1];}
        public Edges moved(int handle,double dx,double dy,int w,int h) {
            double[] p=points.clone();
            if(handle<4){p[2*handle]=clamp(p[2*handle]+dx,0,w-1);p[2*handle+1]=clamp(p[2*handle+1]+dy,0,h-1);}
            else {int first=handle==4?0:2;double minX=Math.min(x(first),x(first+1)),maxX=Math.max(x(first),x(first+1)),minY=Math.min(y(first),y(first+1)),maxY=Math.max(y(first),y(first+1));dx=clamp(dx,-minX,w-1-maxX);dy=clamp(dy,-minY,h-1-maxY);for(int i=first;i<first+2;i++){p[2*i]+=dx;p[2*i+1]+=dy;}}
            return new Edges(p,false);
        }
        double[] original(int ow,int oh,int w,int h){double[] p=points.clone();for(int i=0;i<4;i++){p[2*i]*=(double)ow/w;p[2*i+1]*=(double)oh/h;}return p;}
    }
    public static final class Reference {
        public final Edges edges;
        public final int originalWidth,originalHeight,width,height;
        public final double realWidthMm,originalWidthPixels,originalPixelsPerMm,widthVariation;
        public final long createdAt=System.currentTimeMillis();
        private Reference(Edges edges,double mm,int ow,int oh,int w,int h) {
            if(!Double.isFinite(mm)||mm<=0||ow<=0||oh<=0||w<=0||h<=0)throw new IllegalArgumentException("Informe a largura real da correia em mm.");
            for(int i=0;i<4;i++)if(edges.x(i)<0||edges.x(i)>w-1||edges.y(i)<0||edges.y(i)>h-1)throw new IllegalArgumentException("As alças devem ficar dentro da foto.");
            double[] p=edges.original(ow,oh,w,h),measure=measure(p);
            originalWidthPixels=measure[0];widthVariation=measure[1];
            if(originalWidthPixels<8||measure[2]<8)throw new IllegalArgumentException("Afaste as bordas e as alças ao longo da correia.");
            if(widthVariation>.08)throw new IllegalArgumentException("A largura aparente varia mais de 8%. Confira as alças ou fotografe mais perpendicularmente; esta calibração não corrige perspectiva.");
            this.edges=edges;realWidthMm=mm;originalWidth=ow;originalHeight=oh;width=w;height=h;originalPixelsPerMm=originalWidthPixels/mm;
        }
        public boolean matches(int ow,int oh,int w,int h){return originalWidth==ow&&originalHeight==oh&&width==w&&height==h;}
    }
    public static Reference select(Edges edges,double mm,int ow,int oh,int w,int h){if(edges==null)throw new IllegalArgumentException("Marque as bordas.");return new Reference(edges,mm,ow,oh,w,h);}
    /** Perpendicular dimension line, even when the user moves one edge along the belt. */
    static double[] crossSection(Edges e){
        double ax=e.x(1)-e.x(0),ay=e.y(1)-e.y(0),bx=e.x(3)-e.x(2),by=e.y(3)-e.y(2),a=Math.hypot(ax,ay),b=Math.hypot(bx,by);
        if(a<1||b<1)return null;double tx=ax/a+bx/b,ty=ay/a+by/b,n=Math.hypot(tx,ty);if(n<.1)return null;tx/=n;ty/=n;
        double mx=(e.x(0)+e.x(1))/2,my=(e.y(0)+e.y(1))/2,ox=(e.x(2)+e.x(3))/2,oy=(e.y(2)+e.y(3))/2,target=((mx+ox)*tx+(my+oy)*ty)/2;
        double da=ax*tx+ay*ty,db=bx*tx+by*ty;if(Math.abs(da)<1||Math.abs(db)<1)return null;
        double u=(target-mx*tx-my*ty)/da,v=(target-ox*tx-oy*ty)/db;return new double[]{mx+u*ax,my+u*ay,ox+v*bx,oy+v*by};
    }
    /** Returns perpendicular width, relative variation, shortest longitudinal edge. */
    static double[] measure(double[] p){
        double ax=p[2]-p[0],ay=p[3]-p[1],bx=p[6]-p[4],by=p[7]-p[5],la=Math.hypot(ax,ay),lb=Math.hypot(bx,by);
        if(la<1||lb<1||(ax*bx+ay*by)/(la*lb)<.9)throw new IllegalArgumentException("As duas bordas devem seguir a mesma direção; evite cruzar as linhas.");
        double tx=ax/la+bx/lb,ty=ay/la+by/lb,n=Math.hypot(tx,ty),nx=-ty/n,ny=tx/n;
        double a=(p[4]-p[0])*nx+(p[5]-p[1])*ny,b=(p[6]-p[2])*nx+(p[7]-p[3])*ny;
        if(a*b<=0)throw new IllegalArgumentException("As bordas não podem se cruzar.");
        double width=(Math.abs(a)+Math.abs(b))/2;
        return new double[]{width,Math.abs(a-b)/width,Math.min(la,lb)};
    }
    public static Edges initial(int w,int h,boolean horizontal){return horizontal?new Edges(new double[]{w*.12,h*.18,w*.88,h*.18,w*.12,h*.82,w*.88,h*.82},false):new Edges(new double[]{w*.18,h*.12,w*.18,h*.88,w*.82,h*.12,w*.82,h*.88},false);}
    private static double clamp(double v,double a,double b){return Math.max(a,Math.min(b,v));}
}
