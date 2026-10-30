# Busca rápida do ajuste ao vivo (Windows 0.6.0) — substitui a genética como padrão

## Por que o genético era lento
- **O espaço é pequeno:** 2 métodos (3 com a rede) × 4 perfis × 21 sensibilidades = 168–252 ajustes. Um genético serve para espaços grandes e irregulares; aqui ele sorteia pontos que uma busca ordenada não precisa visitar (30–48 avaliações).
- **Uma avaliação por vez:** cada avaliação roda a detecção completa (mais o ajuste de elipses) em 3 quadros, em série.
- **Trabalho repetido:** no LoG, a sensibilidade só muda o **limiar**. O espaço de escala (8 desfoques gaussianos + laplacianos no quadro) era recalculado para cada sensibilidade do mesmo perfil.

## O que entrou (`core/.../LiveSearch.java`)
1. **Triagem:** cada combinação método × perfil na sensibilidade atual (8 ajustes; 12 com a rede).
2. **Refino:** nas 2 melhores combinações, busca de padrão na sensibilidade com passos 20 → 10 → 5. Avalia os vizinhos ± passo; move para o melhor, senão reduz o passo. A aptidão quase sempre é unimodal na sensibilidade.
3. **Cache do LoG:** `PelletDetector.prepare(...)` guarda o espaço de escala, a máscara e a imagem suavizada por quadro e perfil; cada sensibilidade só refaz picos, supressão e watershed. O resultado é **idêntico** ao detector antigo (teste em 4 perfis × 5 sensibilidades).
4. **Paralelo:** avaliações independentes rodam em `núcleos − 1` threads (até 4). A rede neural roda em série (um `Net` por modelo).
5. **Mesma aptidão do genético** (`LiveTuner.Candidate`), determinística (mesmo resultado em 1 ou N threads), cancelada por movimento.

A genética continua disponível no seletor **Busca rapida / Busca genetica**, ao lado de "Otimizar quando parado".

## Medido aqui (3 fotos reais da webcam de 27/10, 3 quadros cada: original + 2 com ruído σ=2)

| | Tempo médio | Avaliações | Aptidão do melhor |
|---|---|---|---|
| Genética (0.5.5) | 32,4 s | 31–32 | 67,9 |
| **Rápida, 1 thread** | **13,0 s** | 19–20 | **67,9** |
| Rápida, 2 threads | 13,6 s | 19–20 | 67,9 |

- **2,5× mais rápida com a mesma aptidão** nas três fotos (mesmo ajuste ou vizinho de 5 pontos de sensibilidade).
- O ganho do paralelo **não aparece nesta medição**: a máquina tinha 2 núcleos e um deles estava ocupado por outro treino. Num PC com 4–8 núcleos livres, a expectativa é de mais 2–3× na triagem, mas isso **não foi medido**.
- Benchmark reproduzível: `desktop/src/test/java/br/com/pelotavision/LiveSearchBenchmark.java`.

## Ainda dá para acelerar (não implementado)
- **Triagem em 1 quadro** e só o refino em 3 (corta ~⅓ das detecções).
- **Partida a quente:** com a cena parada de novo no mesmo lugar, começar do último ajuste e só refinar a sensibilidade.
- **Elipses só no fim:** pontuar a triagem com as propostas, sem o ajuste de elipses, que hoje é boa parte do custo de cada avaliação.
