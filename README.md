# SCCVRPSPD — serviço de roteirização colaborativa com meta-heurística

Serviço web em Java que resolve o **Shared Customer Collaboration Vehicle Routing
Problem with Simultaneous Pickup and Delivery** (SCCVRPSPD) por meta-heurística, com
validação sistemática contra as soluções ótimas de um modelo exato.

Projeto de iniciação científica (PIBITI, ciclo 2025-2026) do Instituto Militar de
Engenharia, dentro do projeto *Pesquisa Operacional: Aplicação na Logística Militar*.

---

## O problema

Duas transportadoras, cada uma com seu depósito e frota própria, atendem clientes em
uma mesma região. Alguns clientes são **compartilhados**: têm demanda junto às duas.
Cada cliente possui, simultaneamente, uma demanda de **entrega** (que sai do depósito)
e uma de **coleta** (que retorna ao depósito), de modo que a carga do veículo flutua ao
longo da rota.

A pergunta é quanto se economiza quando as transportadoras colaboram, permitindo que um
cliente compartilhado seja atendido por apenas uma delas, escolhida para minimizar o
custo agregado.

O modelo exato correspondente é uma formulação de programação linear inteira mista
baseada em carga, resolvida em Julia com Gurobi. Ele garante otimalidade para instâncias
pequenas, mas o esforço cresce rapidamente: com 30 clientes, apenas 2 de 20 instâncias
foram resolvidas até a otimalidade dentro de 7.200 segundos. Daí a meta-heurística.

## Arquitetura

```
Instâncias .dat ──> dat_to_json_ce.py ──> orchestrator_ce.py ──> relatórios
                                                  │
                                                  ▼
                                     API REST (Spring Boot)
                                                  │
                                                  ▼
                                     VrpService ──> Jsprit (Ruin-and-Recreate)
```

A meta-heurística é o esquema *Ruin-and-Recreate* (Schrimpf et al., 2000) da biblioteca
**Jsprit 1.9.0-beta.3**: destruição por remoção radial ou aleatória, reconstrução por
melhor inserção ou inserção com arrependimento, aceitação por *Threshold Accepting*, com
10 partidas independentes de 1.000 iterações e sementes determinísticas.

## Endpoints

Todos são `POST` sob `/api/solve` e recebem a instância em JSON.

| Endpoint | Cenário | Equivale a |
|---|---|---|
| `/run-CE` | com simultaneidade, colaborativo | `run_CE` (com a restrição `c8`) |
| `/run-CE-no-share` | com simultaneidade, sem colaboração | `run_CE_A` + `run_CE_B` |
| `/run-CEc8` | sem simultaneidade, colaborativo | `run_CEc8` (sem a `c8`) |
| `/run-CEc8-no-share` | sem simultaneidade, sem colaboração | — |

São os quatro cenários da combinação 2×2 entre simultaneidade e colaboração. O último
não tem rotina equivalente no modelo exato, logo não dispõe de referência para comparação.

> A nomenclatura vem do modelo exato e é contraintuitiva: **`CEc8` significa "CE *sem* a
> restrição `c8`"**, ou seja, sem a exigência de visita única.

### Como os cenários diferem na modelagem

Em ambos, a alocação dos clientes compartilhados é decidida pela própria busca: as
tarefas de um cliente exclusivo exigem a *skill* da sua transportadora, e as de um
cliente compartilhado não exigem nenhuma. O que muda é a representação:

| | Com simultaneidade | Sem simultaneidade |
|---|---|---|
| Cliente compartilhado | um par de tarefas, demandas somadas | um par por transportadora |
| Vínculo entrega ↔ coleta | mesmo veículo | mesma transportadora |
| Visitas admitidas | exatamente uma | uma ou mais, por uma ou ambas |

O vínculo por transportadora (`SameCarrierConstraint`) reflete o modelo exato, no qual
as variáveis de roteamento são indexadas por transportadora, sem índice de veículo.

## Executando

Requer **Java 17** e **Maven 3**.

```bash
mvn clean package          # build
mvn spring-boot:run        # sobe em localhost:8080
```

Exemplo de chamada:

```bash
curl -X POST http://localhost:8080/api/solve/run-CE \
     -H "Content-Type: application/json" \
     -d @instancia.json
```

### Formato de entrada

