using JuMP, Gurobi
include("exenew2.jl")


# Limite de tempo para processar cada arquivo (em segundos)
global timelimit = 7200




function rodar_modelo_com_arquivo(file_path::String, output_folder::String)
    dados_arquivo = carregar_arquivo(file_path)
    id = extrair_ID(dados_arquivo)
    clientes = extrair_clientes(dados_arquivo)
    capacity = extrair_capacidade(dados_arquivo)
    #Matriz custo
    dados_custo = ler_dados_custo(dados_arquivo)
    custo = criar_matriz_custo(dados_custo)
    # #Dados coleta
    demand = ler_matriz_demandas(dados_arquivo)
    coleta = processar_matriz(demand)

    demand_tri = construir_demand_tri(demand,coleta)

# ********************************************************************************************************************************************************************************************************************************* 
# *************************************************************************** SALVAMENTO DOS DADOS INICIAIS******************************************************************************************************************************************
# ********************************************************************************************************************************************************************************************************************************* 
    
    # atualizar_arquivo_dat(file_path, custo, demand, coleta, demand_tri)
    function atualizar_arquivo_dat(file_path, custo, demand, coleta, demand_tri)
        # Ler o conteúdo original do arquivo
        original_content = read(file_path, String)
        
        # Criar novo conteúdo com o conteúdo original primeiro
        novo_conteudo = original_content * "\n\n"
        
        # Adicionar o cabeçalho
        novo_conteudo *= "/*********************************************\n"
        novo_conteudo *= " * Author: Zimmermann, Julia. Carvalheira, Rafael.\n"
        novo_conteudo *= " * Creation Date: 30/01/25\n"
        novo_conteudo *= "*********************************************/\n\n"
        
        # Adicionar a mensagem de seção SCCVRPSPD antes da matriz de coleta
        novo_conteudo *= "SCCVRPSPD\n\n"
        
        # Função para formatar matrizes
        function formatar_matriz(nome, matriz)
            conteudo = "$nome = [\n"
            for linha in eachrow(matriz)
                conteudo *= join(linha, " ") * "\n"
            end
            return conteudo * "]\n\n"
        end
        
        # Adicionar matriz de coleta com novo nome "p"
        novo_conteudo *= formatar_matriz("p", coleta)
        
        # Adicionar a mensagem de seção SCCVRPSPDM-M antes da matriz 3D
        novo_conteudo *= "SCCVRPSPDM-M\n\n"
        
        # Adicionar demand_tri (matriz 3D)
        for a in 1:size(demand_tri, 1)
            for b in 1:size(demand_tri, 2)
                for c in 1:size(demand_tri, 3)
                    valor = demand_tri[a, b, c]
                    if valor != 0  # Verificação para ignorar valores zero
                        novo_conteudo *= "m2m[$a, $b, $c] = $valor\n"
                    end
                end
            end
        end
        
        # Sobrescrever o arquivo com o novo conteúdo
        write(file_path, novo_conteudo)
        println("Arquivo atualizado: $file_path")
    end
    

    atualizar_arquivo_dat(file_path, custo, demand, coleta, demand_tri)




