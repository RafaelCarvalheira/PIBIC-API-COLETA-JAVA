# -*- coding: utf-8 -*-
"""Gera as figuras do relatorio final de PIBITI a partir de relatorio_final.xlsx."""
import os
import statistics

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch
import openpyxl

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(BASE, "docs", "figuras")
os.makedirs(OUT, exist_ok=True)

AZUL, LARANJA, VERMELHO = "#1A6E9E", "#D9660A", "#A63A3A"
TINTA, TINTA2, GRADE = "#1F2328", "#5A6169", "#DEE2E6"

plt.rcParams.update({
    "font.family": "sans-serif",
    "font.sans-serif": ["Arial", "DejaVu Sans"],
    "font.size": 9,
    "axes.edgecolor": GRADE,
    "axes.labelcolor": TINTA,
    "text.color": TINTA,
    "xtick.color": TINTA2,
    "ytick.color": TINTA2,
    "axes.grid": True,
    "grid.color": GRADE,
    "grid.linewidth": 0.6,
    "figure.dpi": 300,
    "savefig.dpi": 300,
    "savefig.bbox": "tight",
})


def carregar():
    wb = openpyxl.load_workbook(os.path.join(BASE, "relatorio_final.xlsx"), data_only=True)

    def linhas(ws, cols):
        out = []
        for r in ws.iter_rows(values_only=True):
            if isinstance(r[0], (int, float)) and r[0]:
                out.append({k: r[i] for k, i in cols.items()})
        return out

    ce = linhas(wb["CE"], dict(id=0, n=1, og=2, st=3, cg=5, oj=9, cj=10))
    c8 = linhas(wb["CEc8"], dict(id=0, n=1, og=2, st=3, cg=5, oj=9, cj=10))
    ca = linhas(wb["CE_A"], dict(id=0, n=1, og=4, st=5, cg=6, oj=12, cj=13))
    return ce, c8, ca


CE, C8, CA = carregar()
GRUPOS = [10, 15, 20, 25, 30]


def s2(dados, n):
    return [d for d in dados if d["id"] >= 8001 and d["n"] == n]


def media(vals):
    return statistics.mean(vals)


def limpar(ax, eixo_y_esq=True):
    for lado in ("top", "right"):
        ax.spines[lado].set_visible(False)
    ax.spines["bottom"].set_color(GRADE)
    ax.spines["left"].set_color(GRADE)
    ax.set_axisbelow(True)
    ax.grid(axis="x", visible=False)
    ax.tick_params(length=0)


# ---------------------------------------------------------------- Figura 1
def fig_arquitetura():
    fig, ax = plt.subplots(figsize=(6.3, 3.1))
    ax.set_xlim(0, 100); ax.set_ylim(0, 52); ax.axis("off")

    def caixa(x, y, w, h, titulo, sub, cor, fundo):
        ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0,rounding_size=1.6",
                                    linewidth=1.2, edgecolor=cor, facecolor=fundo))
        ax.text(x + w / 2, y + h * 0.62, titulo, ha="center", va="center",
                fontsize=8.5, fontweight="bold", color=TINTA)
        ax.text(x + w / 2, y + h * 0.26, sub, ha="center", va="center",
                fontsize=7, color=TINTA2)

    def seta(x1, y1, x2, y2, cor=TINTA2, estilo="-|>"):
        ax.add_patch(FancyArrowPatch((x1, y1), (x2, y2), arrowstyle=estilo,
                                     mutation_scale=9, linewidth=1.1, color=cor))

    # Trilha meta-heuristica
    ax.text(1, 47.5, "Abordagem meta-heurística proposta", fontsize=8,
            fontweight="bold", color=AZUL)
    caixa(1, 30, 21, 13, "Instâncias .dat", "S1 (12) e S2 (100)", "#C9CED4", "#F6F7F8")
    caixa(26, 30, 21, 13, "dat_to_json_ce.py", "conversão p/ JSON", "#C9CED4", "#F6F7F8")
    caixa(51, 30, 22, 13, "orchestrator_ce.py", "orquestração dos testes", "#C9CED4", "#F6F7F8")
    caixa(77, 30, 22, 13, "Relatórios", "CSV / Excel", "#C9CED4", "#F6F7F8")
    seta(22, 36.5, 26, 36.5); seta(47, 36.5, 51, 36.5); seta(73, 36.5, 77, 36.5)

    caixa(26, 9, 21, 13, "API REST", "Spring Boot · Java 17", AZUL, "#EAF2F7")
    caixa(51, 9, 22, 13, "VrpService", "4 cenários de resolução", AZUL, "#EAF2F7")
    caixa(77, 9, 22, 13, "Jsprit 1.9.0", "Ruin & Recreate", AZUL, "#EAF2F7")
    seta(47, 15.5, 51, 15.5, AZUL); seta(73, 15.5, 77, 15.5, AZUL)
    seta(62, 30, 62, 22, AZUL)
    seta(36.5, 22, 36.5, 30, AZUL)
    ax.text(63.5, 26, "requisição JSON", fontsize=6.5, color=TINTA2, ha="left", va="center")
    ax.text(38, 26, "solução JSON", fontsize=6.5, color=TINTA2, ha="left", va="center")

    # Trilha exata
    caixa(1, 9, 21, 13, "Julia + JuMP", "Gurobi · método exato", LARANJA, "#FBF0E6")
    seta(11.5, 30, 11.5, 22, LARANJA)
    ax.text(13, 26, "instâncias", fontsize=6.5, color=TINTA2, ha="left", va="center")

    ax.add_patch(FancyBboxPatch((1, 1.5), 98, 5.2, boxstyle="round,pad=0,rounding_size=1.4",
                                linewidth=0, facecolor="#F6F7F8"))
    ax.text(50, 4.1, "Comparação: custo da função objetivo (Dif. %) e tempo de CPU",
            ha="center", va="center", fontsize=7.5, color=TINTA2)

    fig.savefig(os.path.join(OUT, "fig1_arquitetura.png"))
    plt.close(fig)


