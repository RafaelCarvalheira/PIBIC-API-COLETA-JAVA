# -*- coding: utf-8 -*-
"""Confere o .tex gerado sem precisar de um compilador TeX instalado.

Verifica ambientes, chaves, modo matematico, escapes, contagem de colunas das
tabelas, referencias orfas e existencia dos arquivos de figura.
"""
import io
import os
import re
import sys

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEX = os.path.join(BASE, "latex", "relatorio_final.tex")
BARRA = chr(92)


def contar_colunas(spec):
    """Conta colunas de uma especificacao de tabular.

    Descarta os modificadores >{...} e <{...}, cujo conteudo (\\raggedright,
    \\arraybackslash) tem letras que seriam contadas como colunas.
    """
    limpa = []
    i = 0
    while i < len(spec):
        if spec[i] in "><@!" and i + 1 < len(spec) and spec[i + 1] == "{":
            saldo, i = 1, i + 2
            while i < len(spec) and saldo:
                saldo += {"{": 1, "}": -1}.get(spec[i], 0)
                i += 1
            continue
        limpa.append(spec[i])
        i += 1
    return len(re.findall(r"p\{[^}]*\}|[clr]", "".join(limpa).replace("|", "")))


def conferir(caminho):
    tex = io.open(caminho, encoding="utf-8").read()
    erros = []

    # ambientes balanceados
    pilha = []
    for m in re.finditer(r"\\(begin|end)\{(\w+\*?)\}", tex):
        if m.group(1) == "begin":
            pilha.append(m.group(2))
        elif not pilha or pilha[-1] != m.group(2):
            erros.append("ambiente desbalanceado: fecha %s, esperava %s"
                         % (m.group(2), pilha[-1] if pilha else "nada"))
        else:
            pilha.pop()
    if pilha:
        erros.append("ambientes abertos sem fechar: %s" % pilha)

    # chaves balanceadas, ignorando as escapadas
    saldo = 0
    i = 0
    while i < len(tex):
        c = tex[i]
        if c == BARRA and i + 1 < len(tex) and tex[i + 1] in "{}":
            i += 2
            continue
        if c == "{":
            saldo += 1
        elif c == "}":
            saldo -= 1
            if saldo < 0:
                linha = tex[:i].count("\n") + 1
                erros.append("chave fechada a mais na linha %d" % linha)
                break
        i += 1
    if saldo > 0:
        erros.append("faltam %d chaves de fechamento" % saldo)

    if tex.count("$") % 2:
        erros.append("numero impar de $ (modo matematico desbalanceado)")

    for m in re.finditer(r"(?<!" + re.escape(BARRA) + r")%", tex):
        inicio_linha = tex.rfind("\n", 0, m.start()) + 1
        if tex[inicio_linha:m.start()].strip():  # % no meio da linha, nao comentario
            linha = tex[:m.start()].count("\n") + 1
            erros.append("%% possivelmente nao escapado na linha %d" % linha)

    # colunas por linha em cada tabular; a especificacao pode conter chaves
    # aninhadas (p{4.6cm}, @{}), entao e lida contando o saldo de chaves
    for abertura in re.finditer(r"\\begin\{tabular\}\{", tex):
        i = abertura.end()
        saldo = 1
        while i < len(tex) and saldo:
            if tex[i] == "{":
                saldo += 1
            elif tex[i] == "}":
                saldo -= 1
            i += 1
        spec = tex[abertura.end():i - 1]
        fim = tex.find(r"\end{tabular}", i)
        corpo = tex[i:fim]
        n_col = contar_colunas(spec)
        for linha in corpo.split(BARRA + BARRA):
            limpa = re.sub(r"\\(top|mid|bottom)rule", "", linha).strip()
            if not limpa:
                continue
            campos = len(re.split(r"(?<!" + re.escape(BARRA) + r")&", limpa))
            extra = sum(int(x) - 1
                        for x in re.findall(r"\\multicolumn\{(\d+)\}", limpa))
            if campos + extra != n_col:
                erros.append("tabela de %d colunas com linha de %d: %s"
                             % (n_col, campos + extra, limpa[:60]))

    labels = set(re.findall(r"\\label\{([^}]*)\}", tex))
    refs = set(re.findall(r"\\ref\{([^}]*)\}", tex))
    if refs - labels:
        erros.append("referencias sem label: %s" % sorted(refs - labels))

    for arq in re.findall(r"\\includegraphics\[[^]]*\]\{([^}]*)\}", tex):
        if not os.path.exists(os.path.join(os.path.dirname(caminho), arq)):
            erros.append("figura ausente: %s" % arq)

    print("PROBLEMAS ENCONTRADOS:" if erros else "Nenhum problema encontrado.")
    for e in erros[:20]:
        print("  -", e)
    print("\n%d ambientes | %d tabelas | %d figuras | %d labels | %d refs"
          % (len(re.findall(r"\\begin\{", tex)),
             len(re.findall(r"\\begin\{tabular\}", tex)),
             len(re.findall(r"\\includegraphics", tex)),
             len(labels), len(refs)))
    return 1 if erros else 0


if __name__ == "__main__":
    sys.exit(conferir(TEX))
