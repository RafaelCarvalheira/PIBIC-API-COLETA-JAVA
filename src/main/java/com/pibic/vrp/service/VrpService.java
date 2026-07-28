package com.pibic.vrp.service;

import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Delivery;
import com.graphhopper.jsprit.core.problem.job.Pickup;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.problem.solution.route.activity.DeliveryActivity;
import com.graphhopper.jsprit.core.problem.solution.route.activity.PickupActivity;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleType;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import com.graphhopper.jsprit.core.util.Solutions;
import com.graphhopper.jsprit.core.util.VehicleRoutingTransportCostsMatrix;
import com.pibic.vrp.model.*;
import com.pibic.vrp.constraint.SameVehicleConstraint;
import com.pibic.vrp.constraint.SameCarrierConstraint; // V2 experimental
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@Service
public class VrpService {

    private Map<String, Integer> customerDeliveryDemands = new HashMap<>();
    private Map<String, Integer> customerPickupDemands = new HashMap<>();
    private int currentVehicleCapacity = 100;

    /**
     * Numero de copias pre-criadas por carrier quando usamos FleetSize.FINITE.
     * Precisa ser >= numero maximo de trips (um trip pode atender no limite 1 cliente),
     * entao numCustomers eh um teto seguro. Usar FINITE (em vez de INFINITE) garante
     * que cada rota tem uma instancia Vehicle distinta e pre-declarada, permitindo
     * que SameVehicleConstraint (que compara identidade de referencia) funcione de
     * forma deterministica entre iteracoes do ruin-and-recreate do Jsprit.
     */
    private static int fleetCopiesFor(VrpInput input) {
        int n = (input != null && input.getCustomers() != null) ? input.getCustomers().size() : 0;
        return Math.max(n, 20);
    }

    /**
     * Custo da rota = tempo de chegada no depot final (com costPerDistance=1 e
     * transportTime=distance, o tempo acumulado equivale ao custo da rota).
     */
    private static double computeRouteCost(VehicleRoute route) {
        if (route == null || route.isEmpty()) return 0.0;
        return route.getEnd().getArrTime() - route.getStart().getEndTime();
    }

    private static void addVehicleCopies(VehicleRoutingProblem.Builder vrpBuilder,
                                         String baseId, String depotId,
                                         VehicleType type, String skill, int copies) {
        com.graphhopper.jsprit.core.problem.Location depot =
                com.graphhopper.jsprit.core.problem.Location.newInstance(depotId);
        for (int k = 0; k < copies; k++) {
            VehicleImpl.Builder b = VehicleImpl.Builder.newInstance(baseId + "__" + k)
                    .setStartLocation(depot)
                    .setEndLocation(depot)
                    .setType(type);
            if (skill != null) b.addSkill(skill);
            vrpBuilder.addVehicle(b.build());
        }
    }

    // ==================== run_CE (COM restricao C8) ====================

