import os
import re
import pandas as pd

RESULTS_DIR = os.path.join(os.path.dirname(__file__), "novosresultados")
OUTPUT_FILE = os.path.join(os.path.dirname(__file__), "relatorio_novosresultados.xlsx")

# Map suffix to sheet name
CASES = {
    "CE": "outputCE",
    "CEc8": "outputCEc8",
    "CE_A": "outputCE_A",
    "CE_B": "outputCE_B",
}


def parse_file(filepath):
    """Extract ID, num clients, OF, status, GAP, and execution time from a result file."""
    with open(filepath, "r", encoding="utf-8", errors="replace") as f:
        content = f.read()

    instance_id = None
    num_clientes = None
    of_custo = None
    status = None
    gap = None
    tempo = None

    for line in content.splitlines():
        line = line.strip()

        m = re.match(r"status=\s*(.+)", line)
        if m:
            status = m.group(1).strip()

        m = re.match(r"ID\s*=\s*(\d+)", line)
        if m:
            instance_id = int(m.group(1))

        m = re.match(r"Numero de clientes\s*=\s*(\d+)", line)
        if m:
            num_clientes = int(m.group(1))

        m = re.match(r"OF \(custo\)\s*=\s*([\d.]+)", line)
        if m:
            of_custo = float(m.group(1))

        m = re.match(r"GAP\s*=\s*([\d.]+)%", line)
        if m:
            gap = float(m.group(1))

        m = re.match(r"Tempo de execu..o:\s*([\d.]+)\s*segundos", line)
        if m:
            tempo = float(m.group(1))

    return {
        "ID": instance_id,
        "Num. Clientes": num_clientes,
        "OF (custo)": of_custo,
        "Status": status,
        "GAP (%)": gap,
        "Tempo (s)": tempo,
    }


def collect_data(suffix):
    """Collect data for all instances matching a given output suffix."""
    rows = []
    for fname in os.listdir(RESULTS_DIR):
        if fname.endswith(f"_{suffix}.txt"):
            filepath = os.path.join(RESULTS_DIR, fname)
            row = parse_file(filepath)
            if row["ID"] is not None:
                rows.append(row)
    rows.sort(key=lambda r: r["ID"])
    return rows


def main():
    with pd.ExcelWriter(OUTPUT_FILE, engine="openpyxl") as writer:
        for sheet_name, suffix in CASES.items():
            rows = collect_data(suffix)
            df = pd.DataFrame(rows)
            df.to_excel(writer, sheet_name=sheet_name, index=False)

    print(f"Excel gerado: {OUTPUT_FILE}")
    print(f"Abas: {', '.join(CASES.keys())}")


if __name__ == "__main__":
    main()
