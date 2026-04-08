# Documentacao Tecnica CE: Metaheuristica vs Metodo Exato

## 1. Escopo

Este documento descreve tecnicamente o pipeline atual do projeto para o problema de Coleta e Entrega (CE), cobrindo:

- o que esta sendo resolvido do ponto de vista cientifico;
- como a metaheuristica em Java esta sendo executada;
- qual e a estrutura do metodo exato em Julia;
- a leitura detalhada de `run_CE` e `run_CEc8`;
- a comparacao entre os cenarios metaheuristicos e o metodo exato;
- os pontos de atencao que afetam comparacoes experimentais.

O repositorio implementa duas familias de abordagem:

- Metaheuristica em Java/Spring Boot, usando `Jsprit`;
- Metodo exato em Julia, usando `JuMP + Gurobi`.

## 2. Problema modelado

O problema tratado e um VRP de colaboracao horizontal entre duas transportadoras, com:

- dois carriers: `A` e `B` no Julia, `1` e `2` na API Java;
- entregas e coletas simultaneas no mesmo cliente;
- possibilidade de clientes compartilhados entre carriers;
- minimizacao do custo total de deslocamento;
- restricoes de capacidade;
- restricoes de compatibilidade entre demanda do carrier de origem e o carrier que executa o atendimento.

Em termos conceituais, o problema se aproxima de um SCCVRP/VRPSPD:

- `delivery` representa carga que sai do deposito e e descarregada no cliente;
- `pickup` representa carga coletada no cliente e transportada de volta;
- colaboracao horizontal significa que um carrier pode atender demanda originalmente associada ao outro, dependendo do cenario.

## 3. Pipeline experimental atual

### 3.1. Pipeline da metaheuristica

O fluxo atual da abordagem Java e:

1. Ler instancias `.dat` ou `.txt`;
2. Converter para JSON no formato da API;
3. Chamar os endpoints da API;
4. Salvar os resultados por instancia;
5. Gerar relatorios CSV/Excel.

Observacao operacional importante:

- a API expoe quatro endpoints (`/ce-c8`, `/ce-c8-no-share`, `/ce-custom`, `/ce-custom-no-share`);
- o orquestrador atual executa apenas tres:
  - `/ce-custom`
  - `/ce-custom-no-share`
  - `/ce-c8`
- portanto, o cenario `ce-c8-no-share` nao entra no batch principal atual.

Arquivos principais:

- `scripts_ce/dat_to_json_ce.py`
- `scripts_ce/orchestrator_ce.py`
- `scripts_ce/gerar_relatorio_ce.py`
- `src/main/java/com/pibic/vrp/service/VrpService.java`

### 3.2. Pipeline do metodo exato

O fluxo atual da abordagem Julia e:

1. Ler um arquivo `.dat`;
2. Extrair ID, numero de clientes, capacidade, matriz de custo, demanda de entrega e coleta;
3. Construir estruturas auxiliares, inclusive `demand_tri`;
4. Reescrever o proprio `.dat` anexando as secoes `SCCVRPSPD` e `SCCVRPSPDM-M`;
5. Executar sucessivamente varios modelos (`run_CE`, `run_CEc8`, `run_CE_A`, `run_CE_B`, variantes m2m);
6. Salvar um arquivo texto por modelo, com status, objetivo, bound, gap e variaveis positivas.

Arquivo principal:

- `codigo_julia_exato/supercodigo.jl`

Arquivo auxiliar de preprocessamento:

- `codigo_julia_exato/exenew2.jl`

## 4. Pre-processamento

### 4.1. Conversao `.dat/.txt -> JSON` para a API

O script `dat_to_json_ce.py` extrai:

- `Id`;
- numero de carriers `c`;
- numero de clientes `n`;
- capacidade `Q`;
- depositos `D`;
- conjunto `CJ` de carriers permitidos por cliente;
- matriz de entregas `d`;
- matriz de coletas `p`;
- matriz de custos `cost`.

Depois monta um `VrpInput` com:

- `globalParameters`;
- `fleets`;
- `customers`;
- `costMatrix`.

Observacao tecnica importante:

