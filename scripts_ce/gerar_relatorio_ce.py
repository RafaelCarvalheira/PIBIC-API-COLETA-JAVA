"""
Gerador de Relatorio Excel para resultados CE (Coleta e Entrega).

Cria 6 abas no Excel:
1. CE - Resultados com colaboracao SEM restricao C8
2. CE-NoShare - Resultados sem colaboracao SEM restricao C8
3. Comparacao CE - Comparacao entre CE e CE-NoShare
4. CE-C8 - Resultados com colaboracao COM restricao C8 (equivalente ao modelo exato)
5. CE-C8-NoShare - Resultados sem colaboracao COM restricao C8
6. Comparacao CE-C8 - Comparacao entre CE-C8 e CE-C8-NoShare

Uso:
    python gerar_relatorio_ce.py [pasta_resultados] [pasta_json_input]
    python gerar_relatorio_ce.py  # Usa pastas padrao

Autor: PIBIC VRP Project
"""

import json
import os
import sys
import re
from typing import Dict, List, Any, Tuple, Optional
from datetime import datetime

try:
    import openpyxl
    from openpyxl.styles import Font, Alignment, Border, Side, PatternFill
    from openpyxl.utils import get_column_letter
except ImportError:
    print("ERRO: Biblioteca openpyxl nao encontrada.")
    print("Instale com: pip install openpyxl")
    sys.exit(1)


# ============================
# CONFIGURACAO
# ============================
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

RESULTS_DIR = os.path.join(SCRIPT_DIR, "resultados_ce")
JSON_INPUT_DIR = os.path.join(SCRIPT_DIR, "json_output_ce")
OUTPUT_EXCEL = os.path.join(SCRIPT_DIR, "relatorio_ce.xlsx")


def load_json_file(filepath: str) -> Optional[Dict]:
    """Carrega um arquivo JSON. Retorna None se for lista ou erro."""
    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            data = json.load(f)
            # Ignorar se for lista (ex: resultados_consolidados.json)
            if isinstance(data, list):
                return None
            return data
    except Exception as e:
        print(f"  Erro ao ler {filepath}: {e}")
        return None


def extract_id_from_filename(filename: str) -> str:
    """
    Extrai o ID do nome do arquivo.
    Exemplos:
        '1_ce.json' -> '1'
        'ce_10_ce.json' -> '10'
        'ce_8001_ce_no_share.json' -> '8001'
    """
    # Padrao: ce_ID_ce.json ou ce_ID_ce_no_share.json
    match = re.match(r'^ce_(\d+)_', filename)
    if match:
        return match.group(1)

    # Padrao antigo: ID_ce.json
    match = re.match(r'^(\d+)_', filename)
    if match:
        return match.group(1)

    return filename.replace('.json', '').replace('_ce', '').replace('_no_share', '')


def get_num_customers_from_input(problem_id: str, input_dir: str) -> int:
    """Obtem o numero de clientes do arquivo de entrada JSON."""
    # Tentar diferentes padroes de nome de arquivo
    patterns = [
        f"vrps_{problem_id}_ce.json",
        f"test_instance_{problem_id}_ce.json",
        f"ce_{problem_id}.json",
        f"{problem_id}_ce.json",
    ]

    for pattern in patterns:
        filepath = os.path.join(input_dir, pattern)
        if os.path.exists(filepath):
            data = load_json_file(filepath)
            if data:
                # Tentar obter de globalParameters
                if 'globalParameters' in data:
                    return data['globalParameters'].get('numberOfCustomers', 0)
                # Tentar contar customers
                if 'customers' in data:
                    return len(data['customers'])

    return 0


def calculate_carrier_costs(result: Dict) -> Tuple[float, float]:
    """
    Calcula os custos separados por carrier (A e B).
    Carrier A = vehicle_1, Carrier B = vehicle_2
    """
    cost_a = 0.0
    cost_b = 0.0

    routes = result.get('routes', [])
    for route in routes:
        vehicle_id = route.get('vehicleId', '')
        route_cost = route.get('routeCost', 0.0)

        if 'vehicle_1' in vehicle_id or '_1' in vehicle_id:
            cost_a += route_cost
        elif 'vehicle_2' in vehicle_id or '_2' in vehicle_id:
            cost_b += route_cost

    return cost_a, cost_b


