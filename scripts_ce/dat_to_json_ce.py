"""
Conversor DAT/TXT -> JSON para CE (Coleta e Entrega) que LE OS DADOS REAIS do arquivo.

IMPORTANTE: Este script le as secoes:
- d=[...] - matriz de demandas de entrega (n x 2 carriers)
- p=[...] - matriz de demandas de coleta (dois formatos suportados)
- CJ=[...] - carriers permitidos por cliente

Formato .dat (antigo):
    d=[[d1_A, d1_B], [d2_A, d2_B], ...]  - demandas de entrega
    SCCVRPSPD
    p = [
        p1_A p1_B
        p2_A p2_B
        ...
    ]  - demandas de coleta (secao SCCVRPSPD, valores separados por espaco)

Formato .txt (novo):
    d=[[d1_A, d1_B], [d2_A, d2_B], ...];  - demandas de entrega
    p=[[p1_A, p1_B], [p2_A, p2_B], ...];  - demandas de coleta (mesmo formato que d)

Uso:
    python dat_to_json_ce.py [arquivo.dat|arquivo.txt]
    python dat_to_json_ce.py --all  # Converte todos os .dat e .txt da pasta

Autor: PIBIC VRP Project
"""

import re
import json
import os
import sys
from typing import Dict, List, Any, Tuple, Optional


# ============================
# CONFIGURACAO
# ============================
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
INPUT_DAT_DIR = os.path.join(SCRIPT_DIR, "..", "..", "api_vrp_java", "scripts", "DadosModificadosSuperCodigo")
OUTPUT_JSON_DIR = os.path.join(SCRIPT_DIR, "json_output_ce")


def parse_dat_file(filepath: str) -> Dict[str, Any]:
    """
    Le e parseia um arquivo .dat no formato OPL.
    Extrai todas as informacoes necessarias para CE.
    """
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    data = {}

    # ID do problema
    match = re.search(r'Id\s*=\s*(\d+)', content)
    data['id'] = match.group(1) if match else "unknown"

    # Numero de carriers
    match = re.search(r'c\s*=\s*(\d+)', content)
    data['num_carriers'] = int(match.group(1)) if match else 2

    # Numero de clientes
    match = re.search(r'n\s*=\s*(\d+)', content)
    data['num_customers'] = int(match.group(1)) if match else 0

    # Capacidade do veiculo
    match = re.search(r'Q\s*=\s*(\d+)', content)
    data['capacity'] = int(match.group(1)) if match else 100

    # Depositos D={21,22}
    match = re.search(r'D\s*=\s*\{([^}]+)\}', content)
    if match:
        data['depots'] = [int(x.strip()) for x in match.group(1).split(',')]
    else:
        n = data['num_customers']
        data['depots'] = [n + 1, n + 2]

    # CJ - Carriers permitidos por cliente
    # Formato: CJ=[{1,2},{1},{2},{2},{1,2},...]
    data['cj'] = parse_cj(content, data['num_customers'])

    # Matriz de demandas de ENTREGA (d=[...])
    data['delivery_matrix'] = parse_delivery_matrix(content, data['num_customers'])

    # Matriz de demandas de COLETA (p=[...] na secao SCCVRPSPD)
    data['pickup_matrix'] = parse_pickup_matrix(content, data['num_customers'])

    # Matriz de custos
    match = re.search(r'cost\s*=\s*#\[(.*?)\]#', content, re.DOTALL)
    if match:
        entries = re.findall(r'<(\d+),(\d+)>:([\d.]+)', match.group(1))
        data['cost_matrix'] = [(int(f), int(t), float(c)) for f, t, c in entries]
    else:
        data['cost_matrix'] = []

    print(f"  Clientes: {data['num_customers']}")
    print(f"  Depots: {data['depots']}")
    print(f"  CJ entries: {len(data['cj'])}")
    print(f"  Delivery matrix: {len(data['delivery_matrix'])} clientes")
    print(f"  Pickup matrix: {len(data['pickup_matrix'])} clientes")

    return data


def parse_cj(content: str, num_customers: int) -> List[List[int]]:
    """
    Parseia a secao CJ=[{1,2},{1},{2},...] que define quais carriers
    podem atender cada cliente.

    Retorna lista de listas: [[1,2], [1], [2], ...]
    """
    match = re.search(r'CJ\s*=\s*\[(.*?)\];', content, re.DOTALL)
    if not match:
        # Default: todos os clientes podem ser atendidos por ambos carriers
        return [[1, 2] for _ in range(num_customers)]

    cj_str = match.group(1)

    # Encontrar todos os conjuntos {1,2}, {1}, {2}, etc.
    sets = re.findall(r'\{([^}]*)\}', cj_str)

    result = []
    for s in sets:
        if s.strip():
            carriers = [int(x.strip()) for x in s.split(',') if x.strip()]
            result.append(carriers)
        else:
            result.append([])

    return result


