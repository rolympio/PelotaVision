from pathlib import Path
import json,math
import numpy as np,cv2
from PIL import Image,ImageDraw,ImageFont
root=Path('coverage_update');image=np.asarray(Image.open('upload/01-1000419289.webp').convert('RGB'));h,w=image.shape[:2]
old=json.loads((root/'baseline/old_auto_1536.json').read_text());manual=json.loads((root/'baseline/1536_log_p1_s74.json').read_text());new=json.loads((root/'new-results/suggested_1536.json').read_text())
def match(a,b):
 used=set();count=0
 for o in a:
  best=None;distance=.35
  for i,p in enumerate(b):
   if i in used:continue
   ratio=o['radius']/p['radius'];d=math.hypot(o['x']-p['x'],o['y']-p['y'])/(o['radius']+p['radius'])
   if .7<ratio<1.43 and d<distance:best=i;distance=d
  if best is not None:used.add(best);count+=1
 return count
matches=match(manual['objects'],new['objects']);assert matches>=.95*len(manual['objects']),(matches,len(manual['objects']))
font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',26);small=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',21)
rows=[]
for title,data in [('Automático 0.8.0 — reprodução no arquivo recebido',old),('Manual de referência — LoG médio, sensibilidade 74%',manual),('Automático corrigido 0.8.1 — LoG médio, sensibilidade 90%',new)]:
 panel=image.copy()
 for i,o in enumerate(data['objects']):
  good=o['fitted'] and not(o['partial'] or o['review']);color=(83,225,170) if good else (255,205,94)
  cv2.ellipse(panel,(round(o['x']),round(o['y'])),(round(o['a']),round(o['b'])),o['angle'],0,360,color,2,cv2.LINE_AA)
  cv2.putText(panel,str(i+1),(round(o['x'])-10,round(o['y'])+4),cv2.FONT_HERSHEY_SIMPLEX,.38,(0,0,0),3,cv2.LINE_AA);cv2.putText(panel,str(i+1),(round(o['x'])-10,round(o['y'])+4),cv2.FONT_HERSHEY_SIMPLEX,.38,(255,255,255),1,cv2.LINE_AA)
 row=Image.new('RGB',(w,h+90),(16,26,35));draw=ImageDraw.Draw(row);draw.text((20,10),title,fill='white',font=font);draw.text((20,49),f"{len(data['objects'])} detecções · {sum(o['fitted'] and not(o['partial'] or o['review']) for o in data['objects'])} elipses válidas · verde: válidas; amarelo: revisar",fill=(185,203,214),font=small);row.paste(Image.fromarray(panel),(0,90));rows.append(row)
header=Image.new('RGB',(w,125),(16,26,35));d=ImageDraw.Draw(header);d.text((20,13),'PelotaVision 0.8.1 · comparação no mesmo arquivo original',font=font,fill='white');d.text((20,56),'Contagens são propostas do algoritmo; não são uma contagem verdadeira validada.',font=small,fill=(185,203,214));d.text((20,87),f"Correspondência com o ajuste manual: {matches}/{len(manual['objects'])} marcações (posição e tamanho).",font=small,fill=(83,225,170));out=Image.new('RGB',(w,125+len(rows)*(h+90)),(16,26,35));out.paste(header,(0,0))
for i,row in enumerate(rows):out.paste(row,(0,125+i*(h+90)))
out.save(root/'deliverables/PelotaVision_0.8.1_Comparacao.png')
(root/'comparison.json').write_text(json.dumps({'width':w,'height':h,'old_auto':len(old['objects']),'manual_reference':len(manual['objects']),'new_auto':len(new['objects']),'manual_matches_in_new':matches,'matching_rule':'one-to-one center distance <=.35*(r1+r2), radius ratio .7..1.43','is_ground_truth':False},indent=2))
print((root/'comparison.json').read_text())
