package com.pibic.vrp.constraint;

import com.graphhopper.jsprit.core.problem.constraint.HardRouteConstraint;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.misc.JobInsertionContext;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forca jobs relacionados (entrega + coleta de uma mesma demanda) a serem servidos
 * pela MESMA TRANSPORTADORA, permitindo que estejam em veiculos distintos dela.
 *
 * Motivacao: no modelo exato (supercodigo.jl, run_CEc8) a variavel de roteamento
 * x[i,j,r] e indexada por transportadora, sem indice de veiculo, e uma unica
 * variavel z[i,r,s] governa entrega e coleta. O modelo exige, portanto, mesma
 * transportadora, e nao mesmo veiculo. Sem a restricao c8 o grau de um cliente
 * pode ser maior que um, e as solucoes exatas de fato usam essa liberdade.
 *
 * A {@link SameVehicleConstraint} continua correta para o cenario COM c8, em que
 * a visita unica implica mesmo veiculo. Esta classe existe para o cenario SEM c8.
 */
public class SameCarrierConstraint implements HardRouteConstraint {

    private final Map<String, String> relatedJobs;
    // jobId -> Vehicle que o atende, mantido pelo JobAssignmentUpdater
    private final Map<String, Vehicle> jobToVehicleMap;

    public SameCarrierConstraint(Map<String, String> relatedJobs) {
        this.relatedJobs = new HashMap<>(relatedJobs);
        this.jobToVehicleMap = new ConcurrentHashMap<>();
    }

    public Map<String, Vehicle> getJobToVehicleMap() {
        return jobToVehicleMap;
    }

    /**
     * Extrai a transportadora do veiculo. Os ids sao criados como
     * "vehicle_&lt;carrier&gt;__&lt;copia&gt;" em VrpService.addVehicleCopies.
     */
    public static String carrierOf(Vehicle vehicle) {
        if (vehicle == null || vehicle.getId() == null) return null;
        String id = vehicle.getId();
        int inicio = id.indexOf('_');
        if (inicio < 0) return id;
        int fim = id.indexOf("__", inicio + 1);
        return fim < 0 ? id.substring(inicio + 1) : id.substring(inicio + 1, fim);
    }

    @Override
    public boolean fulfilled(JobInsertionContext context) {
        Job newJob = context.getJob();
        String relatedJobId = relatedJobs.get(newJob.getId());
        if (relatedJobId == null) {
            return true;
        }

        Vehicle relatedJobVehicle = findVehicleForJob(context, relatedJobId);
        if (relatedJobVehicle == null) {
            // o par ainda nao foi inserido: qualquer transportadora serve
            return true;
        }

        String alvo = carrierOf(context.getNewVehicle());
        String atual = carrierOf(relatedJobVehicle);
        return alvo != null && alvo.equals(atual);
    }

    private Vehicle findVehicleForJob(JobInsertionContext context, String jobId) {
        VehicleRoute rota = context.getRoute();
        if (rota != null && !rota.isEmpty()) {
            for (Job job : rota.getTourActivities().getJobs()) {
                if (job.getId().equals(jobId)) {
                    return rota.getVehicle();
                }
            }
        }
        return jobToVehicleMap.get(jobId);
    }

    public void clear() {
        jobToVehicleMap.clear();
    }
}
