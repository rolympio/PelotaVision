package br.com.pelotavision;

import java.util.Arrays;

/**
 * Finds the inner edge of a round container's rim in a grayscale scan (long side about 480-800 px)
 * and, inside it, the circle where the outermost pellets meet the wall. Proposes geometry only;
 * the user confirms it. Pure Java so Android and Windows share the same result.
 *
 * 1. Gradient Hough voting for the center of large circles (pellets are too small to win).
 * 2. Radial profile: the radius whose edge persists along most of the circumference (30th
 *    percentile of the signed radial derivative), innermost of the strong rings near the best one.
 * 3. Per-ray sub-pixel edge, robust circle fit, coverage and ovality (second harmonic).
 * 4. Pellet level: per ray, the first crossing from wall to material going inward; circle fitted
 *    to the outer envelope (inner points fall in gaps between pellets and are trimmed).
 */
public final class ContainerRimDetector {
    private ContainerRimDetector() {}
    static final int RAYS=360;
    /** Half-width of the radial search, as a fraction of the radius; covers rims up to about 16% ovality. */
    static final double WINDOW=.09;

    public static ContainerCalibration.Detection detect(byte[] gray,int w,int h){
        if(gray==null||gray.length!=w*h||Math.min(w,h)<64)return null;
        float[] s=blur(blur(toFloat(gray),w,h),w,h);
        // 0.15.4: the rim may be larger than the short side (16:9 webcam over a pan): search up to the long side.
        int m=Math.min(w,h),rMin=Math.max(12,(int)(.15*m)),rMax=(int)(.53*Math.max(w,h));
        int[][] centers=centers(s,w,h,rMin,rMax);
        Ring best=null;
        for(int[] c:centers){Ring ring=ring(s,w,h,c[0],c[1],rMin,rMax);if(ring!=null&&(best==null||ring.score>best.score))best=ring;}
        if(best==null||best.score<6)return null;
        Fit rim=refineRim(s,w,h,best);
        if(rim==null)return null;
        ContainerCalibration.Circle rimCircle=new ContainerCalibration.Circle(rim.cx,rim.cy,rim.r,true);
        // Same framing rule as the confirmation: no proposal the user could not commit.
        if(rimCircle.framingProblem(w,h)!=null)return null;
        Fit level=pelletLevel(s,w,h,rim,best.polarity);
        String note;ContainerCalibration.Circle levelCircle=null;double levelCoverage=Double.NaN;
        if(level==null)note="Nível das pelotas não encontrado: pouco contraste entre parede e material ou pelotas longe da parede.";
        else {levelCircle=new ContainerCalibration.Circle(level.cx,level.cy,level.r,true);levelCoverage=level.coverage;note="Envoltória das pelotas periféricas: supõe parede cilíndrica e pelotas encostadas nela.";}
        return new ContainerCalibration.Detection(rimCircle,rim.ovality,rim.coverage,levelCircle,levelCoverage,note);
    }