# ********************************************************************************************************************************************************************************************************************************* 
# *************************************************************************** RODAGEM CE******************************************************************************************************************************************
# ********************************************************************************************************************************************************************************************************************************* 

    function run_CE(file)
        model = Model(Gurobi.Optimizer)
        set_optimizer_attribute(model, "TimeLimit", timelimit)
        set_optimizer_attribute(model, "MIPGap", 0.00)

        setN = 1:size(demand, 1)
        setC = ["A", "B"]
        carriers_indices = Dict("A" => 1, "B" => 2)
        carriers_indices_custo = Dict{Any, Int}()
        for i in 1:length(setN)
            carriers_indices_custo[i] = i
        end

        carriers_indices_custo["A"] = length(setN) + 1
        carriers_indices_custo["B"] = length(setN) + 2

        nos = [setN; setC]

        customer_transp_Nr = Dict()
        transp_customer_Ci = Dict()
        customerEtransp = Dict()

        for i in 1:size(demand, 2)
            customer_transp_Nr[setC[i]] = Set()
        end

        for i in 1:size(demand, 1)
            transp_customer_Ci[i] = Set()
        end

        for i in setN, j in 1:size(demand, 2)
            if demand[i, j] > 0 || coleta[i, j] > 0
                push!(customer_transp_Nr[setC[j]], i)
            end
        end

        for i in setN, j in 1:size(demand, 2)
            if demand[i, j] > 0 || coleta[i, j] > 0
                push!(transp_customer_Ci[i], setC[j])
            end
        end

        for r in setC
            customerEtransp[r] = copy(customer_transp_Nr[r])
            push!(customerEtransp[r], r)
        end

        x = @variable(model, [nos, nos, setC], Bin, base_name="x")
        z = @variable(model, [setN, setC, setC], Bin, base_name="z")
        l = @variable(model, lower_bound=0, [nos, nos, setC, setN], base_name="l")
        m = @variable(model, lower_bound=0, [nos, nos, setC, setN], base_name="m")

        c2 = @constraint(model, [i in setN, r in transp_customer_Ci[i]], 
            sum(z[i, r, s] for s in transp_customer_Ci[i]) == 1)

        c3 = @constraint(model, [i in setN, r in transp_customer_Ci[i]], 
            sum(x[i, j, r] for j in customerEtransp[r] if j != i) == 
            sum(x[j, i, r] for j in customerEtransp[r] if j != i))

        c4 = @constraint(model, [i in setN, r in transp_customer_Ci[i], s in transp_customer_Ci[i]],
            sum(x[i, j, s] for j in customerEtransp[s] if j != i) >= z[i, r, s])

        c7mod = @constraint(model, [r in setC, i in customerEtransp[r], j in customerEtransp[r]],
            sum(l[i, j, r, h] for h in customer_transp_Nr[r] if i != j) + 
            sum(m[i, j, r, h] for h in customer_transp_Nr[r] if i != j) <= 
            capacity * x[i, j, r])

        # set8 = nothing
        # for (chave, conjunto) in customer_transp_Nr
        #     if !isempty(conjunto)
        #         set8 = copy(conjunto)
        #     end
        # end

        # c8 = @constraint(model, [i in set8], 
        #     sum(x[i, j, r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j != i) == 1)

        # c8 = @constraint(model, [i in setN], 
        #     sum(x[i,j,r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j != i  ) == 1 )


        # c8 = @constraint(model, [i in customer_transp_Nr["B"]], sum(x[i,j,r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j!=i  ) == 1 )

        c8 = @constraint(model, [i in setN], sum(x[i,j,r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j!=i  ) == 1 )

        c6 = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(l[i, j, r, h] for j in customerEtransp[r] if j != i && i != h) == 
            sum(l[j, i, r, h] for j in customerEtransp[r] if j != i && i != h))

        c6linha = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(m[i, j, r, h] for j in customerEtransp[r] if j != i && i != h) == 
            sum(m[j, i, r, h] for j in customerEtransp[r] if j != i && i != h))

        c62 = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(l[i, j, r, h] for j in customerEtransp[r] if j != i && i == h) - 
            sum(l[j, i, r, h] for j in customerEtransp[r] if j != i && i == h) == 
            -sum(demand[i, carriers_indices[s]] * z[i, s, r] for s in transp_customer_Ci[i] if i == h))

        c62linha = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(m[i, j, r, h] for j in customerEtransp[r] if j != i && i == h) - 
            sum(m[j, i, r, h] for j in customerEtransp[r] if j != i && i == h) == 
            sum(coleta[i, carriers_indices[s]] * z[i, s, r] for s in transp_customer_Ci[i] if i == h))

        c5 = @constraint(model, [r in setC, h in customer_transp_Nr[r]],
            sum(l[r, j, r, h] for j in customer_transp_Nr[r]) == 
            sum(demand[h, carriers_indices[s]] * z[h, s, r] for s in transp_customer_Ci[h]))

        c5linha = @constraint(model, [r in setC, h in customer_transp_Nr[r]],
            sum(m[i, r, r, h] for i in customer_transp_Nr[r]) == 
            sum(coleta[h, carriers_indices[s]] * z[h, s, r] for s in transp_customer_Ci[h]))
            

        @objective(model, Min, 
            sum(custo[carriers_indices_custo[i], carriers_indices_custo[j]] * x[i, j, r] 
                for r in setC for i in customerEtransp[r] for j in customerEtransp[r] if i != j))

        set_optimizer_attribute(model, "OutputFlag", 1)
        optimize!(model)
        
        status = termination_status(model)
        println(file, "status= ", status)
        if status in [MOI.OPTIMAL, MOI.TIME_LIMIT]
            println(file, "ID = ", id)
            println(file, "Numero de clientes = ", clientes)
            println(file, "OF (custo) = ", objective_value(model))
            println(file, "Best Objective = ", objective_value(model))
            println(file, "Best Bound = ", objective_bound(model))
            println(file, "GAP = ", relative_gap(model) * 100, "%")
        else
            println(file, "O modelo não encontrou uma solução ótima.")
        end

        if status == MOI.TIME_LIMIT
            println(file, "O tempo limite foi atingido.")
        end

        for k in nos, i in nos, j in setC
            if value(x[k, i, j]) > 0.5 && k != i
                println("x[$k, $i, $j] = ", value(x[k, i, j]))
                println(file,"x[$k, $i, $j] = ", value(x[k, i, j]))
            end
        end
        
        for k in setN, i in setC, j in setC
            if value(z[k, i, j]) >0.5
                println("z[$k, $i, $j] = ", value(z[k, i, j]))
                println(file,"z[$k, $i, $j] = ", value(z[k, i, j]))

            end
        end 
    
    
        for k in nos, g in nos, i in setC, j in setN
            if value(l[k, g, i, j]) >0.5
                println("l[$k, $g, $i, $j] = ", value(l[k, g, i, j]))
                println(file,"l[$k, $g, $i, $j] = ", value(l[k, g, i, j]))

            end
        end 
    
        for k in nos, g in nos, i in setC, j in setN
            if value(m[k, g, i, j]) >0.5
                println("m[$k, $g, $i, $j] = ", value(m[k, g, i, j]))
                println(file,"m[$k, $g, $i, $j] = ", value(m[k, g, i, j]))

            end
        end 
    end

    base_name = basename(file_path)
    output_file = joinpath(output_folder, replace(base_name, ".dat" => "_outputCE.txt"))
    open(output_file, "w") do file
        println(file, "Processando o arquivo: $file_path")
        elapsed_time = @elapsed run_CE(file)
        println(file, "Tempo de execução: ", elapsed_time, " segundos")
        println(demand)
    end


    function run_CEc8(file)
        model = Model(Gurobi.Optimizer)
        set_optimizer_attribute(model, "TimeLimit", timelimit)
        set_optimizer_attribute(model, "MIPGap", 0.00)

        setN = 1:size(demand, 1)
        setC = ["A", "B"]
        carriers_indices = Dict("A" => 1, "B" => 2)
        carriers_indices_custo = Dict{Any, Int}()
        for i in 1:length(setN)
            carriers_indices_custo[i] = i
        end

        carriers_indices_custo["A"] = length(setN) + 1
        carriers_indices_custo["B"] = length(setN) + 2

        nos = [setN; setC]

        customer_transp_Nr = Dict()
        transp_customer_Ci = Dict()
        customerEtransp = Dict()

        for i in 1:size(demand, 2)
            customer_transp_Nr[setC[i]] = Set()
        end

        for i in 1:size(demand, 1)
            transp_customer_Ci[i] = Set()
        end

        for i in setN, j in 1:size(demand, 2)
            if demand[i, j] > 0 || coleta[i, j] > 0
                push!(customer_transp_Nr[setC[j]], i)
            end
        end

        for i in setN, j in 1:size(demand, 2)
            if demand[i, j] > 0 || coleta[i, j] > 0
                push!(transp_customer_Ci[i], setC[j])
            end
        end

        for r in setC
            customerEtransp[r] = copy(customer_transp_Nr[r])
            push!(customerEtransp[r], r)
        end

        x = @variable(model, [nos, nos, setC], Bin, base_name="x")
        z = @variable(model, [setN, setC, setC], Bin, base_name="z")
        l = @variable(model, lower_bound=0, [nos, nos, setC, setN], base_name="l")
        m = @variable(model, lower_bound=0, [nos, nos, setC, setN], base_name="m")

        c2 = @constraint(model, [i in setN, r in transp_customer_Ci[i]], 
            sum(z[i, r, s] for s in transp_customer_Ci[i]) == 1)

        c3 = @constraint(model, [i in setN, r in transp_customer_Ci[i]], 
            sum(x[i, j, r] for j in customerEtransp[r] if j != i) == 
            sum(x[j, i, r] for j in customerEtransp[r] if j != i))

        c4 = @constraint(model, [i in setN, r in transp_customer_Ci[i], s in transp_customer_Ci[i]],
            sum(x[i, j, s] for j in customerEtransp[s] if j != i) >= z[i, r, s])

        c7mod = @constraint(model, [r in setC, i in customerEtransp[r], j in customerEtransp[r]],
            sum(l[i, j, r, h] for h in customer_transp_Nr[r] if i != j) + 
            sum(m[i, j, r, h] for h in customer_transp_Nr[r] if i != j) <= 
            capacity * x[i, j, r])


        c6 = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(l[i, j, r, h] for j in customerEtransp[r] if j != i && i != h) == 
            sum(l[j, i, r, h] for j in customerEtransp[r] if j != i && i != h))

        c6linha = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(m[i, j, r, h] for j in customerEtransp[r] if j != i && i != h) == 
            sum(m[j, i, r, h] for j in customerEtransp[r] if j != i && i != h))

        c62 = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(l[i, j, r, h] for j in customerEtransp[r] if j != i && i == h) - 
            sum(l[j, i, r, h] for j in customerEtransp[r] if j != i && i == h) == 
            -sum(demand[i, carriers_indices[s]] * z[i, s, r] for s in transp_customer_Ci[i] if i == h))

        c62linha = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(m[i, j, r, h] for j in customerEtransp[r] if j != i && i == h) - 
            sum(m[j, i, r, h] for j in customerEtransp[r] if j != i && i == h) == 
            sum(coleta[i, carriers_indices[s]] * z[i, s, r] for s in transp_customer_Ci[i] if i == h))

        c5 = @constraint(model, [r in setC, h in customer_transp_Nr[r]],
            sum(l[r, j, r, h] for j in customer_transp_Nr[r]) == 
            sum(demand[h, carriers_indices[s]] * z[h, s, r] for s in transp_customer_Ci[h]))

        c5linha = @constraint(model, [r in setC, h in customer_transp_Nr[r]],
            sum(m[i, r, r, h] for i in customer_transp_Nr[r]) == 
            sum(coleta[h, carriers_indices[s]] * z[h, s, r] for s in transp_customer_Ci[h]))
            

        @objective(model, Min, 
            sum(custo[carriers_indices_custo[i], carriers_indices_custo[j]] * x[i, j, r] 
                for r in setC for i in customerEtransp[r] for j in customerEtransp[r] if i != j))

        set_optimizer_attribute(model, "OutputFlag", 1)
        optimize!(model)
        
        status = termination_status(model)
        println(file, "status= ", status)
        if status in [MOI.OPTIMAL, MOI.TIME_LIMIT]
            println(file, "ID = ", id)
            println(file, "Numero de clientes = ", clientes)
            println(file, "OF (custo) = ", objective_value(model))
            println(file, "Best Objective = ", objective_value(model))
            println(file, "Best Bound = ", objective_bound(model))
            println(file, "GAP = ", relative_gap(model) * 100, "%")
        else
            println(file, "O modelo não encontrou uma solução ótima.")
        end

        if status == MOI.TIME_LIMIT
            println(file, "O tempo limite foi atingido.")
        end

        for k in nos, i in nos, j in setC
            if value(x[k, i, j]) > 0.5 && k != i
                println("x[$k, $i, $j] = ", value(x[k, i, j]))
                println(file,"x[$k, $i, $j] = ", value(x[k, i, j]))
            end
        end
        
        for k in setN, i in setC, j in setC
            if value(z[k, i, j]) >0.5
                println("z[$k, $i, $j] = ", value(z[k, i, j]))
                println(file,"z[$k, $i, $j] = ", value(z[k, i, j]))

            end
        end 


        for k in nos, g in nos, i in setC, j in setN
            if value(l[k, g, i, j]) >0.5
                println("l[$k, $g, $i, $j] = ", value(l[k, g, i, j]))
                println(file,"l[$k, $g, $i, $j] = ", value(l[k, g, i, j]))

            end
        end 

        for k in nos, g in nos, i in setC, j in setN
            if value(m[k, g, i, j]) >0.5
                println("m[$k, $g, $i, $j] = ", value(m[k, g, i, j]))
                println(file,"m[$k, $g, $i, $j] = ", value(m[k, g, i, j]))

            end
        end 
    end

    base_name = basename(file_path)
    output_file = joinpath(output_folder, replace(base_name, ".dat" => "_outputCEc8.txt"))
    open(output_file, "w") do file
        println(file, "Processando o arquivo: $file_path")
        elapsed_time = @elapsed run_CEc8(file)
        println(file, "Tempo de execução: ", elapsed_time, " segundos")
        println(demand)
    end
    
    function run_CE_A(file)

        
        demandCE_A = demand[:, :]
        demandCE_A[:, 2] .= 0

        coletaCE_A = coleta[:, :]
        coletaCE_A[:, 2] .= 0

        # demand_triA = demand_tri[:, :, :]

        # demand_triA[:,:,2].= 0
        model = Model(Gurobi.Optimizer)
        set_optimizer_attribute(model, "TimeLimit", timelimit)
        set_optimizer_attribute(model, "MIPGap", 0.00)

        setN = 1:size(demand, 1)
        setC = ["A", "B"]
        carriers_indices = Dict("A" => 1, "B" => 2)
        carriers_indices_custo = Dict{Any, Int}()
        for i in 1:length(setN)
            carriers_indices_custo[i] = i
        end

        carriers_indices_custo["A"] = length(setN) + 1
        carriers_indices_custo["B"] = length(setN) + 2

        nos = [setN; setC]

        customer_transp_Nr = Dict()
        transp_customer_Ci = Dict()
        customerEtransp = Dict()

        for i in 1:size(demand, 2)
            customer_transp_Nr[setC[i]] = Set()
        end

        for i in 1:size(demand, 1)
            transp_customer_Ci[i] = Set()
        end

        for i in setN, j in 1:size(demand, 2)
            if demandCE_A[i, j] > 0 || coletaCE_A[i, j] > 0
                push!(customer_transp_Nr[setC[j]], i)
            end
        end

        for i in setN, j in 1:size(demand, 2)
            if demandCE_A[i, j] > 0 || coletaCE_A[i, j] > 0
                push!(transp_customer_Ci[i], setC[j])
            end
        end

        for r in setC
            customerEtransp[r] = copy(customer_transp_Nr[r])
            push!(customerEtransp[r], r)
        end

        x = @variable(model, [nos, nos, setC], Bin, base_name="x")
        z = @variable(model, [setN, setC, setC], Bin, base_name="z")
        l = @variable(model, lower_bound=0, [nos, nos, setC, setN], base_name="l")
        m = @variable(model, lower_bound=0, [nos, nos, setC, setN], base_name="m")

        c2 = @constraint(model, [i in setN, r in transp_customer_Ci[i]], 
            sum(z[i, r, s] for s in transp_customer_Ci[i]) == 1)

        c3 = @constraint(model, [i in setN, r in transp_customer_Ci[i]], 
            sum(x[i, j, r] for j in customerEtransp[r] if j != i) == 
            sum(x[j, i, r] for j in customerEtransp[r] if j != i))

        c4 = @constraint(model, [i in setN, r in transp_customer_Ci[i], s in transp_customer_Ci[i]],
            sum(x[i, j, s] for j in customerEtransp[s] if j != i) >= z[i, r, s])

        c7mod = @constraint(model, [r in setC, i in customerEtransp[r], j in customerEtransp[r]],
            sum(l[i, j, r, h] for h in customer_transp_Nr[r] if i != j) + 
            sum(m[i, j, r, h] for h in customer_transp_Nr[r] if i != j) <= 
            capacity * x[i, j, r])

        # set8 = nothing
        # for (chave, conjunto) in customer_transp_Nr
        #     if !isempty(conjunto)
        #         set8 = copy(conjunto)
        #     end
        # end

        # c8 = @constraint(model, [i in set8], 
        #     sum(x[i, j, r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j != i) == 1)

        # c8 = @constraint(model, [i in setN], 
        #     sum(x[i,j,r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j != i  ) == 1 )


        c8 = @constraint(model, [i in customer_transp_Nr["A"]], sum(x[i,j,r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j!=i  ) == 1 )

        # c8 = @constraint(model, [i in setN], sum(x[i,j,r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j!=i  ) == 1 )

        c6 = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(l[i, j, r, h] for j in customerEtransp[r] if j != i && i != h) == 
            sum(l[j, i, r, h] for j in customerEtransp[r] if j != i && i != h))

        c6linha = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(m[i, j, r, h] for j in customerEtransp[r] if j != i && i != h) == 
            sum(m[j, i, r, h] for j in customerEtransp[r] if j != i && i != h))

        c62 = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(l[i, j, r, h] for j in customerEtransp[r] if j != i && i == h) - 
            sum(l[j, i, r, h] for j in customerEtransp[r] if j != i && i == h) == 
            -sum(demand[i, carriers_indices[s]] * z[i, s, r] for s in transp_customer_Ci[i] if i == h))

        c62linha = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(m[i, j, r, h] for j in customerEtransp[r] if j != i && i == h) - 
            sum(m[j, i, r, h] for j in customerEtransp[r] if j != i && i == h) == 
            sum(coleta[i, carriers_indices[s]] * z[i, s, r] for s in transp_customer_Ci[i] if i == h))

        c5 = @constraint(model, [r in setC, h in customer_transp_Nr[r]],
            sum(l[r, j, r, h] for j in customer_transp_Nr[r]) == 
            sum(demand[h, carriers_indices[s]] * z[h, s, r] for s in transp_customer_Ci[h]))

        c5linha = @constraint(model, [r in setC, h in customer_transp_Nr[r]],
            sum(m[i, r, r, h] for i in customer_transp_Nr[r]) == 
            sum(coleta[h, carriers_indices[s]] * z[h, s, r] for s in transp_customer_Ci[h]))
            

        @objective(model, Min, 
            sum(custo[carriers_indices_custo[i], carriers_indices_custo[j]] * x[i, j, r] 
                for r in setC for i in customerEtransp[r] for j in customerEtransp[r] if i != j))

        set_optimizer_attribute(model, "OutputFlag", 1)
        optimize!(model)
        
        status = termination_status(model)
        println(file, "status= ", status)
        if status in [MOI.OPTIMAL, MOI.TIME_LIMIT]
            println(file, "ID = ", id)
            println(file, "Numero de clientes = ", clientes)
            println(file, "OF (custo) = ", objective_value(model))
            println(file, "Best Objective = ", objective_value(model))
            println(file, "Best Bound = ", objective_bound(model))
            println(file, "GAP = ", relative_gap(model) * 100, "%")
        else
            println(file, "O modelo não encontrou uma solução ótima.")
        end

        if status == MOI.TIME_LIMIT
            println(file, "O tempo limite foi atingido.")
        end

        for k in nos, i in nos, j in setC
            if value(x[k, i, j]) > 0.5 && k != i
                println("x[$k, $i, $j] = ", value(x[k, i, j]))
                println(file,"x[$k, $i, $j] = ", value(x[k, i, j]))
            end
        end
        
        for k in setN, i in setC, j in setC
            if value(z[k, i, j]) >0.5
                println("z[$k, $i, $j] = ", value(z[k, i, j]))
                println(file,"z[$k, $i, $j] = ", value(z[k, i, j]))

            end
        end 


        for k in nos, g in nos, i in setC, j in setN
            if value(l[k, g, i, j]) >0.5
                println("l[$k, $g, $i, $j] = ", value(l[k, g, i, j]))
                println(file,"l[$k, $g, $i, $j] = ", value(l[k, g, i, j]))

            end
        end 

        for k in nos, g in nos, i in setC, j in setN
            if value(m[k, g, i, j]) >0.5
                println("m[$k, $g, $i, $j] = ", value(m[k, g, i, j]))
                println(file,"m[$k, $g, $i, $j] = ", value(m[k, g, i, j]))

            end
        end 
    end

    base_name = basename(file_path)
    output_file = joinpath(output_folder, replace(base_name, ".dat" => "_outputCE_A.txt"))
    open(output_file, "w") do file
        println(file, "Processando o arquivo: $file_path")
        elapsed_time = @elapsed run_CE_A(file)
        println(file, "Tempo de execução: ", elapsed_time, " segundos")
        println(demand)
    end
   
    
    function run_CE_B(file)

        
        demandCE_B = demand[:, :]
        demandCE_B[:, 1] .= 0

        coletaCE_B = coleta[:, :]
        coletaCE_B[:, 1] .= 0

        # demand_triA = demand_tri[:, :, :]

        # demand_triA[:,:,2].= 0
        model = Model(Gurobi.Optimizer)
        set_optimizer_attribute(model, "TimeLimit", timelimit)
        set_optimizer_attribute(model, "MIPGap", 0.00)

        setN = 1:size(demand, 1)
        setC = ["A", "B"]
        carriers_indices = Dict("A" => 1, "B" => 2)
        carriers_indices_custo = Dict{Any, Int}()
        for i in 1:length(setN)
            carriers_indices_custo[i] = i
        end

        carriers_indices_custo["A"] = length(setN) + 1
        carriers_indices_custo["B"] = length(setN) + 2

        nos = [setN; setC]

        customer_transp_Nr = Dict()
        transp_customer_Ci = Dict()
        customerEtransp = Dict()

        for i in 1:size(demand, 2)
            customer_transp_Nr[setC[i]] = Set()
        end

        for i in 1:size(demand, 1)
            transp_customer_Ci[i] = Set()
        end

        for i in setN, j in 1:size(demand, 2)
            if demandCE_B[i, j] > 0 || coletaCE_B[i, j] > 0
                push!(customer_transp_Nr[setC[j]], i)
            end
        end

        for i in setN, j in 1:size(demand, 2)
            if demandCE_B[i, j] > 0 || coletaCE_B[i, j] > 0
                push!(transp_customer_Ci[i], setC[j])
            end
        end

        for r in setC
            customerEtransp[r] = copy(customer_transp_Nr[r])
            push!(customerEtransp[r], r)
        end

        x = @variable(model, [nos, nos, setC], Bin, base_name="x")
        z = @variable(model, [setN, setC, setC], Bin, base_name="z")
        l = @variable(model, lower_bound=0, [nos, nos, setC, setN], base_name="l")
        m = @variable(model, lower_bound=0, [nos, nos, setC, setN], base_name="m")

        c2 = @constraint(model, [i in setN, r in transp_customer_Ci[i]], 
            sum(z[i, r, s] for s in transp_customer_Ci[i]) == 1)

        c3 = @constraint(model, [i in setN, r in transp_customer_Ci[i]], 
            sum(x[i, j, r] for j in customerEtransp[r] if j != i) == 
            sum(x[j, i, r] for j in customerEtransp[r] if j != i))

        c4 = @constraint(model, [i in setN, r in transp_customer_Ci[i], s in transp_customer_Ci[i]],
            sum(x[i, j, s] for j in customerEtransp[s] if j != i) >= z[i, r, s])

        c7mod = @constraint(model, [r in setC, i in customerEtransp[r], j in customerEtransp[r]],
            sum(l[i, j, r, h] for h in customer_transp_Nr[r] if i != j) + 
            sum(m[i, j, r, h] for h in customer_transp_Nr[r] if i != j) <= 
            capacity * x[i, j, r])

        # set8 = nothing
        # for (chave, conjunto) in customer_transp_Nr
        #     if !isempty(conjunto)
        #         set8 = copy(conjunto)
        #     end
        # end

        # c8 = @constraint(model, [i in set8], 
        #     sum(x[i, j, r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j != i) == 1)

        # c8 = @constraint(model, [i in setN], 
        #     sum(x[i,j,r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j != i  ) == 1 )


        c8 = @constraint(model, [i in customer_transp_Nr["B"]], sum(x[i,j,r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j!=i  ) == 1 )

        # c8 = @constraint(model, [i in setN], sum(x[i,j,r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j!=i  ) == 1 )

        c6 = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(l[i, j, r, h] for j in customerEtransp[r] if j != i && i != h) == 
            sum(l[j, i, r, h] for j in customerEtransp[r] if j != i && i != h))

        c6linha = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(m[i, j, r, h] for j in customerEtransp[r] if j != i && i != h) == 
            sum(m[j, i, r, h] for j in customerEtransp[r] if j != i && i != h))

        c62 = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(l[i, j, r, h] for j in customerEtransp[r] if j != i && i == h) - 
            sum(l[j, i, r, h] for j in customerEtransp[r] if j != i && i == h) == 
            -sum(demand[i, carriers_indices[s]] * z[i, s, r] for s in transp_customer_Ci[i] if i == h))

        c62linha = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(m[i, j, r, h] for j in customerEtransp[r] if j != i && i == h) - 
            sum(m[j, i, r, h] for j in customerEtransp[r] if j != i && i == h) == 
            sum(coleta[i, carriers_indices[s]] * z[i, s, r] for s in transp_customer_Ci[i] if i == h))

        c5 = @constraint(model, [r in setC, h in customer_transp_Nr[r]],
            sum(l[r, j, r, h] for j in customer_transp_Nr[r]) == 
            sum(demand[h, carriers_indices[s]] * z[h, s, r] for s in transp_customer_Ci[h]))

        c5linha = @constraint(model, [r in setC, h in customer_transp_Nr[r]],
            sum(m[i, r, r, h] for i in customer_transp_Nr[r]) == 
            sum(coleta[h, carriers_indices[s]] * z[h, s, r] for s in transp_customer_Ci[h]))
            

        @objective(model, Min, 
            sum(custo[carriers_indices_custo[i], carriers_indices_custo[j]] * x[i, j, r] 
                for r in setC for i in customerEtransp[r] for j in customerEtransp[r] if i != j))

        set_optimizer_attribute(model, "OutputFlag", 1)
        optimize!(model)
        
        status = termination_status(model)
        println(file, "status= ", status)
        if status in [MOI.OPTIMAL, MOI.TIME_LIMIT]
            println(file, "ID = ", id)
            println(file, "Numero de clientes = ", clientes)
            println(file, "OF (custo) = ", objective_value(model))
            println(file, "Best Objective = ", objective_value(model))
            println(file, "Best Bound = ", objective_bound(model))
            println(file, "GAP = ", relative_gap(model) * 100, "%")
        else
            println(file, "O modelo não encontrou uma solução ótima.")
        end

        if status == MOI.TIME_LIMIT
            println(file, "O tempo limite foi atingido.")
        end

        for k in nos, i in nos, j in setC
            if value(x[k, i, j]) > 0.5 && k != i
                println("x[$k, $i, $j] = ", value(x[k, i, j]))
                println(file,"x[$k, $i, $j] = ", value(x[k, i, j]))
            end
        end
        
        for k in setN, i in setC, j in setC
            if value(z[k, i, j]) >0.5
                println("z[$k, $i, $j] = ", value(z[k, i, j]))
                println(file,"z[$k, $i, $j] = ", value(z[k, i, j]))

            end
        end 


        for k in nos, g in nos, i in setC, j in setN
            if value(l[k, g, i, j]) >0.5
                println("l[$k, $g, $i, $j] = ", value(l[k, g, i, j]))
                println(file,"l[$k, $g, $i, $j] = ", value(l[k, g, i, j]))

            end
        end 

        for k in nos, g in nos, i in setC, j in setN
            if value(m[k, g, i, j]) >0.5
                println("m[$k, $g, $i, $j] = ", value(m[k, g, i, j]))
                println(file,"m[$k, $g, $i, $j] = ", value(m[k, g, i, j]))

            end
        end 
    end

    base_name = basename(file_path)
    output_file = joinpath(output_folder, replace(base_name, ".dat" => "_outputCE_B.txt"))
    open(output_file, "w") do file
        println(file, "Processando o arquivo: $file_path")
        elapsed_time = @elapsed run_CE_B(file)
        println(file, "Tempo de execução: ", elapsed_time, " segundos")
        println(demand)
    end

    # Interrompe aqui para executar apenas as rotinas CE solicitadas.
    return nothing


 
    
        
