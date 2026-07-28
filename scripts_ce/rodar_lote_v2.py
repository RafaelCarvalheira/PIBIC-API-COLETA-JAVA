# -*- coding: utf-8 -*-
"""Executa todas as instancias no endpoint /run-CEc8-v2 e consolida os custos.

Retomavel: instancias ja resolvidas sao puladas, entao pode ser reexecutado
depois de uma interrupcao sem refazer trabalho.
"""
import csv
import glob
import json
import os
import re
import time
import urllib.request
from collections import defaultdict

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENTRADAS = os.path.join(BASE, "scripts_ce", "json_output_ce")
SAIDAS = os.path.join(BASE, "scripts_ce", "resultados_cec8_v2")
CSV_SAIDA = os.path.join(SAIDAS, "_resumo.csv")
API = "http://localhost:8080/api/solve/run-CEc8-v2"


def resolver(caminho, timeout=1800):
    dados = open(caminho, "rb").read()
    req = urllib.request.Request(API, data=dados,
                                 headers={"Content-Type": "application/json"})
    inicio = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        resultado = json.loads(r.read().decode("utf-8"))
    return resultado, time.time() - inicio


def duas_transportadoras(resultado):
    """Numero de clientes atendidos pelas duas transportadoras."""
    carriers = defaultdict(set)
    for rota in resultado.get("routes", []):
        m = re.match(r"vehicle_(\d+)", rota.get("vehicleId", ""))
        carrier = m.group(1) if m else "?"
        for a in rota.get("activitySequence", []):
            mm = re.match(r"(DELIVERY|PICKUP):(.+)", a)
            if mm:
                carriers[mm.group(2)].add(carrier)
    return sum(1 for t in carriers.values() if len(t) > 1)


def main():
    os.makedirs(SAIDAS, exist_ok=True)
    arquivos = sorted(glob.glob(os.path.join(ENTRADAS, "*.json")))
    print("instancias encontradas:", len(arquivos), flush=True)

    linhas = []
    for i, caminho in enumerate(arquivos, 1):
        nome = os.path.basename(caminho)
        entrada = json.load(open(caminho, encoding="utf-8"))
        pid = entrada.get("problemId") or nome.replace(".json", "")
        destino = os.path.join(SAIDAS, "%s_run_CEc8_v2.json" % pid)

        if os.path.exists(destino):
            resultado = json.load(open(destino, encoding="utf-8"))
            segundos = resultado.get("_tempoSegundos", 0.0)
            marca = "(cache)"
        else:
            try:
                resultado, segundos = resolver(caminho)
            except Exception as e:
                print("[%3d/%d] %-20s ERRO: %s" % (i, len(arquivos), pid, e), flush=True)
                continue
            resultado["_tempoSegundos"] = round(segundos, 2)
            json.dump(resultado, open(destino, "w", encoding="utf-8"),
                      ensure_ascii=False, indent=1)
            marca = ""

        custo = resultado.get("totalCost", 0.0)
        duplos = duas_transportadoras(resultado)
        n_clientes = len(entrada.get("customers", []))
        linhas.append([pid, n_clientes, round(custo, 2), round(segundos, 2), duplos])
        print("[%3d/%d] %-12s clientes=%2d custo=%9.2f tempo=%6.1fs "
              "nas2transp=%d %s" % (i, len(arquivos), pid, n_clientes, custo,
                                    segundos, duplos, marca), flush=True)

    with open(CSV_SAIDA, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["problemId", "numClientes", "custo", "tempoSegundos",
                    "clientesNasDuasTransportadoras"])
        w.writerows(linhas)

    com_duplo = sum(1 for l in linhas if l[4] > 0)
    print("\nconcluidas: %d | com cliente nas duas transportadoras: %d | csv: %s"
          % (len(linhas), com_duplo, CSV_SAIDA), flush=True)


if __name__ == "__main__":
    main()