    // ---------- 1. center voting ----------
    static int[][] centers(float[] s,int w,int h,int rMin,int rMax){
        float[] gx=new float[w*h],gy=new float[w*h],mag=new float[w*h];
        for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){int i=y*w+x;
            float a=s[i-w-1],b=s[i-w],c=s[i-w+1],d=s[i-1],f=s[i+1],g=s[i+w-1],k=s[i+w],l=s[i+w+1];
            gx[i]=(c+2*f+l)-(a+2*d+g);gy[i]=(g+2*k+l)-(a+2*b+c);mag[i]=(float)Math.hypot(gx[i],gy[i]);}
        float[] sorted=mag.clone();Arrays.sort(sorted);float threshold=Math.max(24,sorted[(int)(sorted.length*.85)]);
        int[] acc=new int[w*h];int step=Math.max(1,(rMax-rMin)/160);
        for(int y=2;y<h-2;y++)for(int x=2;x<w-2;x++){int i=y*w+x;if(mag[i]<threshold)continue;
            double dx=gx[i]/mag[i],dy=gy[i]/mag[i];
            for(int sign=-1;sign<=1;sign+=2)for(int r=rMin;r<=rMax;r+=step){int px=(int)Math.round(x+sign*dx*r),py=(int)Math.round(y+sign*dy*r);if(px<0||py<0||px>=w||py>=h)break;acc[py*w+px]++;}}
        float[] sm=blur(blur(toFloat(acc),w,h),w,h);
        int[][] out=new int[6][];int found=0,sep=Math.max(4,Math.min(w,h)/20);
        boolean[] used=new boolean[w*h];
        while(found<out.length){int bi=-1;float bv=0;for(int i=0;i<sm.length;i++)if(!used[i]&&sm[i]>bv){bv=sm[i];bi=i;}
            if(bi<0||bv<=0)break;int bx=bi%w,by=bi/w;out[found++]=new int[]{bx,by};
            for(int y=Math.max(0,by-sep);y<=Math.min(h-1,by+sep);y++)for(int x=Math.max(0,bx-sep);x<=Math.min(w-1,bx+sep);x++)used[y*w+x]=true;}
        return Arrays.copyOf(out,found);
    }

    // ---------- 2. persistent ring ----------
    static final class Ring{double cx,cy,r,score;int polarity;}
    static Ring ring(float[] s,int w,int h,double cx,double cy,int rMin,int rMax){
        // Signed radial derivative per ray (positive = brighter outward), then each radius scores the
        // 30th percentile over rays of the strongest edge within +-WINDOW: tolerates moderate ovality.
        int n=rMax+2,lo=(int)Math.floor(rMin*(1-WINDOW))-2,hi=(int)Math.ceil(rMax*(1+WINDOW))+2;
        double[][] d=new double[RAYS][];
        for(int k=0;k<RAYS;k++){double t=2*Math.PI*k/RAYS,c=Math.cos(t),si=Math.sin(t);d[k]=new double[hi+2];
            for(int r=Math.max(2,lo);r<=hi;r++){double x0=cx+(r-1.5)*c,y0=cy+(r-1.5)*si,x1=cx+(r+1.5)*c,y1=cy+(r+1.5)*si;
                d[k][r]=inside(x0,y0,w,h)&&inside(x1,y1,w,h)?(sample(s,w,h,x1,y1)-sample(s,w,h,x0,y0))/3:Double.NaN;}}
        double[] pos=new double[n],neg=new double[n],bp=new double[RAYS],bn=new double[RAYS];
        for(int r=rMin;r<=rMax;r++){int count=0,a=Math.max(2,(int)Math.floor(r*(1-WINDOW))),b=(int)Math.ceil(r*(1+WINDOW));
            for(int k=0;k<RAYS;k++){if(!Double.isFinite(d[k][r]))continue;double mp=-1e9,mn=-1e9;for(int q=a;q<=b;q++){double v=d[k][q];if(!Double.isFinite(v))continue;if(v>mp)mp=v;if(-v>mn)mn=-v;}bp[count]=mp;bn[count]=mn;count++;}
            if(count<RAYS*ContainerCalibration.MIN_VISIBLE)continue;
            Arrays.sort(bp,0,count);Arrays.sort(bn,0,count);pos[r]=bp[(int)(count*.30)];neg[r]=bn[(int)(count*.30)];}
        Ring best=null;
        for(int polarity=-1;polarity<=1;polarity+=2){double[] p=polarity>0?pos:neg;int bi=-1;
            for(int r=rMin;r<=rMax;r++)if(bi<0||p[r]>p[bi])bi=r;
            if(bi<0||p[bi]<=0)continue;
            // The window widens the plateau; take its middle, then prefer the innermost strong ring (a rolled lip has two edges).
            int a=bi,b=bi;while(a>rMin&&p[a-1]>=p[bi]*.97)a--;while(b<rMax&&p[b+1]>=p[bi]*.97)b++;int mid=(a+b)/2;
            int chosen=mid;double inner=mid*(1-2*WINDOW-.04);for(int r=(int)inner;r<a;r++){if(r<=rMin||r>=rMax)continue;if(p[r]>=.45*p[bi]&&p[r]>=p[r-1]&&p[r]>=p[r+1]&&p[r]<p[bi]*.9&&localMinBetween(p,r,a)){chosen=r;break;}}
            if(best==null||p[bi]>best.score){best=new Ring();best.cx=cx;best.cy=cy;best.r=chosen;best.score=p[bi];best.polarity=polarity;}}
        return best;
    }
    /** A separate ring must be separated from the main plateau by a dip. */
    static boolean localMinBetween(double[] p,int r,int a){double min=Double.MAX_VALUE;for(int q=r+1;q<a;q++)min=Math.min(min,p[q]);return min<p[r]*.8;}

    // ---------- 3. per-ray rim edge ----------
    static final class Fit{double cx,cy,r,coverage,ovality;}
    static Fit refineRim(float[] s,int w,int h,Ring ring){
        double minStrength=Math.max(4,ring.score*.35);
        double cx=ring.cx,cy=ring.cy;double[] model=new double[RAYS];Arrays.fill(model,ring.r);double window=WINDOW;
        double[] rho=new double[RAYS],coef=null;
        for(int pass=0;pass<3;pass++){
            for(int k=0;k<RAYS;k++)rho[k]=edge(s,w,h,cx,cy,k,model[k]*(1-window),model[k]*(1+window),ring.polarity,minStrength);
            coef=harmonics(rho);if(coef==null)return null;
            // Move the center by the first harmonic, then search again near the fitted model.
            cx+=coef[1];cy+=coef[2];for(int k=0;k<RAYS;k++){double t=2*Math.PI*k/RAYS;model[k]=coef[0]+coef[3]*Math.cos(2*t)+coef[4]*Math.sin(2*t);}
            window=.035;
        }
        for(int k=0;k<RAYS;k++)rho[k]=edge(s,w,h,cx,cy,k,model[k]*(1-window),model[k]*(1+window),ring.polarity,minStrength);
        coef=harmonics(rho);if(coef==null)return null;
        Fit f=new Fit();f.cx=cx+coef[1];f.cy=cy+coef[2];f.r=coef[0];f.ovality=2*Math.hypot(coef[3],coef[4])/coef[0];
        int good=0,visible=0;double tol=Math.max(1,.02*coef[0]);
        for(int k=0;k<RAYS;k++){double tv=2*Math.PI*k/RAYS;if(inside(f.cx+f.r*Math.cos(tv),f.cy+f.r*Math.sin(tv),w,h))visible++;}
        for(int k=0;k<RAYS;k++){if(!Double.isFinite(rho[k]))continue;double t=2*Math.PI*k/RAYS,m=coef[0]+coef[1]*Math.cos(t)+coef[2]*Math.sin(t)+coef[3]*Math.cos(2*t)+coef[4]*Math.sin(2*t);if(Math.abs(rho[k]-m)<=tol)good++;}
        // Coverage of the VISIBLE rim (equals the whole rim when the container is fully framed).
        f.coverage=visible==0?0:Math.min(1,good/(double)visible);
        if(visible<RAYS*ContainerCalibration.MIN_VISIBLE||f.coverage<.6)return null;
        return f;
    }
    /** Strongest edge of the given polarity along one ray, sub-pixel; NaN if weak or outside. */
    static double edge(float[] s,int w,int h,double cx,double cy,int k,double from,double to,int polarity,double minStrength){
        double t=2*Math.PI*k/RAYS,c=Math.cos(t),si=Math.sin(t);int a=(int)Math.floor(from),b=(int)Math.ceil(to);if(a<3)a=3;if(b<=a)return Double.NaN;
        double[] d=new double[b-a+3];double bestV=-1e9;int bestR=-1;
        for(int r=a-1;r<=b+1;r++){double x0=cx+(r-1.5)*c,y0=cy+(r-1.5)*si,x1=cx+(r+1.5)*c,y1=cy+(r+1.5)*si;
            d[r-a+1]=inside(x0,y0,w,h)&&inside(x1,y1,w,h)?polarity*(sample(s,w,h,x1,y1)-sample(s,w,h,x0,y0))/3:Double.NaN;}
        for(int r=a;r<=b;r++){double v=d[r-a+1];if(Double.isFinite(v)&&v>bestV){bestV=v;bestR=r;}}
        if(bestR<0||bestV<minStrength)return Double.NaN;
        double d0=d[bestR-a],d2=d[bestR-a+2],den=d0-2*bestV+d2;
        double off=Double.isFinite(d0)&&Double.isFinite(d2)&&den<0?Math.max(-.5,Math.min(.5,.5*(d0-d2)/den)):0;
        return bestR+off;
    }
    /**
     * Robust least squares of r(t) = r0 + a1 cos t + b1 sin t + a2 cos 2t + b2 sin 2t over the valid rays.
     * a1,b1 = center shift; a2,b2 = ovality. Iteratively drops residuals beyond 3 MAD.
     */
    static double[] harmonics(double[] rho){
        int n=rho.length;boolean[] keep=new boolean[n];int valid=0;for(int k=0;k<n;k++){keep[k]=Double.isFinite(rho[k]);if(keep[k])valid++;}
        if(valid<n*.5)return null;double[] c=null;double[] res=new double[n];
        for(int it=0;it<8;it++){
            double[][] A=new double[5][6];
            for(int k=0;k<n;k++){if(!keep[k])continue;double t=2*Math.PI*k/n;double[] row={1,Math.cos(t),Math.sin(t),Math.cos(2*t),Math.sin(2*t)};
                for(int i=0;i<5;i++){for(int j=0;j<5;j++)A[i][j]+=row[i]*row[j];A[i][5]+=row[i]*rho[k];}}
            double[] next=solve(A);if(next==null)return c;c=next;
            int m=0;double[] abs=new double[n];
            for(int k=0;k<n;k++){if(!Double.isFinite(rho[k]))continue;double t=2*Math.PI*k/n;res[k]=rho[k]-(c[0]+c[1]*Math.cos(t)+c[2]*Math.sin(t)+c[3]*Math.cos(2*t)+c[4]*Math.sin(2*t));abs[m++]=Math.abs(res[k]);}
            double[] sorted=Arrays.copyOf(abs,m);Arrays.sort(sorted);double mad=Math.max(.35,sorted[m/2]);int kept=0;
            for(int k=0;k<n;k++){keep[k]=Double.isFinite(rho[k])&&Math.abs(res[k])<=3*mad;if(keep[k])kept++;}
            if(kept<n*.3)return null;
        }
        return c;
    }
    static double[] solve(double[][] a){
        int n=a.length;for(int i=0;i<n;i++){int p=i;for(int r=i+1;r<n;r++)if(Math.abs(a[r][i])>Math.abs(a[p][i]))p=r;double[] tmp=a[i];a[i]=a[p];a[p]=tmp;if(Math.abs(a[i][i])<1e-12)return null;
            for(int r=0;r<n;r++){if(r==i)continue;double f=a[r][i]/a[i][i];for(int c=i;c<=n;c++)a[r][c]-=f*a[i][c];}}
        double[] x=new double[n];for(int i=0;i<n;i++)x[i]=a[i][n]/a[i][i];return x;
    }

    // ---------- 4. pellet level ----------
    static Fit pelletLevel(float[] s,int w,int h,Fit rim,int polarity){
        double gap=Math.max(3,rim.r*.025),band=Math.max(4,rim.r*.04);
        double[] interior=new double[RAYS*8];int ni=0;
        for(int k=0;k<RAYS;k+=3)for(int j=0;j<8;j++){double t=2*Math.PI*k/RAYS,rr=rim.r*(.05+.45*j/7),x=rim.cx+rr*Math.cos(t),y=rim.cy+rr*Math.sin(t);if(inside(x,y,w,h))interior[ni++]=sample(s,w,h,x,y);}
        if(ni<20)return null;Arrays.sort(interior,0,ni);double material=interior[ni/2];
        double[] xs=new double[RAYS],ys=new double[RAYS];int n=0;
        for(int k=0;k<RAYS;k++){double t=2*Math.PI*k/RAYS,c=Math.cos(t),si=Math.sin(t);
            double r0=rim.r-gap;double[] wall=new double[8];int nw=0;
            for(int j=0;j<8;j++){double rr=r0-band*j/7.,x=rim.cx+rr*c,y=rim.cy+rr*si;if(inside(x,y,w,h))wall[nw++]=sample(s,w,h,x,y);}
            if(nw<6)continue;Arrays.sort(wall,0,nw);double wallLevel=wall[nw/2];
            if(Math.abs(material-wallLevel)<15)continue;
            double cut=wallLevel+.5*(material-wallLevel),sign=Math.signum(material-wallLevel),prev=wallLevel;
            for(double rr=r0;rr>rim.r*.5;rr-=.5){double x=rim.cx+rr*c,y=rim.cy+rr*si;if(!inside(x,y,w,h))break;double v=sample(s,w,h,x,y);
                if(sign*(v-cut)>0){double ahead=sample(s,w,h,rim.cx+(rr-1.5)*c,rim.cy+(rr-1.5)*si);if(sign*(ahead-cut)<=0){prev=v;continue;}
                    double frac=(cut-prev)/(v-prev);double edge=rr+.5*(1-Math.max(0,Math.min(1,frac)));xs[n]=rim.cx+edge*c;ys[n]=rim.cy+edge*si;n++;break;}
                prev=v;}}
        int visibleRays=0;for(int k=0;k<RAYS;k++){double t=2*Math.PI*k/RAYS;if(inside(rim.cx+rim.r*.9*Math.cos(t),rim.cy+rim.r*.9*Math.sin(t),w,h))visibleRays++;}
        if(n<visibleRays*.5)return null;
        Fit f=robustCircle(xs,ys,n,true);
        if(f==null)return null;
        double ratio=f.r/rim.r,offset=Math.hypot(f.cx-rim.cx,f.cy-rim.cy)/rim.r;
        // Sector coverage relative to the sectors that are inside the photo at the pellet level.
        int visibleSectors=0;for(int q=0;q<12;q++){double t=2*Math.PI*(q+.5)/12;if(inside(f.cx+f.r*Math.cos(t),f.cy+f.r*Math.sin(t),w,h))visibleSectors++;}
        double sectorCoverage=visibleSectors==0?0:f.coverage*12/visibleSectors;
        if(ratio<.55||ratio>1.0||offset>.12||sectorCoverage<.75)return null;
        f.coverage=Math.min(1,sectorCoverage);
        return f;
    }

    /** Algebraic fit, then trimmed refits. Envelope mode keeps the OUTER points (pellet silhouettes touching the wall). */
    static Fit robustCircle(double[] xs,double[] ys,int n,boolean envelope){
        boolean[] keep=new boolean[n];Arrays.fill(keep,true);double[] c=kasa(xs,ys,keep,n);if(c==null)return null;
        double[] res=new double[n];
        for(int it=0;it<12;it++){
            for(int i=0;i<n;i++)res[i]=Math.hypot(xs[i]-c[0],ys[i]-c[1])-c[2];
            double[] sorted=res.clone();Arrays.sort(sorted);
            if(envelope){double lo=sorted[(int)(n*.40)],hi=sorted[Math.min(n-1,(int)(n*.98))];for(int i=0;i<n;i++)keep[i]=res[i]>=lo&&res[i]<=hi;}
            else{double[] abs=new double[n];for(int i=0;i<n;i++)abs[i]=Math.abs(res[i]-sorted[n/2]);Arrays.sort(abs);double mad=Math.max(.35,abs[n/2]);for(int i=0;i<n;i++)keep[i]=Math.abs(res[i]-sorted[n/2])<=3*mad;}
            double[] next=kasa(xs,ys,keep,n);if(next==null)return null;
            boolean done=Math.abs(next[0]-c[0])+Math.abs(next[1]-c[1])+Math.abs(next[2]-c[2])<1e-4;c=next;if(done)break;}
        Fit f=new Fit();f.cx=c[0];f.cy=c[1];f.r=c[2];
        double tol=Math.max(1,.02*c[2]);boolean[] sector=new boolean[RAYS];boolean[] sectors12=new boolean[12];
        double sa=0,sc=0,ss=0,scc=0,sss=0,scs=0;int m=0;
        for(int i=0;i<n;i++){double dx=xs[i]-c[0],dy=ys[i]-c[1],res0=Math.hypot(dx,dy)-c[2];if(Math.abs(res0)>tol)continue;
            double t=Math.atan2(dy,dx);int k=(int)Math.floor(((t+2*Math.PI)%(2*Math.PI))/(2*Math.PI)*RAYS)%RAYS;sector[k]=true;sectors12[k*12/RAYS]=true;
            double c2=Math.cos(2*t),s2=Math.sin(2*t);sa+=res0;sc+=res0*c2;ss+=res0*s2;scc+=c2*c2;sss+=s2*s2;scs+=c2*s2;m++;}
        int covered=0;for(boolean b:sector)if(b)covered++;
        f.coverage=covered/(double)RAYS;
        if(envelope){int s12=0;for(boolean b:sectors12)if(b)s12++;f.coverage=s12/12.;}
        // r(t) - r = a cos2t + b sin2t; least squares on the 2x2 normal equations.
        double det=scc*sss-scs*scs;if(m>10&&Math.abs(det)>1e-9){double a=(sc*sss-ss*scs)/det,b=(ss*scc-sc*scs)/det;f.ovality=2*Math.hypot(a,b)/c[2];}else f.ovality=Double.NaN;
        return f;
    }
    static double[] kasa(double[] xs,double[] ys,boolean[] keep,int n){
        double mx=0,my=0;int m=0;for(int i=0;i<n;i++)if(keep[i]){mx+=xs[i];my+=ys[i];m++;}if(m<8)return null;mx/=m;my/=m;
        double suu=0,svv=0,suv=0,suuu=0,svvv=0,suvv=0,svuu=0;
        for(int i=0;i<n;i++){if(!keep[i])continue;double u=xs[i]-mx,v=ys[i]-my;suu+=u*u;svv+=v*v;suv+=u*v;suuu+=u*u*u;svvv+=v*v*v;suvv+=u*v*v;svuu+=v*u*u;}
        double det=suu*svv-suv*suv;if(Math.abs(det)<1e-12)return null;
        double b1=.5*(suuu+suvv),b2=.5*(svvv+svuu),uc=(b1*svv-b2*suv)/det,vc=(b2*suu-b1*suv)/det;
        double r=Math.sqrt(uc*uc+vc*vc+(suu+svv)/m);return Double.isFinite(r)&&r>0?new double[]{uc+mx,vc+my,r}:null;
    }

    // ---------- helpers ----------
    static boolean inside(double x,double y,int w,int h){return x>=0&&y>=0&&x<=w-1&&y<=h-1;}
    static double sample(float[] a,int w,int h,double x,double y){
        int x0=(int)Math.floor(x),y0=(int)Math.floor(y),x1=Math.min(w-1,x0+1),y1=Math.min(h-1,y0+1);x0=Math.max(0,x0);y0=Math.max(0,y0);double fx=x-Math.floor(x),fy=y-Math.floor(y);
        return (a[y0*w+x0]*(1-fx)+a[y0*w+x1]*fx)*(1-fy)+(a[y1*w+x0]*(1-fx)+a[y1*w+x1]*fx)*fy;
    }
    static float[] toFloat(byte[] g){float[] f=new float[g.length];for(int i=0;i<g.length;i++)f[i]=g[i]&255;return f;}
    static float[] toFloat(int[] g){float[] f=new float[g.length];for(int i=0;i<g.length;i++)f[i]=g[i];return f;}
    /** Separable 1-2-1 blur, edges clamped. */
    static float[] blur(float[] a,int w,int h){
        float[] t=new float[a.length],o=new float[a.length];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int l=Math.max(0,x-1),r=Math.min(w-1,x+1);t[y*w+x]=(a[y*w+l]+2*a[y*w+x]+a[y*w+r])/4;}
        for(int y=0;y<h;y++){int u=Math.max(0,y-1),d=Math.min(h-1,y+1);for(int x=0;x<w;x++)o[y*w+x]=(t[u*w+x]+2*t[y*w+x]+t[d*w+x])/4;}
        return o;
    }
}
