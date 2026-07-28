package com.pibic.vrp.controller;

import com.pibic.vrp.model.VrpInput;
import com.pibic.vrp.model.VrpSolution;
import com.pibic.vrp.service.VrpService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/solve")
public class VrpController {

    @Autowired
    private VrpService vrpService;

    // ==================== run_CE (COM restricao C8 - cada cliente 1 visita) ====================
    // Equivalente ao run_CE do Julia (supercodigo.jl). Nome alinhado com o modelo exato.

    @PostMapping("/run-CE")
    public VrpSolution solveRunCE(@RequestBody VrpInput input) {
        return vrpService.solveWithC8(input, "CE");
    }

    @PostMapping("/run-CE-no-share")
    public VrpSolution solveRunCENoShare(@RequestBody VrpInput input) {
        return vrpService.solveWithC8NoShare(input);
    }

    // ==================== run_CEc8 (SEM restricao C8 - permite multiplas visitas) ====================
    // Equivalente ao run_CEc8 do Julia. Usa SameVehicleConstraint + multi-start de alocacoes.

    @PostMapping("/run-CEc8")
    public VrpSolution solveRunCEc8(@RequestBody VrpInput input) {
        return vrpService.solveWithCustomConstraint(input, "CE");
    }

    // ---- V2 experimental: modelagem corrigida do run_CEc8 (aditivo, remover para reverter)
    @PostMapping("/run-CEc8-v2")
    public VrpSolution solveRunCEc8V2(@RequestBody VrpInput input) {
        return vrpService.solveWithCustomConstraintV2(input);
    }

    @PostMapping("/run-CEc8-no-share")
    public VrpSolution solveRunCEc8NoShare(@RequestBody VrpInput input) {
        return vrpService.solveWithCustomConstraintNoShare(input);
    }
}
