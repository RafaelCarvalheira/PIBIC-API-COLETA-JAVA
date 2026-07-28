# -*- coding: utf-8 -*-
"""Analisa o lote da modelagem corrigida (V2) do cenario sem simultaneidade.

Responde a tres perguntas, nesta ordem:

1. ESTRUTURAL - a correcao passou a construir a possibilidade que define o
   cenario relaxado, isto e, cliente compartilhado atendido pelas duas
   transportadoras? Este e o criterio decisivo, porque nao depende de custo.
2. COERENCIA  - a anomalia de o cenario relaxado sair mais caro que o restrito
   (impossivel entre otimos verdadeiros) diminuiu?
3. QUALIDADE  - a diferenca percentual contra a referencia exata caiu?

Uso: python scripts_ce/analisar_v2.py
"""
import glob
import json
import os
import re
import statistics
import sys
from collections import defaultdict

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
V2 = os.path.join(BASE, "scripts_ce", "resultados_cec8_v2")
PLANILHA = os.path.join(BASE, "relatorio_final.xlsx")
TOTAL_ESPERADO = 112


def referencia():
    """Le da planilha o exato e o Jsprit antigo, por cenario."""
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


def carregar_v2():
    """problemId numerico -> custo, tempo e clientes nas duas transportadoras."""
    dados = {}
    for caminho in glob.glob(os.path.join(V2, "*_run_CEc8_v2.json")):
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
        dados[int(m.group(1))] = {
            "custo": r.get("totalCost", 0.0),
            "tempo": r.get("_tempoSegundos", 0.0),
            "duplos": sum(1 for t in carriers.values() if len(t) > 1),
        }
    return dados


def dif(novo, base):
    return (novo - base) / base * 100 if base else 0.0


def main():
    if not os.path.isdir(V2):
        print("Lote ainda nao produziu resultados.")
        return 2

    v2 = carregar_v2()
    cec8, ce = referencia()
    ids = sorted(i for i in v2 if i in cec8)

    print("=" * 74)
    print("ANALISE DA MODELAGEM CORRIGIDA (V2) - CENARIO SEM SIMULTANEIDADE")
    print("=" * 74)
    print("\nconcluidas: %d de %d" % (len(v2), TOTAL_ESPERADO))
    if len(v2) < TOTAL_ESPERADO:
        print("ATENCAO: lote incompleto. Os numeros abaixo sao parciais.")

    # ---------------------------------------------------- 1. estrutural
    com_duplo = [i for i in ids if v2[i]["duplos"] > 0]
    total_duplos = sum(v2[i]["duplos"] for i in ids)
    print("\n1. ESTRUTURAL (criterio decisivo)")
    print("   instancias com cliente atendido pelas duas transportadoras: "
          "%d de %d" % (len(com_duplo), len(ids)))
    print("   clientes nessa condicao, somados: %d" % total_duplos)
    print("   antes da correcao: 0 em 112")

    # ---------------------------------------------------- 2. coerencia
    piores_v2 = [i for i in ids if i in ce and v2[i]["custo"] > ce[i]["jsprit"] + 1e-6]
    piores_antes = [i for i in ids if i in ce and cec8[i]["jsprit"] > ce[i]["jsprit"] + 1e-6]
    print("\n2. COERENCIA (relaxado mais caro que o restrito, impossivel entre otimos)")
    print("   antes : %d de %d" % (len(piores_antes), len(ids)))
    print("   depois: %d de %d" % (len(piores_v2), len(ids)))

    # ---------------------------------------------------- 3. qualidade
    print("\n3. QUALIDADE contra a referencia exata")
    print("   %-22s %6s  %9s  %9s  %8s" % ("grupo", "k", "antes", "depois", "ganho"))
    grupos = [("S1 (12 instancias)", lambda i: i < 100)]
    for n in (10, 15, 20, 25, 30):
        grupos.append(("S2 - %d clientes" % n,
                       lambda i, n=n: i >= 8001 and cec8[i]["n"] == n))
    grupos.append(("S2 - total", lambda i: i >= 8001))
    grupos.append(("TODAS", lambda i: True))

    resumo = {}
    for rotulo, sel in grupos:
        sub = [i for i in ids if sel(i)]
        if not sub:
            continue
        antes = statistics.mean(dif(cec8[i]["jsprit"], cec8[i]["gurobi"]) for i in sub)
        depois = statistics.mean(dif(v2[i]["custo"], cec8[i]["gurobi"]) for i in sub)
        resumo[rotulo] = (antes, depois)
        print("   %-22s %6d  %8.2f%%  %8.2f%%  %7.2f pp"
              % (rotulo, len(sub), antes, depois, antes - depois))

    melhores = [i for i in ids if v2[i]["custo"] < cec8[i]["gurobi"] - 1e-6]
    iguais = [i for i in ids if abs(v2[i]["custo"] - cec8[i]["gurobi"]) < 1e-6]
    print("\n   igualou o exato: %d | superou o exato: %d" % (len(iguais), len(melhores)))
    if melhores:
        tl = sum(1 for i in melhores if cec8[i]["status"] == "TIME_LIMIT")
        print("   dos que superaram, %d tinham o exato em TIME_LIMIT (esperado)" % tl)

    # ---------------------------------------------------- tempo
    t_antes = statistics.mean(cec8[i]["tempo"] for i in ids if cec8[i]["tempo"])
    t_depois = statistics.mean(v2[i]["tempo"] for i in ids if v2[i]["tempo"])
    print("\n4. TEMPO medio por instancia: antes %.1fs, depois %.1fs (%.1fx)"
          % (t_antes, t_depois, t_depois / t_antes if t_antes else 0))

    # ---------------------------------------------------- veredito
    antes_tot, depois_tot = resumo.get("TODAS", (0, 0))
    estrutural_ok = len(com_duplo) > 0
    coerencia_ok = len(piores_v2) < len(piores_antes)
    qualidade_ok = depois_tot < antes_tot

    print("\n" + "=" * 74)
    print("VEREDITO")
    print("  [%s] estrutural: a busca passou a usar a liberdade do modelo relaxado"
          % ("OK" if estrutural_ok else "FALHOU"))
    print("  [%s] coerencia : a anomalia diminuiu" % ("OK" if coerencia_ok else "FALHOU"))
    print("  [%s] qualidade : a diferenca contra o exato caiu"
          % ("OK" if qualidade_ok else "FALHOU"))
    print("\n  HIPOTESE %s" % ("CONFIRMADA" if (estrutural_ok and coerencia_ok
                                                and qualidade_ok) else
                               "NAO CONFIRMADA INTEGRALMENTE"))
    print("=" * 74)
    return 0 if estrutural_ok else 1


if __name__ == "__main__":
    sys.exit(main())
