# Ao vivo estável: cena parada, busca genética e consenso temporal (Windows 0.5.5)

## O que acontece
1. **Movimento.**
   - A cada ~100 ms, uma miniatura de 96 px em tons de cinza é comparada com a anterior (diferença absoluta média).
   - **Parado:** abaixo de 1,6 níveis de cinza por 4 medições seguidas (~0,4 s, depois da contagem rápida).
   - **Em movimento:** acima de 3,5. Entre os dois limiares o estado não muda (histerese), para o ruído da webcam não alternar.
2. **Busca genética do ajuste**, com "Otimizar quando parado" ligado. Ao parar, 3 quadros são capturados com 180 ms de intervalo.
   - **Genes:**
     - método: Bordas ou LoG; a rede neural só entra se já estiver escolhida e o modelo existir;
     - perfil de tamanho;
     - sensibilidade, de 0 a 100 em passos de 5.
   - **População e evolução:**
     - 8 indivíduos e até 6 gerações;
     - elitismo de 2, torneio de 3 e cruzamento uniforme;
     - mutação: sensibilidade ± N(0, 12), perfil sorteado com p = 0,25, método trocado com p = 0,2.
     - Para quando o melhor não muda por 2 gerações.
   - **Aptidão:** mesma pontuação da busca na foto (`DetectionAssessment.score`), com a concordância entre quadros como estabilidade, menos três penalidades:
     - 25 × (1 − concordância);
     - 30 × CV da contagem;
     - 100 × tremor mediano do raio (limitado a 0,2).
   - É uma **heurística fixada a priori, não acurácia**.
   - O melhor ajuste é aplicado aos controles (método, perfil, sensibilidade). A foto capturada parte dele.
3. **Consenso temporal.**
   - Enquanto nada se mexe, cada pelota é acompanhada entre quadros: centros a menos de 0,4 r e razão de raios ≤ 1,3.
   - **Confirmada:** aparece em pelo menos 60% dos últimos 10 quadros e em no mínimo 3.
   - A geometria exibida é a do quadro mais típico (medoide): uma detecção real, não uma média.
   - **Fantasmas:** detecções que surgem e somem sem confirmar. São contadas e não aparecem.
4. **Indicadores na faixa:**
   - tempo parado;
   - pelotas confirmadas ± desvio;
   - D50 ± desvio nos últimos 10 quadros;
   - fantasmas descartados e trilhas instáveis;
   - ajuste escolhido pela busca.
5. **Movimento de novo:** cancela a busca e reinicia o consenso.

## Limites
- A aptidão e o consenso medem **estabilidade e coerência**, não exatidão. Uma pelota mal medida do mesmo jeito em todos os quadros continua mal medida.
- Trincas continuam só na foto.
- Android: ainda não tem a busca e o consenso do ao vivo. As classes do núcleo (`LiveMotion`, `LiveConsensus`, `LiveTuner`) já são comuns.

## Testes
`LiveStabilityTest` (Java puro):
- histerese do movimento;
- consenso com 20 pelotas reais e 4 fantasmas por quadro (fica em 20, desvio 0);
- busca genética numa paisagem conhecida (converge e é reprodutível com a mesma semente);
- cancelamento por movimento.
