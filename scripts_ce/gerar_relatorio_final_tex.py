# -*- coding: utf-8 -*-
"""Gera a versao LaTeX do Relatorio Final de PIBITI.

Reaproveita as funcoes de conteudo de gerar_relatorio_final_docx.py: aquelas
funcoes so conversam com a interface do Cursor (p, titulo, tabela, figura,
legenda, fonte_tabela), entao basta oferecer um Cursor que emite LaTeX em vez
de OOXML. O texto e os numeros ficam, assim, num unico lugar.

Saida: latex/relatorio_final.tex + latex/figuras/*.png (pronto para o Overleaf).
"""
import os
import re
import shutil
import sys

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(BASE, "scripts_ce"))

import gerar_relatorio_final_docx as doc  # noqa: E402

SAIDA_DIR = os.path.join(BASE, "latex")
SAIDA_TEX = os.path.join(SAIDA_DIR, "relatorio_final.tex")
FIG_ORIGEM = os.path.join(BASE, "docs", "figuras")
FIG_DESTINO = os.path.join(SAIDA_DIR, "figuras")


# =================================================================== escape
MULTI = [
    ("θ₀", r"$\theta_0$"),
    ("α", r"$\alpha$"),
    ("×", r"$\times$"),
    ("−", "--"),      # sinal de menos usado como intervalo
    ("–", "--"),      # meia-risca das legendas
    ("—", "---"),
    ("“", "``"),
    ("”", "''"),
    ("≥", r"$\geq$"),
    ("≤", r"$\leq$"),
]
SIMPLES = {
    "\\": r"\textbackslash{}",
    "%": r"\%",
    "&": r"\&",
    "_": r"\_",
    "#": r"\#",
    "$": r"\$",
    "{": r"\{",
    "}": r"\}",
    "~": r"\textasciitilde{}",
    "^": r"\textasciicircum{}",
}


def escapar(texto):
    """Escapa o texto para LaTeX.

    Os caracteres reservados sao tratados ANTES das substituicoes de MULTI:
    na ordem inversa, o "$\\theta_0$" recem-inserido seria escapado de novo e
    apareceria literalmente no PDF.
    """
    texto = "".join(SIMPLES.get(ch, ch) for ch in str(texto))
    for antigo, novo in MULTI:
        texto = texto.replace(antigo, novo)
    return texto


def referenciar(texto):
    """Troca "Tabela 3" e "Figura 5" por referencias cruzadas do LaTeX.

    A substituicao usa funcao, e nao texto de template: num template de re.sub
    a sequencia \r seria lida como retorno de carro e comeria a barra do
    \ref. As formas no plural vem antes para nao serem quebradas pelas regras
    do singular.
    """
    def simples(prefixo, rotulo):
        def troca(m):
            return "%s~\\ref{%s:%s}" % (prefixo, rotulo, m.group(1))
        return troca

    def par(prefixo, rotulo):
        def troca(m):
            return "%s~\\ref{%s:%s} e~\\ref{%s:%s}" % (
                prefixo, rotulo, m.group(1), rotulo, m.group(2))
        return troca

    regras = [
        (r"\bTabelas (\d+) e (\d+)\b", par("Tabelas", "tab")),
        (r"\bFiguras (\d+) e (\d+)\b", par("Figuras", "fig")),
        (r"\bTabela (\d+)\b", simples("Tabela", "tab")),
        (r"\bFigura (\d+)\b", simples("Figura", "fig")),
    ]
    for padrao, troca in regras:
        texto = re.sub(padrao, troca, texto)
    return texto


def com_italico(texto, negrito=False):
    """Escapa o texto e envolve os termos estrangeiros em \\textit{}."""
    saida = []
    for pedaco in doc._PADRAO_ITALICO.split(str(texto)):
        if not pedaco:
            continue
        corpo = escapar(pedaco)
        if pedaco in doc._CONJUNTO_ITALICO:
            corpo = r"\textit{" + corpo + "}"
        saida.append(corpo)
    resultado = "".join(saida)
    return r"\textbf{" + resultado + "}" if negrito else resultado


# ============================================================ objeto neutro
class FormatoFalso:
    """Absorve os ajustes de formatacao que so fazem sentido no Word."""

    def __setattr__(self, nome, valor):
        pass


