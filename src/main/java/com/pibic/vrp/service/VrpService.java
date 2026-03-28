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

    // ==================== CE-C8 ====================

    /**
     * Resolve o problema CE COM restricao C8 (cada cliente visitado exatamente uma vez).
     * Modelagem igual ao modelo exato em Julia.
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

        if ("CE".equals(mode)) {
            vrpBuilder.addVehicle(VehicleImpl.Builder.newInstance("vehicle_1")
                    .setStartLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(depotA))
                    .setEndLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(depotA))
                    .setType(typeA)
                    .addSkill("1")
                    .build());

            vrpBuilder.addVehicle(VehicleImpl.Builder.newInstance("vehicle_2")
                    .setStartLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(depotB))
                    .setEndLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(depotB))
                    .setType(typeB)
                    .addSkill("2")
                    .build());
        } else {
            String targetCarrier = "CE_A".equals(mode) ? "1" : "2";
            String depot = "CE_A".equals(mode) ? depotA : depotB;
            VehicleType type = "CE_A".equals(mode) ? typeA : typeB;

            vrpBuilder.addVehicle(VehicleImpl.Builder.newInstance("vehicle_" + targetCarrier)
                    .setStartLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(depot))
                    .setEndLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(depot))
                    .setType(type)
                    .addSkill(targetCarrier)
                    .build());
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

        // 4. Customers - COM RESTRICAO C8
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

                    int serviceDemand = Math.max(totalDelivery, totalPickup);

                    if (serviceDemand > 0) {
                        com.graphhopper.jsprit.core.problem.job.Service.Builder serviceBuilder =
                                com.graphhopper.jsprit.core.problem.job.Service.Builder.newInstance("service_" + customerId)
                                .addSizeDimension(0, serviceDemand)
                                .setLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(customerId));

                        if (!isSharedCustomer && carriersForCustomer.size() == 1) {
                            serviceBuilder.addRequiredSkill(carriersForCustomer.iterator().next());
                        }

                        vrpBuilder.addJob(serviceBuilder.build());
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

                    int serviceDemand = Math.max(carrierDeliveryDemand, carrierPickupDemand);

                    if (serviceDemand > 0) {
                        com.graphhopper.jsprit.core.problem.job.Service.Builder serviceBuilder =
                                com.graphhopper.jsprit.core.problem.job.Service.Builder.newInstance("service_" + customerId)
                                .addSizeDimension(0, serviceDemand)
                                .setLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(customerId))
                                .addRequiredSkill(targetCarrier);

                        vrpBuilder.addJob(serviceBuilder.build());
                    }
                }
            }
        }

        vrpBuilder.setFleetSize(VehicleRoutingProblem.FleetSize.INFINITE);

        VehicleRoutingProblem problem = vrpBuilder.build();

        VehicleRoutingProblemSolution bestSolution = solveWithMultiStart(problem, 10);

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
                .message("CE-C8 Sem colaboracao - Unassigned Jobs: " + totalUnassigned +
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
     * Versao sem colaboracao do CE-Custom.
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
                .message("CE-Custom NoShare - Custo A: " + solutionA.getTotalCost() +
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
            int numStarts) {

        VehicleRoutingProblemSolution bestSolution = null;
        double bestCost = Double.MAX_VALUE;

        for (int seed = 0; seed < numStarts; seed++) {
            VehicleRoutingAlgorithm algorithm = Jsprit.Builder.newInstance(problem)
                    .setStateAndConstraintManager(stateManager, constraintManager)
                    .setProperty(Jsprit.Parameter.THREADS, "4")
                    .setProperty(Jsprit.Parameter.VEHICLE_SWITCH, "false")
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
     * Mapeia a solucao do Jsprit para o modelo de resposta (versao C8).
     */
    private VrpSolution mapSolutionC8(VehicleRoutingProblemSolution solution, String problemId,
                                                VehicleRoutingProblem problem) {
        List<RouteDTO> routes = new ArrayList<>();
        double totalCost = 0.0;

        if (solution != null) {
            for (VehicleRoute route : solution.getRoutes()) {
                List<String> activities = new ArrayList<>();
                String depotId = route.getStart().getLocation().getId();

                activities.add("START-" + depotId);

                List<String> customerLocations = new ArrayList<>();

                for (TourActivity activity : route.getActivities()) {
                    String locId = activity.getLocation().getId();
                    if (!customerLocations.contains(locId)) {
                        customerLocations.add(locId);
                    }
                }

                List<String> optimizedLocations = new ArrayList<>(customerLocations);
                double routeCost = calculateRouteCost(optimizedLocations, depotId, problem, route.getVehicle());

                for (String locId : optimizedLocations) {
                    int deliveryDemand = customerDeliveryDemands.getOrDefault(locId, 0);
                    int pickupDemand = customerPickupDemands.getOrDefault(locId, 0);

                    if (deliveryDemand > 0) {
                        activities.add("DELIVERY:" + locId);
                    }
                    if (pickupDemand > 0) {
                        activities.add("PICKUP:" + locId);
                    }
                    if (deliveryDemand == 0 && pickupDemand == 0) {
                        activities.add("SERVICE:" + locId);
                    }
                }

                activities.add("END-" + depotId);

                totalCost += routeCost;

                routes.add(RouteDTO.builder()
                        .vehicleId(route.getVehicle().getId())
                        .routeCost(routeCost)
                        .activitySequence(activities)
                        .build());
            }
        }

        return VrpSolution.builder()
                .problemId(problemId)
                .totalCost(totalCost)
                .routes(routes)
                .status("COMPLETED")
                .message(solution != null ?
                        "CE-C8 - Unassigned Jobs: " + solution.getUnassignedJobs().size() : "No Solution")
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

        // Config 3: Todos separados (permite 2 visitas)
        Map<String, String> allSeparated = new HashMap<>();
        for (String cust : sharedCustomers) allSeparated.put(cust, "S");
        configs.add(allSeparated);

        // Config 4: Baseado em proximidade ao deposito
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

        vrpBuilder.addVehicle(VehicleImpl.Builder.newInstance("vehicle_1")
                .setStartLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(depotA))
                .setEndLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(depotA))
                .setType(typeA)
                .addSkill("1")
                .build());

        vrpBuilder.addVehicle(VehicleImpl.Builder.newInstance("vehicle_2")
                .setStartLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(depotB))
                .setEndLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(depotB))
                .setType(typeB)
                .addSkill("2")
                .build());

        // 3. Mapa de jobs relacionados
        Map<String, String> relatedJobs = new HashMap<>();

        // 4. Criar jobs baseado na alocacao
        if (input.getCustomers() != null) {
            for (Customer customer : input.getCustomers()) {
                String customerId = String.valueOf(customer.getId());
                boolean hasCarrier1 = customerTranspNr.get("1").contains(customerId);
                boolean hasCarrier2 = customerTranspNr.get("2").contains(customerId);
                boolean isShared = hasCarrier1 && hasCarrier2;

                String alloc = allocation.getOrDefault(customerId, "S");

                if (hasCarrier1 && !hasCarrier2) {
                    createJobsForCarrier(vrpBuilder, customer, customerId, "1", "1", relatedJobs);
                } else if (hasCarrier2 && !hasCarrier1) {
                    createJobsForCarrier(vrpBuilder, customer, customerId, "2", "2", relatedJobs);
                } else if (isShared) {
                    if ("1".equals(alloc)) {
                        createCombinedJobsForCarrier(vrpBuilder, customer, customerId, "1", relatedJobs);
                    } else if ("2".equals(alloc)) {
                        createCombinedJobsForCarrier(vrpBuilder, customer, customerId, "2", relatedJobs);
                    } else {
                        createJobsForCarrier(vrpBuilder, customer, customerId, "1", null, relatedJobs);
                        createJobsForCarrier(vrpBuilder, customer, customerId, "2", null, relatedJobs);
                    }
                }
            }
        }

        vrpBuilder.setFleetSize(VehicleRoutingProblem.FleetSize.INFINITE);
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
                    .message("CE-Custom Multi-Start: No solution found")
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
                    .build());
        }

        // Contar visitas por localizacao
        Map<String, Integer> visitCountByLocation = new HashMap<>();
        for (VehicleRoute route : solution.getRoutes()) {
            Set<String> visitedInRoute = new HashSet<>();
            for (TourActivity activity : route.getActivities()) {
                visitedInRoute.add(activity.getLocation().getId());
            }
            for (String loc : visitedInRoute) {
                visitCountByLocation.merge(loc, 1, Integer::sum);
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
                .message("CE-Custom Multi-Start | Unassigned: " + solution.getUnassignedJobs().size() +
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

        vrpBuilder.addVehicle(VehicleImpl.Builder.newInstance("vehicle_" + carrierId)
                .setStartLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(depotId))
                .setEndLocation(com.graphhopper.jsprit.core.problem.Location.newInstance(depotId))
                .setType(type)
                .build());

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

        vrpBuilder.setFleetSize(VehicleRoutingProblem.FleetSize.INFINITE);
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
}
