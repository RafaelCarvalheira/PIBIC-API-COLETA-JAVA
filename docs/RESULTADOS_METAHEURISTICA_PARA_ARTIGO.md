# Resultados da meta-heurística para o SCCVRPSPD — consolidado para o artigo

**Data:** 28 de julho de 2026
**Escopo:** resultados do Jsprit nos três cenários com referência exata disponível,
para atualização do manuscrito submetido ao *European Journal of Operational Research*.

Este documento reúne os números finais, a descrição da modelagem que os produziu e
os pontos do manuscrito que precisam ser revistos. Todos os valores saem de
`relatorio_final.xlsx` e são reproduzíveis pelos scripts indicados ao final.

---

## 1. O que mudou em relação à versão anterior do manuscrito

A modelagem do cenário **sem simultaneidade** (o `run_CEc8`, sem a restrição de
visita única) foi refeita. A versão que gerou os números atualmente no manuscrito
representava o cliente compartilhado como **um único par de tarefas com as demandas
das duas transportadoras somadas**, atribuído a uma transportadora escolhida por
pré-processamento. Essa representação impede, por construção, que as duas
transportadoras atendam separadamente o mesmo cliente — que é justamente a
liberdade que define o cenário relaxado.

A modelagem vigente cria **um par de tarefas por transportadora**, sem habilidade
restritiva, e vincula entrega e coleta de uma mesma demanda pela **transportadora**,
não pelo veículo. Isso reproduz o modelo matemático, no qual `x[i,j,r]` é indexado
por transportadora, sem índice de veículo, e uma única variável `z[i,r,s]` governa
entrega e coleta.

**Efeito medido:** a diferença média contra o método exato caiu de 3,07% para
**0,89%** no conjunto S2, e as soluções passaram a exibir o padrão que o modelo
admite (cliente atendido pelas duas transportadoras) em **26 das 112 instâncias**,
contra zero antes.

---

## 2. Modelagem no Jsprit (para a Seção 4 do manuscrito)

### 2.1 Representação comum aos cenários

- Cada demanda gera **duas tarefas**: uma `Delivery` (reduz a carga) e uma `Pickup`
  (aumenta a carga), de modo que a capacidade seja verificada corretamente em cada
  ponto da rota.
- A **alocação dos clientes compartilhados não é fixada de antemão**. As tarefas de
  um cliente exclusivo exigem a *skill* da sua transportadora; as de um cliente
  compartilhado não exigem *skill* alguma, e o algoritmo decide, ao inseri-las, qual
  transportadora as atende. É assim que a variável `z[i,r,s]` é representada: pela
  atribuição tarefa → veículo → transportadora.
- Frota finita, com `máx(n, 20)` veículos por transportadora.

### 2.2 O que distingue os dois cenários

| | Com simultaneidade (`run_CE`) | Sem simultaneidade (`run_CEc8`) |
|---|---|---|
| Cliente compartilhado | um par de tarefas, demandas somadas | **um par por transportadora** |
| Vínculo entrega ↔ coleta | mesmo veículo | **mesma transportadora** |
| Visitas admitidas | exatamente uma | uma ou mais, por uma ou ambas |

O vínculo por **mesma transportadora** (classe `SameCarrierConstraint`) é o ponto
central. O modelo exige que entrega e coleta de uma dada demanda sejam servidas pela
mesma transportadora, mas **não** pelo mesmo veículo: as restrições de fluxo operam
sobre a rede inteira da transportadora. Exigir mesmo veículo seria mais restritivo
que o modelo e eliminaria soluções válidas — inclusive a de uma transportadora
visitar o mesmo cliente em duas passagens, padrão que ocorre nas soluções exatas.

### 2.3 Parâmetros de busca (idênticos nos dois cenários)

| Parâmetro | Valor |
|---|---|
| Esquema | Ruin-and-Recreate (Schrimpf et al., 2000), via Jsprit 1.9.0-beta.3 |
| Ruína | Radial e Random |
| Reconstrução | Best Insertion e Regret Insertion |
| Aceitação | Threshold Accepting, limiar inicial 0,03, decaimento 0,15 |
| Iterações por partida | 1.000 |
| Partidas (multi-start) | 10, com sementes determinísticas |
| Threads | 4 |
| Critério de parada | número de iterações (sem limite de tempo) |