    /**
     * Resolve o problema CE COM restricao C8 (cada cliente visitado exatamente uma vez).
     * Modelagem equivalente ao modelo exato em Julia (run_CE).
     *
     * Usa jobs Delivery+Pickup separados para modelar corretamente a capacidade
     * em problemas com coleta e entrega simultanea (VRPSPD):
     * - Delivery: veiculo sai carregado do deposito, descarrega no cliente (carga diminui)
     * - Pickup: veiculo coleta no cliente (carga aumenta)
     * - Capacidade verificada em cada ponto: (entregas restantes + coletas realizadas) <= capacidade
     *
     * C8 eh garantida por construcao: cada cliente tem exatamente 1 job Delivery e 1 job Pickup
     * (com demandas somadas de ambos carriers), e SameVehicleConstraint garante que ambos
     * fiquem no mesmo veiculo.
     */
    public VrpSolution solveWithC8(VrpInput input, String mode) {
        customerDeliveryDemands.clear();
        customerPickupDemands.clear();

        VehicleRoutingProblem.Builder vrpBuilder = VehicleRoutingProblem.Builder.newInstance();

        // Coletar informacoes dos depositos
        Map<String, String> carrierToDepot = new HashMap<>();
        if (input.getFleets() != null) {
            for (Fleet fleet : input.getFleets()) {
                carrierToDepot.put(String.valueOf(fleet.getCarrierId()), String.valueOf(fleet.getDepotLocationId()));
            }
        }

        String depotA = carrierToDepot.getOrDefault("1", "16");
        String depotB = carrierToDepot.getOrDefault("2", "17");

        // 1. Cost Matrix
        VehicleRoutingTransportCostsMatrix.Builder costMatrixBuilder = VehicleRoutingTransportCostsMatrix.Builder.newInstance(true);
        if (input.getCostMatrix() != null) {
            for (CostMatrixEntry entry : input.getCostMatrix()) {
                String from = String.valueOf(entry.getFrom());
                String to = String.valueOf(entry.getTo());
                costMatrixBuilder.addTransportDistance(from, to, entry.getCost());
                costMatrixBuilder.addTransportTime(from, to, entry.getCost());
            }
        }

        if ("CE".equals(mode)) {
            costMatrixBuilder.addTransportDistance(depotA, depotA, 0.0);
            costMatrixBuilder.addTransportTime(depotA, depotA, 0.0);
            costMatrixBuilder.addTransportDistance(depotB, depotB, 0.0);
            costMatrixBuilder.addTransportTime(depotB, depotB, 0.0);
        }

        vrpBuilder.setRoutingCost(costMatrixBuilder.build());

        // 2. Veiculos com rotas circulares
        int defaultCapacity = input.getGlobalParameters() != null ? input.getGlobalParameters().getVehicleCapacity() : 100;
        currentVehicleCapacity = defaultCapacity;

        VehicleType typeA = VehicleTypeImpl.Builder.newInstance("type_1")
                .addCapacityDimension(0, defaultCapacity)
                .setCostPerDistance(1.0)
                .setFixedCost(0.0)
                .build();

        VehicleType typeB = VehicleTypeImpl.Builder.newInstance("type_2")
                .addCapacityDimension(0, defaultCapacity)
                .setCostPerDistance(1.0)
                .setFixedCost(0.0)
                .build();

        int copies = fleetCopiesFor(input);
        if ("CE".equals(mode)) {
            addVehicleCopies(vrpBuilder, "vehicle_1", depotA, typeA, "1", copies);
            addVehicleCopies(vrpBuilder, "vehicle_2", depotB, typeB, "2", copies);
        } else {
            String targetCarrier = "CE_A".equals(mode) ? "1" : "2";
            String depot = "CE_A".equals(mode) ? depotA : depotB;
            VehicleType type = "CE_A".equals(mode) ? typeA : typeB;
            addVehicleCopies(vrpBuilder, "vehicle_" + targetCarrier, depot, type, targetCarrier, copies);
        }

        // 3. Construir customer_transp_Nr[r]
        Map<String, Set<String>> customerTranspNr = new HashMap<>();
        customerTranspNr.put("1", new HashSet<>());
        customerTranspNr.put("2", new HashSet<>());

        if (input.getCustomers() != null) {
            for (Customer customer : input.getCustomers()) {
                String custId = String.valueOf(customer.getId());
                if (customer.getDeliveryDemandForCarrier("1") > 0 || customer.getPickupDemandForCarrier("1") > 0) {
                    customerTranspNr.get("1").add(custId);
                }
                if (customer.getDeliveryDemandForCarrier("2") > 0 || customer.getPickupDemandForCarrier("2") > 0) {
                    customerTranspNr.get("2").add(custId);
                }
            }
        }

        // 4. Mapa de jobs relacionados (Delivery <-> Pickup do mesmo cliente)
        Map<String, String> relatedJobs = new HashMap<>();

        // 5. Customers - COM RESTRICAO C8 usando Delivery+Pickup separados
        if (input.getCustomers() != null) {
            for (Customer customer : input.getCustomers()) {
                String customerId = String.valueOf(customer.getId());

                if ("CE".equals(mode)) {
                    Set<String> carriersForCustomer = new HashSet<>();
                    if (customerTranspNr.get("1").contains(customerId)) {
                        carriersForCustomer.add("1");
                    }
                    if (customerTranspNr.get("2").contains(customerId)) {
                        carriersForCustomer.add("2");
                    }

                    boolean isSharedCustomer = carriersForCustomer.size() > 1;

                    int totalDelivery = 0;
                    int totalPickup = 0;
                    for (String carrierId : carriersForCustomer) {
                        totalDelivery += customer.getDeliveryDemandForCarrier(carrierId);
                        totalPickup += customer.getPickupDemandForCarrier(carrierId);
                    }

                    customerDeliveryDemands.put(customerId, totalDelivery);
                    customerPickupDemands.put(customerId, totalPickup);

                    String dJobId = "d_" + customerId;
                    String pJobId = "p_" + customerId;

                    if (totalDelivery > 0) {
                        Delivery.Builder builder = Delivery.Builder.newInstance(dJobId);
                        builder.addSizeDimension(0, totalDelivery);
                        builder.setLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(customerId));
                        if (!isSharedCustomer && carriersForCustomer.size() == 1) {
                            builder.addRequiredSkill(carriersForCustomer.iterator().next());
                        }
                        vrpBuilder.addJob(builder.build());
                    }

                    if (totalPickup > 0) {
                        Pickup.Builder builder = Pickup.Builder.newInstance(pJobId);
                        builder.addSizeDimension(0, totalPickup);
                        builder.setLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(customerId));
                        if (!isSharedCustomer && carriersForCustomer.size() == 1) {
                            builder.addRequiredSkill(carriersForCustomer.iterator().next());
                        }
                        vrpBuilder.addJob(builder.build());
                    }

                    if (totalDelivery > 0 && totalPickup > 0) {
                        relatedJobs.put(dJobId, pJobId);
                        relatedJobs.put(pJobId, dJobId);
                    }
                } else {
                    String targetCarrier = "CE_A".equals(mode) ? "1" : "2";
                    if (!customerTranspNr.get(targetCarrier).contains(customerId)) {
                        continue;
                    }

                    int carrierDeliveryDemand = customer.getDeliveryDemandForCarrier(targetCarrier);
                    int carrierPickupDemand = customer.getPickupDemandForCarrier(targetCarrier);

                    customerDeliveryDemands.put(customerId, carrierDeliveryDemand);
                    customerPickupDemands.put(customerId, carrierPickupDemand);

                    String dJobId = "d_" + customerId;
                    String pJobId = "p_" + customerId;

                    if (carrierDeliveryDemand > 0) {
                        Delivery.Builder builder = Delivery.Builder.newInstance(dJobId);
                        builder.addSizeDimension(0, carrierDeliveryDemand);
                        builder.setLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(customerId));
                        builder.addRequiredSkill(targetCarrier);
                        vrpBuilder.addJob(builder.build());
                    }

                    if (carrierPickupDemand > 0) {
                        Pickup.Builder builder = Pickup.Builder.newInstance(pJobId);
                        builder.addSizeDimension(0, carrierPickupDemand);
                        builder.setLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(customerId));
                        builder.addRequiredSkill(targetCarrier);
                        vrpBuilder.addJob(builder.build());
                    }