class ParagrafoFalso:
    def __init__(self):
        object.__setattr__(self, "paragraph_format", FormatoFalso())


# =================================================================== cursor
class CursorTex:
    """Mesma interface do Cursor do gerador .docx, emitindo LaTeX."""

    def __init__(self):
        self.linhas = []
        self._legenda_pendente = None
        self._figura_pendente = None

    # ------------------------------------------------------------ texto
    def p(self, texto="", estilo=None, recuo=True, tam=12, alinh=None, negrito=False):
        if texto:
            self.linhas.append(referenciar(com_italico(texto, negrito=negrito)))
            self.linhas.append("")
        return ParagrafoFalso()

    def rico(self, partes, recuo=True, tam=12):
        pedacos = []
        for texto, neg, ital in partes:
            corpo = com_italico(texto)
            if neg:
                corpo = r"\textbf{" + corpo + "}"
            if ital:
                corpo = r"\textit{" + corpo + "}"
            pedacos.append(corpo)
        self.linhas.append("".join(pedacos))
        self.linhas.append("")
        return ParagrafoFalso()

    def titulo(self, texto, nivel=2):
        self.linhas.append(r"\subsubsection{" + com_italico(texto) + "}")
        return ParagrafoFalso()

    def secao(self, texto, nivel=1, rotulo=None):
        comando = {1: "section", 2: "subsection", 3: "subsubsection"}[nivel]
        marca = ("\n" + r"\label{" + rotulo + "}") if rotulo else ""
        self.linhas.append("\\" + comando + "{" + com_italico(texto) + "}" + marca)
        return ParagrafoFalso()

    # --------------------------------------------------------- legendas
    def legenda(self, texto, tam=10, antes=0, depois=4):
        if self._figura_pendente is not None:
            arquivo, largura = self._figura_pendente
            self._figura_pendente = None
            self._emitir_figura(arquivo, largura, texto)
        else:
            self._legenda_pendente = texto
        return ParagrafoFalso()

    def fonte_tabela(self, texto):
        # notas em corpo menor e italico, como no relatorio parcial
        self.linhas.append(r"\noindent{\footnotesize \textit{"
                           + com_italico(texto) + "}}")
        self.linhas.append("")
        return ParagrafoFalso()

    # ---------------------------------------------------------- figuras
    def figura(self, arquivo, largura=None):
        self._figura_pendente = (arquivo, largura)
        return ParagrafoFalso()

    def _emitir_figura(self, arquivo, largura, legenda):
        cm = f"{largura.cm:.1f}" if largura is not None else "13.5"
        titulo, rotulo = self._partir_legenda(legenda, "fig")
        self.linhas += [
            r"\begin{figure}[H]",
            r"    \centering",
            r"    \includegraphics[width=" + cm + "cm]{figuras/" + arquivo + "}",
            r"    \caption{" + titulo + "}",
            r"    \label{" + rotulo + "}",
            r"\end{figure}",
            "",
        ]

    @staticmethod
    def _partir_legenda(legenda, prefixo):
        """Separa "Tabela 3 - Titulo" em rotulo e titulo (o LaTeX numera sozinho)."""
        texto = legenda or ""
        casamento = re.match(r"^(Tabela|Figura)\s+(\d+)\s*[–-]\s*(.*)$", texto, re.S)
        if casamento:
            numero, titulo = casamento.group(2), casamento.group(3)
            return com_italico(titulo), f"{prefixo}:{numero}"
        return com_italico(texto), f"{prefixo}:auto{abs(hash(texto)) % 1000}"

    # ---------------------------------------------------------- tabelas
    def tabela(self, cabecalho, corpo_linhas, larguras=None, tam=9.5,
               alinhamentos=None, mesclar=None, cabecalhos_repetidos=1):
        n_col = len(cabecalho[-1])
        separador = self._separadores(cabecalho[0], n_col)
        spec = self._especificacao(n_col, larguras, alinhamentos, separador)

        titulo, rotulo = self._partir_legenda(self._legenda_pendente, "tab")
        self._legenda_pendente = None

        corpo = [r"\begin{table}[H]", r"    \centering",
                 r"    \caption{" + titulo + "}", r"    \label{" + rotulo + "}",
                 r"    \vspace{0.2cm}", r"    \footnotesize",
                 r"    \setlength{\tabcolsep}{4pt}",
                 r"    \begin{adjustbox}{max width=\textwidth}",
                 r"    \begin{tabular}{" + spec + "}", r"        \toprule"]

        for linha_cab in cabecalho:
            corpo.append("        " + self._linha_cabecalho(linha_cab, separador) + r" \\")
        corpo.append(r"        \midrule")

        for linha in corpo_linhas:
            corpo.append("        " + self._linha_corpo(linha, mesclar, separador) + r" \\")

        corpo += [r"        \bottomrule", r"    \end{tabular}",
                  r"    \end{adjustbox}", r"\end{table}", ""]
        self.linhas += corpo
        return None

    @staticmethod
    def _separadores(primeira_linha, n_col):
        """Marca onde entram as reguas verticais, separando os grupos do cabecalho."""
        sep = [False] * n_col
        col = 0
        itens = []
        for item in primeira_linha:
            texto, span = item if isinstance(item, tuple) else (item, 1)
            itens.append((col, span))
            col += span
        for indice, (inicio, span) in enumerate(itens[:-1]):
            proximo_span = itens[indice + 1][1]
            if span > 1 or proximo_span > 1:
                sep[inicio + span - 1] = True
        return sep

    @staticmethod
    def _especificacao(n_col, larguras, alinhamentos, separador):
        from docx.enum.text import WD_ALIGN_PARAGRAPH
        partes = []
        for j in range(n_col):
            esquerda = (alinhamentos and j < len(alinhamentos)
                        and alinhamentos[j] == WD_ALIGN_PARAGRAPH.LEFT)
            if esquerda and larguras and j < len(larguras):
                # raggedright evita os buracos entre palavras que a justificacao
                # produz numa coluna estreita
                partes.append(r">{\raggedright\arraybackslash}"
                              + f"p{{{larguras[j].cm:.1f}cm}}")
            else:
                partes.append("c")
            if separador[j]:
                partes.append("|")
        return " ".join(partes)

    @staticmethod
    def _linha_cabecalho(linha, separador):
        celulas = []
        col = 0
        for item in linha:
            texto, span = item if isinstance(item, tuple) else (item, 1)
            conteudo = r"\textbf{" + com_italico(texto) + "}" if texto else ""
            if span > 1:
                borda = "|" if separador[col + span - 1] else ""
                conteudo = (r"\multicolumn{" + str(span) + "}{c" + borda + "}{"
                            + conteudo + "}")
            celulas.append(conteudo)
            col += span
        return " & ".join(celulas)

    @staticmethod
    def _linha_corpo(linha, mesclar, separador):
        valores = [com_italico(v) for v in linha]
        if mesclar:
            ini, fim = mesclar
            if fim < len(linha):
                brutos = [str(v).strip() for v in linha[ini:fim + 1]]
                if len(set(brutos)) == 1 and brutos[0]:
                    borda = "|" if separador[fim] else ""
                    fundido = (r"\multicolumn{" + str(fim - ini + 1) + "}{c" + borda
                               + "}{" + valores[ini] + "}")
                    valores = valores[:ini] + [fundido] + valores[fim + 1:]
        return " & ".join(valores)


