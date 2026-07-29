# -*- coding: utf-8 -*-
"""Analisa o cenario sem simultaneidade (run_CEc8) contra a referencia exata.

Responde a tres perguntas:

1. ESTRUTURAL - a busca explora a liberdade que define o cenario relaxado, isto
   e, chega a atribuir um cliente compartilhado as duas transportadoras? E o
   criterio decisivo, porque nao depende de custo: no cenario com simultaneidade
   esse padrao e proibido por construcao.
2. COERENCIA  - retirar a restricao so amplia o conjunto viavel, entao o custo
   do cenario relaxado nunca deveria superar o do restrito. Ocorrencias em
   contrario medem a folga da busca, nao um defeito do modelo.
3. QUALIDADE  - a diferenca percentual contra o metodo exato, por grupo, e se
   alguma solucao ficou abaixo de um otimo comprovado, o que indicaria
   inviabilidade.

Uso: python scripts_ce/analisar_cec8.py
"""
import glob
import json
import os
import re
import statistics
import sys
from collections import defaultdict

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTADOS = os.path.join(BASE, "scripts_ce", "resultados_cec8")
PLANILHA = os.path.join(BASE, "relatorio_final.xlsx")
TOTAL_ESPERADO = 112


def referencia():
    """Le da planilha o metodo exato e a meta-heuristica, por cenario."""
    import openpyxl
    wb = openpyxl.load_workbook(PLANILHA, data_only=True)

    def ler(aba, cols):
        out = {}
        for r in wb[aba].iter_rows(values_only=True):
            if isinstance(r[0], (int, float)) and r[0]:
                out[int(r[0])] = {k: r[i] for k, i in cols.items()}
        return out

    return (ler("CEc8", dict(n=1, gurobi=2, status=3, jsprit=9, tempo=10)),
            ler("CE", dict(n=1, gurobi=2, jsprit=9)))


def clientes_nas_duas():
    """problemId -> quantos clientes foram atendidos pelas duas transportadoras."""
    out = {}
    for caminho in glob.glob(os.path.join(RESULTADOS, "*_run_CEc8.json")):
        r = json.load(open(caminho, encoding="utf-8"))
        m = re.search(r"ce_(\d+)", r.get("problemId", ""))
        if not m:
            continue
        carriers = defaultdict(set)
        for rota in r.get("routes", []):
            mm = re.match(r"vehicle_(\d+)", rota.get("vehicleId", ""))
            carrier = mm.group(1) if mm else "?"
            for a in rota.get("activitySequence", []):
                c = re.match(r"(DELIVERY|PICKUP):(.+)", a)
                if c:
                    carriers[c.group(2)].add(carrier)
        out[int(m.group(1))] = sum(1 for t in carriers.values() if len(t) > 1)
    return out


def dif(novo, base):
    return (novo - base) / base * 100 if base else 0.0


def main():
    if not os.path.isdir(RESULTADOS):
        print("Lote ainda nao produziu resultados em", RESULTADOS)
        return 2

    duplos = clientes_nas_duas()
    cec8, ce = referencia()
    ids = sorted(i for i in cec8 if i in duplos)

    print("=" * 74)
    print("CENARIO SEM SIMULTANEIDADE - ANALISE CONTRA A REFERENCIA EXATA")
    print("=" * 74)
    print("\ninstancias analisadas: %d de %d" % (len(ids), TOTAL_ESPERADO))
    if len(ids) < TOTAL_ESPERADO:
        print("ATENCAO: conjunto incompleto. Os numeros abaixo sao parciais.")

    # ---------------------------------------------------- 1. estrutural
    com_duplo = [i for i in ids if duplos[i] > 0]
    print("\n1. ESTRUTURAL (criterio decisivo)")
    print("   instancias com cliente atendido pelas duas transportadoras: %d de %d"
          % (len(com_duplo), len(ids)))
    print("   clientes nessa condicao, somados: %d" % sum(duplos[i] for i in ids))
    print("   no cenario com simultaneidade este numero e necessariamente zero")

    # ---------------------------------------------------- 2. coerencia
    piores = [i for i in ids if i in ce and cec8[i]["jsprit"] > ce[i]["jsprit"] + 1e-6]
    print("\n2. COERENCIA (relaxado mais caro que o restrito)")
    print("   ocorrencias: %d de %d" % (len(piores), len(ids)))

    # ---------------------------------------------------- 3. qualidade
    print("\n3. QUALIDADE contra a referencia exata")
    print("   %-22s %5s  %10s  %7s  %8s"
          % ("grupo", "k", "dif. media", "iguais", "melhores"))
    grupos = [("S1 (12 instancias)", lambda i: i < 100)]
    for n in (10, 15, 20, 25, 30):
        grupos.append(("S2 - %d clientes" % n,
                       lambda i, n=n: i >= 8001 and cec8[i]["n"] == n))
    grupos.append(("S2 - total", lambda i: i >= 8001))
    grupos.append(("TODAS", lambda i: True))

    for rotulo, sel in grupos:
        sub = [i for i in ids if sel(i)]
        if not sub:
            continue
        difs = [dif(cec8[i]["jsprit"], cec8[i]["gurobi"]) for i in sub]
        print("   %-22s %5d  %9.2f%%  %7d  %8d"
              % (rotulo, len(sub), statistics.mean(difs),
                 sum(1 for d in difs if abs(d) < 1e-6),
                 sum(1 for d in difs if d < -1e-6)))

    abaixo = [i for i in ids if cec8[i]["jsprit"] < cec8[i]["gurobi"] - 1e-6]
    suspeitas = [i for i in abaixo if cec8[i]["status"] == "OPTIMAL"
                 and abs(cec8[i]["jsprit"] - cec8[i]["gurobi"]) > 0.05]
    if abaixo:
        tl = sum(1 for i in abaixo if cec8[i]["status"] == "TIME_LIMIT")
        print("\n   ficaram abaixo da referencia: %d, dos quais %d com o exato em "
              "TIME_LIMIT (esperado)" % (len(abaixo), tl))
    if suspeitas:
        print("   ATENCAO: %d abaixo de um otimo comprovado por margem relevante; "
              "verificar viabilidade: %s" % (len(suspeitas), suspeitas))

    # ---------------------------------------------------- 4. tempo
    tj = statistics.mean(cec8[i]["tempo"] for i in ids if cec8[i]["tempo"])
    tg = statistics.mean(cec8[i]["gurobi"] for i in ids) and statistics.mean(
        cec8[i].get("tempo") or 0 for i in ids)
    print("\n4. TEMPO medio da meta-heuristica: %.1f s por instancia" % tj)

    # ---------------------------------------------------- veredito
    estrutural_ok = len(com_duplo) > 0
    coerencia_ok = len(piores) <= len(ids) * 0.15
    sem_suspeita = not suspeitas

    print("\n" + "=" * 74)
    print("VEREDITO")
    print("  [%s] estrutural : a busca usa a liberdade do modelo relaxado"
          % ("OK" if estrutural_ok else "FALHOU"))
    print("  [%s] coerencia  : anomalias dentro do esperado para busca estocastica"
          % ("OK" if coerencia_ok else "ATENCAO"))
    print("  [%s] viabilidade: nenhuma solucao abaixo de um otimo comprovado"
          % ("OK" if sem_suspeita else "FALHOU"))
    print("=" * 74)
    return 0 if (estrutural_ok and sem_suspeita) else 1


if __name__ == "__main__":
    sys.exit(main())
