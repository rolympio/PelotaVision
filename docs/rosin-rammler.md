# Curva acumulada e ajuste Rosin–Rammler (Android 0.15.3 / Windows 0.5.3)

**Modelo (RRSB):** F(d) = 100·[1 − exp(−(d/d′)ⁿ)], F = % passante acumulado.
d′ é o diâmetro com 63,2% passante e n o índice de uniformidade.

## Como é calculado
- **Ajuste:** máxima verossimilhança sobre os diâmetros de cada pelota incluída no histograma. Não usa as classes, então o resultado não muda com a largura de classe.
- **Pontos:** % passante nos limites superiores das classes do histograma, como pontos de peneira. Eles mudam com a largura de classe.
- **Qualidade:**
  - R² e RMSE (pontos percentuais) calculados nos pontos, para comparar com gráficos da literatura.
  - KS (Kolmogorov–Smirnov) sobre as pelotas individuais.
  - R² de curva acumulada é sempre alto; prefira RMSE e KS.

## Bases
- **Número de pelotas** (padrão): é o que a imagem mede. Não é comparável aos parâmetros RR de peneiramento, que são em massa.
- **Massa estimada (∝ d³):** cada pelota pesa d³, supondo esferas de mesma densidade. Aproxima a base das curvas de peneira, mas **não é peneiramento**:
  - o diâmetro vem da projeção 2D;
  - o erro de diâmetro é elevado ao cubo;
  - pelotas ocultas ou cortadas na borda não entram.

## Onde aparece
- **Windows:** painel "Curva acumulada · Rosin–Rammler", com seletor de base. O antigo painel "Escala e histograma" agora abre pelo botão **Escala e histograma…**.
- **Android:** abas **Histograma | Rosin–Rammler** no mesmo espaço do gráfico. Toque na curva para escolher a base.
- **Relatório PDF:**
  - seção "Ajuste Rosin–Rammler" com as duas bases;
  - figura de página inteira com o histograma em cima e a curva RR embaixo;
  - fórmula ajustada no gráfico e na legenda.
- **JSON:** bloco `rosin_rammler` com `selected_basis`, `number` e `estimated_mass_d3`. Cada base traz `n`, `d_prime`, `d50_fit`, `formula`, `r2_at_class_limits`, `rmse_percent_points`, `ks_individual` e `points`.

## Limites
- Mínimo de 12 pelotas com variação de diâmetro; abaixo disso o ajuste fica indisponível e o gráfico mostra só os pontos.
- Não validado contra peneiramento real.

As imagens em `verification/rosin-rammler-windows/` foram geradas com uma amostra sintética (Weibull n = 4,2, d′ = 12,8 mm, 180 pelotas), não com fotos de usina.