def parse_delivery_matrix(content: str, num_customers: int) -> List[Tuple[int, int]]:
    """
    Parseia a matriz de demandas de ENTREGA d=[...].
    Formato: d=[[d1_A, d1_B], [d2_A, d2_B], ...]

    Retorna lista de tuplas: [(d1_A, d1_B), (d2_A, d2_B), ...]
    """
    # Encontrar a secao d=[...]
    match = re.search(r'd\s*=\s*\[(.*?)\];', content, re.DOTALL)
    if not match:
        return [(0, 0) for _ in range(num_customers)]

    d_str = match.group(1)

    # Encontrar todos os pares [dA, dB]
    pairs = re.findall(r'\[(\d+)\s*,\s*(\d+)\]', d_str)

    result = [(int(a), int(b)) for a, b in pairs]

    # Garantir que temos o numero correto de entradas
    while len(result) < num_customers:
        result.append((0, 0))

    return result[:num_customers]


def parse_pickup_matrix(content: str, num_customers: int) -> List[Tuple[int, int]]:
    """
    Parseia a matriz de demandas de COLETA p=[...].

    Suporta dois formatos:

    Formato novo (.txt): p=[[p1_A, p1_B], [p2_A, p2_B], ...];
        - Mesmo formato da matriz d, com pares entre colchetes e separados por virgula

    Formato antigo (.dat):
        SCCVRPSPD
        p = [
        26 3
        31 0
        ...
        ]
        - Valores separados por espaco, sem colchetes internos

    Retorna lista de tuplas: [(p1_A, p1_B), (p2_A, p2_B), ...]
    """
    # Tentar formato novo: p=[[a,b],[c,d],...];
    new_match = re.search(r'(?<!\w)p\s*=\s*\[(.*?)\];', content, re.DOTALL)
    if new_match:
        p_str = new_match.group(1)
        pairs = re.findall(r'\[(\d+)\s*,\s*(\d+)\]', p_str)
        if pairs:
            result = [(int(a), int(b)) for a, b in pairs]
            while len(result) < num_customers:
                result.append((0, 0))
            return result[:num_customers]

    # Fallback: formato antigo (SCCVRPSPD section com valores separados por espaco)
    sccvrpspd_match = re.search(r'SCCVRPSPD\s*\n\s*p\s*=\s*\[(.*?)\]', content, re.DOTALL)
    if not sccvrpspd_match:
        print("  AVISO: Matriz de coleta p nao encontrada!")
        return [(0, 0) for _ in range(num_customers)]

    p_content = sccvrpspd_match.group(1)
    lines = p_content.strip().split('\n')

    result = []
    for line in lines:
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) >= 2:
            try:
                result.append((int(parts[0]), int(parts[1])))
            except ValueError:
                continue

    while len(result) < num_customers:
        result.append((0, 0))

    return result[:num_customers]


def convert_to_ce_json(data: Dict[str, Any]) -> Dict[str, Any]:
    """
    Converte os dados parseados para o formato JSON da API CE (VrpInput).
    """
    num_customers = data['num_customers']
    depots = data['depots']

    # Mapear depots para carriers
    depot_a = depots[0] if len(depots) > 0 else num_customers + 1
    depot_b = depots[1] if len(depots) > 1 else num_customers + 2

    # Fleets
    fleets = [
        {"carrierId": "1", "depotLocationId": str(depot_a)},
        {"carrierId": "2", "depotLocationId": str(depot_b)}
    ]

    # Customers
    customers = []
    for i in range(num_customers):
        customer_id = str(i + 1)  # IDs comecam em 1

        # Demandas de entrega (d)
        d_a, d_b = data['delivery_matrix'][i] if i < len(data['delivery_matrix']) else (0, 0)

        # Demandas de coleta (p)
        p_a, p_b = data['pickup_matrix'][i] if i < len(data['pickup_matrix']) else (0, 0)

        # Carriers permitidos (CJ)
        cj = data['cj'][i] if i < len(data['cj']) else [1, 2]
        allowed_carriers = [str(c) for c in cj]

        # Construir demandas por carrier
        delivery_demand = {}
        pickup_demand = {}

        if d_a > 0:
            delivery_demand["1"] = d_a
        if d_b > 0:
            delivery_demand["2"] = d_b
        if p_a > 0:
            pickup_demand["1"] = p_a
        if p_b > 0:
            pickup_demand["2"] = p_b

        customer = {
            "id": customer_id,
            "deliveryDemandByCarrier": delivery_demand,
            "pickupDemandByCarrier": pickup_demand,
            "allowedCarriers": allowed_carriers
        }

        customers.append(customer)

    # Matriz de custos
    cost_matrix = [{"from": f, "to": t, "cost": c} for f, t, c in data['cost_matrix']]

    return {
        "problemId": "ce_" + data['id'],
        "globalParameters": {
            "vehicleCapacity": data['capacity'],
            "numberOfCustomers": num_customers,
            "numberOfCarriers": data['num_carriers']
        },
        "fleets": fleets,
        "customers": customers,
        "costMatrix": cost_matrix
    }


