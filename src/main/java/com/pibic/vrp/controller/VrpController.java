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

    // ==================== CE-C8 (COM RESTRICAO C8) ====================

    /**
     * Resolve o problema CE COM restricao C8 (cada cliente visitado exatamente uma vez).
     * Equivalente ao modelo exato em Julia.
     */
    @PostMapping("/ce-c8")
    public VrpSolution solveCEWithC8(@RequestBody VrpInput input) {
        return vrpService.solveWithC8(input, "CE");
    }

    /**
     * Resolve o problema CE COM C8 e SEM colaboracao horizontal.
     * Cada transportadora atende apenas suas proprias demandas.
     */
    @PostMapping("/ce-c8-no-share")
    public VrpSolution solveCEWithC8NoShare(@RequestBody VrpInput input) {
        return vrpService.solveWithC8NoShare(input);
    }

    // ==================== CE-CUSTOM (COM CONSTRAINT DE MESMO VEICULO) ====================

    /**
     * Resolve o problema CE SEM restricao C8, mas com constraint customizada.
     * Multi-start com diferentes alocacoes de clientes compartilhados.
     * Simula a variavel z[i,r,s] do modelo exato.
     */
    @PostMapping("/ce-custom")
    public VrpSolution solveCECustom(@RequestBody VrpInput input) {
        return vrpService.solveWithCustomConstraint(input, "CE");
    }

    /**
     * Resolve CE-Custom SEM colaboracao horizontal.
     * Cada carrier atende apenas suas proprias demandas.
     */
    @PostMapping("/ce-custom-no-share")
    public VrpSolution solveCECustomNoShare(@RequestBody VrpInput input) {
        return vrpService.solveWithCustomConstraintNoShare(input);
    }
}
