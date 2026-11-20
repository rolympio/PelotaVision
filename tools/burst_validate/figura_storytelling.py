import json, cv2, numpy as np, matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, Rectangle, Circle
V='/home/claude/verif2'
rd=lambda p: cv2.cvtColor(cv2.imread(p),cv2.COLOR_BGR2RGB)
def case(foto,c):
    d=f'{V}/{foto}/{c}'; b=json.load(open(d+'/res/rajada.json'))['burst']; base=b['base_frame']
    meta=json.load(open(d+'/sim/offsets.json'))
    return d,b,base,meta
# ---- dados
dN,bN,baseN,metaN=case('bancada','mao_r35')
dS,bS,baseS,metaS=case('bancada','grade_r3')
X0,Y0,S=CROP=(40,60,150)  # recorte LR (x,y,lado)
def crop(img,k=1): return img[k*Y0:k*(Y0+S),k*X0:k*(X0+S)]
singleN=crop(rd(f'{dN}/sim/quadros/quadro_{baseN:02d}.png')); fusedN=crop(rd(dN+'/res/rajada_empilhada.png'))
thumbs=[crop(rd(f'{dN}/sim/quadros/quadro_{k:02d}.png')) for k in [0,3,6,9]]
c=bS['sr_output']['crop_in_base_photo']
bx,by=[(o['dx'],o['dy']) for o in metaS['offsets'] if o['indice']==baseS][0]
v=rd(dS+'/sim/verdade_hr.png'); vb=cv2.warpAffine(v,np.float32([[1,0,2*bx],[0,1,2*by]]),(v.shape[1],v.shape[0]),flags=cv2.INTER_CUBIC,borderMode=cv2.BORDER_REFLECT)
fusedS=rd(dS+'/res/rajada_empilhada.png'); sr=rd(dS+'/res/rajada_sr2x.png')
s2=56; x2,y2=100,100
upS=cv2.resize(fusedS[y2:y2+s2,x2:x2+s2],(2*s2,2*s2),interpolation=cv2.INTER_CUBIC)
srC=sr[2*(y2-c['y']):2*(y2-c['y']+s2),2*(x2-c['x']):2*(x2-c['x']+s2)]
trC=vb[2*y2:2*(y2+s2),2*x2:2*(x2+s2)]
lrS=cv2.resize(fusedS[y2:y2+s2,x2:x2+s2],(2*s2,2*s2),interpolation=cv2.INTER_NEAREST)

INK='#1f2933'; MUTE='#5f6b7a'; ACC='#c2410c'; GOOD='#15803d'; BAD='#b91c1c'; BLUE='#1d4ed8'; PAPER='#faf7f2'
plt.rcParams.update({'font.family':'DejaVu Sans','text.color':INK})
fig=plt.figure(figsize=(13,34.2),dpi=110,facecolor=PAPER)
H=34.2
def Y(y): return 1-(1-y)*22/H
def h(v): return v*22/H
def T(x,y,s,**k): fig.text(x,Y(y),s,**k)
T(0.05,0.975,'A rajada: como 12 fotos “ruins” viram uma boa',fontsize=27,weight='bold',va='top')
T(0.05,0.952,'PelotaVision 0.19.0 · uma história em 6 capítulos, com imagens reais da verificação (bancada, sensor simulado 2× pior)',fontsize=12.5,color=MUTE,va='top')

def chapter(y,n,title,sub):
    fig.patches.append(FancyBboxPatch((0.05,Y(y-0.005)),0.045,h(0.03),boxstyle='round,pad=0.004',transform=fig.transFigure,fc=ACC,ec='none'))
    T(0.0725,y+0.0095,str(n),fontsize=17,weight='bold',color='white',ha='center',va='center')
    T(0.11,y+0.016,title,fontsize=18.5,weight='bold',va='center')
    T(0.11,y-0.0035,sub,fontsize=12,color=MUTE,va='center')
def img(rect,a,title=None,col=INK,border=None):
    ax=fig.add_axes([rect[0],Y(rect[1]+rect[3])-0+0,rect[2],h(rect[3])]) if False else fig.add_axes([rect[0],Y(rect[1]),rect[2],h(rect[3])]); ax.imshow(a,interpolation='nearest'); ax.set_xticks([]); ax.set_yticks([])
    for s in ax.spines.values(): s.set_color(border or '#cfc8bd'); s.set_linewidth(3 if border else 1)
    if title: ax.set_title(title,fontsize=12,color=col,weight='bold',pad=6)
    return ax

