package io.peek.core.presentation;

import io.peek.core.exceptions.InvestigationService;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class InvestigationController {
    private final InvestigationService investigations;
    public InvestigationController(InvestigationService investigations) { this.investigations = investigations; }
    @GetMapping("/api/v1/exceptions/{id}/context")
    public InvestigationService.InvestigationView context(@PathVariable UUID id) { return investigations.get(id); }
}