```jsonc
{
  "problemId": "ce_1",
  "globalParameters": { "vehicleCapacity": 100 },
  "fleets":    [ { "carrierId": "1", "depotLocationId": "24" } ],
  "customers": [ { "id": "5",
                   "deliveryDemandByCarrier": { "1": 12, "2": 7 },
                   "pickupDemandByCarrier":   { "1": 9 },
                   "allowedCarriers": ["1", "2"] } ],
  "costMatrix": [ { "from": "5", "to": "6", "cost": 12.4 } ]
}
```

A resposta traz `totalCost`, as rotas com sua sequência de atividades e o custo de cada
uma.

## Scripts

| Script | Função |
|---|---|
| `dat_to_json_ce.py` | converte as instâncias `.dat` do modelo exato para JSON |
| `orchestrator_ce.py` | submete o lote de instâncias à API e consolida os resultados |
| `rodar_lote_cec8.py` | executa o lote no cenário sem simultaneidade (retomável) |
| `analisar_cec8.py` | compara os resultados contra a referência exata |
| `auditar_visitas.py` | audita quantas transportadoras visitam cada cliente |
| `auditar_exato_relaxado.py` | mesma auditoria sobre as soluções do modelo exato |
| `testar_cec8.py` | teste de aceitação da modelagem do cenário relaxado |
| `gerar_*.py`, `verificar_latex.py` | geração do relatório final em `.docx` e LaTeX |

## Resultados

Validação sobre 112 instâncias adaptadas de Fernández, Roca-Riu e Speranza (2018):
12 no conjunto S1 e 100 no S2 (50 aleatórias, 50 agrupadas, de 10 a 30 clientes).
Referência: Gurobi com limite de 7.200 s, no mesmo hardware.

**Diferença percentual média contra o método exato**

| Cenário | S1 | S2 | Soluções idênticas ao ótimo (S2) |
|---|---|---|---|
| Com simultaneidade | +0,22% | +0,36% | 60 de 100 |
| Sem simultaneidade | +0,89% | +0,89% | 57 de 100 |
| Sem compartilhamento | +0,11% | +0,16% | 76 de 100 |

**Tempo.** No cenário com simultaneidade nenhuma execução ultrapassou 82 segundos,
contra médias acima de 6.500 s do método exato nas maiores instâncias — uma aceleração
de até 97 vezes. O cenário sem simultaneidade custa cerca do dobro, chegando a 213 s.

**Economia da colaboração.** Comparando o modo colaborativo com a operação independente,
a economia média é de **9,50%** no conjunto S2, chegando a 13,3% no grupo de 20 clientes.
O ponto relevante é que a meta-heurística estima essa economia com desvio **inferior a
0,2 ponto percentual** em relação ao método exato: os erros nos dois cenários comparados
se cancelam na razão que define a economia. Isso habilita o serviço a dimensionar o
ganho de uma coalizão entre transportadoras em segundos, sem depender de solver
comercial.

## Estrutura

```
src/main/java/com/pibic/vrp/
├── controller/VrpController.java      # camada REST
├── service/VrpService.java            # construção do problema e execução do Jsprit
├── constraint/
│   ├── SameVehicleConstraint.java     # entrega e coleta no mesmo veículo
│   └── SameCarrierConstraint.java     # entrega e coleta na mesma transportadora
└── model/                             # DTOs de entrada e saída

codigo_julia_exato/                    # modelo exato de referência (JuMP + Gurobi)
scripts_ce/                            # conversão, execução em lote, auditoria, relatório
```

### Não versionado

Instâncias, resultados brutos e os entregáveis do relatório (`.docx`, LaTeX, figuras,
planilhas) ficam fora do controle de versão, conforme o `.gitignore`. As instâncias em
JSON são regeneráveis a partir dos `.dat` com `dat_to_json_ce.py`, e os resultados, pela
execução do lote. Versões anteriores permanecem recuperáveis no histórico do Git.

## Referências

- Fernández, E.; Roca-Riu, M.; Speranza, M. G. *The shared customer collaboration vehicle
  routing problem*. European Journal of Operational Research, v. 265, n. 3, 2018.
- Schrimpf, G. et al. *Record breaking optimization results using the ruin and recreate
  principle*. Journal of Computational Physics, v. 159, n. 2, 2000.
- [Jsprit](https://github.com/graphhopper/jsprit) — GraphHopper.

## Licença e autoria

Rafael Vargas Carvalheira, sob orientação do Prof. Orivalde Soares da Silva Júnior
(Instituto Militar de Engenharia).