# Cap 1
y=0.905; chapter(y,1,'O problema: uma foto só é granulada','Pouca luz na bancada → ruído alto. As bordas das pelotas e as trincas se perdem no granulado.')
img([0.07,0.735,0.27,0.145],singleN,'1 foto (ruído 35)',BAD)
T(0.37,0.86,'O que o Ricardo vê:',fontsize=13,weight='bold')
T(0.37,0.843,'• contorno da pelota “serrilhado”\n• trinca fina confunde com ruído\n• diâmetro medido oscila de foto para foto',fontsize=12.5,va='top',linespacing=1.6)
T(0.37,0.775,'Ideia: a cena está parada.\nE se eu tirar várias fotos seguidas?',fontsize=14,color=ACC,weight='bold',va='top',linespacing=1.4)

# Cap 2
y=0.695; chapter(y,2,'A rajada: 12 fotos, e a câmera treme frações de pixel','Ninguém vê esse tremor a olho nu — mas ele existe, e o app o mede.')
for i,t in enumerate(thumbs):
    img([0.07+i*0.085,0.585,0.075,0.075],t,f'#{[1,4,7,10][i]}')
T(0.07,0.572,'… + 8 fotos. Parecem iguais;\no ruído muda em cada uma.',fontsize=11.5,color=MUTE)
ax=fig.add_axes([0.47,Y(0.535),0.2,h(0.135)]);
offs=[(f['dx'],f['dy']) for f in bN['frames']]
ax.scatter([o[0] for o in offs],[o[1] for o in offs],s=60,c=BLUE,edgecolor='white',zorder=3)
ax.scatter([0],[0],s=140,marker='*',c=ACC,zorder=4)
ax.add_patch(Rectangle((-0.5,-0.5),1,1,fill=False,ec=MUTE,ls='--'))
ax.set_xlim(-2,2); ax.set_ylim(2,-2); ax.set_aspect('equal'); ax.grid(alpha=.3)
ax.set_xlabel('desvio x (px)',fontsize=10); ax.set_ylabel('desvio y (px)',fontsize=10); ax.tick_params(labelsize=9)
ax.set_title('Onde cada foto “caiu”',fontsize=12,weight='bold')
T(0.70,0.655,'★ foto base (a mais nítida)\n● as outras 11\n┄ tamanho de 1 pixel',fontsize=11.5,va='top',linespacing=1.6)
T(0.70,0.585,'Correlação de fase + ECC\nacham cada deslocamento\ncom erro ≈ 0,05 px\n(1/20 de pixel).',fontsize=11.5,va='top',linespacing=1.45,color=BLUE)

# Cap 3 laudo
y=0.485; chapter(y,3,'O laudo: antes de prometer, medir','O app decide sozinho o que vale a pena fazer com essas 12 fotos.')
rows=[('Fotos usadas','12 de 12','≥ 8',True),('Tremor (p90)','1,25 px','0,15 a 2 px',True),('Cobertura das fases ½ px','100%','≥ 60%',True),
      ('Erro de alinhamento','0,13 px','≤ 0,15 px',True),('Deriva de brilho','0,15 nível','≤ 1,5',True),('Ruído','23 níveis','≤ 10',False)]
ax=fig.add_axes([0.07,Y(0.34),0.55,h(0.125)]); ax.axis('off'); ax.set_xlim(0,1); ax.set_ylim(0,len(rows)+1)
for j,(a,b_,cc,ok) in enumerate(rows):
    yy=len(rows)-j-0.1
    ax.text(0.0,yy,a,fontsize=12.5,va='center'); ax.text(0.5,yy,b_,fontsize=12.5,va='center',weight='bold')
    ax.text(0.7,yy,cc,fontsize=11.5,va='center',color=MUTE); ax.text(0.95,yy,'✔' if ok else '✘',fontsize=16,va='center',color=GOOD if ok else BAD,weight='bold')
ax.text(0.0,len(rows)+0.75,'medida',fontsize=10,color=MUTE); ax.text(0.5,len(rows)+0.75,'valor',fontsize=10,color=MUTE); ax.text(0.7,len(rows)+0.75,'p/ SR 2×',fontsize=10,color=MUTE)
fig.patches.append(FancyBboxPatch((0.66,Y(0.35)),0.29,h(0.11),boxstyle='round,pad=0.01',transform=fig.transFigure,fc='#fff7ed',ec=ACC,lw=2))
T(0.805,0.443,'VEREDITO',fontsize=11,color=ACC,ha='center',weight='bold')
T(0.805,0.418,'SOMENTE MEDIANA',fontsize=17,ha='center',weight='bold')
T(0.805,0.375,'Ruído alto demais para inventar\nresolução. Empilhar é o certo.',fontsize=11.5,ha='center',va='center',linespacing=1.4)