                    if (carrierDeliveryDemand > 0 && carrierPickupDemand > 0) {
                        relatedJobs.put(dJobId, pJobId);
                        relatedJobs.put(pJobId, dJobId);
                    }
                }
            }
        }

        vrpBuilder.setFleetSize(VehicleRoutingProblem.FleetSize.FINITE);

        VehicleRoutingProblem problem = vrpBuilder.build();

        // 6. StateManager e ConstraintManager com SameVehicleConstraint
        com.graphhopper.jsprit.core.algorithm.state.StateManager stateManager =
                new com.graphhopper.jsprit.core.algorithm.state.StateManager(problem);

        com.graphhopper.jsprit.core.problem.constraint.ConstraintManager constraintManager =
                new com.graphhopper.jsprit.core.problem.constraint.ConstraintManager(problem, stateManager);

        SameVehicleConstraint sameVehicleConstraint = new SameVehicleConstraint(relatedJobs);
        constraintManager.addConstraint(sameVehicleConstraint);

        stateManager.addStateUpdater(new SameVehicleConstraint.JobAssignmentUpdater(
                sameVehicleConstraint.getJobToVehicleMap()));

        VehicleRoutingProblemSolution bestSolution = solveWithConstraintMultiStart(problem, stateManager, constraintManager, 10, true);

        return mapSolutionC8(bestSolution, input.getProblemId(), problem);
    }

    /**
     * Resolve o problema CE COM C8 e SEM colaboracao horizontal.
     */
    public VrpSolution solveWithC8NoShare(VrpInput input) {
        VrpSolution solutionA = solveWithC8(input, "CE_A");
        VrpSolution solutionB = solveWithC8(input, "CE_B");

        List<RouteDTO> combinedRoutes = new ArrayList<>();
        combinedRoutes.addAll(solutionA.getRoutes());
        combinedRoutes.addAll(solutionB.getRoutes());

        double totalCost = solutionA.getTotalCost() + solutionB.getTotalCost();

        int unassignedA = extractUnassignedCount(solutionA.getMessage());
        int unassignedB = extractUnassignedCount(solutionB.getMessage());
        int totalUnassigned = unassignedA + unassignedB;

        return VrpSolution.builder()
                .problemId(input.getProblemId())
                .totalCost(totalCost)
                .routes(combinedRoutes)
                .status("COMPLETED")
                .message("run_CE NoShare - Unassigned Jobs: " + totalUnassigned +
                         " (A: " + unassignedA + ", B: " + unassignedB + ")")
                .build();
    }

    // ==================== CE-CUSTOM ====================

    /**
     * Resolve o problema CE SEM restricao C8, mas com constraint customizada que garante
     * que Delivery e Pickup da MESMA demanda sejam atendidos pelo MESMO carrier.
     * Multi-start com diferentes alocacoes de clientes compartilhados.
     */
    public VrpSolution solveWithCustomConstraint(VrpInput input, String mode) {
        customerDeliveryDemands.clear();
        customerPickupDemands.clear();

        // 1. Coletar informacoes dos depositos
        Map<String, String> carrierToDepot = new HashMap<>();
        if (input.getFleets() != null) {
            for (Fleet fleet : input.getFleets()) {
                carrierToDepot.put(String.valueOf(fleet.getCarrierId()), String.valueOf(fleet.getDepotLocationId()));
            }
        }

        String depotA = carrierToDepot.getOrDefault("1", "16");
        String depotB = carrierToDepot.getOrDefault("2", "17");

        // 2. Identificar clientes compartilhados
        Map<String, Set<String>> customerTranspNr = new HashMap<>();
        customerTranspNr.put("1", new HashSet<>());
        customerTranspNr.put("2", new HashSet<>());
        List<String> sharedCustomers = new ArrayList<>();

        if (input.getCustomers() != null) {
            for (Customer customer : input.getCustomers()) {
                String custId = String.valueOf(customer.getId());
                boolean hasCarrier1 = customer.getDeliveryDemandForCarrier("1") > 0 || customer.getPickupDemandForCarrier("1") > 0;
                boolean hasCarrier2 = customer.getDeliveryDemandForCarrier("2") > 0 || customer.getPickupDemandForCarrier("2") > 0;

                if (hasCarrier1) customerTranspNr.get("1").add(custId);
                if (hasCarrier2) customerTranspNr.get("2").add(custId);
                if (hasCarrier1 && hasCarrier2) sharedCustomers.add(custId);
            }
        }

        // 3. MULTI-START COM DIFERENTES ALOCACOES
        VehicleRoutingProblemSolution bestSolution = null;
        double bestCost = Double.MAX_VALUE;
        String bestAllocation = "";
        int numShared = sharedCustomers.size();

        List<Map<String, String>> allocationConfigs = generateAllocationConfigs(sharedCustomers, input, depotA, depotB);

        System.out.println("\n========== CE-CUSTOM MULTI-START ==========");
        System.out.println("Clientes compartilhados: " + numShared);
        System.out.println("Configuracoes a testar: " + allocationConfigs.size());

        int configNum = 0;
        for (Map<String, String> allocation : allocationConfigs) {
            configNum++;

            VehicleRoutingProblemSolution solution = solveWithAllocation(
                input, allocation, customerTranspNr, depotA, depotB);

            if (solution != null && solution.getCost() < bestCost) {
                bestCost = solution.getCost();
                bestSolution = solution;
                bestAllocation = allocation.toString();
                System.out.println("  Config " + configNum + ": custo=" + String.format("%.2f", solution.getCost()) + " [MELHOR]");
            } else if (solution != null) {
                System.out.println("  Config " + configNum + ": custo=" + String.format("%.2f", solution.getCost()));
            }
        }

        System.out.println("Melhor alocacao: " + bestAllocation);
        System.out.println("Melhor custo: " + String.format("%.2f", bestCost));
        System.out.println("============================================\n");

        return mapSolutionCustomMultiStart(bestSolution, input.getProblemId(), bestAllocation, numShared);
    }

    /**
     * Versao sem colaboracao do run_CEc8 (SEM restricao C8).
     */
    public VrpSolution solveWithCustomConstraintNoShare(VrpInput input) {
        VrpSolution solutionA = solveWithCustomConstraintSingleCarrier(input, "1");
        VrpSolution solutionB = solveWithCustomConstraintSingleCarrier(input, "2");

        List<RouteDTO> combinedRoutes = new ArrayList<>();
        combinedRoutes.addAll(solutionA.getRoutes());
        combinedRoutes.addAll(solutionB.getRoutes());

        double totalCost = solutionA.getTotalCost() + solutionB.getTotalCost();

        return VrpSolution.builder()
                .problemId(input.getProblemId())
                .totalCost(totalCost)
                .routes(combinedRoutes)
                .message("run_CEc8 NoShare - Custo A: " + solutionA.getTotalCost() +
                        " | Custo B: " + solutionB.getTotalCost())
                .build();
    }

    // ==================== METODOS PRIVADOS ====================

    /**
     * Resolve com multi-start para escapar de otimos locais.
     */
    private VehicleRoutingProblemSolution solveWithMultiStart(VehicleRoutingProblem problem, int numStarts) {
        VehicleRoutingProblemSolution bestSolution = null;
        double bestCost = Double.MAX_VALUE;

        for (int seed = 0; seed < numStarts; seed++) {
            VehicleRoutingAlgorithm algorithm = Jsprit.Builder.newInstance(problem)
                    .setProperty(Jsprit.Parameter.THREADS, "8")
                    .setProperty(Jsprit.Parameter.VEHICLE_SWITCH, "true")
                    .setProperty(Jsprit.Parameter.FAST_REGRET, "false")
                    .setProperty(Jsprit.Parameter.THRESHOLD_ALPHA, "0.1")
                    .setProperty(Jsprit.Parameter.THRESHOLD_INI, "0.05")
                    .setRandom(new Random(seed * 1000 + 42))
                    .buildAlgorithm();

            algorithm.setMaxIterations(2000);

            Collection<VehicleRoutingProblemSolution> solutions = algorithm.searchSolutions();
            VehicleRoutingProblemSolution currentSolution = Solutions.bestOf(solutions);

            if (currentSolution != null && currentSolution.getCost() < bestCost) {
                bestCost = currentSolution.getCost();
                bestSolution = currentSolution;
            }
        }

        return bestSolution;
    }

    /**
     * Resolve com multi-start usando constraints customizadas.
     */
    private VehicleRoutingProblemSolution solveWithConstraintMultiStart(
            VehicleRoutingProblem problem,
            com.graphhopper.jsprit.core.algorithm.state.StateManager stateManager,
            com.graphhopper.jsprit.core.problem.constraint.ConstraintManager constraintManager,
            int numStarts,
            boolean allowVehicleSwitch) {

        VehicleRoutingProblemSolution bestSolution = null;
        double bestCost = Double.MAX_VALUE;

        for (int seed = 0; seed < numStarts; seed++) {
            VehicleRoutingAlgorithm algorithm = Jsprit.Builder.newInstance(problem)
                    .setStateAndConstraintManager(stateManager, constraintManager)
                    .setProperty(Jsprit.Parameter.THREADS, "4")
                    .setProperty(Jsprit.Parameter.VEHICLE_SWITCH, String.valueOf(allowVehicleSwitch))
                    .setProperty(Jsprit.Parameter.FAST_REGRET, "false")
                    .setRandom(new Random(seed * 1000 + 42))
                    .buildAlgorithm();

            algorithm.setMaxIterations(1000);

            Collection<VehicleRoutingProblemSolution> solutions = algorithm.searchSolutions();
            VehicleRoutingProblemSolution currentSolution = Solutions.bestOf(solutions);

            if (currentSolution != null && currentSolution.getCost() < bestCost) {
                bestCost = currentSolution.getCost();
                bestSolution = currentSolution;
            }
        }

        return bestSolution;
    }

    /**
     * Overload para manter compatibilidade: VEHICLE_SWITCH=false por padrao (usado pelo ce-custom).
     */
    private VehicleRoutingProblemSolution solveWithConstraintMultiStart(
            VehicleRoutingProblem problem,
            com.graphhopper.jsprit.core.algorithm.state.StateManager stateManager,
            com.graphhopper.jsprit.core.problem.constraint.ConstraintManager constraintManager,
            int numStarts) {
        return solveWithConstraintMultiStart(problem, stateManager, constraintManager, numStarts, false);
    }

    /**
     * Mapeia a solucao do Jsprit para o modelo de resposta (versao C8).
     * Usa tipos de atividade reais (DeliveryActivity/PickupActivity) do Jsprit
     * e o custo calculado pelo solver.
     */
    private VrpSolution mapSolutionC8(VehicleRoutingProblemSolution solution, String problemId,
                                                VehicleRoutingProblem problem) {
        if (solution == null) {
            return VrpSolution.builder()
                    .problemId(problemId)
                    .totalCost(0.0)
                    .routes(Collections.emptyList())
                    .status("COMPLETED")
                    .message("run_CE - No Solution")
                    .build();
        }

        List<RouteDTO> routes = new ArrayList<>();

        for (VehicleRoute route : solution.getRoutes()) {
            if (route.isEmpty()) continue;

            String vehicleId = route.getVehicle().getId();
            String depotId = route.getStart().getLocation().getId();

            List<String> activities = new ArrayList<>();
            activities.add("START-" + depotId);

            for (TourActivity activity : route.getActivities()) {
                String locationId = activity.getLocation().getId();
                String type = "VISIT";
                if (activity instanceof DeliveryActivity) type = "DELIVERY";
                else if (activity instanceof PickupActivity) type = "PICKUP";
                activities.add(type + ":" + locationId);
            }

            activities.add("END-" + depotId);

            routes.add(RouteDTO.builder()
                    .vehicleId(vehicleId)
                    .activitySequence(activities)
                    .routeCost(computeRouteCost(route))
                    .build());
        }

        return VrpSolution.builder()
                .problemId(problemId)
                .totalCost(solution.getCost())
                .routes(routes)
                .status("COMPLETED")
                .message("run_CE - Unassigned Jobs: " + solution.getUnassignedJobs().size())
                .build();
    }

    /**
     * Calcula o custo de uma rota.
     */
    private double calculateRouteCost(List<String> customerLocations, String depotId,
                                       VehicleRoutingProblem problem, com.graphhopper.jsprit.core.problem.vehicle.Vehicle vehicle) {
        if (customerLocations.isEmpty()) {
            return 0.0;
        }

        double cost = 0.0;
        com.graphhopper.jsprit.core.problem.Location depotLoc = com.graphhopper.jsprit.core.problem.Location.newInstance(depotId);
        com.graphhopper.jsprit.core.problem.Location previousLoc = depotLoc;

        for (String locId : customerLocations) {
            com.graphhopper.jsprit.core.problem.Location currentLoc = com.graphhopper.jsprit.core.problem.Location.newInstance(locId);
            cost += problem.getTransportCosts().getTransportCost(previousLoc, currentLoc, 0.0, null, vehicle);
            previousLoc = currentLoc;
        }

        cost += problem.getTransportCosts().getTransportCost(previousLoc, depotLoc, 0.0, null, vehicle);

        return cost;
    }

    /**
     * Gera configuracoes de alocacao para clientes compartilhados.
     */
    private List<Map<String, String>> generateAllocationConfigs(
            List<String> sharedCustomers, VrpInput input, String depotA, String depotB) {

        List<Map<String, String>> configs = new ArrayList<>();
        int n = sharedCustomers.size();

        if (n == 0) {
            configs.add(new HashMap<>());
            return configs;
        }

        // Construir mapa de custos para heuristica de proximidade
        Map<String, Double> costToDepotA = new HashMap<>();
        Map<String, Double> costToDepotB = new HashMap<>();

        if (input.getCostMatrix() != null) {
            for (CostMatrixEntry entry : input.getCostMatrix()) {
                String from = String.valueOf(entry.getFrom());
                String to = String.valueOf(entry.getTo());
                if (from.equals(depotA)) costToDepotA.put(to, entry.getCost());
                if (from.equals(depotB)) costToDepotB.put(to, entry.getCost());
            }
        }

        // Config 1: Todos com carrier 1
        Map<String, String> allCarrier1 = new HashMap<>();
        for (String cust : sharedCustomers) allCarrier1.put(cust, "1");
        configs.add(allCarrier1);

        // Config 2: Todos com carrier 2
        Map<String, String> allCarrier2 = new HashMap<>();
        for (String cust : sharedCustomers) allCarrier2.put(cust, "2");
        configs.add(allCarrier2);

        // (Removido: alocacao "S" - Todos separados permitindo 2 visitas.
        //  Violava a semantica de colaboracao do run_CEc8: cada cliente compartilhado
        //  era atendido por ambos os carriers independentemente, gerando custo menor
        //  que o Gurobi com rotas que o modelo exato nao admite.)

        // Config 3: Baseado em proximidade ao deposito
        Map<String, String> byProximity = new HashMap<>();
        for (String cust : sharedCustomers) {
            double distA = costToDepotA.getOrDefault(cust, Double.MAX_VALUE);
            double distB = costToDepotB.getOrDefault(cust, Double.MAX_VALUE);
            byProximity.put(cust, distA <= distB ? "1" : "2");
        }
        configs.add(byProximity);

        // Config 5: Inverso da proximidade
        Map<String, String> byProximityInverse = new HashMap<>();
        for (String cust : sharedCustomers) {
            double distA = costToDepotA.getOrDefault(cust, Double.MAX_VALUE);
            double distB = costToDepotB.getOrDefault(cust, Double.MAX_VALUE);
            byProximityInverse.put(cust, distA > distB ? "1" : "2");
        }
        configs.add(byProximityInverse);

        // Combinacoes extras (maximo 8 totais)
        if (n <= 3 && configs.size() < 8) {
            int totalCombinations = (int) Math.pow(2, n);
            for (int i = 0; i < totalCombinations && configs.size() < 8; i++) {
                Map<String, String> combo = new HashMap<>();
                for (int j = 0; j < n; j++) {
                    String carrier = ((i >> j) & 1) == 0 ? "1" : "2";
                    combo.put(sharedCustomers.get(j), carrier);
                }
                if (!configsContains(configs, combo)) {
                    configs.add(combo);
                }
            }
        } else if (configs.size() < 8) {
            Random rand = new Random(42);
            for (int i = 0; i < 3 && configs.size() < 8; i++) {
                Map<String, String> randomConfig = new HashMap<>();
                for (String cust : sharedCustomers) {
                    int choice = rand.nextInt(2);
                    randomConfig.put(cust, choice == 0 ? "1" : "2");
                }
                if (!configsContains(configs, randomConfig)) {
                    configs.add(randomConfig);
                }
            }
        }

        return configs;
    }

    private boolean configsContains(List<Map<String, String>> configs, Map<String, String> target) {
        for (Map<String, String> existing : configs) {
            if (existing.equals(target)) return true;
        }
        return false;
    }

    /**
     * Resolve o problema com uma alocacao especifica de clientes compartilhados.
     */
    private VehicleRoutingProblemSolution solveWithAllocation(
            VrpInput input,
            Map<String, String> allocation,
            Map<String, Set<String>> customerTranspNr,
            String depotA, String depotB) {

        VehicleRoutingProblem.Builder vrpBuilder = VehicleRoutingProblem.Builder.newInstance();

        // 1. Cost Matrix
        VehicleRoutingTransportCostsMatrix.Builder costMatrixBuilder = VehicleRoutingTransportCostsMatrix.Builder.newInstance(true);
        if (input.getCostMatrix() != null) {
            for (CostMatrixEntry entry : input.getCostMatrix()) {
                String from = String.valueOf(entry.getFrom());
                String to = String.valueOf(entry.getTo());
                costMatrixBuilder.addTransportDistance(from, to, entry.getCost());
                costMatrixBuilder.addTransportTime(from, to, entry.getCost());
            }
        }
        costMatrixBuilder.addTransportDistance(depotA, depotA, 0.0);
        costMatrixBuilder.addTransportTime(depotA, depotA, 0.0);
        costMatrixBuilder.addTransportDistance(depotB, depotB, 0.0);
        costMatrixBuilder.addTransportTime(depotB, depotB, 0.0);
        vrpBuilder.setRoutingCost(costMatrixBuilder.build());

        // 2. Veiculos
        int defaultCapacity = input.getGlobalParameters() != null ? input.getGlobalParameters().getVehicleCapacity() : 100;
        currentVehicleCapacity = defaultCapacity;

        VehicleType typeA = VehicleTypeImpl.Builder.newInstance("type_1")
                .addCapacityDimension(0, defaultCapacity)
                .setCostPerDistance(1.0)
                .setFixedCost(0.0)
                .build();

        VehicleType typeB = VehicleTypeImpl.Builder.newInstance("type_2")
                .addCapacityDimension(0, defaultCapacity)
                .setCostPerDistance(1.0)
                .setFixedCost(0.0)
                .build();

        int copies = fleetCopiesFor(input);
        addVehicleCopies(vrpBuilder, "vehicle_1", depotA, typeA, "1", copies);
        addVehicleCopies(vrpBuilder, "vehicle_2", depotB, typeB, "2", copies);

        // 3. Mapa de jobs relacionados
        Map<String, String> relatedJobs = new HashMap<>();

        // 4. Criar jobs baseado na alocacao
        if (input.getCustomers() != null) {
            for (Customer customer : input.getCustomers()) {
                String customerId = String.valueOf(customer.getId());
                boolean hasCarrier1 = customerTranspNr.get("1").contains(customerId);
                boolean hasCarrier2 = customerTranspNr.get("2").contains(customerId);
                boolean isShared = hasCarrier1 && hasCarrier2;

                // Allocation deve sempre conter "1" ou "2" (estrategia "S" foi removida
                // por violar a colaboracao do run_CEc8). Default "1" para defesa.
                String alloc = allocation.getOrDefault(customerId, "1");

                if (hasCarrier1 && !hasCarrier2) {
                    createJobsForCarrier(vrpBuilder, customer, customerId, "1", "1", relatedJobs);
                } else if (hasCarrier2 && !hasCarrier1) {
                    createJobsForCarrier(vrpBuilder, customer, customerId, "2", "2", relatedJobs);
                } else if (isShared) {
                    String chosen = "2".equals(alloc) ? "2" : "1";
                    createCombinedJobsForCarrier(vrpBuilder, customer, customerId, chosen, relatedJobs);
                }
            }
        }

        vrpBuilder.setFleetSize(VehicleRoutingProblem.FleetSize.FINITE);
        VehicleRoutingProblem problem = vrpBuilder.build();

        // 5. StateManager e ConstraintManager
        com.graphhopper.jsprit.core.algorithm.state.StateManager stateManager =
                new com.graphhopper.jsprit.core.algorithm.state.StateManager(problem);

        com.graphhopper.jsprit.core.problem.constraint.ConstraintManager constraintManager =
                new com.graphhopper.jsprit.core.problem.constraint.ConstraintManager(problem, stateManager);

        // 6. Adicionar SameVehicleConstraint
        SameVehicleConstraint sameVehicleConstraint = new SameVehicleConstraint(relatedJobs);
        constraintManager.addConstraint(sameVehicleConstraint);

        stateManager.addStateUpdater(new SameVehicleConstraint.JobAssignmentUpdater(
                sameVehicleConstraint.getJobToVehicleMap()));

        // 7. Resolver com multi-start
        return solveWithConstraintMultiStart(problem, stateManager, constraintManager, 2);
    }

    /**
     * Cria jobs (Delivery e Pickup) para um carrier especifico.
     */
    private void createJobsForCarrier(VehicleRoutingProblem.Builder vrpBuilder,
                                       Customer customer, String customerId,
                                       String carrierId, String skill,
                                       Map<String, String> relatedJobs) {
        int deliveryDemand = customer.getDeliveryDemandForCarrier(carrierId);
        int pickupDemand = customer.getPickupDemandForCarrier(carrierId);

        String dJobId = "d_" + customerId + "_" + carrierId;
        String pJobId = "p_" + customerId + "_" + carrierId;

        if (deliveryDemand > 0) {
            Delivery.Builder builder = Delivery.Builder.newInstance(dJobId);
            builder.addSizeDimension(0, deliveryDemand);
            builder.setLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(customerId));
            if (skill != null) builder.addRequiredSkill(skill);
            vrpBuilder.addJob(builder.build());
        }

        if (pickupDemand > 0) {
            Pickup.Builder builder = Pickup.Builder.newInstance(pJobId);
            builder.addSizeDimension(0, pickupDemand);
            builder.setLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(customerId));
            if (skill != null) builder.addRequiredSkill(skill);
            vrpBuilder.addJob(builder.build());
        }

        if (deliveryDemand > 0 && pickupDemand > 0) {
            relatedJobs.put(dJobId, pJobId);
            relatedJobs.put(pJobId, dJobId);
        }
    }

    /**
     * Cria jobs COMBINADOS (soma demandas de ambos carriers) para um unico carrier atender.
     */
    private void createCombinedJobsForCarrier(VehicleRoutingProblem.Builder vrpBuilder,
                                               Customer customer, String customerId,
                                               String assignedCarrier,
                                               Map<String, String> relatedJobs) {
        int totalDelivery = customer.getDeliveryDemandForCarrier("1") + customer.getDeliveryDemandForCarrier("2");
        int totalPickup = customer.getPickupDemandForCarrier("1") + customer.getPickupDemandForCarrier("2");

        String dJobId = "d_" + customerId + "_combined";
        String pJobId = "p_" + customerId + "_combined";

        if (totalDelivery > 0) {
            Delivery.Builder builder = Delivery.Builder.newInstance(dJobId);
            builder.addSizeDimension(0, totalDelivery);
            builder.setLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(customerId));
            builder.addRequiredSkill(assignedCarrier);
            vrpBuilder.addJob(builder.build());
        }

        if (totalPickup > 0) {
            Pickup.Builder builder = Pickup.Builder.newInstance(pJobId);
            builder.addSizeDimension(0, totalPickup);
            builder.setLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(customerId));
            builder.addRequiredSkill(assignedCarrier);
            vrpBuilder.addJob(builder.build());
        }

        if (totalDelivery > 0 && totalPickup > 0) {
            relatedJobs.put(dJobId, pJobId);
            relatedJobs.put(pJobId, dJobId);
        }
    }

    /**
     * Mapeia a solucao do Jsprit para o modelo de resposta (versao Multi-Start).
     */
    private VrpSolution mapSolutionCustomMultiStart(VehicleRoutingProblemSolution solution,
                                                     String problemId,
                                                     String allocationUsed,
                                                     int numSharedCustomers) {
        if (solution == null) {
            return VrpSolution.builder()
                    .problemId(problemId)
                    .totalCost(0.0)
                    .routes(Collections.emptyList())
                    .status("FAILED")
                    .message("run_CEc8 Multi-Start: No solution found")
                    .build();
        }

        List<RouteDTO> routes = new ArrayList<>();

        for (VehicleRoute route : solution.getRoutes()) {
            if (route.isEmpty()) continue;

            String vehicleId = route.getVehicle().getId();
            String depotId = route.getStart().getLocation().getId();

            List<String> activities = new ArrayList<>();
            activities.add("START-" + depotId);

            for (TourActivity activity : route.getActivities()) {
                String locationId = activity.getLocation().getId();
                String type = "VISIT";
                if (activity instanceof DeliveryActivity) type = "DELIVERY";
                else if (activity instanceof PickupActivity) type = "PICKUP";
                activities.add(type + ":" + locationId);
            }

            activities.add("END-" + depotId);

            routes.add(RouteDTO.builder()
                    .vehicleId(vehicleId)
                    .activitySequence(activities)
                    .routeCost(computeRouteCost(route))
                    .build());
        }

        // Contar visitas fisicas por localizacao.
        // Usar Set intra-rota mascarava duplicatas no mesmo veiculo (ex: uma rota
        // com DELIVERY:8 e PICKUP:8 nao-consecutivos = 2 visitas, nao 1).
        // Contamos uma visita por par DELIVERY/PICKUP no cliente, em qualquer rota.
        Map<String, Integer> visitCountByLocation = new HashMap<>();
        for (VehicleRoute route : solution.getRoutes()) {
            Map<String, int[]> perLoc = new HashMap<>();
            for (TourActivity activity : route.getActivities()) {
                String loc = activity.getLocation().getId();
                int[] pd = perLoc.computeIfAbsent(loc, k -> new int[2]);
                if (activity instanceof DeliveryActivity) pd[0]++;
                else if (activity instanceof PickupActivity) pd[1]++;
            }
            for (Map.Entry<String, int[]> e : perLoc.entrySet()) {
                int visits = Math.max(e.getValue()[0], e.getValue()[1]);
                if (visits > 0) visitCountByLocation.merge(e.getKey(), visits, Integer::sum);
            }
        }

        int singleVisit = 0;
        int multipleVisit = 0;
        for (int count : visitCountByLocation.values()) {
            if (count == 1) singleVisit++;
            else multipleVisit++;
        }

        return VrpSolution.builder()
                .problemId(problemId)
                .totalCost(solution.getCost())
                .routes(routes)
                .status("COMPLETED")
                .message("run_CEc8 Multi-Start | Unassigned: " + solution.getUnassignedJobs().size() +
                        " | Clientes compartilhados: " + numSharedCustomers +
                        " | Visitas (1x: " + singleVisit + ", 2x: " + multipleVisit + ")")
                .build();
    }

    /**
     * Resolve para um unico carrier (usado no modo no-share).
     */
    private VrpSolution solveWithCustomConstraintSingleCarrier(VrpInput input, String carrierId) {
        customerDeliveryDemands.clear();
        customerPickupDemands.clear();

        VehicleRoutingProblem.Builder vrpBuilder = VehicleRoutingProblem.Builder.newInstance();

        // Configurar deposito
        String depotId = "16";
        if (input.getFleets() != null) {
            for (Fleet fleet : input.getFleets()) {
                if (String.valueOf(fleet.getCarrierId()).equals(carrierId)) {
                    depotId = String.valueOf(fleet.getDepotLocationId());
                    break;
                }
            }
        }

        // Cost Matrix
        VehicleRoutingTransportCostsMatrix.Builder costMatrixBuilder = VehicleRoutingTransportCostsMatrix.Builder.newInstance(true);
        if (input.getCostMatrix() != null) {
            for (CostMatrixEntry entry : input.getCostMatrix()) {
                String from = String.valueOf(entry.getFrom());
                String to = String.valueOf(entry.getTo());
                costMatrixBuilder.addTransportDistance(from, to, entry.getCost());
                costMatrixBuilder.addTransportTime(from, to, entry.getCost());
            }
        }
        vrpBuilder.setRoutingCost(costMatrixBuilder.build());

        // Veiculo
        int defaultCapacity = input.getGlobalParameters() != null ? input.getGlobalParameters().getVehicleCapacity() : 100;
        VehicleType type = VehicleTypeImpl.Builder.newInstance("type_" + carrierId)
                .addCapacityDimension(0, defaultCapacity)
                .setCostPerDistance(1.0)
                .build();

        addVehicleCopies(vrpBuilder, "vehicle_" + carrierId, depotId, type, null, fleetCopiesFor(input));

        // Jobs apenas deste carrier
        Map<String, String> relatedJobs = new HashMap<>();
        if (input.getCustomers() != null) {
            for (Customer customer : input.getCustomers()) {
                String customerId = String.valueOf(customer.getId());
                int deliveryDemand = customer.getDeliveryDemandForCarrier(carrierId);
                int pickupDemand = customer.getPickupDemandForCarrier(carrierId);

                if (deliveryDemand <= 0 && pickupDemand <= 0) continue;

                String dJobId = "d_" + customerId;
                String pJobId = "p_" + customerId;

                if (deliveryDemand > 0) {
                    vrpBuilder.addJob(Delivery.Builder.newInstance(dJobId)
                            .addSizeDimension(0, deliveryDemand)
                            .setLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(customerId))
                            .build());
                }

                if (pickupDemand > 0) {
                    vrpBuilder.addJob(Pickup.Builder.newInstance(pJobId)
                            .addSizeDimension(0, pickupDemand)
                            .setLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(customerId))
                            .build());
                }

                if (deliveryDemand > 0 && pickupDemand > 0) {
                    relatedJobs.put(dJobId, pJobId);
                    relatedJobs.put(pJobId, dJobId);
                }

                customerDeliveryDemands.put(customerId, deliveryDemand);
                customerPickupDemands.put(customerId, pickupDemand);
            }
        }

        vrpBuilder.setFleetSize(VehicleRoutingProblem.FleetSize.FINITE);
        VehicleRoutingProblem problem = vrpBuilder.build();

        VehicleRoutingProblemSolution bestSolution = solveWithMultiStart(problem, 5);

        return mapSolutionSimple(bestSolution, input.getProblemId(), carrierId);
    }

    /**
     * Mapeamento simples de solucao (usado no modo no-share).
     */
    private VrpSolution mapSolutionSimple(VehicleRoutingProblemSolution solution, String problemId, String carrierId) {
        if (solution == null) {
            return VrpSolution.builder()
                    .problemId(problemId)
                    .totalCost(0.0)
                    .routes(Collections.emptyList())
                    .status("FAILED")
                    .message("Carrier " + carrierId + ": No solution found")
                    .build();
        }

        List<RouteDTO> routes = new ArrayList<>();
        for (VehicleRoute route : solution.getRoutes()) {
            if (route.isEmpty()) continue;

            String vehicleId = route.getVehicle().getId();
            String depotId = route.getStart().getLocation().getId();

            List<String> activities = new ArrayList<>();
            activities.add("START-" + depotId);

            for (TourActivity activity : route.getActivities()) {
                String locationId = activity.getLocation().getId();
                String type = "VISIT";
                if (activity instanceof DeliveryActivity) type = "DELIVERY";
                else if (activity instanceof PickupActivity) type = "PICKUP";
                activities.add(type + ":" + locationId);
            }

            activities.add("END-" + depotId);

            routes.add(RouteDTO.builder()
                    .vehicleId(vehicleId)
                    .activitySequence(activities)
                    .routeCost(computeRouteCost(route))
                    .build());
        }

        return VrpSolution.builder()
                .problemId(problemId)
                .totalCost(solution.getCost())
                .routes(routes)
                .status("COMPLETED")
                .message("Carrier " + carrierId + " | Unassigned: " + solution.getUnassignedJobs().size())
                .build();
    }

    /**
     * Extrai o numero de jobs nao atendidos da mensagem de status.
     */
    private int extractUnassignedCount(String message) {
        if (message == null) return 0;
        try {
            String[] parts = message.split(":");
            if (parts.length > 1) {
                return Integer.parseInt(parts[1].trim().split(" ")[0]);
            }
        } catch (Exception e) {
            // Ignorar erros de parsing
        }
        return 0;
    }

    // ==================== INICIO V2 (experimental, aditivo) ====================

    /**
     * Versao corrigida do cenario SEM a restricao c8 (run_CEc8).
     *
     * Diferencas em relacao a solveWithCustomConstraint:
     *
     * 1. O cliente compartilhado recebe UM PAR DE JOBS POR TRANSPORTADORA, sem
     *    skill restritiva. A escolha de quem o atende passa a ser feita pela
     *    busca, exatamente como a variavel z[i,r,s] do modelo exato. Na versao
     *    anterior as duas demandas eram somadas num unico par com a skill da
     *    transportadora escolhida por pre-processamento, o que impedia que as
     *    duas transportadoras atendessem o mesmo cliente separadamente.
     *
     * 2. Usa SameCarrierConstraint no lugar de SameVehicleConstraint: o modelo
     *    exige mesma transportadora para entrega e coleta de uma demanda, nao
     *    mesmo veiculo.
     *
     * 3. Dispensa a enumeracao externa de alocacoes e usa o mesmo orcamento de
     *    busca do cenario com simultaneidade (10 partidas), o que torna os dois
     *    cenarios comparaveis.
     */
    public VrpSolution solveWithCustomConstraintV2(VrpInput input) {
        customerDeliveryDemands.clear();
        customerPickupDemands.clear();

        Map<String, String> carrierToDepot = new HashMap<>();
        if (input.getFleets() != null) {
            for (Fleet fleet : input.getFleets()) {
                carrierToDepot.put(String.valueOf(fleet.getCarrierId()),
                        String.valueOf(fleet.getDepotLocationId()));
            }
        }
        String depotA = carrierToDepot.getOrDefault("1", "16");
        String depotB = carrierToDepot.getOrDefault("2", "17");

        VehicleRoutingProblem.Builder vrpBuilder = VehicleRoutingProblem.Builder.newInstance();

        // 1. Matriz de custos
        VehicleRoutingTransportCostsMatrix.Builder costMatrixBuilder =
                VehicleRoutingTransportCostsMatrix.Builder.newInstance(true);
        if (input.getCostMatrix() != null) {
            for (CostMatrixEntry entry : input.getCostMatrix()) {
                String from = String.valueOf(entry.getFrom());
                String to = String.valueOf(entry.getTo());
                costMatrixBuilder.addTransportDistance(from, to, entry.getCost());
                costMatrixBuilder.addTransportTime(from, to, entry.getCost());
            }
        }
        costMatrixBuilder.addTransportDistance(depotA, depotA, 0.0);
        costMatrixBuilder.addTransportTime(depotA, depotA, 0.0);
        costMatrixBuilder.addTransportDistance(depotB, depotB, 0.0);
        costMatrixBuilder.addTransportTime(depotB, depotB, 0.0);
        vrpBuilder.setRoutingCost(costMatrixBuilder.build());

        // 2. Veiculos
        int defaultCapacity = input.getGlobalParameters() != null
                ? input.getGlobalParameters().getVehicleCapacity() : 100;
        currentVehicleCapacity = defaultCapacity;

        VehicleType typeA = VehicleTypeImpl.Builder.newInstance("type_1")
                .addCapacityDimension(0, defaultCapacity)
                .setCostPerDistance(1.0).setFixedCost(0.0).build();
        VehicleType typeB = VehicleTypeImpl.Builder.newInstance("type_2")
                .addCapacityDimension(0, defaultCapacity)
                .setCostPerDistance(1.0).setFixedCost(0.0).build();

        int copies = fleetCopiesFor(input);
        addVehicleCopies(vrpBuilder, "vehicle_1", depotA, typeA, "1", copies);
        addVehicleCopies(vrpBuilder, "vehicle_2", depotB, typeB, "2", copies);

        // 3. Jobs: um par por transportadora, sem skill quando o cliente e compartilhado
        Map<String, String> relatedJobs = new HashMap<>();
        int compartilhados = 0;

        if (input.getCustomers() != null) {
            for (Customer customer : input.getCustomers()) {
                String customerId = String.valueOf(customer.getId());
                boolean tem1 = customer.getDeliveryDemandForCarrier("1") > 0
                        || customer.getPickupDemandForCarrier("1") > 0;
                boolean tem2 = customer.getDeliveryDemandForCarrier("2") > 0
                        || customer.getPickupDemandForCarrier("2") > 0;
                boolean compartilhado = tem1 && tem2;
                if (compartilhado) compartilhados++;

                // skill nula no cliente compartilhado: a busca decide a transportadora
                if (tem1) {
                    createJobsForCarrier(vrpBuilder, customer, customerId, "1",
                            compartilhado ? null : "1", relatedJobs);
                }
                if (tem2) {
                    createJobsForCarrier(vrpBuilder, customer, customerId, "2",
                            compartilhado ? null : "2", relatedJobs);
                }
            }
        }

        vrpBuilder.setFleetSize(VehicleRoutingProblem.FleetSize.FINITE);
        VehicleRoutingProblem problem = vrpBuilder.build();

        com.graphhopper.jsprit.core.algorithm.state.StateManager stateManager =
                new com.graphhopper.jsprit.core.algorithm.state.StateManager(problem);
        com.graphhopper.jsprit.core.problem.constraint.ConstraintManager constraintManager =
                new com.graphhopper.jsprit.core.problem.constraint.ConstraintManager(problem, stateManager);

        SameCarrierConstraint sameCarrier = new SameCarrierConstraint(relatedJobs);
        constraintManager.addConstraint(sameCarrier);
        stateManager.addStateUpdater(new SameVehicleConstraint.JobAssignmentUpdater(
                sameCarrier.getJobToVehicleMap()));

        VehicleRoutingProblemSolution best = solveWithConstraintMultiStart(
                problem, stateManager, constraintManager, 10, true);

        return mapSolutionCustomMultiStart(best, input.getProblemId(), "V2", compartilhados);
    }

    // ==================== FIM V2 ====================
}
