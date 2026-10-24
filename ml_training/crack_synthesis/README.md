# Gerador procedural de trincas sobre pelotas reais

O gerador cria exemplos de treino para a triagem de trincas **de bancada**. Ele recorta pelotas reais sem trinca de fotos de bancada e desenha nelas uma trinca por algoritmo. A máscara de cada trinca é exata, porque é o próprio desenho. Não usa rede generativa, difusão nem imagens de IA.

## Por que procedural, e não difusão

- O rótulo sai de graça e é exato: o pixel da trinca é conhecido, não estimado.
- A textura, a cor, o desfoque e a compressão da pelota são de fotos reais. Só a trinca é sintética.
- A geração é reprodutível (semente fixa), auditável e roda sem GPU em menos de 1 minuto.
- Imagens de difusão reproduzem o "estilo" das trincas que o modelo gerador aprendeu, não a física da pelota. Além disso, 6 das 7 cenas antigas de `ml_training/data` já parecem imagens geradas (ver abaixo). Treinar com mais disso reforçaria o mesmo viés.

## Por que só bancada

Na correia, as pelotas têm 10–25 px de diâmetro e a câmera não resolve uma trinca: ela tem fração de pixel de largura. O app já declara inconclusiva qualquer pelota com raio de recorte < 20 px. O gerador começa em raio 20 px e não tenta simular correia.

## Pelotas doadoras (inventário)

| Foto | Resolução | Raio típico | Candidatas (Hough) | Doadoras |
|---|---|---|---|---|
| `verification/fixtures/1000411645.jpg` | 1536×1253 | ~58 px | 56 | 34 |
| `verification/fixtures/1000411646.jpg` | 1536×1536 | ~100 px | 10 | 7 |
| `verification/fixtures/1000411647.jpg` | 1536×919 | ~41 px | 113 | 90 |

**131 doadoras em 3 fotos.** São fotos de pelotas queimadas empilhadas, aparentemente de divulgação: uma tem marca d'água "Jindal Steel & Power", outra tem barra de escala de 12 mm. Têm compressão JPEG forte e resolução efetiva baixa, porque foram ampliadas. Não há foto de bancada da Vale no repositório. A exclusão foi manual: desfocadas, ocluídas, duas pelotas num círculo, marcas escuras que podem ser trinca e marca d'água. Os motivos estão em `select_donors.py` e as folhas em `folhas/*_doadoras.jpg` (vermelho = excluída). **A variedade real é de 3 fotos, todas de pelota queimada cinza-arroxeada.** O aumento (rotação, espelho, escala, iluminação) multiplica amostras, não fontes.

Outras imagens do repositório:

- `crack_example.png` (= `coverage-0.8.1/reference_input.webp`) é uma foto real externa, com trincas visíveis. Fica **fora** das doadoras e serve de teste.
- As 7 cenas de `ml_training/data` não servem como doadoras: grades perfeitas, textura uniforme e aspecto de estúdio sugerem imagens geradas, e `paired40` é declarada sintética.
- `belt-0.9.0/input_correia.jpg` é de correia e não serve para trinca.

## Como gerar

```
cd ml_training/crack_synthesis
python select_donors.py --sheets folhas         # reprodução das doadoras (donors.json já versionado)
python contact_sheet.py --out folhas            # folhas de inspeção
python build_dataset.py                         # -> ../training_synth/synth_features.npz (72 atributos)
cd .. && python train_synth.py                  # -> training_synth/crack_forest_candidate.pvrf + validation.json
```

A semente padrão é `20261024`. O gerador de números de cada doadora usa a semente `(seed, foto, índice)`, então incluir ou retirar uma doadora não altera as amostras das outras.

## O que o gerador faz (`synth.py`)

