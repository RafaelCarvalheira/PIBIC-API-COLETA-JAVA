# -*- coding: utf-8 -*-
"""Confere, nas saidas do metodo exato do modelo relaxado, se ha cliente
atendido pelas duas transportadoras.

No modelo sem a restricao c8 isso e permitido. Serve de contraprova para a
auditoria das solucoes da meta-heuristica (auditar_visitas.py).
"""
import glob
import os
import re
from collections import defaultdict

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PASTA = os.path.join(BASE, "codigo_julia_exato", "resultados_ce_only")

ARCO = re.compile(r"^x\[\s*([^,\]]+),\s*([^,\]]+),\s*([^,\]]+)\s*\]\s*=\s*1", re.M)


def auditar(sufixo, rotulo):
    arquivos = sorted(glob.glob(os.path.join(PASTA, "*_output%s.txt" % sufixo)))
    inst = 0
    inst_com_duplo = 0
    clientes_duplos = 0
    exemplos = []

    for caminho in arquivos:
        texto = open(caminho, encoding="utf-8", errors="ignore").read()
        arcos = ARCO.findall(texto)
        if not arcos:
            continue
        inst += 1
        transportadoras = defaultdict(set)
        for origem, _destino, carrier in arcos:
            origem = origem.strip()
            if origem.isdigit():  # ignora os depositos (A/B)
                transportadoras[origem].add(carrier.strip())
        duplos = sorted(c for c, t in transportadoras.items() if len(t) > 1)
        if duplos:
            inst_com_duplo += 1
            clientes_duplos += len(duplos)
            if len(exemplos) < 3:
                exemplos.append((os.path.basename(caminho), duplos[:6]))

    print("%-30s instancias=%3d | com cliente nas 2 transportadoras=%3d | "
          "clientes assim=%3d" % (rotulo, inst, inst_com_duplo, clientes_duplos))
    for nome, cs in exemplos:
        print("      exemplo:", nome, "-> clientes", cs)
    return inst, inst_com_duplo


if __name__ == "__main__":
    print("Metodo exato: cliente atendido pelas duas transportadoras?\n")
    auditar("CE", "CE   (com a restricao c8)")
    auditar("CEc8", "CEc8 (sem a restricao c8)")