# ================================================================ preambulo
PREAMBULO = r"""\documentclass[a4paper,12pt]{article}

% Pacotes fundamentais
\usepackage[brazil]{babel}
\usepackage[utf8]{inputenc}
\usepackage[T1]{fontenc}
\usepackage{geometry}
\usepackage{indentfirst}
\usepackage{graphicx}
\usepackage{booktabs}
\usepackage{array}
\usepackage{adjustbox}
\usepackage{float}
\usepackage{xcolor}
\usepackage{hyperref}
\usepackage{longtable}
\usepackage{amsmath}
\usepackage{amssymb}

% Configuracao das margens
\geometry{
    top=3cm,
    bottom=2cm,
    left=3cm,
    right=2cm
}

% Configuracao de hyperlinks
\hypersetup{
    colorlinks=true,
    linkcolor=blue,
    citecolor=blue,
    urlcolor=blue
}

\begin{document}

% --- CAPA ---
\begin{center}
    MINISTÉRIO DA DEFESA\\
    EXÉRCITO BRASILEIRO\\
    DEPARTAMENTO DE CIÊNCIA E TECNOLOGIA\\
    \textbf{INSTITUTO MILITAR DE ENGENHARIA}\\
    (Real Academia de Artilharia, Fortificação e Desenho -- 1792)\\[1.5cm]

    Programa Institucional de Bolsas de Iniciação em Desenvolvimento\\
    Tecnológico e Inovação (PIBITI)\\[0.4cm]
    Edital 2025/2026\\[0.8cm]

    \textbf{\large RELATÓRIO FINAL}\\[1.5cm]

    \textbf{\large Adaptação e comparação de algoritmos para o problema\\
    de roteamento de veículos utilizando programação em Julia}\\[1cm]

    Projeto: Pesquisa Operacional: Aplicação na Logística Militar\\[2cm]

    Rio de Janeiro, RJ\\
    Julho de 2026
\end{center}

\newpage

% --- IDENTIFICACAO ---
\section*{Identificação}

\begin{table}[H]
    \begin{tabular}{@{}p{4.5cm}p{10cm}@{}}
        \textbf{Autor:} & Rafael Vargas Carvalheira \\
        \textbf{E-mail:} & rafaelvargascar20@gmail.com \\
        \textbf{Telefone:} & (12) 98141-8480 \\[0.3cm]
        \textbf{Orientador:} & Prof. Orivalde Soares da Silva Júnior -- D.Sc. \\
        \textbf{E-mail:} & orivalde@ime.eb.br \\
        \textbf{Telefone:} & (21) 99939-8145 \\[0.3cm]
        \textbf{Instituição:} & Instituto Militar de Engenharia \\
        \textbf{Endereço:} & Praça Gen Tibúrcio, nr 80, Praia Vermelha -- Urca
                             (CEP: 22290-270) \\
        \textbf{Telefone:} & (21) 2546-7198 \\
        \textbf{E-mail:} & pibiti@ime.eb.br \\
    \end{tabular}
\end{table}

\newpage
\tableofcontents
\newpage
"""


