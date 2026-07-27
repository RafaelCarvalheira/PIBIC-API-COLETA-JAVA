# -*- coding: utf-8 -*-
"""Teste de aceitacao do endpoint /run-CEc8-v2.

Criterio, derivado do modelo exato (supercodigo.jl, run_CEc8):
  - no cenario SEM a restricao c8, cliente compartilhado PODE ser atendido pelas
    duas transportadoras. Se nenhuma instancia produzir esse padrao, a correcao
    nao pegou;
  - no cenario COM a restricao c8 o padrao NAO pode aparecer. Serve de controle.
"""
import json
import os
import re
import sys
import urllib.request
from collections import defaultdict

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENTRADAS = os.path.join(BASE, "scripts_ce", "json_output_ce")
API = "http://localhost:8080/api/solve"

INSTANCIAS = ["vrps_1L_ce.json", "vrps_8011_ce.json", "vrps_8091_ce.json"]


def resolver(arquivo, rota, timeout=600):
    entrada = open(os.path.join(ENTRADAS, arquivo), "rb").read()
    req = urllib.request.Request(API + rota, data=entrada,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def analisar(resultado):
    """cliente -> transportadoras que o visitaram; e numero de visitas."""
    carriers = defaultdict(set)
    visitas = defaultdict(int)
    for rota in resultado.get("routes", []):
        m = re.match(r"vehicle_(\d+)", rota.get("vehicleId", ""))
        carrier = m.group(1) if m else "?"
        nesta = set()
        for a in rota.get("activitySequence", []):
            mm = re.match(r"(DELIVERY|PICKUP):(.+)", a)
            if not mm:
                continue
            cliente = mm.group(2)
            carriers[cliente].add(carrier)
            if cliente not in nesta:
                nesta.add(cliente)
                visitas[cliente] += 1
    return carriers, visitas


def compartilhados(arquivo):
    d = json.load(open(os.path.join(ENTRADAS, arquivo), encoding="utf-8"))
    return {str(c["id"]) for c in d.get("customers", [])
            if len(c.get("allowedCarriers", [])) > 1}


def rodar(rota, rotulo):
    print("\n===== %s  (%s)" % (rotulo, rota))
    total_duplo = 0
    for arquivo in INSTANCIAS:
        try:
            res = resolver(arquivo, rota)
        except Exception as e:
            print("  %-18s ERRO: %s" % (arquivo, e))
            continue
        carriers, visitas = analisar(res)
        comp = compartilhados(arquivo)
        duplos = sorted(c for c, t in carriers.items() if len(t) > 1)
        multi = sorted(c for c, n in visitas.items() if n > 1)
        total_duplo += len(duplos)
        print("  %-18s custo=%8.2f | compartilhados=%d | nas 2 transportadoras=%d %s "
              "| com >1 visita=%d"
              % (arquivo, res.get("totalCost", 0), len(comp), len(duplos),
                 duplos if duplos else "", len(multi)))
    return total_duplo


if __name__ == "__main__":
    antes = rodar("/run-CEc8", "ANTES (implementacao atual)")
    depois = rodar("/run-CEc8-v2", "DEPOIS (V2 corrigida)")
    controle = rodar("/run-CE", "CONTROLE (com c8, deve dar zero)")

    print("\n---------------- VEREDITO ----------------")
    print("clientes atendidos pelas duas transportadoras:")
    print("  implementacao atual : %d  (esperado 0, e o defeito)" % antes)
    print("  V2 corrigida        : %d  (precisa ser > 0)" % depois)
    print("  controle com c8     : %d  (precisa ser 0)" % controle)
    ok = depois > 0 and controle == 0
    print("\nRESULTADO:", "correcao FUNCIONOU" if ok else "correcao NAO passou no teste")
    sys.exit(0 if ok else 1)