# ---------------------------------------------------------------- Figura 2
def fig_ruin_recreate():
    fig, ax = plt.subplots(figsize=(6.3, 2.6))
    ax.set_xlim(0, 100); ax.set_ylim(0, 42); ax.axis("off")

    def caixa(x, y, w, h, titulo, sub, cor, fundo, negrito=True):
        ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0,rounding_size=1.6",
                                    linewidth=1.2, edgecolor=cor, facecolor=fundo))
        ax.text(x + w / 2, y + h * 0.62, titulo, ha="center", va="center", fontsize=8.5,
                fontweight="bold" if negrito else "normal", color=TINTA)
        ax.text(x + w / 2, y + h * 0.24, sub, ha="center", va="center", fontsize=6.4, color=TINTA2)

    def seta(x1, y1, x2, y2, cor=TINTA2, conn="arc3,rad=0"):
        ax.add_patch(FancyArrowPatch((x1, y1), (x2, y2), arrowstyle="-|>", mutation_scale=9,
                                     linewidth=1.1, color=cor, connectionstyle=conn))

    caixa(0.5, 16, 17, 13, "Solução inicial", "Regret Insertion", "#C9CED4", "#F6F7F8")
    caixa(22, 16, 18, 13, "Ruína", "Radial · Random", AZUL, "#EAF2F7")
    caixa(44, 16, 18, 13, "Reconstrução", "Best · Regret", AZUL, "#EAF2F7")
    caixa(66, 16, 18, 13, "Aceitação", "Threshold Accepting", AZUL, "#EAF2F7")
    caixa(88, 16, 11.5, 13, "Melhor\nsolução", "", "#C9CED4", "#F6F7F8")

    seta(17.5, 22.5, 22, 22.5); seta(40, 22.5, 44, 22.5); seta(62, 22.5, 66, 22.5)
    seta(84, 22.5, 88, 22.5)

    # laco de realimentacao por baixo das caixas
    for x1, y1, x2, y2 in [(75, 16, 75, 10.5), (75, 10.5, 31, 10.5)]:
        ax.add_patch(FancyArrowPatch((x1, y1), (x2, y2), arrowstyle="-",
                                     linewidth=1.1, color=AZUL))
    seta(31, 10.5, 31, 16, AZUL)
    ax.text(53, 8.4, "1.000 iterações por partida", fontsize=7, color=AZUL, ha="center",
            va="top")

    ax.add_patch(FancyBboxPatch((0.5, 32.5), 99, 7, boxstyle="round,pad=0,rounding_size=1.4",
                                linewidth=1.1, edgecolor=LARANJA, facecolor="#FBF0E6"))
    ax.text(50, 36, "Multi-start: 10 partidas independentes com sementes determinísticas "
                    "— retém-se a melhor solução",
            ha="center", va="center", fontsize=7.5, color=TINTA)
    seta(9, 32.5, 9, 29, LARANJA)

    fig.savefig(os.path.join(OUT, "fig2_ruin_recreate.png"))
    plt.close(fig)


