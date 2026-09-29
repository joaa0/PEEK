package io.peek.core.presentation;

import io.peek.core.reconciliation.EvaluationService;
import java.time.Instant;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/evaluations")
public class EvaluationController {
    private final EvaluationService service;
    public EvaluationController(EvaluationService service) { this.service = service; }
    public record EvaluationInput(Instant asOf) {}
    @PostMapping
    public EvaluationService.EvaluationResult evaluate(@RequestBody EvaluationInput input) {
        if (input == null) throw new IllegalArgumentException("asOf is required");
        return service.evaluate(input.asOf());
    }
}