- o campo `allowedCarriers` e carregado para o JSON, mas nao e usado pela logica do solver em `VrpService`.
- na pratica, a elegibilidade do cliente por carrier esta sendo inferida a partir de demanda/coleta positiva, e nao a partir de `CJ`.

### 4.2. Pre-processamento do Julia

Em `supercodigo.jl`, o metodo `rodar_modelo_com_arquivo`:

- carrega o arquivo;
- extrai `id`, `clientes`, `capacity`, `custo`, `demand` e `coleta`;
- chama `construir_demand_tri(demand, coleta)`;
- regrava o `.dat` anexando:
  - a matriz `p` em secao `SCCVRPSPD`;
  - a estrutura `m2m` em secao `SCCVRPSPDM-M`.

Observacao tecnica importante:

- para `run_CE` e `run_CEc8`, a otimizacao usa `demand` e `coleta`, nao `demand_tri`;
- logo, o preprocessamento aleatorio de `construir_demand_tri` nao altera a formulacao de `run_CE` nem de `run_CEc8`;
- ele afeta principalmente as variantes m2m (`run_A`, `run_B` e correlatas).

### 4.3. Natureza cientifica de `construir_demand_tri`

O metodo `construir_demand_tri` em `exenew2.jl` faz uma redistribuicao aleatoria de fluxos:

- identifica clientes compartilhados (`hub`);
- divide esses hubs aleatoriamente entre `rotaA` e `rotaB`;
- seleciona subconjuntos de clientes exclusivos;
- atribui hubs/deposito como pontos de atendimento intermediario;
- redistribui a demanda inteira por proporcoes aleatorias;
- preenche a matriz tridimensional `demand_tri`.

Isto caracteriza um preprocessamento estocastico para cenarios m2m/hub, e nao parte do nucleo do modelo exato CE basico.

## 5. Metaheuristica em Java

## 5.1. Solver utilizado

A metaheuristica usada e o `Jsprit`, invocado como engine de VRP dentro da API Spring Boot.

No codigo atual:

- nao ha implementacao propria de operador de busca local;
- nao ha operador customizado de ruin-and-recreate implementado manualmente no repositorio;
- o algoritmo de busca e o algoritmo padrao do `Jsprit`, parametrizado e repetido em esquema `multi-start`.

Do ponto de vista experimental, o que esta sendo usado e:

- uma metaheuristica de roteamento do `Jsprit`;
- com varias reinicializacoes (`multi-start`);
- com sementes pseudoaleatorias controladas;
- com restricoes extras de acoplamento entre jobs de entrega e coleta.

## 5.2. Como o problema e traduzido para o Jsprit

O problema e modelado com:

- uma matriz de custo assimetrica ou geral, carregada em `VehicleRoutingTransportCostsMatrix`;
- custo de transporte igual ao tempo de transporte;
- veiculos com capacidade unica;
- jobs separados de `Delivery` e `Pickup`;
- uma `HardRouteConstraint` para forcar jobs relacionados a ficarem no mesmo veiculo.

Interpretacao:

- `Delivery` reduz a carga do veiculo;
- `Pickup` aumenta a carga do veiculo;
- a combinacao `Delivery + Pickup` no mesmo cliente reproduz a logica de coleta e entrega simultanea;
- a constraint `SameVehicleConstraint` funciona como aproximacao operacional da consistencia representada pela variavel `z` do modelo exato.

## 5.3. Cenario CE-C8 na metaheuristica

O endpoint `/ce-c8` chama `solveWithC8`.

Ideia central:

- cada cliente compartilhado gera um par de jobs agregados:
  - `d_<cliente>`
  - `p_<cliente>`
- as demandas dos dois carriers sao somadas nesse mesmo cliente;
- `SameVehicleConstraint` garante que entrega e coleta desse cliente fiquem no mesmo veiculo;
- clientes exclusivos recebem `requiredSkill` do carrier correspondente;
- clientes compartilhados, nesse modo, ficam sem `requiredSkill`, logo podem ser atendidos por qualquer um dos dois carriers.

Em termos de modelagem, o CE-C8 da API representa:

