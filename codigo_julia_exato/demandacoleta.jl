using JuMP, Gurobi, Random, PrettyTables

function carregar_arquivo(file_path::String)
    return readlines(file_path)
end

#Função para extrair o valor de "Id=" (ID)
function extrair_ID(dados::Vector{String})
    for linha in dados
        if occursin("Id=", linha)
            ID = parse(Int, replace(linha, r"Id=|;" => ""))
            return ID
        end
    end
    error("Id 'Id=' não encontrada.")
end


#Função para extrair o valor de "n=" (número de clientes)
function extrair_clientes(dados::Vector{String})
    for linha in dados
        if occursin("n=", linha)
            clientes = parse(Int, replace(linha, r"n=|;" => ""))
            return clientes
        end
    end
    error("Clientes 'n=' não encontrada.")
end


# Função para extrair o valor de "Q=" no arquivo
function extrair_capacidade(dados::Vector{String})
    for linha in dados
        if occursin("Q=", linha)
            capacidade = parse(Int, replace(linha, r"Q=|;" => ""))
            return capacidade
        end
    end
    error("Capacidade 'Q=' não encontrada.")
end

# Função para extrair os dados de custo do arquivo já carregado
function ler_dados_custo(dados::Vector{String})
    start_line = 0
    for (i, line) in enumerate(dados)
        if occursin("cost = #[", line)
            start_line = i
            break
        end
    end
    if start_line == 0
        error("Não foi possível encontrar a linha 'cost = #['.")
    end
    custo_raw = dados[start_line + 1:end]
    matriz_dados = []
    for linha in custo_raw
        if occursin("]", linha)
            break
        end
        linha_limpa = replace(linha, r"[<,>:]" => " ")
        valores = split(linha_limpa)
        push!(matriz_dados, (parse(Int, valores[1]), parse(Int, valores[2]), parse(Float64, valores[3])))
    end
    return matriz_dados
end

# Função para criar a matriz de custo
function criar_matriz_custo(dados_custo)
    max_nodo = maximum([maximum([i, j]) for (i, j, _) in dados_custo])
    matriz_custo = fill(0.0, max_nodo, max_nodo)
    for (i, j, valor) in dados_custo
        matriz_custo[i, j] = valor
    end
    for i in 1:max_nodo
        matriz_custo[i, i] = 0.0
    end
    return matriz_custo
end

# Função para ler a matriz de demandas do arquivo já carregado
function ler_matriz_demandas(dados::Vector{String})
    inside_demand = false
    valores = []
    for linha in dados
        if occursin("d=[", linha)
            inside_demand = true
            linha = replace(linha, "d=[" => "")
        end
        if inside_demand
            if occursin("];", linha)
                linha = replace(linha, "];" => "")
                inside_demand = false
            end
            linha_limpa = replace(linha, r"\[|\]" => "")
            if !isempty(linha_limpa)
                append!(valores, parse.(Int, split(linha_limpa, ",")))
            end
        end
    end
    n = div(length(valores), 2)
    matriz = fill(0, n, 2)
    for i in 1:length(valores)
        row = div(i-1, 2) + 1
        col = mod(i-1, 2) + 1
        matriz[row, col] = valores[i]
    end
    return matriz
end


function processar_matriz(matriz)
    # 1. Somar as colunas da matriz original
    soma_colunas = sum(matriz, dims=1)
    
    # 2. Determinar o máximo e o mínimo (diferente de zero) elemento de cada coluna
    maximos = maximum(matriz, dims=1)
    minimos = [minimum(filter(x -> x != 0, matriz[:, j])) for j in 1:size(matriz, 2)]
    
    # 3. Gerar uma matriz com valores aleatórios entre o máximo e o mínimo de cada coluna,
    # preservando zeros nas mesmas posições que a matriz original
    n_linhas, n_colunas = size(matriz)
    matriz_aleatoria = [matriz[i, j] == 0 ? 0 : rand(minimos[j]:maximos[j]) for i in 1:n_linhas, j in 1:n_colunas]
    
    # 4. Somar os termos de cada coluna da matriz aleatória
    soma_colunas_aleatoria = sum(matriz_aleatoria, dims=1)
    
    # 5. Calcular a razão entre as colunas da matriz original e da matriz aleatória
    razoes = soma_colunas ./ soma_colunas_aleatoria
    
    # 6. Criar outra matriz com os valores ajustados
    matriz_ajustada = [round(Int, razoes[j] * matriz_aleatoria[i, j]) for i in 1:n_linhas, j in 1:n_colunas]
      
    
    # 7. Ajustar o valor da primeira linha diferente de zero de cada coluna que não contém apenas zeros
    soma_colunas_ajustada = sum(matriz_ajustada, dims=1)
    
    for j in 1:n_colunas
        # Verificar se a soma da coluna é diferente
        diferenca = soma_colunas[j] - soma_colunas_ajustada[j]
        if diferenca != 0
            # Encontrar a primeira linha diferente de zero da coluna para ajustar
            for i in 1:n_linhas
                if matriz_ajustada[i, j] != 0
                    matriz_ajustada[i, j] += diferenca
                    break
                end
            end
        end
    end
    
    # Retornar a matriz final ajustada
    return matriz_ajustada
end