# ********************************************************************************************************************************************************************************************************************************* 
# *************************************************************************** RODAGEM M2M******************************************************************************************************************************************
# ********************************************************************************************************************************************************************************************************************************* 




    
    function runM2M(file)
        model = Model(Gurobi.Optimizer)
        set_optimizer_attribute(model, "TimeLimit", timelimit)
        set_optimizer_attribute(model, "MIPGap", 0.00)
     
    
        # Acessando os dados    
    
        num_clients = size(demand_tri, 1)-1
        num_carriers = size(demand_tri, 3)

        
        #sets
        setN = 1:num_clients
        setC = ["A","B"]
        carriers_indices = Dict("A" => 1, "B" => 2)
        carriers_indices_custo = Dict{Any, Int}()
        for i in 1:num_clients
            carriers_indices_custo[i] = i
        end
    
    
        carriers_indices_custo["A"] = num_clients+1
        carriers_indices_custo["B"] = num_clients+2
        nos = [setN; setC]     
    
        customer_transp_Nr = Dict() #conjunto Nr - possui só dois subconjuntos, r e s
        transp_customer_Ci = Dict() #conjunto Ci - possui i subconjuntos
        customerEtransp = Dict()
    
        for i in 1:num_carriers
            customer_transp_Nr[setC[i]] = Set() #criando conjuntos vazios
        end
    
        for i in 1:num_clients
            transp_customer_Ci[i] = Set() #criando conjuntos vazios
        end
    
        # for i in setN, j in 1:size(demand_tri, 1), k in 1:num_carriers
        #     if demand_tri[i,j,k]>0  
        #         push!(customer_transp_Nr[setC[k]],i) #indices dos clientes que possuem demanda>0 da transp C
        #     end
        # end
    
        for i in setN, k in 1:num_carriers
            if sum(demand_tri[i,:,k])>0  || sum(demand_tri[:,i,k])>0
                push!(customer_transp_Nr[setC[k]],i) #indices dos clientes que possuem demanda>0 da transp C
            end
        end
        #   println(customer_transp_Nr)
             
        #indices das transp C que possuem clientes N com demanda>0
        for i in setN, k in 1:num_carriers
            if sum(demand_tri[i,:,k])>0  || sum(demand_tri[:,i,k])>0
                push!(transp_customer_Ci[i],setC[k]) #indices das transp C que possuem clientes N com demanda>0
            end
        end
     #   println(transp_customer_Ci)
    
    
       # println(transp_customer_Ci)
        for r in setC
            customerEtransp[r] = copy(customer_transp_Nr[r]) # cópia do conjunto de clientes para evitar alteração
            push!(customerEtransp[r], r) # Adicionando a transportadora como um elemento adicional
        end
    
    
        #variables
        x = @variable(model, [nos, nos, setC], Bin, base_name="x")
        z = @variable(model, [setN, setC, setC], Bin, base_name="z")
        l = @variable(model, lower_bound=0, [nos, nos, setC, setN], base_name="l")
        m = @variable(model, lower_bound=0, [nos, nos, setC, setN], base_name="m")
        n = @variable(model, lower_bound=0, [setN, setN, setC, setN], base_name="n")
    
        #constraint
      
        c2 = @constraint(model,[i in setN, r in transp_customer_Ci[i]], sum(z[i,r,s] for s in transp_customer_Ci[i] ) == 1 ) #check
        
        c3 = @constraint(model, [i in setN, r in transp_customer_Ci[i]], sum(x[i,j,r] for j in customerEtransp[r] if j!=i) == 
        sum(x[j,i,r] for j in customerEtransp[r] if j!=i)) #check
       
        c4 = @constraint(model, [i in setN, r in transp_customer_Ci[i], s in transp_customer_Ci[i]],
        sum(x[i,j,s] for j in customerEtransp[s] if j!=i) >= z[i,r,s]) #check
    
         
        c5_transportadoras = @constraint(model, [r in setC, h in customer_transp_Nr[r]], sum(l[r,j,r,h] for j in customer_transp_Nr[r]) == 
        sum(demand_tri[h,num_clients+1,carriers_indices[s]]*z[h,s,r] for s in transp_customer_Ci[h] ))    #somando toda a ultima coluna
    
       #println(c5_transportadoras)
        
        c5linha_transportadoras = @constraint(model, [r in setC, h in customer_transp_Nr[r]], sum(m[i,r,r,h] for i in customer_transp_Nr[r]) == 
        sum(demand_tri[num_clients+1,h,carriers_indices[s]]*z[h,s,r] for s in transp_customer_Ci[h])) #somando toda a ultima linha
    
     
        c6_l = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
        sum(l[i,j,r,h] for j in customerEtransp[r] if j!=i && i!=h)  == 
        sum(l[j,i,r,h] for j in customerEtransp[r] if j!=i && i!=h)) 
     
        c6linha_m = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
        sum(m[i,j,r,h] for j in customerEtransp[r] if j!=i && i!=h)   == 
        sum(m[j,i,r,h] for j in customerEtransp[r] if j!=i && i!=h))
        
         
     
    
        c62 = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
        sum(l[i,j,r,h] for j in customerEtransp[r] if j!=i && i==h) -
        sum(l[j,i,r,h] for j in customerEtransp[r] if j!=i && i==h) ==
        - sum(demand_tri[i,num_clients+1,carriers_indices[s]]*z[i,s,r] for s in transp_customer_Ci[i] if i==h ) )
    
        c62linha_m = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
        sum(m[i,j,r,h] for j in customerEtransp[r] if j!=i && i==h) -
        sum(m[j,i,r,h] for j in customerEtransp[r] if j!=i && i==h) ==
        sum(demand_tri[num_clients+1,i,carriers_indices[s]]*z[i,s,r] for s in transp_customer_Ci[i]  if i==h ))
       
        c6linha_n = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]], ##restrição das COLETAS
        sum(n[i,j,r,h] for j in customer_transp_Nr[r] if j!=i && i!=h)  - 
        sum(n[j,i,r,h] for j in customer_transp_Nr[r] if j!=i && i!=h) ==
        sum(demand_tri[h,i,carriers_indices[s]]*z[i,s,r] for s in transp_customer_Ci[i] if i!=h )) #restrição somando linha ou coluna

       
    
        c62linha_n = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]], ##restrição das ENTREGAS
        sum(n[i,j,r,h] for j in customer_transp_Nr[r] if j!=i && i==h) -
        sum(n[j,i,r,h] for j in customer_transp_Nr[r] if j!=i && i==h) == -
        sum(demand_tri[h,j,carriers_indices[s]]*z[i,s,r] for s in transp_customer_Ci[i] for j in 1:num_clients if i==h ))
    
 
     
        c7mod_l = @constraint(model, [r in setC, j in customer_transp_Nr[r]], 
        sum(l[r,j,r,h] for h in customer_transp_Nr[r] )  <= capacity*x[r,j,r])
     
        c7mod_m = @constraint(model, [r in setC, i in customer_transp_Nr[r]], 
        sum(m[i,r,r,h] for h in customer_transp_Nr[r] )  <= capacity*x[i,r,r])
          
        c7mod_n = @constraint(model, [r in setC, i in customer_transp_Nr[r], j in customer_transp_Nr[r]], 
        sum(l[i,j,r,h] for h in customer_transp_Nr[r] if i!=j) + sum(m[i,j,r,h] for h in customer_transp_Nr[r] if i!=j)  
        + sum(n[i,j,r,h] for h in customer_transp_Nr[r] if i!=j) <= capacity*x[i,j,r]) #check
      
      #println(customer_transp_Nr["A"])
        c8 = @constraint(model, [i in setN], sum(x[i,j,r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j!=i  ) == 1 )
        #println(c8)
        #c8 = @constraint(model, [i in customer_transp_Nr["B"]], sum(x[i,j,r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j!=i  ) == 1 )
        
    
        ##ATENÇÃO: AS MATRIZES COLETA E DEMANDA ESTÃO NUMERADAS DE 1 ATÉ CLIENTES + 1, SENDO A ÚLTIMA LINHA/COLUNA A Transportadora
    
       #objectivee function
        @objective(model, Min, sum(custo[carriers_indices_custo[i],carriers_indices_custo[j]]*x[i,j,r] for r in setC for i in customerEtransp[r] for j in customerEtransp[r] if i!=j))
        
        #Optimizer
        set_optimizer_attribute(model,"OutputFlag", 1)
        optimize!(model)
    
       
        #print solution
        # Solução adaptada para seguir o estilo do primeiro código
        status = termination_status(model)
        println(file, "status= ", status)

        if status in [MOI.OPTIMAL, MOI.TIME_LIMIT]
            println(file, "ID = ", id)
            println(file, "Numero de clientes = ", clientes)
            if has_values(model)
                println(file, "OF (custo) = ", objective_value(model))
                println(file, "Best Objective = ", objective_value(model))
                println(file, "Best Bound = ", objective_bound(model))
                println(file, "GAP = ", relative_gap(model) * 100, "%")
            else
                println(file, "Nenhuma solução viável foi encontrada.")
            end
        else
            println(file, "O modelo não encontrou uma solução ótima ou viável.")
        end

        if status == MOI.TIME_LIMIT
            println(file, "O tempo limite foi atingido.")
        elseif status == MOI.INFEASIBLE
            println(file, "O modelo é inviável. Verifique as restrições ou os dados de entrada.")
        end

        if has_values(model)
            for k in nos, i in nos, j in setC
                if value(x[k, i, j]) > 0.5 && k != i
                    println("x[$k, $i, $j] = ", value(x[k, i, j]))
                    println(file, "x[$k, $i, $j] = ", value(x[k, i, j]))
                end
            end
        
            for k in nos, g in nos, i in setC, j in setN
                if value(l[k, g, i, j]) > 0.5
                    println("l[$k, $g, $i, $j] = ", value(l[k, g, i, j]))
                    println(file, "l[$k, $g, $i, $j] = ", value(l[k, g, i, j]))
                end
            end
        
            for k in nos, g in nos, i in setC, j in setN
                if value(m[k, g, i, j]) > 0.5
                    println("m[$k, $g, $i, $j] = ", value(m[k, g, i, j]))
                    println(file, "m[$k, $g, $i, $j] = ", value(m[k, g, i, j]))
                end
            end
            for k in setN, g in setN, i in setC, j in setN
                if value(n[k, g, i, j]) > 0.5
                    println("n[$k, $g, $i, $j] = ", value(n[k, g, i, j]))
                    println(file, "n[$k, $g, $i, $j] = ", value(n[k, g, i, j]))
                end
            end
        else
            println(file, "Nenhuma solução parcial disponível para as variáveis.")
        end


    end

    base_name = basename(file_path)
    output_file = joinpath(output_folder, replace(base_name, ".dat" => "_outputM2M.txt"))
    open(output_file, "w") do file
        println(file, "Processando o arquivo: $file_path")
        elapsed_time = @elapsed (runM2M(file))
        println(file, "Tempo de execução: ", elapsed_time, " segundos")
    end

    function run_c8(file)
            model = Model(Gurobi.Optimizer)
            set_optimizer_attribute(model, "TimeLimit", timelimit)
            set_optimizer_attribute(model, "MIPGap", 0.00)
        
        
            # Acessando os dados    
        
            num_clients = size(demand_tri, 1)-1
            num_carriers = size(demand_tri, 3)

            
            #sets
            setN = 1:num_clients
            setC = ["A","B"]
            carriers_indices = Dict("A" => 1, "B" => 2)
            carriers_indices_custo = Dict{Any, Int}()
            for i in 1:num_clients
                carriers_indices_custo[i] = i
            end
        
        
            carriers_indices_custo["A"] = num_clients+1
            carriers_indices_custo["B"] = num_clients+2
            nos = [setN; setC]     
        
            customer_transp_Nr = Dict() #conjunto Nr - possui só dois subconjuntos, r e s
            transp_customer_Ci = Dict() #conjunto Ci - possui i subconjuntos
            customerEtransp = Dict()
        
            for i in 1:num_carriers
                customer_transp_Nr[setC[i]] = Set() #criando conjuntos vazios
            end
        
            for i in 1:num_clients
                transp_customer_Ci[i] = Set() #criando conjuntos vazios
            end
        
            # for i in setN, j in 1:size(demand_tri, 1), k in 1:num_carriers
            #     if demand_tri[i,j,k]>0  
            #         push!(customer_transp_Nr[setC[k]],i) #indices dos clientes que possuem demanda>0 da transp C
            #     end
            # end
        
            for i in setN, k in 1:num_carriers
                if sum(demand_tri[i,:,k])>0  || sum(demand_tri[:,i,k])>0
                    push!(customer_transp_Nr[setC[k]],i) #indices dos clientes que possuem demanda>0 da transp C
                end
            end
            #   println(customer_transp_Nr)
                
            #indices das transp C que possuem clientes N com demanda>0
            for i in setN, k in 1:num_carriers
                if sum(demand_tri[i,:,k])>0  || sum(demand_tri[:,i,k])>0
                    push!(transp_customer_Ci[i],setC[k]) #indices das transp C que possuem clientes N com demanda>0
                end
            end
        #   println(transp_customer_Ci)
        
        
        # println(transp_customer_Ci)
            for r in setC
                customerEtransp[r] = copy(customer_transp_Nr[r]) # cópia do conjunto de clientes para evitar alteração
                push!(customerEtransp[r], r) # Adicionando a transportadora como um elemento adicional
            end
        
        
            #variables
            x = @variable(model, [nos, nos, setC], Bin, base_name="x")
            z = @variable(model, [setN, setC, setC], Bin, base_name="z")
            l = @variable(model, lower_bound=0, [nos, nos, setC, setN], base_name="l")
            m = @variable(model, lower_bound=0, [nos, nos, setC, setN], base_name="m")
            n = @variable(model, lower_bound=0, [setN, setN, setC, setN], base_name="n")
        
            #constraint
        
            c2 = @constraint(model,[i in setN, r in transp_customer_Ci[i]], sum(z[i,r,s] for s in transp_customer_Ci[i] ) == 1 ) #check
            
            c3 = @constraint(model, [i in setN, r in transp_customer_Ci[i]], sum(x[i,j,r] for j in customerEtransp[r] if j!=i) == 
            sum(x[j,i,r] for j in customerEtransp[r] if j!=i)) #check
        
            c4 = @constraint(model, [i in setN, r in transp_customer_Ci[i], s in transp_customer_Ci[i]],
            sum(x[i,j,s] for j in customerEtransp[s] if j!=i) >= z[i,r,s]) #check
        
            
            c5_transportadoras = @constraint(model, [r in setC, h in customer_transp_Nr[r]], sum(l[r,j,r,h] for j in customer_transp_Nr[r]) == 
            sum(demand_tri[h,num_clients+1,carriers_indices[s]]*z[h,s,r] for s in transp_customer_Ci[h] ))    #somando toda a ultima coluna
        
        #println(c5_transportadoras)
            
            c5linha_transportadoras = @constraint(model, [r in setC, h in customer_transp_Nr[r]], sum(m[i,r,r,h] for i in customer_transp_Nr[r]) == 
            sum(demand_tri[num_clients+1,h,carriers_indices[s]]*z[h,s,r] for s in transp_customer_Ci[h])) #somando toda a ultima linha
        
        
            c6_l = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(l[i,j,r,h] for j in customerEtransp[r] if j!=i && i!=h)  == 
            sum(l[j,i,r,h] for j in customerEtransp[r] if j!=i && i!=h)) 
        
            c6linha_m = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(m[i,j,r,h] for j in customerEtransp[r] if j!=i && i!=h)   == 
            sum(m[j,i,r,h] for j in customerEtransp[r] if j!=i && i!=h))
            
            
        
        
            c62 = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(l[i,j,r,h] for j in customerEtransp[r] if j!=i && i==h) -
            sum(l[j,i,r,h] for j in customerEtransp[r] if j!=i && i==h) ==
            - sum(demand_tri[i,num_clients+1,carriers_indices[s]]*z[i,s,r] for s in transp_customer_Ci[i] if i==h ) )
        
            c62linha_m = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(m[i,j,r,h] for j in customerEtransp[r] if j!=i && i==h) -
            sum(m[j,i,r,h] for j in customerEtransp[r] if j!=i && i==h) ==
            sum(demand_tri[num_clients+1,i,carriers_indices[s]]*z[i,s,r] for s in transp_customer_Ci[i]  if i==h ))
        
            c6linha_n = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]], ##restrição das COLETAS
            sum(n[i,j,r,h] for j in customer_transp_Nr[r] if j!=i && i!=h)  - 
            sum(n[j,i,r,h] for j in customer_transp_Nr[r] if j!=i && i!=h) ==
            sum(demand_tri[h,i,carriers_indices[s]]*z[i,s,r] for s in transp_customer_Ci[i] if i!=h )) #restrição somando linha ou coluna

        
        
            c62linha_n = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]], ##restrição das ENTREGAS
            sum(n[i,j,r,h] for j in customer_transp_Nr[r] if j!=i && i==h) -
            sum(n[j,i,r,h] for j in customer_transp_Nr[r] if j!=i && i==h) == -
            sum(demand_tri[h,j,carriers_indices[s]]*z[i,s,r] for s in transp_customer_Ci[i] for j in 1:num_clients if i==h ))
        
    
        
            c7mod_l = @constraint(model, [r in setC, j in customer_transp_Nr[r]], 
            sum(l[r,j,r,h] for h in customer_transp_Nr[r] )  <= capacity*x[r,j,r])
        
            c7mod_m = @constraint(model, [r in setC, i in customer_transp_Nr[r]], 
            sum(m[i,r,r,h] for h in customer_transp_Nr[r] )  <= capacity*x[i,r,r])
            
            c7mod_n = @constraint(model, [r in setC, i in customer_transp_Nr[r], j in customer_transp_Nr[r]], 
            sum(l[i,j,r,h] for h in customer_transp_Nr[r] if i!=j) + sum(m[i,j,r,h] for h in customer_transp_Nr[r] if i!=j)  
            + sum(n[i,j,r,h] for h in customer_transp_Nr[r] if i!=j) <= capacity*x[i,j,r]) #check
        
        #println(customer_transp_Nr["A"])
            # c8 = @constraint(model, [i in setN], sum(x[i,j,r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j!=i  ) == 1 )
            #println(c8)
            #c8 = @constraint(model, [i in customer_transp_Nr["B"]], sum(x[i,j,r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j!=i  ) == 1 )
            
        
            ##ATENÇÃO: AS MATRIZES COLETA E DEMANDA ESTÃO NUMERADAS DE 1 ATÉ CLIENTES + 1, SENDO A ÚLTIMA LINHA/COLUNA A Transportadora
        
        #objectivee function
            @objective(model, Min, sum(custo[carriers_indices_custo[i],carriers_indices_custo[j]]*x[i,j,r] for r in setC for i in customerEtransp[r] for j in customerEtransp[r] if i!=j))
            
            #Optimizer
            set_optimizer_attribute(model,"OutputFlag", 1)
            optimize!(model)
        
        
            #print solution
            # Solução adaptada para seguir o estilo do primeiro código
            status = termination_status(model)
            println(file, "status= ", status)

            if status in [MOI.OPTIMAL, MOI.TIME_LIMIT]
                println(file, "ID = ", id)
                println(file, "Numero de clientes = ", clientes)
                if has_values(model)
                    println(file, "OF (custo) = ", objective_value(model))
                    println(file, "Best Objective = ", objective_value(model))
                    println(file, "Best Bound = ", objective_bound(model))
                    println(file, "GAP = ", relative_gap(model) * 100, "%")
                else
                    println(file, "Nenhuma solução viável foi encontrada.")
                end
            else
                println(file, "O modelo não encontrou uma solução ótima ou viável.")
            end

            if status == MOI.TIME_LIMIT
                println(file, "O tempo limite foi atingido.")
            elseif status == MOI.INFEASIBLE
                println(file, "O modelo é inviável. Verifique as restrições ou os dados de entrada.")
            end

            if has_values(model)
                for k in nos, i in nos, j in setC
                    if value(x[k, i, j]) > 0.5 && k != i
                        println("x[$k, $i, $j] = ", value(x[k, i, j]))
                        println(file, "x[$k, $i, $j] = ", value(x[k, i, j]))
                    end
                end
            
                for k in nos, g in nos, i in setC, j in setN
                    if value(l[k, g, i, j]) > 0.5
                        println("l[$k, $g, $i, $j] = ", value(l[k, g, i, j]))
                        println(file, "l[$k, $g, $i, $j] = ", value(l[k, g, i, j]))
                    end
                end
            
                for k in nos, g in nos, i in setC, j in setN
                    if value(m[k, g, i, j]) > 0.5
                        println("m[$k, $g, $i, $j] = ", value(m[k, g, i, j]))
                        println(file, "m[$k, $g, $i, $j] = ", value(m[k, g, i, j]))
                    end
                end
                for k in setN, g in setN, i in setC, j in setN
                    if value(n[k, g, i, j]) > 0.5
                        println("n[$k, $g, $i, $j] = ", value(n[k, g, i, j]))
                        println(file, "n[$k, $g, $i, $j] = ", value(n[k, g, i, j]))
                    end
                end
            else
                println(file, "Nenhuma solução parcial disponível para as variáveis.")
            end


        end

        base_name = basename(file_path)
        output_file = joinpath(output_folder, replace(base_name, ".dat" => "_output8m2m.txt"))
        open(output_file, "w") do file
            println(file, "Processando o arquivo: $file_path")
            elapsed_time = @elapsed (run_c8(file))
            println(file, "Tempo de execução: ", elapsed_time, " segundos")
        end




        function run_A(file)

            demand_triA = demand_tri[:, :, :]

            demand_triA[:,:,2].= 0


            model = Model(Gurobi.Optimizer)
            set_optimizer_attribute(model, "TimeLimit", timelimit)
            set_optimizer_attribute(model, "MIPGap", 0.00)
         
        
            # Acessando os dados    
        
            num_clients = size(demand_triA, 1)-1
            num_carriers = size(demand_triA, 3)
    
            
            #sets
            setN = 1:num_clients
            setC = ["A","B"]
            carriers_indices = Dict("A" => 1, "B" => 2)
            carriers_indices_custo = Dict{Any, Int}()
            for i in 1:num_clients
                carriers_indices_custo[i] = i
            end
        
        
            carriers_indices_custo["A"] = num_clients+1
            carriers_indices_custo["B"] = num_clients+2
            nos = [setN; setC]     
        
            customer_transp_Nr = Dict() #conjunto Nr - possui só dois subconjuntos, r e s
            transp_customer_Ci = Dict() #conjunto Ci - possui i subconjuntos
            customerEtransp = Dict()
        
            for i in 1:num_carriers
                customer_transp_Nr[setC[i]] = Set() #criando conjuntos vazios
            end
        
            for i in 1:num_clients
                transp_customer_Ci[i] = Set() #criando conjuntos vazios
            end
        
            # for i in setN, j in 1:size(demand_tri, 1), k in 1:num_carriers
            #     if demand_tri[i,j,k]>0  
            #         push!(customer_transp_Nr[setC[k]],i) #indices dos clientes que possuem demanda>0 da transp C
            #     end
            # end
        
            for i in setN, k in 1:num_carriers
                if sum(demand_triA[i,:,k])>0  || sum(demand_triA[:,i,k])>0
                    push!(customer_transp_Nr[setC[k]],i) #indices dos clientes que possuem demanda>0 da transp C
                end
            end
            #   println(customer_transp_Nr)
                 
            #indices das transp C que possuem clientes N com demanda>0
            for i in setN, k in 1:num_carriers
                if sum(demand_triA[i,:,k])>0  || sum(demand_triA[:,i,k])>0
                    push!(transp_customer_Ci[i],setC[k]) #indices das transp C que possuem clientes N com demanda>0
                end
            end
         #   println(transp_customer_Ci)
        
        
           # println(transp_customer_Ci)
            for r in setC
                customerEtransp[r] = copy(customer_transp_Nr[r]) # cópia do conjunto de clientes para evitar alteração
                push!(customerEtransp[r], r) # Adicionando a transportadora como um elemento adicional
            end
        
        
            #variables
            x = @variable(model, [nos, nos, setC], Bin, base_name="x")
            z = @variable(model, [setN, setC, setC], Bin, base_name="z")
            l = @variable(model, lower_bound=0, [nos, nos, setC, setN], base_name="l")
            m = @variable(model, lower_bound=0, [nos, nos, setC, setN], base_name="m")
            n = @variable(model, lower_bound=0, [setN, setN, setC, setN], base_name="n")
        
            #constraint
          
            c2 = @constraint(model,[i in setN, r in transp_customer_Ci[i]], sum(z[i,r,s] for s in transp_customer_Ci[i] ) == 1 ) #check
            
            c3 = @constraint(model, [i in setN, r in transp_customer_Ci[i]], sum(x[i,j,r] for j in customerEtransp[r] if j!=i) == 
            sum(x[j,i,r] for j in customerEtransp[r] if j!=i)) #check
           
            c4 = @constraint(model, [i in setN, r in transp_customer_Ci[i], s in transp_customer_Ci[i]],
            sum(x[i,j,s] for j in customerEtransp[s] if j!=i) >= z[i,r,s]) #check
        
             
            c5_transportadoras = @constraint(model, [r in setC, h in customer_transp_Nr[r]], sum(l[r,j,r,h] for j in customer_transp_Nr[r]) == 
            sum(demand_triA[h,num_clients+1,carriers_indices[s]]*z[h,s,r] for s in transp_customer_Ci[h] ))    #somando toda a ultima coluna
        
           #println(c5_transportadoras)
            
            c5linha_transportadoras = @constraint(model, [r in setC, h in customer_transp_Nr[r]], sum(m[i,r,r,h] for i in customer_transp_Nr[r]) == 
            sum(demand_triA[num_clients+1,h,carriers_indices[s]]*z[h,s,r] for s in transp_customer_Ci[h])) #somando toda a ultima linha
        
         
            c6_l = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(l[i,j,r,h] for j in customerEtransp[r] if j!=i && i!=h)  == 
            sum(l[j,i,r,h] for j in customerEtransp[r] if j!=i && i!=h)) 
         
            c6linha_m = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(m[i,j,r,h] for j in customerEtransp[r] if j!=i && i!=h)   == 
            sum(m[j,i,r,h] for j in customerEtransp[r] if j!=i && i!=h))
            
             
         
        
            c62 = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(l[i,j,r,h] for j in customerEtransp[r] if j!=i && i==h) -
            sum(l[j,i,r,h] for j in customerEtransp[r] if j!=i && i==h) ==
            - sum(demand_triA[i,num_clients+1,carriers_indices[s]]*z[i,s,r] for s in transp_customer_Ci[i] if i==h ) )
        
            c62linha_m = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(m[i,j,r,h] for j in customerEtransp[r] if j!=i && i==h) -
            sum(m[j,i,r,h] for j in customerEtransp[r] if j!=i && i==h) ==
            sum(demand_triA[num_clients+1,i,carriers_indices[s]]*z[i,s,r] for s in transp_customer_Ci[i]  if i==h ))
           
            c6linha_n = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]], ##restrição das COLETAS
            sum(n[i,j,r,h] for j in customer_transp_Nr[r] if j!=i && i!=h)  - 
            sum(n[j,i,r,h] for j in customer_transp_Nr[r] if j!=i && i!=h) ==
            sum(demand_triA[h,i,carriers_indices[s]]*z[i,s,r] for s in transp_customer_Ci[i] if i!=h )) #restrição somando linha ou coluna
    
           
        
            c62linha_n = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]], ##restrição das ENTREGAS
            sum(n[i,j,r,h] for j in customer_transp_Nr[r] if j!=i && i==h) -
            sum(n[j,i,r,h] for j in customer_transp_Nr[r] if j!=i && i==h) == -
            sum(demand_triA[h,j,carriers_indices[s]]*z[i,s,r] for s in transp_customer_Ci[i] for j in 1:num_clients if i==h ))
        
     
         
            c7mod_l = @constraint(model, [r in setC, j in customer_transp_Nr[r]], 
            sum(l[r,j,r,h] for h in customer_transp_Nr[r] )  <= capacity*x[r,j,r])
         
            c7mod_m = @constraint(model, [r in setC, i in customer_transp_Nr[r]], 
            sum(m[i,r,r,h] for h in customer_transp_Nr[r] )  <= capacity*x[i,r,r])
              
            c7mod_n = @constraint(model, [r in setC, i in customer_transp_Nr[r], j in customer_transp_Nr[r]], 
            sum(l[i,j,r,h] for h in customer_transp_Nr[r] if i!=j) + sum(m[i,j,r,h] for h in customer_transp_Nr[r] if i!=j)  
            + sum(n[i,j,r,h] for h in customer_transp_Nr[r] if i!=j) <= capacity*x[i,j,r]) #check

            c8 = @constraint(model, [i in customer_transp_Nr["A"]], sum(x[i,j,r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j!=i  ) == 1 )
          
          #println(customer_transp_Nr["A"])
            # c8 = @constraint(model, [i in setN], sum(x[i,j,r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j!=i  ) == 1 )
            #println(c8)
            #c8 = @constraint(model, [i in customer_transp_Nr["B"]], sum(x[i,j,r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j!=i  ) == 1 )
            
        
            ##ATENÇÃO: AS MATRIZES COLETA E DEMANDA ESTÃO NUMERADAS DE 1 ATÉ CLIENTES + 1, SENDO A ÚLTIMA LINHA/COLUNA A Transportadora
        
           #objectivee function
            @objective(model, Min, sum(custo[carriers_indices_custo[i],carriers_indices_custo[j]]*x[i,j,r] for r in setC for i in customerEtransp[r] for j in customerEtransp[r] if i!=j))
            
            #Optimizer
            set_optimizer_attribute(model,"OutputFlag", 1)
            optimize!(model)
        
           
            #print solution
            # Solução adaptada para seguir o estilo do primeiro código
            status = termination_status(model)
            println(file, "status= ", status)
    
            if status in [MOI.OPTIMAL, MOI.TIME_LIMIT]
                println(file, "ID = ", id)
                println(file, "Numero de clientes = ", clientes)
                if has_values(model)
                    println(file, "OF (custo) = ", objective_value(model))
                    println(file, "Best Objective = ", objective_value(model))
                    println(file, "Best Bound = ", objective_bound(model))
                    println(file, "GAP = ", relative_gap(model) * 100, "%")
                else
                    println(file, "Nenhuma solução viável foi encontrada.")
                end
            else
                println(file, "O modelo não encontrou uma solução ótima ou viável.")
            end
    
            if status == MOI.TIME_LIMIT
                println(file, "O tempo limite foi atingido.")
            elseif status == MOI.INFEASIBLE
                println(file, "O modelo é inviável. Verifique as restrições ou os dados de entrada.")
            end
    
            if has_values(model)
                for k in nos, i in nos, j in setC
                    if value(x[k, i, j]) > 0.5 && k != i
                        println("x[$k, $i, $j] = ", value(x[k, i, j]))
                        println(file, "x[$k, $i, $j] = ", value(x[k, i, j]))
                    end
                end
            
                for k in nos, g in nos, i in setC, j in setN
                    if value(l[k, g, i, j]) > 0.5
                        println("l[$k, $g, $i, $j] = ", value(l[k, g, i, j]))
                        println(file, "l[$k, $g, $i, $j] = ", value(l[k, g, i, j]))
                    end
                end
            
                for k in nos, g in nos, i in setC, j in setN
                    if value(m[k, g, i, j]) > 0.5
                        println("m[$k, $g, $i, $j] = ", value(m[k, g, i, j]))
                        println(file, "m[$k, $g, $i, $j] = ", value(m[k, g, i, j]))
                    end
                end
                for k in setN, g in setN, i in setC, j in setN
                    if value(n[k, g, i, j]) > 0.5
                        println("n[$k, $g, $i, $j] = ", value(n[k, g, i, j]))
                        println(file, "n[$k, $g, $i, $j] = ", value(n[k, g, i, j]))
                    end
                end
            else
                println(file, "Nenhuma solução parcial disponível para as variáveis.")
            end
    
    
        end
    
        base_name = basename(file_path)
        output_file = joinpath(output_folder, replace(base_name, ".dat" => "_outputAm2m.txt"))
        open(output_file, "w") do file
            println(file, "Processando o arquivo: $file_path")
            elapsed_time = @elapsed (run_A(file))
            println(file, "Tempo de execução: ", elapsed_time, " segundos")
        end

        function run_B(file)

            demand_tri[:,:,1].= 0
            model = Model(Gurobi.Optimizer)
            set_optimizer_attribute(model, "TimeLimit", timelimit)
            set_optimizer_attribute(model, "MIPGap", 0.00)
         
        
            # Acessando os dados    
        
            num_clients = size(demand_tri, 1)-1
            num_carriers = size(demand_tri, 3)
    
            
            #sets
            setN = 1:num_clients
            setC = ["A","B"]
            carriers_indices = Dict("A" => 1, "B" => 2)
            carriers_indices_custo = Dict{Any, Int}()
            for i in 1:num_clients
                carriers_indices_custo[i] = i
            end
        
        
            carriers_indices_custo["A"] = num_clients+1
            carriers_indices_custo["B"] = num_clients+2
            nos = [setN; setC]     
        
            customer_transp_Nr = Dict() #conjunto Nr - possui só dois subconjuntos, r e s
            transp_customer_Ci = Dict() #conjunto Ci - possui i subconjuntos
            customerEtransp = Dict()
        
            for i in 1:num_carriers
                customer_transp_Nr[setC[i]] = Set() #criando conjuntos vazios
            end
        
            for i in 1:num_clients
                transp_customer_Ci[i] = Set() #criando conjuntos vazios
            end
        
            # for i in setN, j in 1:size(demand_tri, 1), k in 1:num_carriers
            #     if demand_tri[i,j,k]>0  
            #         push!(customer_transp_Nr[setC[k]],i) #indices dos clientes que possuem demanda>0 da transp C
            #     end
            # end
        
            for i in setN, k in 1:num_carriers
                if sum(demand_tri[i,:,k])>0  || sum(demand_tri[:,i,k])>0
                    push!(customer_transp_Nr[setC[k]],i) #indices dos clientes que possuem demanda>0 da transp C
                end
            end
            #   println(customer_transp_Nr)
                 
            #indices das transp C que possuem clientes N com demanda>0
            for i in setN, k in 1:num_carriers
                if sum(demand_tri[i,:,k])>0  || sum(demand_tri[:,i,k])>0
                    push!(transp_customer_Ci[i],setC[k]) #indices das transp C que possuem clientes N com demanda>0
                end
            end
         #   println(transp_customer_Ci)
        
        
           # println(transp_customer_Ci)
            for r in setC
                customerEtransp[r] = copy(customer_transp_Nr[r]) # cópia do conjunto de clientes para evitar alteração
                push!(customerEtransp[r], r) # Adicionando a transportadora como um elemento adicional
            end
        
        
            #variables
            x = @variable(model, [nos, nos, setC], Bin, base_name="x")
            z = @variable(model, [setN, setC, setC], Bin, base_name="z")
            l = @variable(model, lower_bound=0, [nos, nos, setC, setN], base_name="l")
            m = @variable(model, lower_bound=0, [nos, nos, setC, setN], base_name="m")
            n = @variable(model, lower_bound=0, [setN, setN, setC, setN], base_name="n")
        
            #constraint
          
            c2 = @constraint(model,[i in setN, r in transp_customer_Ci[i]], sum(z[i,r,s] for s in transp_customer_Ci[i] ) == 1 ) #check
            
            c3 = @constraint(model, [i in setN, r in transp_customer_Ci[i]], sum(x[i,j,r] for j in customerEtransp[r] if j!=i) == 
            sum(x[j,i,r] for j in customerEtransp[r] if j!=i)) #check
           
            c4 = @constraint(model, [i in setN, r in transp_customer_Ci[i], s in transp_customer_Ci[i]],
            sum(x[i,j,s] for j in customerEtransp[s] if j!=i) >= z[i,r,s]) #check
        
             
            c5_transportadoras = @constraint(model, [r in setC, h in customer_transp_Nr[r]], sum(l[r,j,r,h] for j in customer_transp_Nr[r]) == 
            sum(demand_tri[h,num_clients+1,carriers_indices[s]]*z[h,s,r] for s in transp_customer_Ci[h] ))    #somando toda a ultima coluna
        
           #println(c5_transportadoras)
            
            c5linha_transportadoras = @constraint(model, [r in setC, h in customer_transp_Nr[r]], sum(m[i,r,r,h] for i in customer_transp_Nr[r]) == 
            sum(demand_tri[num_clients+1,h,carriers_indices[s]]*z[h,s,r] for s in transp_customer_Ci[h])) #somando toda a ultima linha
        
         
            c6_l = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(l[i,j,r,h] for j in customerEtransp[r] if j!=i && i!=h)  == 
            sum(l[j,i,r,h] for j in customerEtransp[r] if j!=i && i!=h)) 
         
            c6linha_m = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(m[i,j,r,h] for j in customerEtransp[r] if j!=i && i!=h)   == 
            sum(m[j,i,r,h] for j in customerEtransp[r] if j!=i && i!=h))
            
             
         
        
            c62 = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(l[i,j,r,h] for j in customerEtransp[r] if j!=i && i==h) -
            sum(l[j,i,r,h] for j in customerEtransp[r] if j!=i && i==h) ==
            - sum(demand_tri[i,num_clients+1,carriers_indices[s]]*z[i,s,r] for s in transp_customer_Ci[i] if i==h ) )
        
            c62linha_m = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]],
            sum(m[i,j,r,h] for j in customerEtransp[r] if j!=i && i==h) -
            sum(m[j,i,r,h] for j in customerEtransp[r] if j!=i && i==h) ==
            sum(demand_tri[num_clients+1,i,carriers_indices[s]]*z[i,s,r] for s in transp_customer_Ci[i]  if i==h ))
           
            c6linha_n = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]], ##restrição das COLETAS
            sum(n[i,j,r,h] for j in customer_transp_Nr[r] if j!=i && i!=h)  - 
            sum(n[j,i,r,h] for j in customer_transp_Nr[r] if j!=i && i!=h) ==
            sum(demand_tri[h,i,carriers_indices[s]]*z[i,s,r] for s in transp_customer_Ci[i] if i!=h )) #restrição somando linha ou coluna
    
           
        
            c62linha_n = @constraint(model, [r in setC, i in customer_transp_Nr[r], h in customer_transp_Nr[r]], ##restrição das ENTREGAS
            sum(n[i,j,r,h] for j in customer_transp_Nr[r] if j!=i && i==h) -
            sum(n[j,i,r,h] for j in customer_transp_Nr[r] if j!=i && i==h) == -
            sum(demand_tri[h,j,carriers_indices[s]]*z[i,s,r] for s in transp_customer_Ci[i] for j in 1:num_clients if i==h ))
        
     
         
            c7mod_l = @constraint(model, [r in setC, j in customer_transp_Nr[r]], 
            sum(l[r,j,r,h] for h in customer_transp_Nr[r] )  <= capacity*x[r,j,r])
         
            c7mod_m = @constraint(model, [r in setC, i in customer_transp_Nr[r]], 
            sum(m[i,r,r,h] for h in customer_transp_Nr[r] )  <= capacity*x[i,r,r])
              
            c7mod_n = @constraint(model, [r in setC, i in customer_transp_Nr[r], j in customer_transp_Nr[r]], 
            sum(l[i,j,r,h] for h in customer_transp_Nr[r] if i!=j) + sum(m[i,j,r,h] for h in customer_transp_Nr[r] if i!=j)  
            + sum(n[i,j,r,h] for h in customer_transp_Nr[r] if i!=j) <= capacity*x[i,j,r]) #check

            c8 = @constraint(model, [i in customer_transp_Nr["B"]], sum(x[i,j,r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j!=i  ) == 1 )
          
          #println(customer_transp_Nr["A"])
            # c8 = @constraint(model, [i in setN], sum(x[i,j,r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j!=i  ) == 1 )
            #println(c8)
            #c8 = @constraint(model, [i in customer_transp_Nr["B"]], sum(x[i,j,r] for r in transp_customer_Ci[i] for j in customerEtransp[r] if j!=i  ) == 1 )
            
        
            ##ATENÇÃO: AS MATRIZES COLETA E DEMANDA ESTÃO NUMERADAS DE 1 ATÉ CLIENTES + 1, SENDO A ÚLTIMA LINHA/COLUNA A Transportadora
        
           #objectivee function
            @objective(model, Min, sum(custo[carriers_indices_custo[i],carriers_indices_custo[j]]*x[i,j,r] for r in setC for i in customerEtransp[r] for j in customerEtransp[r] if i!=j))
            
            #Optimizer
            set_optimizer_attribute(model,"OutputFlag", 1)
            optimize!(model)
        
           
            #print solution
            # Solução adaptada para seguir o estilo do primeiro código
            status = termination_status(model)
            println(file, "status= ", status)
    
            if status in [MOI.OPTIMAL, MOI.TIME_LIMIT]
                println(file, "ID = ", id)
                println(file, "Numero de clientes = ", clientes)
                if has_values(model)
                    println(file, "OF (custo) = ", objective_value(model))
                    println(file, "Best Objective = ", objective_value(model))
                    println(file, "Best Bound = ", objective_bound(model))
                    println(file, "GAP = ", relative_gap(model) * 100, "%")
                else
                    println(file, "Nenhuma solução viável foi encontrada.")
                end
            else
                println(file, "O modelo não encontrou uma solução ótima ou viável.")
            end
    
            if status == MOI.TIME_LIMIT
                println(file, "O tempo limite foi atingido.")
            elseif status == MOI.INFEASIBLE
                println(file, "O modelo é inviável. Verifique as restrições ou os dados de entrada.")
            end
    
            if has_values(model)
                for k in nos, i in nos, j in setC
                    if value(x[k, i, j]) > 0.5 && k != i
                        println("x[$k, $i, $j] = ", value(x[k, i, j]))
                        println(file, "x[$k, $i, $j] = ", value(x[k, i, j]))
                    end
                end
            
                for k in nos, g in nos, i in setC, j in setN
                    if value(l[k, g, i, j]) > 0.5
                        println("l[$k, $g, $i, $j] = ", value(l[k, g, i, j]))
                        println(file, "l[$k, $g, $i, $j] = ", value(l[k, g, i, j]))
                    end
                end
            
                for k in nos, g in nos, i in setC, j in setN
                    if value(m[k, g, i, j]) > 0.5
                        println("m[$k, $g, $i, $j] = ", value(m[k, g, i, j]))
                        println(file, "m[$k, $g, $i, $j] = ", value(m[k, g, i, j]))
                    end
                end
                for k in setN, g in setN, i in setC, j in setN
                    if value(n[k, g, i, j]) > 0.5
                        println("n[$k, $g, $i, $j] = ", value(n[k, g, i, j]))
                        println(file, "n[$k, $g, $i, $j] = ", value(n[k, g, i, j]))
                    end
                end
            else
                println(file, "Nenhuma solução parcial disponível para as variáveis.")
            end
    
    
        end
    
        base_name = basename(file_path)
        output_file = joinpath(output_folder, replace(base_name, ".dat" => "_outputBm2m.txt"))
        open(output_file, "w") do file
            println(file, "Processando o arquivo: $file_path")
            elapsed_time = @elapsed (run_B(file))
            println(file, "Tempo de execução: ", elapsed_time, " segundos")
        end
    

end


function processar_arquivos_na_pasta(folder_path::String, output_folder::String; max_tentativas::Int = 1000000)
    arquivos = readdir(folder_path)
    arquivos_dat = filter(x -> endswith(x, ".dat"), arquivos)

    for arquivo in arquivos_dat
        file_path = joinpath(folder_path, arquivo)
        println("Processando arquivo: $file_path")

        tentativas = 0
        sucesso = false

        while tentativas < max_tentativas && !sucesso
            try
                rodar_modelo_com_arquivo(file_path, output_folder)
                println("Arquivo processado com sucesso na tentativa $(tentativas + 1)!\n")
                sucesso = true
            catch e
                tentativas += 1
                println("Erro ao processar o arquivo $file_path (tentativa $tentativas):")
                println(e)  # Mostra o erro completo
                if tentativas >= max_tentativas
                    println("Falha ao processar o arquivo após $max_tentativas tentativas. Pulando para o próximo arquivo...\n")
                else
                    println("Repetindo tentativa para o arquivo: $file_path\n")
                end
            end
        end
    end
end





# Definir o caminho da pasta dos arquivos e a pasta de saída
folder_path = joinpath(@__DIR__, "input")
output_folder = joinpath(@__DIR__, "resultados_ce_only")
 
# Criar a pasta de saída se ela não existir
if !isdir(output_folder)
    mkpath(output_folder)
end

# Executar o processamento dos arquivos
processar_arquivos_na_pasta(folder_path, output_folder)