- uma visita fisica unica por cliente;
- com consolidacao da demanda total do cliente;
- e escolha do carrier executor pelo proprio solver.

## 5.4. Cenario CE-Custom na metaheuristica

O endpoint `/ce-custom` chama `solveWithCustomConstraint`.

Esse modo relaxa a unicidade de visita por cliente e trabalha por configuracoes de alocacao:

- todos os compartilhados com carrier 1;
- todos com carrier 2;
- todos separados (`S`);
- alocacao por proximidade ao deposito;
- alocacao inversa da proximidade;
- combinacoes adicionais exaustivas para poucos compartilhados;
- configuracoes aleatorias adicionais quando necessario.

Para cada configuracao:

- o problema e reconstruido;
- sao criados jobs por carrier ou jobs combinados;
- `SameVehicleConstraint` e aplicada;
- o `Jsprit` e executado em `multi-start`;
- a melhor configuracao e escolhida pelo menor custo total.

Interpretacao cientifica:

- `CE-Custom` e uma heuristica de enumeracao parcial do espaco de alocacoes dos clientes compartilhados;
- a busca de roteamento e delegada ao `Jsprit`;
- a decisao de colaboracao e feita num nivel acima do roteamento, por cenarios.

## 5.5. Parametrizacao da metaheuristica

Ha dois esquemas principais:

### A. `solveWithConstraintMultiStart`

Usado em:

- `solveWithC8`
- `solveWithCustomConstraint`

Parametros atuais:

- `numStarts = 10` no CE-C8;
- `numStarts = 2` em cada alocacao do CE-Custom;
- `THREADS = 4`;
- `VEHICLE_SWITCH = false`;
- `FAST_REGRET = false`;
- `maxIterations = 1000`;
- sementes `seed * 1000 + 42`.

### B. `solveWithMultiStart`

Usado em:

- `solveWithCustomConstraintSingleCarrier`

Parametros atuais:

- `numStarts = 5`;
- `THREADS = 8`;
- `VEHICLE_SWITCH = true`;
- `FAST_REGRET = false`;
- `THRESHOLD_ALPHA = 0.1`;
- `THRESHOLD_INI = 0.05`;
- `maxIterations = 2000`;
- sementes `seed * 1000 + 42`.

Consequencia pratica:

- CE-C8 e CE-Custom usam o mesmo tipo de engine, mas com intensidades de busca diferentes;
- CE-Custom depende de duas camadas heuristicas:
  - geracao de alocacoes;
  - resolucao do VRP via `Jsprit`.

## 5.6. Pos-processamento da metaheuristica

Na API Java, o pos-processamento inclui:

- conversao da solucao para `VrpSolution`;
- serializacao em JSON;
- consolidacao em relatorios CSV/Excel.

No caso de `mapSolutionC8`:

- as atividades sao transformadas em sequencias `START`, `DELIVERY`, `PICKUP`, `END`;
- as localizacoes sao deduplicadas por rota;
- o custo da rota e recalculado explicitamente por `calculateRouteCost`.

No caso de `mapSolutionCustomMultiStart` e `mapSolutionSimple`:

- a sequencia de atividades e mantida;
- o `routeCost` nao e preenchido explicitamente.

Ponto de atencao:

- o orquestrador calcula `cost_a` e `cost_b` do `ce-custom-no-share` somando `routeCost`;
- como `mapSolutionSimple` nao popula `routeCost`, os relatorios por carrier nesse cenario tendem a ficar incorretos ou zerados.

## 6. Metodo exato em Julia

## 6.1. Solver utilizado

O metodo exato usa:

- `JuMP` para modelagem;
- `Gurobi` como resolvedor MIP.

Parametros atuais em `run_CE` e `run_CEc8`:

- `TimeLimit = 5` segundos;
- `MIPGap = 0.00`.

Interpretacao:

- o modelo e formulado como MILP;
- a comparacao custo/otimalidade usa `objective_value`, `objective_bound` e `relative_gap`;
- se o tempo limite e atingido, a comparacao correta nao e "otimo vs heuristica", mas "melhor incumbente do Gurobi vs solucao heuristica".

## 6.2. Estruturas e variaveis do modelo

