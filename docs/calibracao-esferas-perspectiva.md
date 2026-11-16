# Calibração por várias esferas e correção de perspectiva (0.17.0 / Windows 0.9.0)

## Por quê
Teste de 11/11/2026: 10 esferas de 11,00 mm (paquímetro), 17 fotos de celular a ~12 cm, régua na mesa (plano abaixo das esferas).
- A mesma esfera aparece **~6–9% maior no canto da foto do que no centro** (perspectiva + lente).
- Calibrando com **uma esfera no centro**, as demais saem **+2,4% (silhueta), +3,8% (StarDist), +4,1% (SAM 2)**.
- A régua só serve para escala se estiver no **plano do equador** das pelotas (na mesa, abaixo, errou ~26%).

## O que mudou no app (Windows e Android, mesmo código em `core/ObjectCalibration`)
- **Várias esferas padrão:** depois de calibrar por uma esfera, ao tocar em outra esfera aparece
  "Somar às N esferas já marcadas". Todas ficam fora das estatísticas e entram na escala.
- **Correção de perspectiva:** escala local = escala do centro × (1 + k·ρ²), ρ = distância ao centro / meia diagonal
  (0 no centro, 1 no canto). Cada pelota usa a escala da sua posição (histograma, D10/D50/D90, mapa, tabelas, JSON).
  - Com **3+ esferas** (ou 2, uma perto do centro e outra na borda) o **k é ajustado pelas próprias esferas**.
  - Com 1 esfera, vale o k informado (padrão 0,07, medido neste teste; 0 desliga). Limites: −0,05 a 0,30.
  - A tela mostra k, origem e quanto as esferas concordam entre si.
- JSON: `object_reference.perspective_correction` (k, origem, modelo, lista de esferas com ρ e desvio).

## Efeito nas esferas de 11 mm (as não usadas como referência)

| Método | 1 esfera no centro, sem correção | 1 esfera, k=0,07 | 3 esferas (centro + 2 bordas), k ajustado |
|---|---|---|---|
| Silhueta (limiar) | +0,26 mm · erro 0,31 | +0,11 · 0,25 | **+0,08 · 0,23** |
| StarDist | +0,42 · 0,49 | +0,26 · 0,36 | **+0,18 · 0,25** |
| SAM 2 | +0,45 · 0,48 | +0,30 · 0,34 | **+0,17 · 0,22** |

(viés médio · erro absoluto médio, mm). O que sobra (~±0,25 mm, ~2%) é dispersão foto a foto, não posição.

## Recomendação de uso
1. 3 esferas: uma perto do centro e duas em cantos opostos.
2. Celular mais alto com zoom 2–3× reduz o próprio efeito.
3. O k ajustado depende da câmera e da distância: não reaproveite entre montagens diferentes sem recalibrar.
