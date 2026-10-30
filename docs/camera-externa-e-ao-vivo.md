# Câmera externa (USB/OTG) e ao vivo completo — Android 0.16.0 / Windows 0.6.0

## Escolha da câmera (Android)
- Toque no rótulo da câmera, abaixo da imagem. Aparecem todas as câmeras que o app consegue abrir: traseiras, **externas (USB)** e frontais, com a maior resolução JPEG de cada uma.
- A escolha fica salva. Se a câmera salva sumir (USB desconectada), o app volta para a traseira.
- **Atualizar lista:** reinicia o CameraX para enxergar uma câmera USB conectada depois que o app abriu. Ao conectar ou desconectar o cabo OTG, o app também atualiza sozinho.
- **Diagnóstico USB:** mostra se o aparelho tem USB host, se declara suporte a câmera externa, os ids do Camera2, as câmeras abríveis e os dispositivos USB de vídeo (classe UVC 0x0E, com VID/PID).
- **Frontal:** a prévia é desespelhada para as marcações coincidirem com a análise.

### Limite importante
O Android só entrega uma câmera USB aos aplicativos quando o **fabricante** habilitou o suporte UVC no sistema (driver no kernel e provedor de câmera externa; nível de hardware `EXTERNAL`). Muitos celulares não habilitam. Nesse caso:
- a câmera aparece no **Diagnóstico USB** como dispositivo USB de vídeo, mas **não** na lista de câmeras;
- nenhum app que use a API de câmera do Android consegue abri-la; só apps com driver UVC próprio (libusb/libuvc).

Se o seu aparelho for desse tipo, o próximo passo é embutir um driver UVC em espaço de usuário (por exemplo, `libuvc`, BSD, ou uma biblioteca Android baseada nele). Isso adiciona código nativo e precisa de teste no aparelho real. Fica como fase 2, decidida depois do diagnóstico no seu celular.

### Câmeras que não aceitam foto simultânea
Algumas câmeras externas e LEGACY não aceitam prévia + análise + foto ao mesmo tempo. O app tenta os três; se o sistema recusar, liga prévia + análise e a **foto passa a ser o quadro de análise** (1280×720 pedido). O rótulo avisa "foto = quadro ao vivo" e o tamanho efetivo aparece depois da captura.

## Ao vivo com o que a foto faz (Android e Windows)
O ao vivo passa a usar o mesmo pipeline nas duas plataformas (`core/.../LiveFrameAnalysis.java`):

| Recurso | Android ao vivo | Windows ao vivo |
|---|---|---|
| ROI | botão **Área** (editor sobre o quadro atual); guardada em proporção do quadro | **ROI ao vivo** (já existia) |
| Filtro de luz | **Luz** → Automático, Gama+CLAHE, Suavizar+CLAHE, Retinex; prévia no quadro; opção de ver a imagem filtrada | **Luz ao vivo…** (mesmas opções) |
| Escala física | **Escala / mm** → recipiente detectado no quadro, px/mm do quadro, ou a da última foto | **Escala ao vivo…** (mesmas opções) |
| Histograma e Rosin–Rammler | sim (opção em **Luz**) | sim (já existia) |
| Mapa granulométrico | botão **Seg./Mapa/Orig.** também no ao vivo | sim (0.5.5) |
| D10 / D50 / D90 | na linha de detalhe | na faixa |
| Busca de ajuste com a cena parada | ainda não | sim (agora rápida; ver `busca-rapida.md`) |

Regras:
- **Filtro de luz:** o filtro só **propõe** as pelotas. As elipses e os diâmetros são medidos nos pixels **originais** do quadro, como na foto.
- **Escala transferida:** a escala da foto vale no ao vivo quando o enquadramento é o mesmo (proporção do quadro igual à da foto, tolerância 2%). O px/mm é convertido pela resolução. Se a proporção mudou, o app avisa e mostra o histograma em px.
- **ROI e escala da foto passam para o ao vivo** ao voltar da foto. Se a foto não tinha ROI ou escala, as do ao vivo continuam.
- **Trincas continuam só na foto.** No quadro de análise a pelota tem poucos pixels e a triagem seria inconclusiva por construção.

## Verificação
- Núcleo (`LiveFrameAnalysisTest`, Java + OpenCV 4.12 nativo): ROI em proporção e de volta, escala transferida entre resoluções e recusada com outra proporção, filtro de luz não altera as medidas no quadro original, calibração pelo recipiente num quadro sintético.
- Android: o código de câmera e de ao vivo **não foi compilado neste ambiente** (sem acesso ao repositório Maven do Google). Foi conferido por compilação contra `android.jar` 35 e interfaces mínimas do CameraX; a compilação real e o teste no celular ficam para o build local.
