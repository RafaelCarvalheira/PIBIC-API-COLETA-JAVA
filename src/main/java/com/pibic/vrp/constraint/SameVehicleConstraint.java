package com.pibic.vrp.constraint;

import com.graphhopper.jsprit.core.algorithm.state.StateUpdater;
import com.graphhopper.jsprit.core.problem.constraint.HardRouteConstraint;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.misc.JobInsertionContext;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.ActivityVisitor;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Constraint que forca jobs relacionados a serem atendidos pelo mesmo veiculo.
 *
 * No contexto do problema CE (Coleta e Entrega), esta constraint garante que
 * o Delivery e Pickup da mesma demanda sejam feitos pelo mesmo carrier.
 *
 * Exemplo:
 * - d_5_1 (delivery da demanda do carrier 1 no cliente 5)
 * - p_5_1 (pickup da demanda do carrier 1 no cliente 5)
 *
 * A constraint garante que d_5_1 e p_5_1 estejam no mesmo veiculo,
 * equivalente a variavel z[i,r,s] do modelo exato onde o mesmo carrier s
 * que faz delivery tambem faz pickup da demanda de r no cliente i.
 */
public class SameVehicleConstraint implements HardRouteConstraint {

    private final Map<String, String> relatedJobs;
    private final Map<String, String> jobToVehicleMap;

    public SameVehicleConstraint(Map<String, String> relatedJobs) {
        this.relatedJobs = new HashMap<>(relatedJobs);
        this.jobToVehicleMap = new ConcurrentHashMap<>();
    }

    public Map<String, String> getJobToVehicleMap() {
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

        String targetVehicleId = context.getNewVehicle().getId();
        String relatedJobVehicleId = findVehicleForJob(context, relatedJobId);

        if (relatedJobVehicleId == null) {
            return true;
        }

        return targetVehicleId.equals(relatedJobVehicleId);
    }

    private String findVehicleForJob(JobInsertionContext context, String jobId) {
        VehicleRoute currentRoute = context.getRoute();
        if (currentRoute != null && !currentRoute.isEmpty()) {
            for (Job job : currentRoute.getTourActivities().getJobs()) {
                if (job.getId().equals(jobId)) {
                    return currentRoute.getVehicle().getId();
                }
            }
        }

        String vehicleId = jobToVehicleMap.get(jobId);
        if (vehicleId != null) {
            return vehicleId;
        }

        return null;
    }

    public void clear() {
        jobToVehicleMap.clear();
    }

    public static class JobAssignmentUpdater implements StateUpdater, ActivityVisitor {

        private final Map<String, String> jobToVehicleMap;
        private VehicleRoute currentRoute;

        public JobAssignmentUpdater(Map<String, String> jobToVehicleMap) {
            this.jobToVehicleMap = jobToVehicleMap;
        }

        @Override
        public void begin(VehicleRoute route) {
            this.currentRoute = route;
            String vehicleId = route.getVehicle().getId();
            jobToVehicleMap.entrySet().removeIf(entry -> entry.getValue().equals(vehicleId));
        }

        @Override
        public void visit(TourActivity activity) {
            if (activity instanceof TourActivity.JobActivity) {
                TourActivity.JobActivity jobActivity = (TourActivity.JobActivity) activity;
                String jobId = jobActivity.getJob().getId();
                String vehicleId = currentRoute.getVehicle().getId();
                jobToVehicleMap.put(jobId, vehicleId);
            }
        }

        @Override
        public void finish() {
        }
    }
}