Nos dois modelos, a formulacao basica usa:

- `setN`: clientes;
- `setC`: carriers (`A`, `B`);
- `nos = clientes + depositos/carriers`.

Variaveis:

- `x[i,j,r]` binaria:
  - ativa o arco de `i` para `j` na rota do carrier `r`.
- `z[i,r,s]` binaria:
  - associa a demanda originalmente ligada a `r` no cliente `i` ao carrier executor `s`.
- `l[i,j,r,h]` continua nao-negativa:
  - fluxo de entrega associado ao cliente `h` atravessando o arco `(i,j)` na rota de `r`.
- `m[i,j,r,h]` continua nao-negativa:
  - fluxo de coleta associado ao cliente `h` atravessando o arco `(i,j)` na rota de `r`.

Interpretacao cientifica:

- `x` modela a estrutura de roteamento;
- `z` modela a decisao de colaboracao/intercambio;
- `l` e `m` fazem o rastreamento multicommodity simplificado da carga de entrega e coleta;
- a capacidade e controlada sobre a soma desses fluxos.

## 6.3. Restricoes principais do modelo exato

### `c2`

`sum(z[i,r,s]) == 1`

Cada demanda do cliente `i`, originalmente pertencente a `r`, deve ser atribuida a exatamente um carrier executor viavel `s`.

### `c3`

Conservacao de fluxo de visita:

- numero de arcos saindo do cliente = numero de arcos entrando no cliente, por carrier executor.

### `c4`

Ligacao entre atribuicao e visita:

- se `z[i,r,s] = 1`, entao o carrier `s` precisa visitar o cliente `i`.

### `c5` e `c5linha`

Inicializacao dos fluxos no deposito/carrier:

- `c5` injeta fluxo de entrega;
- `c5linha` fecha a logica de coleta.

### `c6`, `c6linha`, `c62`, `c62linha`

Conservacao de fluxo por commodity:

- preservam o balanceamento dos fluxos `l` e `m`;
- retiram a demanda entregue no proprio cliente;
- adicionam a coleta correspondente.

### `c7mod`

Restricao de capacidade:

- a soma de cargas de entrega e coleta sobre um arco nao pode exceder `capacity * x[i,j,r]`.

### `c8`

Restricao de visita unica:

- cada cliente deve ser visitado exatamente uma vez no conjunto das rotas/carriers elegiveis.

Essa restricao e a principal fronteira conceitual entre os cenarios analisados aqui.

## 7. Analise detalhada de `run_CE`

`run_CE` e o modelo exato CE com restricao de visita unica explicita.

Caracteristicas observadas:

- resolve um MILP via Gurobi;
- usa `demand` e `coleta` diretamente;
- define `customer_transp_Nr`, `transp_customer_Ci` e `customerEtransp`;
- cria `x`, `z`, `l`, `m`;
- inclui `c8` para todos os clientes:
  - `sum(x[i,j,r] ...) == 1`.

Leitura cientifica:

- e o modelo mais aderente ao conceito "um cliente, uma visita fisica";
- permite colaboracao via `z`;
- preserva a rastreabilidade de fluxo de entrega e coleta por cliente;
- fornece bound dual e gap, o que o torna referencia para comparacao de qualidade de solucao.

## 8. Analise detalhada de `run_CEc8`

Apesar do nome, `run_CEc8` nao contem a restricao `c8`.

O corpo da funcao e praticamente identico ao de `run_CE`, com uma diferenca decisiva:

- em `run_CE`, `c8` esta ativa;
- em `run_CEc8`, `c8` nao aparece.

Leitura correta do que o codigo faz hoje:

- `run_CEc8` resolve um modelo mais relaxado do que `run_CE`;
- ele mantem atribuicao `z`, estrutura de arcos `x` e capacidade por fluxos `l/m`;
- porem nao impoe explicitamente que cada cliente seja visitado exatamente uma vez.

Consequencia cientifica:

- `run_CEc8` nao representa o "modelo com C8";
- ele representa um cenario sem essa restricao, ou pelo menos um cenario em que a visita unica nao esta garantida no modelo MIP.