def print_demand_summary(json_data: Dict[str, Any]):
    """Imprime resumo das demandas para verificacao."""
    customers = json_data.get('customers', [])

    total_delivery_a = 0
    total_delivery_b = 0
    total_pickup_a = 0
    total_pickup_b = 0

    shared_customers = 0
    exclusive_a = 0
    exclusive_b = 0

    for customer in customers:
        delivery = customer.get('deliveryDemandByCarrier', {})
        pickup = customer.get('pickupDemandByCarrier', {})
        allowed = customer.get('allowedCarriers', [])

        total_delivery_a += delivery.get('1', 0)
        total_delivery_b += delivery.get('2', 0)
        total_pickup_a += pickup.get('1', 0)
        total_pickup_b += pickup.get('2', 0)

        if len(allowed) > 1:
            shared_customers += 1
        elif '1' in allowed:
            exclusive_a += 1
        elif '2' in allowed:
            exclusive_b += 1

    print(f"  Resumo de demandas:")
    print(f"    Entrega - Carrier A: {total_delivery_a}, Carrier B: {total_delivery_b}")
    print(f"    Coleta  - Carrier A: {total_pickup_a}, Carrier B: {total_pickup_b}")
    print(f"  Clientes: {len(customers)} total")
    print(f"    Compartilhados: {shared_customers}, Exclusivos A: {exclusive_a}, Exclusivos B: {exclusive_b}")


def convert_single_file(dat_path: str, output_dir: str) -> bool:
    """Converte um unico arquivo .dat ou .txt para JSON."""
    try:
        print(f"\nProcessando: {os.path.basename(dat_path)}")

        # 1. Parsear arquivo .dat
        data = parse_dat_file(dat_path)

        # 2. Converter para JSON
        json_data = convert_to_ce_json(data)

        # 3. Mostrar resumo
        print_demand_summary(json_data)

        # 4. Salvar JSON
        base_name = os.path.splitext(os.path.basename(dat_path))[0]
        output_filename = f"{base_name}_ce.json"
        output_path = os.path.join(output_dir, output_filename)

        with open(output_path, 'w', encoding='utf-8') as f:
            json.dump(json_data, f, indent=2, ensure_ascii=False)

        print(f"  Salvo em: {output_path}")
        return True

    except Exception as e:
        print(f"  ERRO: {str(e)}")
        import traceback
        traceback.print_exc()
        return False


def main():
    print("=" * 70)
    print("CONVERSOR DAT/TXT -> JSON (CE - Coleta e Entrega)")
    print("Le as matrizes d (entrega) e p (coleta) do arquivo .dat ou .txt")
    print("=" * 70)

    # Criar pasta de saida
    os.makedirs(OUTPUT_JSON_DIR, exist_ok=True)

    # Verificar argumentos
    if len(sys.argv) > 1:
        arg = sys.argv[1]

        if arg == "--all":
            # Converter todos os arquivos da pasta
            if not os.path.isdir(INPUT_DAT_DIR):
                print(f"\nERRO: Pasta nao encontrada: {INPUT_DAT_DIR}")
                return

            dat_files = sorted([f for f in os.listdir(INPUT_DAT_DIR) if f.endswith(".dat") or f.endswith(".txt")])
            print(f"\nPasta de entrada: {INPUT_DAT_DIR}")
            print(f"Arquivos encontrados: {len(dat_files)}")

            sucesso = 0
            for dat_file in dat_files:
                dat_path = os.path.join(INPUT_DAT_DIR, dat_file)
                if convert_single_file(dat_path, OUTPUT_JSON_DIR):
                    sucesso += 1

            print(f"\n{'=' * 70}")
            print(f"Convertidos: {sucesso}/{len(dat_files)}")

        elif os.path.isfile(arg):
            # Converter arquivo especifico
            convert_single_file(arg, OUTPUT_JSON_DIR)
        else:
            print(f"\nERRO: Arquivo nao encontrado: {arg}")
    else:
        print("\nUso:")
        print("  python dat_to_json_ce.py arquivo.dat    # Converte arquivo .dat especifico")
        print("  python dat_to_json_ce.py arquivo.txt    # Converte arquivo .txt especifico")
        print("  python dat_to_json_ce.py --all          # Converte todos da pasta")
        print(f"\nPasta configurada: {INPUT_DAT_DIR}")
        print(f"Saida: {OUTPUT_JSON_DIR}")

    print("=" * 70)


if __name__ == "__main__":
    main()