def montar():
    cur = CursorTex()

    cur.secao("Resumo")
    doc.escrever_resumo(cur)

    cur.secao("Apresentação")
    cur.secao("Introdução", nivel=2)
    doc.escrever_introducao(cur)
    cur.secao("Justificativa", nivel=2)
    doc.escrever_justificativa(cur)
    cur.secao("Objetivos", nivel=2)
    doc.escrever_objetivos(cur)

    cur.secao("Desenvolvimento")
    cur.secao("Metodologia", nivel=2, rotulo="sec:metodologia")
    doc.escrever_metodologia(cur)
    cur.secao("Resultados e Análise", nivel=2, rotulo="sec:resultados")
    doc.escrever_resultados(cur)

    cur.secao("Conclusão")
    doc.escrever_conclusao(cur)

    corpo = "\n".join(cur.linhas)

    referencias = [r"\begin{thebibliography}{99}", r"\addcontentsline{toc}{section}{Referências}"]
    for i, entrada in enumerate(doc.REFERENCIAS, 1):
        referencias.append(r"\bibitem{ref" + str(i) + "} " + com_italico(entrada))
    referencias.append(r"\end{thebibliography}")

    return (PREAMBULO + "\n" + corpo + "\n"
            + r"\section*{Referências Bibliográficas}" + "\n"
            + "\n".join(referencias) + "\n\n" + r"\end{document}" + "\n")


def main():
    conteudo = montar()

    # copia apenas as figuras efetivamente citadas, para o diretorio ficar
    # pronto para ser compactado e enviado ao Overleaf
    os.makedirs(FIG_DESTINO, exist_ok=True)
    usadas = set(re.findall(r"figuras/([^}]+)", conteudo))
    for obsoleta in os.listdir(FIG_DESTINO):
        if obsoleta not in usadas:
            os.remove(os.path.join(FIG_DESTINO, obsoleta))
    for nome in sorted(usadas):
        shutil.copy2(os.path.join(FIG_ORIGEM, nome),
                     os.path.join(FIG_DESTINO, nome))

    with open(SAIDA_TEX, "w", encoding="utf-8") as saida:
        saida.write(conteudo)
    print("LaTeX gerado:", SAIDA_TEX, "|", len(usadas), "figuras")


if __name__ == "__main__":
    main()