Hardware: Intel Core i7 de 3,20 GHz, 16 GB de RAM — a mesma configuração das
execuções do Gurobi, o que torna os tempos diretamente comparáveis. Limite de
7.200 s imposto ao método exato.

---

## 3. Resultados

### 3.1 Conjunto S1 — sem simultaneidade, com colaboração

Substitui a Tabela 3 do manuscrito.

| Inst. | Clientes | Obj (Gurobi) | Status | CPU (s) | Obj (Jsprit) | CPU (s) | Dif. (%) |
|---|---|---|---|---|---|---|---|
| 1 | 23 | 274,72 | OPTIMAL | 2.687,0 | 279,74 | 63,3 | +1,83 |
| 2 | 29 | 323,54 | TIME LIMIT | 7.207,9 | 338,91 | 101,6 | +4,75 |
| 3 | 20 | 236,02 | OPTIMAL | 558,2 | 236,02 | 98,6 | 0,00 |
| 4 | 23 | 322,30 | OPTIMAL | 3.595,5 | 322,30 | 85,7 | 0,00 |
| 5 | 24 | 328,26 | TIME LIMIT | 7.200,8 | 328,26 | 83,5 | 0,00 |
| 6 | 21 | 230,08 | OPTIMAL | 6,2 | 236,33 | 54,5 | +2,72 |
| 7 | 20 | 156,93 | OPTIMAL | 14,8 | 156,93 | 64,6 | 0,00 |
| 8 | 30 | 239,51 | OPTIMAL | 153,0 | 239,95 | 137,2 | +0,18 |
| 9 | 20 | 392,06 | OPTIMAL | 1,9 | 392,06 | 48,1 | 0,00 |
| 10 | 20 | 455,74 | OPTIMAL | 6,7 | 455,73 | 44,7 | 0,00 |
| 11 | 18 | 493,96 | OPTIMAL | 16,6 | 499,41 | 37,0 | +1,10 |
| 12 | 20 | 755,36 | TIME LIMIT | 7.200,9 | 756,48 | 50,0 | +0,15 |
| **Média** | | | | | | | **+0,89** |

Cinco instâncias reproduziram exatamente o valor de referência. A instância 10
difere em 0,01 unidade, ou seja, arredondamento.

**Comparar com os números antigos do manuscrito:** a média era +3,85%, com casos de
+12,46% (instância 10) e +8,93% (instância 8). Ambos desapareceram.

### 3.2 Conjunto S2 — os três cenários

| Cenário | 10 cli. | 15 cli. | 20 cli. | 25 cli. | 30 cli. | Média | Iguais |
|---|---|---|---|---|---|---|---|
| Com simultaneidade | 0,00 | −0,05 | +0,22 | +0,46 | +1,17 | **+0,36** | 60/100 |
| Sem simultaneidade | +0,17 | +0,70 | +0,99 | +0,33 | +2,24 | **+0,89** | 57/100 |
| Sem compartilhamento | 0,00 | 0,00 | +0,22 | +0,13 | +0,42 | **+0,16** | 76/100 |

### 3.3 Sem simultaneidade — aleatórias e agrupadas

| Subconjunto | Dif. (%) | Iguais | Aceleração |
|---|---|---|---|
| S2R (aleatórias, 50) | +0,71 | 26 | 33,0× |
| S2C (agrupadas, 50) | +1,06 | 31 | 27,7× |

### 3.4 Uso efetivo da liberdade do modelo relaxado

Evidência de que o cenário relaxado é de fato explorado, e não apenas aproximado
pela solução do cenário restrito:

| Métrica | Valor |
|---|---|
| Instâncias com cliente atendido pelas duas transportadoras | **26 de 112** |
| Clientes nessa condição, somados | 35 |
| Instâncias em que o relaxado sai mais caro que o restrito | 7 de 112 |

A última linha merece nota: como retirar a restrição só amplia o conjunto viável,
o custo do cenário relaxado nunca deveria superar o do restrito. As 7 ocorrências
remanescentes são variação normal de busca estocástica.

### 3.5 Tempo de processamento — sem simultaneidade

