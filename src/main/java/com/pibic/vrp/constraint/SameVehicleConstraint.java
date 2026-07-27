package com.pibic.vrp.constraint;

import com.graphhopper.jsprit.core.algorithm.state.StateUpdater;
import com.graphhopper.jsprit.core.problem.constraint.HardRouteConstraint;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.misc.JobInsertionContext;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.ActivityVisitor;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Constraint que forca jobs relacionados (delivery+pickup da mesma demanda) a serem
 * servidos pelo MESMO VEICULO FISICO - nao apenas pelo mesmo ID.
 *
 * IMPORTANTE: com FleetSize.INFINITE o Jsprit instancia varias copias do mesmo
 * template de veiculo, todas compartilhando o ID (ex: "vehicle_2"). Comparar so
 * IDs levava a bug onde delivery e pickup acabavam em rotas fisicas distintas
 * do mesmo ID, gerando solucoes invalidas com custo menor que o Gurobi.
 * Agora comparamos a referencia do objeto Vehicle (identidade).
 */
public class SameVehicleConstraint implements HardRouteConstraint {

    private final Map<String, String> relatedJobs;
    // jobId -> Vehicle (instancia fisica), atualizado pelo StateUpdater
    private final Map<String, Vehicle> jobToVehicleMap;

    public SameVehicleConstraint(Map<String, String> relatedJobs) {
        this.relatedJobs = new HashMap<>(relatedJobs);
        this.jobToVehicleMap = new ConcurrentHashMap<>();
    }

    public Map<String, Vehicle> getJobToVehicleMap() {
        return jobToVehicleMap;
    }

    @Override
    public boolean fulfilled(JobInsertionContext context) {
        Job newJob = context.getJob();
        String newJobId = newJob.getId();

        String relatedJobId = relatedJobs.get(newJobId);
        if (relatedJobId == null) {
            return true;
        }

        Vehicle targetVehicle = context.getNewVehicle();
        Vehicle relatedJobVehicle = findVehicleForJob(context, relatedJobId);

        if (relatedJobVehicle == null) {
            return true;
        }

        // Identidade de referencia: veiculos com mesmo ID mas objetos distintos
        // (copias sob FleetSize.INFINITE) representam rotas fisicas diferentes.
        return targetVehicle == relatedJobVehicle;
    }

    private Vehicle findVehicleForJob(JobInsertionContext context, String jobId) {
        VehicleRoute currentRoute = context.getRoute();
        if (currentRoute != null && !currentRoute.isEmpty()) {
            for (Job job : currentRoute.getTourActivities().getJobs()) {
                if (job.getId().equals(jobId)) {
                    return currentRoute.getVehicle();
                }
            }
        }
        return jobToVehicleMap.get(jobId);
    }

    public void clear() {
        jobToVehicleMap.clear();
    }

    public static class JobAssignmentUpdater implements StateUpdater, ActivityVisitor {

        private final Map<String, Vehicle> jobToVehicleMap;
        private VehicleRoute currentRoute;

        public JobAssignmentUpdater(Map<String, Vehicle> jobToVehicleMap) {
            this.jobToVehicleMap = jobToVehicleMap;
        }

        @Override
        public void begin(VehicleRoute route) {
            this.currentRoute = route;
            Vehicle vehicle = route.getVehicle();
            // Remove entradas previas cujo Vehicle era esta mesma instancia
            // (a rota esta sendo revisitada e sera reescrita).
            jobToVehicleMap.entrySet().removeIf(entry -> entry.getValue() == vehicle);
        }

        @Override
        public void visit(TourActivity activity) {
            if (activity instanceof TourActivity.JobActivity) {
                TourActivity.JobActivity jobActivity = (TourActivity.JobActivity) activity;
                String jobId = jobActivity.getJob().getId();
                jobToVehicleMap.put(jobId, currentRoute.getVehicle());
            }
        }

        @Override
        public void finish() {
        }
    }
}