- **Caminho:** passeio aleatório sobre a superfície da esfera (projeção ortográfica), com curvatura suave, dobras ocasionais e tendência radial, tangencial ou livre. Há 0–3 ramos laterais e o tipo "rede", com 3–4 braços a partir de um ponto. Cerca de 30% das trincas começam na borda, e o caminho para quando chega ao limbo (trinca que alcança a borda da pelota). Perto do limbo, o escorço estreita a largura.
- **Largura:** fração do raio (0,5–5%), com piso de 0,45 px. A mesma trinca em mm fica mais fina em px numa foto de menor resolução. A largura varia ao longo do caminho, afina nas pontas livres e tem zigue-zague lateral de alta frequência. Tipos: `fina`, `media`, `aberta` e `rede`.
- **Profundidade e relevo:** escurecimento multiplicativo com núcleo mais escuro nas abertas. Uma parede fica iluminada e a outra em sombra, conforme a direção da luz.
- **Lascas:** pequenas concavidades poligonais coladas ao caminho, também com relevo.
- **PSF:** a trinca é desfocada com σ de 0,35–0,9 px na escala da doadora, para não ficar mais nítida que a textura em volta. Ela é desenhada na resolução mais grosseira entre doadora e alvo e depois ampliada junto com a pelota.
- **Aquisição** (mesma aplicada ao par limpo): gradiente de iluminação, ganho, gamma, contraste, desfoque gaussiano, tremido ocasional, ruído gaussiano + de disparo, JPEG 35–95, raio-alvo de 20 a 120 px (log-uniforme), rotação e espelho. O círculo da extração recebe erro de centro (±8%) e de raio (−10 a +8%), como o detector.
- **Negativos difíceis:** `poros`, `risco` (claro ou escuro, curto e reto), `sombra` de pelota vizinha, `granular`, `umidade` (manchas escuras amplas) e `relevo` (saliências com sombra). Toda positiva tem um **par limpo**: mesma doadora, geometria, distratores e aquisição, sem a trinca, rotulado 0.
- **Visibilidade:** positivas cuja trinca não sobrevive à aquisição (contraste dentro da máscara < 0,6 desvio-padrão da textura, ou menos de 15% da máscara dentro do disco de 78% do raio usado pelos atributos) são **descartadas**, não rotuladas. Foram 149 de 1572.

## Conjunto gerado

São 3632 amostras: 1423 positivas, 1423 pares limpos e 786 negativos extras com distratores, sobre 131 doadoras. Os 72 atributos estão em `../training_synth/synth_features.npz` e os parâmetros em `synth_features.json`.

## O que se vê nas folhas de contato (`folhas/`)

- `positivas.jpg`: por tipo × raio (22–110 px), imagem e máscara. No canto, o tamanho real nos raios pequenos.
- `negativos.jpg`: um distrator por linha.
- `real_vs_sint.jpg`: as 7 trincas reais visíveis de `crack_example.png` ao lado de sintéticas no mesmo raio.

**O que convence:** trincas médias, abertas e em rede, entre 40 e 110 px de raio. Têm traço irregular, afinamento nas pontas, ramificação plausível e borda iluminada/sombreada. Em raio 20–30 px, a trinca fina vira poucos pixels escuros, o que é coerente com a física e com o gate do app.

**O que ainda não convence (honestamente):**

1. As trincas reais de `crack_example.png` são **degraus de relevo**: uma borda clara de um lado e sombra larga e difusa do outro, frequentemente como arcos acompanhando o contorno. As sintéticas ainda são sobretudo **linhas escuras** finas, com relevo leve. Não há modelagem de deslocamento da superfície entre as duas faces.
2. Não há trincas em arco concêntrico ao contorno (casca soltando), que aparecem nas fotos reais.
3. A textura de todas as doadoras é de pelota queimada cinza-arroxeada de 3 fotos. Pelota crua, verde, molhada ou de outra mineralogia não está representada.
4. Os distratores são estatísticos (ruído filtrado, elipses): poros e umidade parecem razoáveis, mas os riscos são retos demais e a "sombra de vizinha" é um disco escuro, não a sombra dura de uma pelota real.
5. A PSF e o JPEG são aplicados ao conjunto, mas a trinca foi desenhada sobre uma imagem já comprimida. Os blocos JPEG da doadora e a trinca não têm a mesma história de compressão.

## Limitações

- **Domínio sintético:** o modelo pode aprender o gerador (o traço desenhado) em vez da trinca. Os pares limpos e os distratores reduzem esse risco, mas não o eliminam. Só fotos reais rotuladas medem isso: ver `../crack_validation/`.
- Validar por foto-fonte com 3 fotos dá só 3 grupos. Os resultados sintéticos fora da foto medem generalização entre doadoras, não entre usinas.
- Os rótulos das 7 cenas e de `crack_example.png` são **provisórios** (assistente).
