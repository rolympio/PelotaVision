# Proposta de interface — fluxo direto, mínimo de interação (0.18 → 0.19)

Objetivo: o operador **aponta, confere e salva**. Escala, área, modelo e iluminação são decididos pelo app. O
operador só intervém quando o próprio app sinaliza baixa confiança.

## Princípio: semáforo de confiança em vez de botões
Cada decisão automática mostra um selo verde, amarelo ou vermelho. O operador só toca no que estiver amarelo ou
vermelho.

| Decisão | Automático (0.18) | Verde quando | Ação pedida se não |
|---|---|---|---|
| Área (ROI) | `AutoRoi`: recipiente redondo/retangular, correia carregada, campo cheio | confiança ≥ 75% | "Ajustar área" (abre editor já com a proposta) |
| Escala | esfera 11 mm / aro do recipiente / bordas da correia | referência identificada e dentro da ROI | "Tocar na esfera" |
| Iluminação | `LowLightRecovery` na ROI | qualidade adequada ou realce aplicado | — (só informa) |
| Modelo | StarDist + SAM 2 + MobileNetV3 (padrão 0.18) | redes carregadas | aviso se caiu no gradiente |
| Foco/estabilidade (ao vivo) | `LiveStabilizer` | quadro parado ≥ 1 s | "Segure firme" |

## Tela única (ao vivo) — proposta de layout
```
┌──────────────────────────────────────────────┬───────────────────────┐
│                                              │  D50 10,5 mm  ● verde │
│   vídeo com contorno da ROI automática       │  D10–D90 6,6–12,0     │
│   + pelotas coloridas por classe             │  116 pelotas · 5 trinc│
│                                              │  [histograma mini]    │
│   selos:  ● Área recipiente  ● Escala esfera │                       │
│           ● Luz ok           ● Modelo SD+SAM2│                       │
├──────────────────────────────────────────────┴───────────────────────┤
│  [ CAPTURAR E ANALISAR ]           Amostra: [Teste Pelota Queimada ▾] │
└───────────────────────────────────────────────────────────────────────┘
```
- **Um botão principal.** "Capturar e analisar" fotografa, aplica ROI e escala, roda o pipeline completo e
  salva JSON, CSV e PDF.
- Nome da amostra persistente, com histórico (lista suspensa).
- Os ajustes avançados (perfil, sensibilidade, método, classe, opacidade, aceleração) saem da tela principal e
  vão para "Avançado". Hoje o painel Segmentação do Windows tem 12 controles; a proposta é deixar 2 visíveis.
- **Resultado:** a mesma tela congela, com o laudo resumido à direita e três ações: Salvar PDF, Revisar trincas
  (só se houver possíveis trincas) e Nova captura.

## Revisão guiada (só quando necessário)
- Possíveis trincas: carrossel com os recortes em grade, como no PDF. Um toque marca "trinca" ou "sem trinca".
- Pelotas "revisar" (borda incerta): mostradas só se forem mais de 5% do total.
- Esfera de referência: confirmar com um toque quando a detecção automática tiver confiança média.

## Windows × Android
Mesmo fluxo e mesmos selos, porque as decisões já estão no núcleo (`AutoRoi`, `ReportLayout`, `LiveFrameAnalysis`).
No Windows, a coluna direita vira o painel lateral. No Android, vira uma folha inferior deslizante.

## Ordem sugerida de implementação
1. Selos de confiança na barra de status (dados já existem: `AutoRoi.Result.confidence`, referência de escala, `LowLightInfo`).
2. Botão único "Capturar e analisar", com salvamento automático na pasta da amostra.
3. Mover os controles avançados para "Avançado"; manter atalhos de teclado no Windows.
4. Carrossel de revisão de trincas.
5. Escala automática pela esfera de 11 mm.
   - Depende da validação com as fotos de controle: medir as esferas com o paquímetro antes.

## Riscos
- Automatizar escala sem validação desloca todas as medidas.
  - A escala automática só fica verde depois de passar no conjunto de controle das esferas.
- Esconder controles dificulta diagnóstico.
  - O JSON e a página de rastreabilidade continuam registrando tudo o que foi decidido automaticamente.
