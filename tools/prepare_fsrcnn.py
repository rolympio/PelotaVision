from pathlib import Path
import struct,json,hashlib
import argparse
parser=argparse.ArgumentParser();parser.add_argument('input');parser.add_argument('output');args=parser.parse_args()
src=Path(args.input).read_bytes()
def readvar(b,p):
 n=0;s=0
 while True:
  v=b[p];p+=1;n|=(v&127)<<s
  if v<128:return n,p
  s+=7

def fields(b):
 p=0
 while p<len(b):
  start=p;k,p=readvar(b,p);n=k>>3;t=k&7
  if t==2:l,p=readvar(b,p);v=b[p:p+l];p+=l
  elif t==0:v,p=readvar(b,p)
  elif t==5:v=b[p:p+4];p+=4
  elif t==1:v=b[p:p+8];p+=8
  else:raise ValueError(t)
  yield n,t,v,b[start:p]

def txt(b,n):return next((v.decode() for k,t,v,raw in fields(b) if k==n and t==2),'')
nodes={}
for n,t,v,raw in fields(src):
 if n==1:nodes[txt(v,1)]={'op':txt(v,2),'inputs':[vv.decode() for k,tt,vv,rr in fields(v) if k==3],'value':v,'raw':raw}
for name,data in nodes.items():
 if data['op']!='Const':print(name,data['op'],data['inputs'])
print('last bias',nodes['NHWC_output']['inputs'])
# Graph ends at conv8. Remove the custom DepthToSpace and output layout nodes; Java performs exactly the original shuffle and scalar bias.
removed={'DepthToSpace','NHWC_output','NCHW_output'}
output=b''.join(raw for n,t,v,raw in fields(src) if n!=1 or txt(v,1) not in removed)
Path(args.output).write_bytes(output)
biasname=nodes['NHWC_output']['inputs'][1]
print('bias node',biasname)
for k,t,v,raw in fields(nodes[biasname]['value']):
 if k==5:
  key=txt(v,1)
  if key=='value':
   av=next(vv for kk,tt,vv,rr in fields(v) if kk==2)
   tensor=next(vv for kk,tt,vv,rr in fields(av) if kk==8)
   for kk,tt,vv,rr in fields(tensor):
    if kk in (4,5):print('bias bytes',kk,tt,vv.hex() if isinstance(vv,bytes) else vv)
print('original_sha256',hashlib.sha256(src).hexdigest());print('packaged_sha256',hashlib.sha256(output).hexdigest())
