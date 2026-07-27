# -*- coding: utf-8 -*-
"""Audita quantas vezes cada cliente e visitado, por cenario.

O modelo relaxado (run_CEc8, sem a restricao c8) admite que um cliente
compartilhado seja atendido pelas duas transportadoras, em visitas separadas.
Este script confere se a meta-heuristica chegou a produzir esse padrao.
"""
import glob
import json
import os
import re
from collections import defaultdict

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTADOS = os.path.join(BASE, "scripts_ce", "resultados_ce")
ENTRADAS = os.path.join(BASE, "scripts_ce", "json_output_ce")


def transportadora(vehicle_id):
    m = re.match(r"vehicle_(\d+)", vehicle_id or "")
    return m.group(1) if m else "?"


def clientes_por_transportadora(resultado):
    """cliente -> conjunto de transportadoras que o visitaram."""
    mapa = defaultdict(set)
    visitas = defaultdict(int)
    for rota in resultado.get("routes", []):
        carrier = transportadora(rota.get("vehicleId"))
        vistos_nesta_rota = set()
        for atividade in rota.get("activitySequence", []):
            m = re.match(r"(DELIVERY|PICKUP):(.+)", atividade)
            if not m:
                continue
            cliente = m.group(2)
            mapa[cliente].add(carrier)
            if cliente not in vistos_nesta_rota:
                vistos_nesta_rota.add(cliente)
                visitas[cliente] += 1
    return mapa, visitas


def compartilhados(problem_id):
    """Clientes com demanda junto as duas transportadoras, lidos da entrada."""
    for nome in (problem_id, problem_id.replace("ce_", "vrps_") + "_ce"):
        caminho = os.path.join(ENTRADAS, nome + ".json")
        if os.path.exists(caminho):
            entrada = json.load(open(caminho, encoding="utf-8"))
            saida = set()
            for c in entrada.get("customers", []):
                carriers = set()
                for campo in ("deliveryDemands", "pickupDemands"):
                    for k, v in (c.get(campo) or {}).items():
                        if v:
                            carriers.add(str(k))
                if len(carriers) > 1:
                    saida.add(str(c.get("id")))
            return saida
    return None


def auditar(sufixo, rotulo):
    arquivos = sorted(glob.glob(os.path.join(RESULTADOS, "*_run_%s.json" % sufixo)))
    total_inst = 0
    inst_com_duplo = 0
    total_clientes_duplos = 0
    total_multi_visita = 0
    exemplos = []

    for caminho in arquivos:
        try:
            r = json.load(open(caminho, encoding="utf-8"))
        except Exception:
            continue
        if not r.get("routes"):
            continue
        total_inst += 1
        mapa, visitas = clientes_por_transportadora(r)
        duplos = [c for c, carriers in mapa.items() if len(carriers) > 1]
        multi = [c for c, n in visitas.items() if n > 1]
        if duplos:
            inst_com_duplo += 1
            total_clientes_duplos += len(duplos)
            if len(exemplos) < 3:
                exemplos.append((os.path.basename(caminho), duplos[:5]))
        total_multi_visita += len(multi)

    print("%-34s instancias=%3d | com cliente atendido por 2 transportadoras=%3d "
          "| clientes assim=%4d | clientes com >1 visita=%4d"
          % (rotulo, total_inst, inst_com_duplo, total_clientes_duplos,
             total_multi_visita))
    for nome, cs in exemplos:
        print("      exemplo:", nome, "->", cs)


if __name__ == "__main__":
    print("Cada cliente foi atendido por quantas transportadoras?\n")
    auditar("CE", "CE  (com simultaneidade)")
    auditar("CEc8", "CEc8 (sem simultaneidade)")

    # quantos clientes compartilhados existem, para a auditoria ter sentido
    alvo = os.path.join(RESULTADOS, "ce_1_run_CEc8.json")
    if os.path.exists(alvo):
        pid = json.load(open(alvo, encoding="utf-8")).get("problemId")
        comp = compartilhados(pid)
        print("\nclientes compartilhados na instancia %s: %s"
              % (pid, sorted(comp) if comp else "entrada nao localizada"))
