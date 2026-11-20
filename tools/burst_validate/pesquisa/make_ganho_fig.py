#!/usr/bin/env python3
"""Regenera burst_sr_ganho.png a partir de burst_sr_results.json.

Painel esquerdo: o que empilhar FAZ (ruído cai ~1/sqrt(N)) contra o teto teórico.
Painel direito: o que empilhar NÃO faz sozinho (fases sub-pixel / resolução), por cenário.
"""
import json
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

plt.rcParams.update({'font.size': 10, 'axes.grid': True,
                     'grid.alpha': .3, 'axes.spines.top': False,
                     'axes.spines.right': False, 'figure.dpi': 130})
d = json.load(open('/home/ubuntu/pelotavision-sr/burst_sr_results.json'))
sw = d['varredura_N']
N = np.array(sorted(int(k) for k in sw))
media = np.array([sw[str(n)]['psnr_B'] for n in N])
driz = np.array([sw[str(n)]['psnr_C'] for n in N])
diam = np.array([sw[str(n)]['erro_diam_C_px'] for n in N])

fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(11.6, 4.6))

# --- painel 1: SNR vs N -----------------------------------------------------
teto = media[0] + 10 * np.log10(N / N[0])          # limite √N a partir de N=1
ax1.plot(N, teto, ls=':', color='#444', lw=1.6,
         label='teto teórico: 10·log10(N) acima de 1 quadro')
ax1.plot(N, media, 'o-', color='#1f77b4', ms=6, lw=2,
         label='média alinhada (medida)')
ax1.plot(N, driz, 's-', color='#ff7f0e', ms=5, lw=1.8,
         label='burst drizzle 2× (medida)')
ax1.axhline(media[0] - (media[0] - 0) + 0, visible=False)
ax1.annotate(f'+{media[-1]-media[0]:.1f} dB em {N[-1]} fotos\n(ganho de ruído, não de fase)',
             xy=(N[-1], media[-1]), xytext=(N[-1]*0.42, media[-1]+4.4),
             arrowprops=dict(arrowstyle='->', color='#1f77b4', lw=1.4), color='#1f77b4')
ax1.set_xlabel('frames na rajada (N)')
ax1.set_ylabel('PSNR vs ground truth (dB)')
ax1.set_title('O ruído cai com √N — e achata entre N=6 e N=12\n'
              '(tremor 1,5 px, ruído 35/255, pelotas reais de correia)', fontsize=10.5)
ax1.set_xticks(N)
ax1.set_ylim(min(media[0], driz[0]) - 1.2, max(teto) + 3)
ax1.legend(loc='lower right', fontsize=8.5, framealpha=.95)

# --- painel 2: resolução não aparece sem fases ------------------------------
cen = d['resultados']
lab = [k for k in cen]
x = np.arange(len(lab))
m = [cen[k]['psnr_B_media_alinhada'] for k in lab]
c = [cen[k]['psnr_C_burst_drizzle'] for k in lab]
e = [cen[k]['psnr_A_1frame_bicubica'] for k in lab]
w = .26
ax2.bar(x - w, e, w, color='#999', label='1 quadro + bicúbica')
ax2.bar(x, m, w, color='#1f77b4', label='média alinhada (N=24)')
ax2.bar(x + w, c, w, color='#ff7f0e', label='drizzle 2× (N=24)')
for xi, vals in zip(x, zip(e, m, c)):
    for dx, v in zip((-w, 0, w), vals):
        ax2.text(xi + dx, v + .35, f'{v:.1f}', ha='center', fontsize=8)
ax2.set_xticks(x)
ax2.set_xticklabels([k.replace(' (', '\n(').replace('trepé', 'tremor') for k in lab], fontsize=8.5)
ax2.set_ylabel('PSNR (dB)')
ax2.set_title('Mais fotos ≠ mais resolução: a fusão 2× perde da média\n'
              'quando o tremor não cobre as fases (e o registro erra)', fontsize=10.5)
ax2.set_ylim(0, max(m + c + e) + 5)
ax2.legend(fontsize=8.5, loc='lower right')

fig.suptitle('Super-resolução por rajada no PelotaVision: separando ganho de ruído de ganho de detalhe',
             fontsize=12, y=1.005)
fig.tight_layout()
out = '/home/ubuntu/pelotavision-sr/burst_sr_ganho.png'
fig.savefig(out, bbox_inches='tight')
print('escrito:', out, '| Δmédia', round(media[-1] - media[0], 2),
      '| Δdrizzle', round(driz[-1] - driz[0], 2), '| diam', diam.tolist())
