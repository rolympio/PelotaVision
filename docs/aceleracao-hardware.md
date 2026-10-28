# Aceleração por hardware (Android 0.16.0 · Windows 0.6.0)

Opção **desligada por padrão** que executa as redes neurais do PelotaVision num motor capaz de usar a GPU:

| Plataforma | Onde ligar | Motor | Ordem de tentativa |
|---|---|---|---|
| Windows | barra "Modelos de analise" → **Aceleracao GPU** (botão **Estado…** mostra o motor de cada rede) | ONNX Runtime 1.31.1 | CUDA (GPU NVIDIA) → CPU do ONNX Runtime → OpenCV DNN |
| Android | **Ajustes** → "Aceleração por hardware" (o texto abaixo da caixa mostra o motor de cada rede) | LiteRT 1.4.2 (GpuDelegate) | GPU fp16 → GPU fp32 → CPU do LiteRT (XNNPACK) → OpenCV DNN |

A escolha fica salva. Trocar a opção recarrega as redes na fila de análise, nunca no meio de uma análise.

## O que acelera e o que não acelera

- **Acelera:** só a inferência das redes — StarDist (localização neural, bancada e correia) e MobileNetV3 (triagem de trincas).
- **Não muda:** pré-processamento, escolha de escala, NMS, ajuste elíptico, granulometria, mapa, Rosin–Rammler, métodos Bordas e LoG e a super-resolução FSRCNN. Tudo isso continua na CPU, como antes.
- Com os métodos clássicos (Bordas, LoG) e o Random Forest de trincas, a opção não tem efeito.

## Garantia de resultado igual: sonda de paridade

Ao carregar cada rede no motor novo, o app roda a mesma entrada sintética no motor novo e no OpenCV DNN (o motor validado das versões anteriores). O motor novo só é aceito se as saídas coincidirem dentro da tolerância:

| Rede | Tolerância |
|---|---|
| StarDist | probabilidade ±0,02; distâncias ±0,5 px (escala da rede) |
| MobileNetV3 | escore ±0,02 |

Fora da tolerância (por exemplo, GPU em fp16 numa placa que arredonda demais), o Android tenta a GPU em fp32; persistindo, a rede fica no OpenCV DNN e o estado mostra o motivo. Se o motor acelerado falhar **durante** uma análise (driver, memória), a rede volta ao OpenCV na hora e a análise continua.

A sonda também mede o tempo de uma execução nos dois motores; o estado mostra, por exemplo, `LiteRT · GPU (fp16) · paridade 0.12 da tolerância · sonda 18 ms (OpenCV 240 ms)`. **Esse é o número que diz se vale a pena no seu aparelho.**

## Requisitos

**Windows (CUDA):** placa NVIDIA, driver atualizado e as bibliotecas CUDA e cuDNN instaladas no Windows, nas versões exigidas pelo ONNX Runtime 1.31 (ver a [página do provedor CUDA](https://onnxruntime.ai/docs/execution-providers/CUDA-ExecutionProvider.html)). Elas **não vão no pacote** (tamanho e licença). Faltando qualquer uma, o estado mostra `ONNX Runtime · CPU (CUDA indisponível: …)`. GPU Intel/AMD não usa CUDA; o ONNX Runtime publicado para Java não traz DirectML.

**Android:** GPU na lista de compatibilidade do LiteRT (a maioria dos Adreno e Mali recentes). Sem ela, o LiteRT roda na CPU. As bibliotecas nativas do LiteRT são alinhadas a 16 KB (exigência da Play para targetSdk 35).

## Os modelos .tflite

O Android usa um segundo formato do **mesmo** modelo: `tools/litert/convert_tflite.py` converte cada ONNX embarcado com o onnx2tf no CI (`.github/workflows/litert-convert.yml`) e só grava o `.tflite` se a detecção completa der as mesmas pelotas que o ONNX. O `.tflite.json` ao lado guarda o SHA-256 do ONNX de origem; o app recusa um `.tflite` convertido de outro ONNX. **Retreinou um modelo? O workflow roda sozinho quando um `.onnx` de `app/src/main/assets` muda.**

Resultado da conversão (reconvertida em 28/10 a partir dos modelos novos da 0.16.0, `verification/litert-0.16.0/litert_conversion.json`):

| Modelo | Fotos | Pelotas ONNX = TFLite | Maior diferença |
|---|---|---|---|
| StarDist bancada (modelo real v2) | 4 | 159, 38, 253, 177 (todas casadas) | 1e-5 do raio |
| StarDist correia | 3 | 2921, 3495, 3316 (todas casadas) | 6e-5 do raio |
| MobileNetV3 (v3) | 130 recortes | nenhuma pelota mudou de classe (0,4/0,6) | 8e-6 no escore |

## Limitações

- Nenhum ganho de velocidade foi medido em GPU real: o CI não tem GPU NVIDIA nem celular. A sonda mede no aparelho de quem usa.
- O ganho total da análise é menor que o da rede: no método neural a rede é a parte mais pesada, mas escolha de escala, NMS e ajuste das elipses continuam na CPU.
- O pacote Windows cresce por causa da DLL CUDA do ONNX Runtime (≈200 MB descompactada), mesmo em máquinas sem NVIDIA.
