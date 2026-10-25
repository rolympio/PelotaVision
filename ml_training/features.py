import cv2,numpy as np
from cv4compat import warp_crop
N=96
yy,xx=np.mgrid[:N,:N];inside=((xx-(N-1)/2)**2+(yy-(N-1)/2)**2)<(.78*(N-1)/2)**2
def extract(gray,x,y,r,return_marks=False):
 matrix=np.array([[2*r/(N-1),0,x-r],[0,2*r/(N-1),y-r]],np.float64)
 raw=warp_crop(gray,matrix,N)  # = cv2.warpAffine INTER_LINEAR|WARP_INVERSE_MAP, BORDER_REFLECT_101 do OpenCV 4.x (ver cv4compat.py)
 vals=raw[inside];hist=np.bincount(vals,minlength=256);cdf=hist.cumsum();n=int(inside.sum());lo=int(np.searchsorted(cdf,n*.05));hi=int(np.searchsorted(cdf,n*.95));median=int(np.searchsorted(cdf,n*.5));spread=max(16,hi-lo)
 norm=np.clip(np.rint((raw.astype(float)-lo)*200/spread+25),0,255).astype('uint8');norm[~inside]=int(np.clip(np.rint((median-lo)*200/spread+25),0,255));norm=cv2.GaussianBlur(norm,(3,3),.7)
 f=[];marks=np.zeros((N,N),np.uint8)
 for k in [5,9,15]:
  bh=cv2.morphologyEx(norm,cv2.MORPH_BLACKHAT,cv2.getStructuringElement(cv2.MORPH_ELLIPSE,(k,k)))
  vv=bh[inside];hh=np.bincount(vv,minlength=256).cumsum();f.extend([float(vv.mean())/255,float(np.searchsorted(hh,n*.95))/255,float(np.searchsorted(hh,n*.99))/255])
  for t in [10,20,30]:
   mask=((bh>=t)&inside).astype('uint8');mask=cv2.morphologyEx(mask,cv2.MORPH_CLOSE,np.ones((3,3),np.uint8));mask[~inside]=0
   nc,lab,stats,centers=cv2.connectedComponentsWithStats(mask,8);best_len=best_area=total_area=count=best_elong=best_intensity=0.
   for c in range(1,nc):
    area=int(stats[c,4])
    if area<6:continue
    yx=np.column_stack(np.where(lab==c));cov=np.cov(yx.T);eig=np.linalg.eigvalsh(cov);length=float(np.sqrt(max(0,eig[1])*12));elong=float(np.sqrt((eig[1]+1)/(max(0,eig[0])+1)));thin=area/max(length,1)
    if elong<1.6 or thin>9 or area/n>.20:continue
    best_len=max(best_len,length/N);best_area=max(best_area,area/n);total_area+=area/n;count+=1;best_elong=max(best_elong,min(20,elong)/20);best_intensity=max(best_intensity,float(bh[lab==c].mean())/255)
    if k==9 and t==20 and length>=N*.18 and thin<8:marks[lab==c]=1
   f.extend([best_len,best_area,total_area,min(30,count)/30,best_elong,best_intensity,float(mask.sum())/n])
 return (np.array(f,np.float32),marks,{'spread':hi-lo,'median':median,'mean':float(vals.mean())}) if return_marks else np.array(f,np.float32)

def lighting(im,index):
 if index==0:return im
 g=im.astype(np.float32)/255
 if index==1:g=g**1.55
 elif index==2:g=g**.70
 elif index==3:g=(g-.5)*.65+.5
 elif index==4:
  yy,xx=np.mgrid[:g.shape[0],:g.shape[1]];g=g*(.5+.5*xx/max(1,g.shape[1]-1))
 else:g=cv2.GaussianBlur(g,(3,3),.6)
 return np.clip(np.rint(g*255),0,255).astype('uint8')