def find_result_files(results_dir: str) -> Dict[str, Dict]:
    """
    Encontra todos os arquivos de resultado e organiza por ID.
    Retorna: {id: {'ce': filepath, 'no_share': filepath, 'ce_c8': filepath, 'ce_c8_no_share': filepath}}

    Reconhece padroes:
        - ce_ID_ce.json / ce_ID_ce_no_share.json (CE sem C8)
        - ce_ID_ce_c8.json / ce_ID_ce_c8_no_share.json (CE com C8)
        - ID_ce.json / ID_ce_no_share.json (antigo)
    """
    result_files = {}

    if not os.path.isdir(results_dir):
        print(f"ERRO: Pasta de resultados nao encontrada: {results_dir}")
        return {}

    for filename in os.listdir(results_dir):
        if not filename.endswith('.json'):
            continue

        # Ignorar arquivos consolidados
        if 'consolidado' in filename.lower():
            continue

        problem_id = extract_id_from_filename(filename)

        # Ignorar se nao conseguiu extrair ID numerico
        if not problem_id or not any(c.isdigit() for c in problem_id):
            continue

        if problem_id not in result_files:
            result_files[problem_id] = {}

        filepath = os.path.join(results_dir, filename)

        # CE-C8 (COM restricao C8)
        if '_ce_c8_no_share' in filename:
            result_files[problem_id]['ce_c8_no_share'] = filepath
        elif '_ce_c8' in filename:
            result_files[problem_id]['ce_c8'] = filepath
        # CE (SEM restricao C8)
        elif '_no_share' in filename or '_noshare' in filename:
            result_files[problem_id]['no_share'] = filepath
        elif '_ce' in filename:
            result_files[problem_id]['ce'] = filepath

    return result_files