# ---------------------------------------------------------------- Figura 3
def fig_tempo():
    cg = [media([d["cg"] for d in s2(CE, n)]) for n in GRUPOS]
    cj = [media([d["cj"] for d in s2(CE, n)]) for n in GRUPOS]

    fig, ax = plt.subplots(figsize=(5.9, 3.3))
    ax.plot(GRUPOS, cg, color=LARANJA, linewidth=2, marker="o", markersize=7,
            markeredgecolor="white", markeredgewidth=1.2, label="Gurobi (método exato)")
    ax.plot(GRUPOS, cj, color=AZUL, linewidth=2, marker="s", markersize=7,
            markeredgecolor="white", markeredgewidth=1.2, label="Jsprit (meta-heurística)")
    ax.set_yscale("log")
    ax.set_xticks(GRUPOS)
    ax.set_xlabel("Número de clientes")
    ax.set_ylabel("Tempo médio de CPU (s, escala logarítmica)")
    ax.axhline(7200, color=TINTA2, linewidth=0.9, linestyle=(0, (4, 3)))
    ax.text(10, 8100, "limite de tempo do Gurobi: 7.200 s", fontsize=6.8, color=TINTA2,
            va="bottom", ha="left")

    def rotulo(v):
        return (f"{v:,.0f}".replace(",", ".") if v >= 100 else f"{v:.1f}".replace(".", ","))

    for i, (x, y) in enumerate(zip(GRUPOS, cg)):
        ultimo = i == len(GRUPOS) - 1
        ax.annotate(rotulo(y), (x, y), textcoords="offset points",
                    xytext=(13, -7) if ultimo else (0, 9),
                    ha="left" if ultimo else "center", va="center" if ultimo else "bottom",
                    fontsize=7, color=TINTA)
    for i, (x, y) in enumerate(zip(GRUPOS, cj)):
        # em n=10 a curva do Gurobi passa abaixo: o rotulo do Jsprit sobe para nao colidir
        ax.annotate(rotulo(y), (x, y), textcoords="offset points",
                    xytext=(0, 9) if i == 0 else (0, -14),
                    ha="center", va="bottom" if i == 0 else "top",
                    fontsize=7, color=TINTA)
    limpar(ax)
    ax.set_xlim(8.6, 33.8)
    ax.set_ylim(1.6, 26000)
    ax.legend(frameon=False, loc="lower right", fontsize=8)
    fig.savefig(os.path.join(OUT, "fig3_tempo_cpu.png"))
    plt.close(fig)


# ---------------------------------------------------------------- Figura 4
def fig_desvio():
    def difs(dados, n):
        return media([(d["oj"] - d["og"]) / d["og"] * 100 for d in s2(dados, n)])

    ce = [difs(CE, n) for n in GRUPOS]
    c8 = [difs(C8, n) for n in GRUPOS]
    ca = [difs(CA, n) for n in GRUPOS]

    fig, ax = plt.subplots(figsize=(5.9, 3.3))
    series = [
        (c8, VERMELHO, "^", "Sem simultaneidade (colaborativo)"),
        (ce, AZUL, "s", "Com simultaneidade (colaborativo)"),
        (ca, LARANJA, "o", "Sem compartilhamento"),
    ]
    for vals, cor, marca, rotulo in series:
        ax.plot(GRUPOS, vals, color=cor, linewidth=2, marker=marca, markersize=7,
                markeredgecolor="white", markeredgewidth=1.2, label=rotulo)
    for vals, cor, _, _ in series:
        ax.annotate(f"{vals[-1]:.2f}%".replace(".", ","), (GRUPOS[-1], vals[-1]),
                    textcoords="offset points", xytext=(8, 0), ha="left", va="center",
                    fontsize=7.5, color=TINTA)
    ax.axhline(0, color=TINTA2, linewidth=0.9)
    ax.set_xticks(GRUPOS)
    ax.set_xlabel("Número de clientes")
    ax.set_ylabel("Diferença média de custo\nJsprit − Gurobi (%)")
    limpar(ax)
    ax.set_xlim(9, 34.5)
    ax.legend(frameon=False, loc="upper left", fontsize=7.6)
    fig.savefig(os.path.join(OUT, "fig4_desvio_custo.png"))
    plt.close(fig)


# ---------------------------------------------------------------- Figura 5
def fig_colaboracao():
    mapa = {d["id"]: d for d in CA}
    eg, ej = [], []
    for n in GRUPOS:
        sub = [d for d in s2(CE, n) if d["id"] in mapa]
        eg.append(media([(mapa[d["id"]]["og"] - d["og"]) / mapa[d["id"]]["og"] * 100 for d in sub]))
        ej.append(media([(mapa[d["id"]]["oj"] - d["oj"]) / mapa[d["id"]]["oj"] * 100 for d in sub]))

    fig, ax = plt.subplots(figsize=(5.9, 3.3))
    x = range(len(GRUPOS))
    larg = 0.36
    b1 = ax.bar([i - larg / 2 - 0.012 for i in x], eg, larg, color=LARANJA,
                label="Gurobi (método exato)")
    b2 = ax.bar([i + larg / 2 + 0.012 for i in x], ej, larg, color=AZUL,
                label="Jsprit (meta-heurística)")
    for barras in (b1, b2):
        for b in barras:
            ax.annotate(f"{b.get_height():.2f}".replace(".", ","),
                        (b.get_x() + b.get_width() / 2, b.get_height()),
                        textcoords="offset points", xytext=(0, 3), ha="center",
                        fontsize=6.8, color=TINTA)
    ax.set_xticks(list(x))
    ax.set_xticklabels(GRUPOS)
    ax.set_xlabel("Número de clientes")
    ax.set_ylabel("Economia da colaboração (%)")
    ax.set_ylim(0, max(max(eg), max(ej)) * 1.22)
    limpar(ax)
    ax.legend(frameon=False, loc="upper left", fontsize=8, ncol=2)
    fig.savefig(os.path.join(OUT, "fig5_economia_colaboracao.png"))
    plt.close(fig)


if __name__ == "__main__":
    fig_arquitetura()
    fig_ruin_recreate()
    fig_tempo()
    fig_desvio()
    fig_colaboracao()
    print("Figuras geradas em", OUT)
