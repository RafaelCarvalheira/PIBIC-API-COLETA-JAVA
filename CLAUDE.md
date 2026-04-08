# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring Boot REST API for solving the Shared Customer Collaboration Vehicle Routing Problem (SCCVRP) using the Jsprit metaheuristic solver. Part of a PIBIC research project comparing VRP algorithms (original exact implementation is in Julia with Gurobi in `codigo_julia_exato/`).

## Build & Run

```bash
mvn clean package                    # Build JAR (target/vrp-api-0.0.1-SNAPSHOT.jar)
mvn spring-boot:run                  # Run API on localhost:8080
mvn test                             # Run all tests
mvn test -Dtest=ClassName            # Run a single test class
mvn test -Dtest=ClassName#method     # Run a single test method
```

Requires **Java 17** and **Maven 3.x**. No `application.properties` — Spring Boot defaults apply (port 8080).

## API Endpoints

All endpoints are POST under `/api/solve/` and accept `VrpInput` JSON:

- `/ce-c8` — C8 constraint (each customer visited exactly once), with horizontal collaboration
- `/ce-c8-no-share` — C8 without collaboration
- `/ce-custom` — Custom same-vehicle constraint with collaboration
- `/ce-custom-no-share` — Custom constraint without collaboration

## Architecture

Standard Spring MVC layering: **Controller → Service → Jsprit solver**

- `VrpController` — Thin REST layer dispatching to service methods
- `VrpService` (~1000 lines) — Core solver logic. Builds Jsprit `VehicleRoutingProblem` instances, configures cost matrices, runs the algorithm, and maps solutions to `VrpSolution` DTOs. Has four main solving methods matching the four endpoints.
- `SameVehicleConstraint` — Implements Jsprit's `HardRouteConstraint` to enforce that related pickup/delivery jobs use the same vehicle. Contains `JobAssignmentUpdater` inner class for state tracking.

### Solver Design (cross-cutting, spans VrpService + SameVehicleConstraint)

Two constraint approaches exist, each with a collaboration and no-collaboration variant:

**C8 mode** (`solveWithC8`/`solveWithC8NoShare`): Uses Jsprit vehicle skills to enforce which carrier can visit which customer. Multi-start with 10 iterations, 8 threads. In collaboration mode, shared customers get skills for both carriers; without collaboration, each carrier solves independently and results are merged.

**Custom constraint mode** (`solveWithCustomConstraint`/`solveWithCustomConstraintNoShare`): Generates up to 8 allocation strategies for shared customers (all to carrier 1, all to carrier 2, split, proximity-based, random variations). For each allocation, builds a Jsprit problem with `SameVehicleConstraint` + `StateManager` and runs multi-start (2 iterations). Returns the best allocation by total cost.

Key defaults: vehicle capacity 100, depot IDs "16" (carrier 1) and "17" (carrier 2) as hardcoded fallbacks, transport distance = transport time.

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