# Cap 4 empilhar
y=0.295; chapter(y,4,'Trilha 1 · Empilhar: o ruído cai, a geometria não muda','Por pixel: mediana das 12 fotos alinhadas. Quem discorda (algo que se mexeu) perde peso.')
img([0.07,0.105,0.27,0.165],singleN,'1 foto',BAD)
T(0.365,0.19,'→',fontsize=40,color=ACC,ha='center',va='center')
img([0.39,0.105,0.27,0.165],fusedN,'empilhada (12 fotos)',GOOD,border=GOOD)
T(0.69,0.255,'+11,8 dB',fontsize=30,weight='bold',color=GOOD,va='top')
T(0.69,0.215,'de qualidade (PSNR)\nruído 23 → 5 níveis',fontsize=12.5,va='top',linespacing=1.4)
T(0.69,0.165,'Mesmo tamanho, mesma escala\npx/mm: pode medir D50, elipses\ne trincas normalmente.',fontsize=12,va='top',linespacing=1.45,color=MUTE)

# rodapé cap 5 em figura separada abaixo? -> continuar

# Cap 5 SR
y=0.045; chapter(y,5,'Trilha 2 · SR 2×: só quando o laudo libera','Outra rajada: luz boa (ruído 3) e a câmera deslocada ½ pixel de propósito. Agora o laudo diz SR_2X_PLAUSIVEL.')
for i,(a,t,cc,bd) in enumerate([(lrS,'empilhada (pixels originais)',INK,None),(upS,'empilhada ampliada 2×\n(bicúbica)',MUTE,None),(srC,'SR 2× multiframe',GOOD,GOOD),(trC,'verdade (2× mais fina)',MUTE,None)]):
    img([0.07+i*0.225,-0.165,0.2,0.165],a,t,cc,border=bd)
T(0.07,-0.177,'Por que funciona: cada foto amostrou a cena num ponto diferente (meio pixel ao lado). Somando os 4 “tabuleiros”,',fontsize=12,va='top')
T(0.07,-0.191,'a grade fica 2× mais densa — o algoritmo (IBP) acha a imagem fina que, reduzida, explica as 12 fotos ao mesmo tempo.',fontsize=12,va='top')
T(0.07,-0.213,'+2,8 dB',fontsize=26,weight='bold',color=GOOD,va='top'); T(0.215,-0.216,'sobre a empilhada ampliada · checagem de consistência χ² = 0,54 (aprovada)\nArquivo derivado: só para ver. Medidas continuam nos pixels da foto.',fontsize=12,va='top',linespacing=1.4)

# Moral
y=-0.305; chapter(y,6,'A moral da história','O que a rajada entrega de fato, medido em 3 fotos reais do projeto (correia, webcam, bancada).')
ax=fig.add_axes([0.17,Y(-0.47),0.43,h(0.14)])
lab=['Empilhar\n(qualquer luz)','SR 2×\nruído 3 + ½ px','SR 2×\nruído 10','SR 2×\nruído 35']
lo=[9.1,1.15,-0.14,-0.32]; hi=[14.3,2.79,1.30,0.0]
cols=[GOOD,GOOD,'#ca8a04',BAD]
for i in range(4):
    ax.barh(i,hi[i]-lo[i],left=lo[i],height=0.55,color=cols[i],alpha=.85)
    ax.text(hi[i]+0.3,i,(f'{lo[i]:+.1f} a {hi[i]:+.1f} dB' if hi[i]!=0 else f'{lo[i]:+.1f} a 0,0 dB').replace('.',','),va='center',fontsize=11.5)
ax.axvline(0,color=INK,lw=1); ax.set_yticks(range(4)); ax.set_yticklabels(lab,fontsize=11.5); ax.invert_yaxis()
ax.set_xlim(-2,18); ax.set_xlabel('ganho de qualidade (dB de PSNR)',fontsize=11); ax.tick_params(axis='x',labelsize=10)
for sp in ['top','right']: ax.spines[sp].set_visible(False)
ax.set_facecolor(PAPER)
fig.patches.append(FancyBboxPatch((0.66,Y(-0.465)),0.29,h(0.135),boxstyle='round,pad=0.01',transform=fig.transFigure,fc='#f0fdf4',ec=GOOD,lw=2))
T(0.68,-0.345,'Na prática',fontsize=14,weight='bold',color=GOOD,va='top')
T(0.68,-0.37,'1. Trave foco e exposição.\n2. Tire 8 a 24 fotos, cena parada.\n3. Leia o laudo.\n4. Use a empilhada: ruído\n    cai ~√N, escala intacta.\n5. SR 2× é bônus raro:\n    luz boa + deslocamento ½ px.',fontsize=12,va='top',linespacing=1.5)
T(0.05,-0.51,'Mil fotos não dão “resolução infinita”: o teto físico é 2×. O ganho grande e confiável é o de ruído.',fontsize=13.5,weight='bold',color=ACC)
T(0.05,-0.523,'Estado: testado em software e em imagens de verificação simuladas · ainda não validado com amostra física (ensaio A pendente) · docs/rajada-burst.md',fontsize=10.5,color=MUTE)
fig.savefig('/home/claude/story/rajada_storytelling.png',facecolor=PAPER)