def create_excel_report(results_dir: str, input_dir: str, output_path: str):
    """Cria o relatorio Excel com as 3 abas."""

    print("=" * 70)
    print("GERADOR DE RELATORIO CE")
    print("=" * 70)

    # Encontrar arquivos de resultado
    result_files = find_result_files(results_dir)

    if not result_files:
        print("Nenhum arquivo de resultado encontrado!")
        return

    print(f"Encontrados {len(result_files)} problemas")

    # Criar workbook
    wb = openpyxl.Workbook()

    # Estilos
    header_font = Font(bold=True, color="FFFFFF")
    header_fill = PatternFill(start_color="4472C4", end_color="4472C4", fill_type="solid")
    header_alignment = Alignment(horizontal="center", vertical="center")
    thin_border = Border(
        left=Side(style='thin'),
        right=Side(style='thin'),
        top=Side(style='thin'),
        bottom=Side(style='thin')
    )
    number_format = '#,##0.00'

    # ========================================
    # ABA 1: CE (Colaboracao)
    # ========================================
    ws_ce = wb.active
    ws_ce.title = "CE"

    headers_ce = ["ID", "Num Clientes", "OF (Custo)", "Tempo (s)"]
    for col, header in enumerate(headers_ce, 1):
        cell = ws_ce.cell(row=1, column=col, value=header)
        cell.font = header_font
        cell.fill = header_fill
        cell.alignment = header_alignment
        cell.border = thin_border

    # ========================================
    # ABA 2: CE-NoShare (Sem Colaboracao)
    # ========================================
    ws_noshare = wb.create_sheet("CE-NoShare")

    headers_noshare = ["ID", "Num Clientes", "OF Custo A", "OF Custo B", "OF Total", "Tempo (s)"]
    for col, header in enumerate(headers_noshare, 1):
        cell = ws_noshare.cell(row=1, column=col, value=header)
        cell.font = header_font
        cell.fill = header_fill
        cell.alignment = header_alignment
        cell.border = thin_border

    # ========================================
    # ABA 3: Comparacao CE
    # ========================================
    ws_comp = wb.create_sheet("Comparacao CE")

    headers_comp = ["ID", "Custo Compartilhado", "Custo A (NoShare)", "Custo B (NoShare)",
                    "Custo Total NoShare", "Economia (%)", "Diferenca"]
    for col, header in enumerate(headers_comp, 1):
        cell = ws_comp.cell(row=1, column=col, value=header)
        cell.font = header_font
        cell.fill = header_fill
        cell.alignment = header_alignment
        cell.border = thin_border

    # ========================================
    # ABA 4: CE-C8 (Com Colaboracao, COM restricao C8)
    # ========================================
    ws_ce_c8 = wb.create_sheet("CE-C8")

    # Estilo roxo para CE-C8
    header_fill_c8 = PatternFill(start_color="7030A0", end_color="7030A0", fill_type="solid")

    headers_ce_c8 = ["ID", "Num Clientes", "OF (Custo)", "Tempo (s)"]
    for col, header in enumerate(headers_ce_c8, 1):
        cell = ws_ce_c8.cell(row=1, column=col, value=header)
        cell.font = header_font
        cell.fill = header_fill_c8
        cell.alignment = header_alignment
        cell.border = thin_border

    # ========================================
    # ABA 5: CE-C8-NoShare (Sem Colaboracao, COM restricao C8)
    # ========================================
    ws_ce_c8_noshare = wb.create_sheet("CE-C8-NoShare")

    headers_ce_c8_noshare = ["ID", "Num Clientes", "OF Custo A", "OF Custo B", "OF Total", "Tempo (s)"]
    for col, header in enumerate(headers_ce_c8_noshare, 1):
        cell = ws_ce_c8_noshare.cell(row=1, column=col, value=header)
        cell.font = header_font
        cell.fill = header_fill_c8
        cell.alignment = header_alignment
        cell.border = thin_border

    # ========================================
    # ABA 6: Comparacao CE-C8
    # ========================================
    ws_comp_c8 = wb.create_sheet("Comparacao CE-C8")

    headers_comp_c8 = ["ID", "CE-C8 (Colab.)", "Custo A (NoShare)", "Custo B (NoShare)",
                       "CE-C8 Total NoShare", "Economia (%)", "Diferenca"]
    for col, header in enumerate(headers_comp_c8, 1):
        cell = ws_comp_c8.cell(row=1, column=col, value=header)
        cell.font = header_font
        cell.fill = header_fill_c8
        cell.alignment = header_alignment
        cell.border = thin_border

    # Processar cada problema
    row_ce = 2
    row_noshare = 2
    row_comp = 2
    row_ce_c8 = 2
    row_ce_c8_noshare = 2
    row_comp_c8 = 2

    # Ordenar IDs numericamente
    def sort_key(x):
        try:
            return int(x)
        except ValueError:
            return float('inf')

    sorted_ids = sorted(result_files.keys(), key=sort_key)

    for problem_id in sorted_ids:
        files = result_files[problem_id]
        print(f"\nProcessando ID {problem_id}...")

        # Obter numero de clientes
        num_customers = get_num_customers_from_input(problem_id, input_dir)

        ce_cost = None
        noshare_cost = None
        cost_a = None
        cost_b = None

        # Processar CE (colaboracao)
        if 'ce' in files:
            ce_data = load_json_file(files['ce'])
            if ce_data:
                ce_cost = ce_data.get('totalCost', 0.0)

                # Se nao temos num_customers, tentar contar das rotas
                if num_customers == 0:
                    customers_set = set()
                    for route in ce_data.get('routes', []):
                        for activity in route.get('activitySequence', []):
                            if ':' in activity:
                                cust_id = activity.split(':')[1]
                                customers_set.add(cust_id)
                    num_customers = len(customers_set)

                # Escrever na aba CE
                ws_ce.cell(row=row_ce, column=1, value=int(problem_id) if problem_id.isdigit() else problem_id).border = thin_border
                ws_ce.cell(row=row_ce, column=2, value=num_customers).border = thin_border
                cell_cost = ws_ce.cell(row=row_ce, column=3, value=ce_cost)
                cell_cost.number_format = number_format
                cell_cost.border = thin_border
                ws_ce.cell(row=row_ce, column=4, value=ce_data.get("execution_time", "")).border = thin_border

                row_ce += 1

        # Processar CE-NoShare (sem colaboracao)
        if 'no_share' in files:
            noshare_data = load_json_file(files['no_share'])
            if noshare_data:
                noshare_cost = noshare_data.get('totalCost', 0.0)
                cost_a, cost_b = calculate_carrier_costs(noshare_data)

                # Se nao temos num_customers, tentar contar das rotas
                if num_customers == 0:
                    customers_set = set()
                    for route in noshare_data.get('routes', []):
                        for activity in route.get('activitySequence', []):
                            if ':' in activity:
                                cust_id = activity.split(':')[1]
                                customers_set.add(cust_id)
                    num_customers = len(customers_set)

                # Escrever na aba CE-NoShare
                ws_noshare.cell(row=row_noshare, column=1, value=int(problem_id) if problem_id.isdigit() else problem_id).border = thin_border
                ws_noshare.cell(row=row_noshare, column=2, value=num_customers).border = thin_border

                cell_a = ws_noshare.cell(row=row_noshare, column=3, value=cost_a)
                cell_a.number_format = number_format
                cell_a.border = thin_border

                cell_b = ws_noshare.cell(row=row_noshare, column=4, value=cost_b)
                cell_b.number_format = number_format
                cell_b.border = thin_border

                cell_total = ws_noshare.cell(row=row_noshare, column=5, value=noshare_cost)
                cell_total.number_format = number_format
                cell_total.border = thin_border

                ws_noshare.cell(row=row_noshare, column=6, value=noshare_data.get("execution_time", "")).border = thin_border

                row_noshare += 1

        # Escrever na aba Comparacao (se temos ambos os resultados)
        if ce_cost is not None and noshare_cost is not None:
            economia = ((noshare_cost - ce_cost) / noshare_cost * 100) if noshare_cost > 0 else 0
            diferenca = noshare_cost - ce_cost

            ws_comp.cell(row=row_comp, column=1, value=int(problem_id) if problem_id.isdigit() else problem_id).border = thin_border

            cell_ce = ws_comp.cell(row=row_comp, column=2, value=ce_cost)
            cell_ce.number_format = number_format
            cell_ce.border = thin_border

            cell_a = ws_comp.cell(row=row_comp, column=3, value=cost_a if cost_a else 0)
            cell_a.number_format = number_format
            cell_a.border = thin_border

            cell_b = ws_comp.cell(row=row_comp, column=4, value=cost_b if cost_b else 0)
            cell_b.number_format = number_format
            cell_b.border = thin_border

            cell_total = ws_comp.cell(row=row_comp, column=5, value=noshare_cost)
            cell_total.number_format = number_format
            cell_total.border = thin_border

            cell_eco = ws_comp.cell(row=row_comp, column=6, value=economia)
            cell_eco.number_format = '0.00"%"'
            cell_eco.border = thin_border
            # Colorir economia positiva de verde, negativa de vermelho
            if economia > 0:
                cell_eco.fill = PatternFill(start_color="C6EFCE", end_color="C6EFCE", fill_type="solid")
            elif economia < 0:
                cell_eco.fill = PatternFill(start_color="FFC7CE", end_color="FFC7CE", fill_type="solid")

            cell_dif = ws_comp.cell(row=row_comp, column=7, value=diferenca)
            cell_dif.number_format = number_format
            cell_dif.border = thin_border

            row_comp += 1

        # ========================================
        # Processar CE-C8 (COM restricao C8)
        # ========================================
        ce_c8_cost = None
        noshare_c8_cost = None
        cost_a_c8 = None
        cost_b_c8 = None

        # Processar CE-C8 (colaboracao com C8)
        if 'ce_c8' in files:
            ce_c8_data = load_json_file(files['ce_c8'])
            if ce_c8_data:
                ce_c8_cost = ce_c8_data.get('totalCost', 0.0)

                # Escrever na aba CE-C8
                ws_ce_c8.cell(row=row_ce_c8, column=1, value=int(problem_id) if problem_id.isdigit() else problem_id).border = thin_border
                ws_ce_c8.cell(row=row_ce_c8, column=2, value=num_customers).border = thin_border
                cell_cost = ws_ce_c8.cell(row=row_ce_c8, column=3, value=ce_c8_cost)
                cell_cost.number_format = number_format
                cell_cost.border = thin_border
                ws_ce_c8.cell(row=row_ce_c8, column=4, value=ce_c8_data.get("execution_time", "")).border = thin_border

                row_ce_c8 += 1

        # Processar CE-C8-NoShare (sem colaboracao com C8)
        if 'ce_c8_no_share' in files:
            noshare_c8_data = load_json_file(files['ce_c8_no_share'])
            if noshare_c8_data:
                noshare_c8_cost = noshare_c8_data.get('totalCost', 0.0)
                cost_a_c8, cost_b_c8 = calculate_carrier_costs(noshare_c8_data)

                # Escrever na aba CE-C8-NoShare
                ws_ce_c8_noshare.cell(row=row_ce_c8_noshare, column=1, value=int(problem_id) if problem_id.isdigit() else problem_id).border = thin_border
                ws_ce_c8_noshare.cell(row=row_ce_c8_noshare, column=2, value=num_customers).border = thin_border

                cell_a_c8 = ws_ce_c8_noshare.cell(row=row_ce_c8_noshare, column=3, value=cost_a_c8)
                cell_a_c8.number_format = number_format
                cell_a_c8.border = thin_border

                cell_b_c8 = ws_ce_c8_noshare.cell(row=row_ce_c8_noshare, column=4, value=cost_b_c8)
                cell_b_c8.number_format = number_format
                cell_b_c8.border = thin_border

                cell_total_c8 = ws_ce_c8_noshare.cell(row=row_ce_c8_noshare, column=5, value=noshare_c8_cost)
                cell_total_c8.number_format = number_format
                cell_total_c8.border = thin_border

                ws_ce_c8_noshare.cell(row=row_ce_c8_noshare, column=6, value=noshare_c8_data.get("execution_time", "")).border = thin_border

                row_ce_c8_noshare += 1

        # Escrever na aba Comparacao CE-C8 (se temos ambos os resultados)
        if ce_c8_cost is not None and noshare_c8_cost is not None:
            economia_c8 = ((noshare_c8_cost - ce_c8_cost) / noshare_c8_cost * 100) if noshare_c8_cost > 0 else 0
            diferenca_c8 = noshare_c8_cost - ce_c8_cost

            ws_comp_c8.cell(row=row_comp_c8, column=1, value=int(problem_id) if problem_id.isdigit() else problem_id).border = thin_border

            cell_ce_c8 = ws_comp_c8.cell(row=row_comp_c8, column=2, value=ce_c8_cost)
            cell_ce_c8.number_format = number_format
            cell_ce_c8.border = thin_border

            cell_a_c8 = ws_comp_c8.cell(row=row_comp_c8, column=3, value=cost_a_c8 if cost_a_c8 else 0)
            cell_a_c8.number_format = number_format
            cell_a_c8.border = thin_border

            cell_b_c8 = ws_comp_c8.cell(row=row_comp_c8, column=4, value=cost_b_c8 if cost_b_c8 else 0)
            cell_b_c8.number_format = number_format
            cell_b_c8.border = thin_border

            cell_total_c8 = ws_comp_c8.cell(row=row_comp_c8, column=5, value=noshare_c8_cost)
            cell_total_c8.number_format = number_format
            cell_total_c8.border = thin_border

            cell_eco_c8 = ws_comp_c8.cell(row=row_comp_c8, column=6, value=economia_c8)
            cell_eco_c8.number_format = '0.00"%"'
            cell_eco_c8.border = thin_border
            # Colorir economia positiva de verde, negativa de vermelho
            if economia_c8 > 0:
                cell_eco_c8.fill = PatternFill(start_color="C6EFCE", end_color="C6EFCE", fill_type="solid")
            elif economia_c8 < 0:
                cell_eco_c8.fill = PatternFill(start_color="FFC7CE", end_color="FFC7CE", fill_type="solid")

            cell_dif_c8 = ws_comp_c8.cell(row=row_comp_c8, column=7, value=diferenca_c8)
            cell_dif_c8.number_format = number_format
            cell_dif_c8.border = thin_border

            row_comp_c8 += 1

    # Ajustar largura das colunas
    for ws in [ws_ce, ws_noshare, ws_comp, ws_ce_c8, ws_ce_c8_noshare, ws_comp_c8]:
        for col in range(1, ws.max_column + 1):
            ws.column_dimensions[get_column_letter(col)].width = 18

    # Adicionar linha de totais/medias na aba Comparacao CE
    if row_comp > 2:
        row_comp += 1  # Linha em branco

        # Media
        ws_comp.cell(row=row_comp, column=1, value="MEDIA").font = Font(bold=True)
        ws_comp.cell(row=row_comp, column=2, value=f"=AVERAGE(B2:B{row_comp-2})").number_format = number_format
        ws_comp.cell(row=row_comp, column=3, value=f"=AVERAGE(C2:C{row_comp-2})").number_format = number_format
        ws_comp.cell(row=row_comp, column=4, value=f"=AVERAGE(D2:D{row_comp-2})").number_format = number_format
        ws_comp.cell(row=row_comp, column=5, value=f"=AVERAGE(E2:E{row_comp-2})").number_format = number_format
        ws_comp.cell(row=row_comp, column=6, value=f"=AVERAGE(F2:F{row_comp-2})").number_format = '0.00"%"'
        ws_comp.cell(row=row_comp, column=7, value=f"=AVERAGE(G2:G{row_comp-2})").number_format = number_format

    # Adicionar linha de totais/medias na aba Comparacao CE-C8
    if row_comp_c8 > 2:
        row_comp_c8 += 1  # Linha em branco

        # Media
        ws_comp_c8.cell(row=row_comp_c8, column=1, value="MEDIA").font = Font(bold=True)
        ws_comp_c8.cell(row=row_comp_c8, column=2, value=f"=AVERAGE(B2:B{row_comp_c8-2})").number_format = number_format
        ws_comp_c8.cell(row=row_comp_c8, column=3, value=f"=AVERAGE(C2:C{row_comp_c8-2})").number_format = number_format
        ws_comp_c8.cell(row=row_comp_c8, column=4, value=f"=AVERAGE(D2:D{row_comp_c8-2})").number_format = number_format
        ws_comp_c8.cell(row=row_comp_c8, column=5, value=f"=AVERAGE(E2:E{row_comp_c8-2})").number_format = number_format
        ws_comp_c8.cell(row=row_comp_c8, column=6, value=f"=AVERAGE(F2:F{row_comp_c8-2})").number_format = '0.00"%"'
        ws_comp_c8.cell(row=row_comp_c8, column=7, value=f"=AVERAGE(G2:G{row_comp_c8-2})").number_format = number_format

    # Salvar arquivo
    wb.save(output_path)
    print(f"\n{'=' * 70}")
    print(f"Relatorio salvo em: {output_path}")
    print(f"{'=' * 70}")


def main():
    results_dir = RESULTS_DIR
    input_dir = JSON_INPUT_DIR
    output_path = OUTPUT_EXCEL

    # Processar argumentos da linha de comando
    if len(sys.argv) > 1:
        results_dir = sys.argv[1]
    if len(sys.argv) > 2:
        input_dir = sys.argv[2]
    if len(sys.argv) > 3:
        output_path = sys.argv[3]

    create_excel_report(results_dir, input_dir, output_path)


if __name__ == "__main__":
    main()