Portanto, para fins de experimento, a nomenclatura atual gera ambiguidade:

- `run_CE` e o modelo com `c8`;
- `run_CEc8` e o modelo sem `c8`.

## 9. Comparacao direta: `run_CE` vs `run_CEc8`

### 9.1. Semelhancas

Ambos:

- usam `JuMP + Gurobi`;
- possuem os mesmos conjuntos e variaveis `x`, `z`, `l`, `m`;
- usam o mesmo objetivo de minimizacao do custo;
- usam a mesma estrutura de capacidade e conservacao de fluxo;
- usam o mesmo limite de tempo e gap alvo.

### 9.2. Diferenca estrutural

A diferenca central e:

- `run_CE`: tem `c8`;
- `run_CEc8`: nao tem `c8`.

### 9.3. Implicacao operacional

Com `c8`:

- cada cliente e forcado a ter uma unica visita global;
- o carrier executor e escolhido, mas a visita nao pode ser duplicada.

Sem `c8`:

- o modelo pode aceitar mais liberdade estrutural;
- a interpretacao "uma demanda, uma visita" deixa de ser automaticamente valida;
- a comparacao de custo com o modelo com `c8` precisa ser feita como comparacao entre formulacoes diferentes, e nao apenas entre algoritmos diferentes.

### 9.4. Implicacao experimental

Se o objetivo do experimento e comparar:

- metaheuristica com visita unica;
- metodo exato com visita unica;

entao o pareamento correto hoje e:

- `solveWithC8` na API Java;
- `run_CE` no Julia.

Se o objetivo e comparar cenarios sem a visita unica explicita, o pareamento conceitual seria:

- `solveWithCustomConstraint` na API Java;
- `run_CEc8` no Julia.

Mesmo assim, esse pareamento ainda nao e perfeito, porque a logica operacional dos dois lados continua diferente.

## 10. Comparacao: metaheuristica vs metodo exato

## 10.1. O que a metaheuristica aproxima do modelo exato

A metaheuristica aproxima bem os seguintes elementos:

- capacidade do veiculo;
- coleta e entrega simultaneas;
- colaboracao entre carriers;
- acoplamento entre entrega e coleta do mesmo cliente/demanda;
- restricao de visita unica no modo CE-C8.

## 10.2. O que nao e equivalente um-para-um

### A. Representacao da colaboracao

No Julia:

- a colaboracao esta explicitamente na variavel `z[i,r,s]`.

No Java:

- ela e representada por:
  - agregacao de jobs no CE-C8;
  - ou enumeracao heuristica de alocacoes no CE-Custom.

Logo, o Java nao resolve a mesma formulacao MIP; ele resolve uma traducao operacional do problema.

### B. Estrutura de busca

No Julia:

- o Gurobi faz branch-and-bound / branch-and-cut sobre a formulacao MILP.

No Java:

- o `Jsprit` executa uma busca heuristica iterativa;
- nao ha garantia de otimalidade;
- o resultado depende do conjunto de seeds, iteracoes e configuracoes de alocacao.

### C. Interpretacao da visita unica

No Julia:

- `c8` e uma restricao matematica explicita.

No Java CE-C8:

- a visita unica e garantida por construcao da instancia heuristica:
  - um par agregado `Delivery/Pickup` por cliente compartilhado;
  - constraint de mesmo veiculo;
  - um unico local por cliente.

No Java CE-Custom:

- clientes compartilhados podem ser separados por carrier;
- entao o mesmo cliente pode aparecer em mais de uma rota.

### D. Possivel diferenca no numero de rotas

No Julia:

- a formulacao `x[i,j,r]` e indexada por carrier, nao por veiculo individual.

No Java:

- o problema e construido com `FleetSize.INFINITE`;
- portanto, a configuracao nao reflete necessariamente a mesma interpretacao estrutural do MIP em termos de numero de rotas/veiculos.

Para comparacao cientifica, isso deve ser declarado como diferenca de modelagem.

## 10.3. Qual comparacao e metodologicamente mais limpa

### Comparacao 1: "metaheuristica com visita unica" vs "metodo exato com visita unica"

Usar:

- Java: `solveWithC8` (`/ce-c8`);
- Julia: `run_CE`.

Essa e a comparacao mais coerente para avaliar:

- perda de qualidade por trocar exatidao por heuristica;
- ganho de tempo computacional;
- impacto de representar C8 por construcao heuristica em vez de restricao MIP.

### Comparacao 2: "metaheuristica sem visita unica estrita" vs "metodo exato sem C8"

Usar:

- Java: `solveWithCustomConstraint` (`/ce-custom`);
- Julia: `run_CEc8`.

Essa comparacao avalia:

- cenarios mais flexiveis de colaboracao;
- efeito de permitir separacao de atendimento entre carriers;
- reducao potencial de custo ao relaxar a visita unica.

Mas essa comparacao tem maior assimetria metodologica, porque:

- o Java escolhe alocacoes por enumeracao heuristica;
- o Julia continua resolvendo uma formulacao matematica unica.

## 11. Pontos criticos para relatar em artigo, TCC ou relatorio

### 11.1. Inversao/ambiguidade de nomenclatura no Julia

Hoje, pelo codigo:

- `run_CE` e o modelo com `c8`;
- `run_CEc8` e o modelo sem `c8`.

Isso deve ser explicitado para evitar conclusoes invertidas.

### 11.2. `allowedCarriers` / `CJ` nao participa do solver Java

O dado existe no JSON, mas nao entra na restricao de elegibilidade do `VrpService`.

Isso significa que a API nao esta usando integralmente a semantica do preprocessamento de entrada.

### 11.3. Relatorio de custo por carrier no `ce-custom-no-share`

Os campos `cost_a` e `cost_b` calculados no orquestrador dependem de `routeCost`, mas `mapSolutionSimple` nao preenche esse valor.

Assim, a comparacao por carrier nesse cenario pode estar errada no relatorio atual.

### 11.4. Tempo limite do metodo exato

Como o `TimeLimit` atual e 5 segundos:

- em muitas instancias o resultado exato pode ser apenas incumbente com gap aberto;
- nesse caso, a comparacao correta deve considerar:
  - custo incumbente;
  - best bound;
  - gap;
  - tempo.

### 11.5. O batch principal nao roda `ce-c8-no-share`

O `orchestrator_ce.py` mede atualmente:

- `ce-custom`;
- `ce-custom-no-share`;
- `ce-c8`.

Assim, qualquer comparacao sistematica envolvendo `ce-c8-no-share` exige:

- ajustar o orquestrador;
- ou usar um fluxo adicional de coleta de resultados.

## 12. Recomendacao de uso para comparacao experimental

Se a meta for comparar "metaheuristico vs metodo exato" de forma mais defensavel:

1. Compare `/ce-c8` com `run_CE`;
2. Reporte, no lado Julia, `objective_value`, `objective_bound`, `gap` e `time`;
3. Reporte, no lado Java, `totalCost`, `numRoutes`, `unassignedJobs` e `time`;
4. Trate `/ce-custom` vs `run_CEc8` como comparacao de cenario relaxado, nao como equivalencia exata de formulacao;
5. Declare explicitamente as diferencas de modelagem, sobretudo:
   - `z` explicita no MIP vs alocacao heuristica no Java;
   - `c8` explicita no MIP vs construcao por jobs no Java;
   - possivel diferenca de numero de rotas/veiculos;
   - uso atual de `CJ` apenas no preprocessamento.

## 13. Resumo executivo

- A metaheuristica atual usa `Jsprit` com `multi-start`, jobs `Delivery/Pickup`, matriz de custos, capacidade e constraint customizada de mesmo veiculo.
- O metodo exato atual usa um MILP em `JuMP + Gurobi` com variaveis `x`, `z`, `l`, `m`.
- `run_CE` e o modelo exato com visita unica (`c8`).
- `run_CEc8`, apesar do nome, esta sem `c8`.
- A comparacao mais limpa entre heuristica e exato e `CE-C8` (Java) vs `run_CE` (Julia).
- A comparacao `CE-Custom` vs `run_CEc8` deve ser apresentada como comparacao entre cenarios relaxados, nao como equivalencia plena de formulacao.
