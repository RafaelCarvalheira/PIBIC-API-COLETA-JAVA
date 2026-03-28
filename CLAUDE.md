# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring Boot REST API for solving the Shared Customer Collaboration Vehicle Routing Problem (SCCVRP) using the Jsprit metaheuristic solver. Part of a PIBIC research project comparing VRP algorithms (original implementation was in Julia with Gurobi).

## Build & Run

```bash
mvn clean package          # Build JAR (target/vrp-api-0.0.1-SNAPSHOT.jar)
mvn spring-boot:run        # Run API on localhost:8080
mvn test                   # Run tests
```

Requires **Java 17** and **Maven 3.x**.

## API Endpoints

All endpoints are POST under `/api/solve/` and accept `VrpInput` JSON:

- `/ce-c8` — C8 constraint (each customer visited exactly once), with horizontal collaboration
- `/ce-c8-no-share` — C8 without collaboration
- `/ce-custom` — Custom same-vehicle constraint with collaboration
- `/ce-custom-no-share` — Custom constraint without collaboration

## Architecture

Standard Spring MVC layering: **Controller → Service → Jsprit solver**

- `VrpController` — Thin REST layer dispatching to service methods
- `VrpService` (~12K lines) — Core solver logic. Builds Jsprit `VehicleRoutingProblem` instances, configures cost matrices, runs the algorithm, and maps solutions to `VrpSolution` DTOs. Has four main solving methods matching the four endpoints.
- `SameVehicleConstraint` — Implements Jsprit's `HardRouteConstraint` to enforce that related pickup/delivery jobs use the same vehicle. Contains `JobAssignmentUpdater` inner class for state tracking.

## Key Dependencies

- **Spring Boot 3.2.1** — Web framework
- **Jsprit 1.9.0-beta.3** — VRP solving engine (GraphHopper)
- **Lombok** — Annotation-based boilerplate reduction on model classes

## Data Format

**Input:** JSON with `problemId`, `globalParameters` (capacity, counts), `fleets` (carrier + depot), `customers` (delivery/pickup demand per carrier), `costMatrix` (from/to/cost entries).

**Output:** JSON with `problemId`, `totalCost`, `routes` (vehicleId, activitySequence, routeCost), `status`, `message`.

## Python Scripts (`scripts_ce/`)

- `dat_to_json_ce.py` — Converts benchmark `.dat` files to API-compatible JSON
- `orchestrator_ce.py` — Batch-tests API with all JSON instances, generates CSV/Excel reports
- `gerar_relatorio_ce.py` — Generates performance reports from results
- Test instances live in `scripts_ce/json_output_ce/`