| Clientes | CPU Gurobi (s) | CPU Jsprit (s) | Aceleração |
|---|---|---|---|
| 10 | 2,6 | 16,4 | 0,2× |
| 15 | 15,0 | 29,0 | 0,5× |
| 20 | 1.150,0 | 54,7 | 21,0× |
| 25 | 2.913,5 | 79,7 | 36,5× |
| 30 | 5.607,3 | 142,3 | 39,4× |

Máximo observado no Jsprit: 212,6 s. O cenário sem simultaneidade custa cerca do
dobro do cenário restrito, porque o cliente compartilhado gera o dobro de tarefas.

### 3.6 Instâncias que superaram o método exato

| Inst. | Gurobi | Status | Jsprit | Dif. |
|---|---|---|---|---|
| 8016 | 1.139,32 | TIME LIMIT | 1.137,80 | −0,13% |
| 8047 | 936,06 | TIME LIMIT | 934,33 | −0,19% |
| 8066 | 843,38 | TIME LIMIT | 842,93 | −0,05% |
| 10 | 455,74 | OPTIMAL | 455,73 | −0,002% (arredondamento) |

**Nenhuma** instância superou um ótimo comprovado por margem relevante. Superar o
incumbente quando o solver para por limite de tempo é esperado e legítimo.

---

## 4. Pontos do manuscrito a revisar

1. **Tabela 3** (SCCVRPSPD sem simultaneidade, S1): substituir pelos valores da
   Seção 3.1. A diferença média cai de **+3,85% para +0,89%**, e os dois piores
   casos do manuscrito (+12,46% na instância 10 e +8,93% na 8) deixam de existir.

2. **Texto que segue a Tabela 3.** A redação atual atribui a diferença ao fato de
   que "a ausência dessa restrição amplia consideravelmente o espaço de soluções
   admissíveis, tornando a exploração metaheurística mais desafiadora". A ampliação
   do espaço é real, mas convém observar que, para uma meta-heurística, conjunto
   viável maior não implica busca mais difícil: relaxar restrição também cria mais
   soluções boas e mais caminhos de melhoria. A redação pode ganhar precisão ao
   apontar que a dificuldade vem da representação exigida — vínculo por
   transportadora e possibilidade de visitas múltiplas — e não do tamanho do espaço
   em si.

3. **Seção 5.7 (equivalência e limitações).** O trecho que diz que a atribuição dos
   clientes compartilhados "é tratada pelo mecanismo de *skills*, o que pode não
   cobrir todas as combinações quando o número de clientes compartilhados é elevado"
   continua correto e vale para os dois cenários. Sugere-se acrescentar a distinção
   entre exigir **mesma transportadora** e exigir **mesmo veículo**, que é onde o
   mapeamento para o Jsprit pode divergir do modelo.

4. **Seção 4.2 (modelagem no Jsprit).** Atualizar com o conteúdo da Seção 2 deste
   documento, em especial a representação do cliente compartilhado nos dois cenários
   e o vínculo por transportadora.

5. **Dado novo, sem equivalente no manuscrito.** A economia proporcionada pela
   colaboração, calculada pela meta-heurística, difere da calculada pelo método
   exato em no máximo 0,66 ponto percentual e em 0,17 na média do S2 (9,50% contra
   9,68%). Isso sustenta o uso da meta-heurística como ferramenta de dimensionamento
   do ganho de uma coalizão, sem depender do solver comercial. Pode render um
   parágrafo na Seção 5.4 ou na conclusão.

---

## 5. Reprodutibilidade

| O quê | Onde |
|---|---|
| Resultados por instância | `scripts_ce/resultados_cec8_v2/` e `relatorio_final.xlsx` |
| Execução do lote | `scripts_ce/rodar_lote_v2.py` |
| Análise agregada | `scripts_ce/analisar_v2.py` |
| Auditoria das visitas por cliente | `scripts_ce/auditar_visitas.py` |
| Auditoria das soluções exatas | `scripts_ce/auditar_exato_relaxado.py` |
| Implementação | `VrpService.solveWithCustomConstraintV2`, `SameCarrierConstraint` |
| Endpoint | `POST /api/solve/run-CEc8-v2` |
