using JuMP, Random

include("demandacoleta.jl")
# global demand = [0 10; 0 13; 0 19; 0 19; 11 0; 0 3; 3  3; 0 17; 8 8; 16 0; 5 4; 5 4; 0 13; 0 14; 18 0; 0 2; 9 9; 36 0; 6 0; 0 13; 0 14; 12 11; 23 0]
# global coleta = [0  	5
#   0  	10
#   0  	13
#   0  	5
#   20	 0
#   0  	14
#   10	 8
#   0  	10
#   10	 14
#   3  	0
#   14	 14
#   2  	11
#   0  	16
#   0  	3
#   19	 0
#   0  	16
#   24	 8
#   18	 0
#   24	 0
#   0  	7
#   0  	15
#   3  	9
#   3  	0
# ]    
# global capacity = 100
# global name = "Instancia 1"



function construir_demand_tri(demand,coleta)
    
    num_clients = size(demand, 1)
    num_carriers = size(demand, 2)
    demand_tri = zeros(Int, num_clients + 1, num_clients + 1, num_carriers) 



    # Restante do código original
    for i in 1:size(demand,1)
        for r in 1:num_carriers
            demand_tri[i, size(demand_tri,1), r] = demand[i, r]
        end
    end

    for i in 1:size(demand,1)
        for r in 1:num_carriers
            demand_tri[size(demand_tri,1),i,r] = coleta[i,r]
        end
    end

    # for a in 1:size(demand_tri, 1)
    #     for b in 1:size(demand_tri, 2)
    #         for c in 1:size(demand_tri, 3)
    #             valor = demand_tri[a, b, c]
    #             # println("demand_tri[$a, $b, $c] = $valor")
    #         end
    #     end
    # end


    # (Adicione o restante do seu código original aqui, sem mudanças)
    #clientes compartilhados que serão hub
    hub = []
    for i in 1:size(demand, 1)
        if demand[i, 1] > 0 && demand[i, 2] > 0
            push!(hub, i)
        end
    end

    # Dividindo aleatoriamente os hub nas duas rotas
    rotaA = []
    rotaB = []

    for clientecomp in hub
        if rand(Bool) # 50% de chance para cada grupo
            push!(rotaA, clientecomp)
        else
            push!(rotaB, clientecomp)
        end
    end

    # Identificando clientes não compartilhados
    clientes_A = []
    clientes_B = []

    for i in 1:size(demand, 1)
        if demand[i, 1] > 0 && !(i in hub)
            push!(clientes_A, i)
        end
        if demand[i, 2] > 0 && !(i in hub)
            push!(clientes_B, i)
        end
    end

    #println("Clientes não compartilhados para Transportadora 1: ", clientes_A)
    #println("Clientes não compartilhados para Transportadora 2: ", clientes_B)

    # Selecionando aleatoriamente metade dos clientes restantes para cada transportadora

    num_selec_A = floor(Int, length(clientes_A) / 2)
    num_selec_B = floor(Int, length(clientes_B) / 2)

    selec_A = shuffle(clientes_A)[1:num_selec_A]
    selec_B = shuffle(clientes_B)[1:num_selec_B]


    # Definindo aleatoriamente se cada cliente selecionado será atendido pelo depósito
    atendidos_A = []
    atendidos_B = []

    for cliente in selec_A
        if rand(Bool)  # 50% de chance de ser atendido pelo depósito
            push!(atendidos_A, cliente)
        end
    end

    for cliente in selec_B
        if rand(Bool)  # 50% de chance de ser atendido pelo depósito
            push!(atendidos_B, cliente)
        end
    end


    # Atribuindo hubs aos clientes selecionados para Transportadora 1
    hubs_disponiveis_A = rotaA
    hubs_atendimento_A = Dict{Int, Vector{Union{Int, Char}}}()
    hubs_disponiveis_A_copia = copy(hubs_disponiveis_A)  # Cópia dos hubs disponíveis

    for cliente in selec_A
        hubs_selecionados = []
        # Se ainda houver hubs não selecionados, escolha um desses
        if !isempty(hubs_disponiveis_A_copia)
            hub = popfirst!(hubs_disponiveis_A_copia)  # Seleciona e remove o hub da cópia
            push!(hubs_selecionados, hub)
        else
            num_hubs = rand(1:length(hubs_disponiveis_A))
            hubs_selecionados = shuffle(hubs_disponiveis_A)[1:num_hubs]
        end
        # Verifica se o cliente está na lista atendidos_A
        if cliente in atendidos_A
            push!(hubs_selecionados, 'A')  # Inclui a transportadora 'A'
        end
        hubs_atendimento_A[cliente] = hubs_selecionados
    end

    # Atribuindo hubs aos clientes selecionados para Transportadora 2
    hubs_disponiveis_B = rotaB
    hubs_atendimento_B = Dict{Int, Vector{Union{Int, Char}}}()
    hubs_disponiveis_B_copia = copy(hubs_disponiveis_B)  # Cópia dos hubs disponíveis

    for cliente in selec_B
        hubs_selecionados = []
        # Se ainda houver hubs não selecionados, escolha um desses
        if !isempty(hubs_disponiveis_B_copia)
            hub = popfirst!(hubs_disponiveis_B_copia)  # Seleciona e remove o hub da cópia
            push!(hubs_selecionados, hub)
        else
            num_hubs = rand(1:length(hubs_disponiveis_B))
            hubs_selecionados = shuffle(hubs_disponiveis_B)[1:num_hubs]
        end
        # Verifica se o cliente está na lista atendidos_B
        if cliente in atendidos_B
            push!(hubs_selecionados, 'B')  # Inclui a transportadora 'B'
        end
        hubs_atendimento_B[cliente] = hubs_selecionados
    end

        


    # Redistribuindo a demanda inteira para Transportadora 1
    redistributed_demand_A = Dict{Int, Dict{Union{Int, Char}, Int}}()

    for cliente in selec_A
        total_demand = demand[cliente, 1]  # Obtém a demanda do cliente para a Transportadora 1
        hubs = hubs_atendimento_A[cliente]
        num_hubs = length(hubs)
        
        # Gera proporções aleatórias para redistribuir a demanda
        proportions = rand(num_hubs)
        proportions /= sum(proportions)  # Normaliza para que a soma seja 1
        
        # Calcula a demanda para cada hub e arredonda para inteiro
        demanda_inteira = [round(Int, total_demand * p) for p in proportions]
        
        # Ajusta para garantir que a soma seja igual à demanda original
        soma_arredondada = sum(demanda_inteira)
        diferenca = total_demand - soma_arredondada
        
        # Corrige a diferença (se houver)
        for i in 1:abs(diferenca)
            demanda_inteira[i] += sign(diferenca)
        end
        
        # Armazena no dicionário redistribuído
        redistributed_demand_A[cliente] = Dict(hubs[i] => demanda_inteira[i] for i in 1:num_hubs)
    end

    # Redistribuindo a demanda inteira para Transportadora 2
    redistributed_demand_B = Dict{Int, Dict{Union{Int, Char}, Int}}()

    for cliente in selec_B
        total_demand = demand[cliente, 2]  # Obtém a demanda do cliente para a Transportadora 2
        hubs = hubs_atendimento_B[cliente]
        num_hubs = length(hubs)
        
        # Gera proporções aleatórias para redistribuir a demanda
        proportions = rand(num_hubs)
        proportions /= sum(proportions)  # Normaliza para que a soma seja 1
        
        # Calcula a demanda para cada hub e arredonda para inteiro
        demanda_inteira = [round(Int, total_demand * p) for p in proportions]
        
        # Ajusta para garantir que a soma seja igual à demanda original
        soma_arredondada = sum(demanda_inteira)
        diferenca = total_demand - soma_arredondada
        
        # Corrige a diferença (se houver)
        for i in 1:abs(diferenca)
            demanda_inteira[i] += sign(diferenca)
        end
        
        # Armazena no dicionário redistribuído
        redistributed_demand_B[cliente] = Dict(hubs[i] => demanda_inteira[i] for i in 1:num_hubs)
    end

    # println("Demanda redistribuída para Transportadora 1: ", redistributed_demand_A)
    # println("Demanda redistribuída para Transportadora 2: ", redistributed_demand_B)

    # Número total de clientes
    num_total_clientes = size(demand, 1)

    # Imprimindo valores zerados para demanda_tri[24,b,c] e demanda[a,24,c]
    # Para selec_A e hubs_disponiveis_A
    for cliente in vcat(selec_A, hubs_disponiveis_A)
        b = num_total_clientes + 1  # Transportadora será sempre num_total_clientes + 1
        c = 1  # Transportadora 1 (A)
        # println("demand_tri[$(num_total_clientes + 1 ),$cliente,$c]=0")
        # println("demand_tri[$cliente,$(num_total_clientes + 1 ),$c]=0")
        demand_tri[b,cliente,c]=0
        demand_tri[cliente,b,c]=0
    end

    # Para selec_B e hubs_disponiveis_B
    for cliente in vcat(selec_B, hubs_disponiveis_B)
        b = num_total_clientes + 1  # Transportadora será sempre num_total_clientes + 1
        c = 2  # Transportadora 2 (B)
        # println("demand_tri[$(num_total_clientes + 1 ),$cliente,$c]=0")
        # println("demand_tri[$cliente,$(num_total_clientes + 1 ),$c]=0")
        demand_tri[b,cliente,c]=0
        demand_tri[cliente,b,c]=0
    end


    # Imprimindo a demanda redistribuída para Transportadora 1
    for (cliente, hubs_dict) in redistributed_demand_A
        for (hub, demanda) in hubs_dict
            a = cliente
            b = hub isa Int ? hub : num_total_clientes + 1 
            c = 1  # Transportadora 1 (A)
            # println("demand_tri[$a,$b,$c]=$demanda")
            demand_tri[a, b, c] = demanda
        end
    end


    # Imprimindo a demanda redistribuída para Transportadora 2
    for (cliente, hubs_dict) in redistributed_demand_B
        for (hub, demanda) in hubs_dict
            a = cliente
            b = hub isa Int ? hub : num_total_clientes + 1 
            c = 2  # Transportadora 2 (B)
            # println("demand_tri[$a,$b,$c]=$demanda")
            demand_tri[a, b, c] = demanda
        end

    end

    return demand_tri
# println("AGORA VOU PRINTAR A DEMANDTRI")

# #PRINT DA DEMAND TRI DEPOIS
# println("Valores finais da demand_tri:")
# for a in 1:size(demand_tri, 1)
#     for b in 1:size(demand_tri, 2)
#         for c in 1:size(demand_tri, 3)
#             valor = demand_tri[a, b, c]
#             println("demand_tri[$a, $b, $c] = $valor")
#         end
#     end
# end

# println(demand_tri)

end

