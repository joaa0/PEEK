package io.peek.core.presentation;

import io.peek.core.exceptions.InvestigationService;
import io.peek.core.intelligence.JevContract;
import io.peek.core.intelligence.JevService;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class InvestigationController {
    private final InvestigationService investigations;
    private final JevService jev;
    public InvestigationController(InvestigationService investigations, JevService jev) {
        this.investigations = investigations; this.jev = jev;
    }
    public record InvestigationResponse(@JsonUnwrapped InvestigationService.InvestigationView context,
                                        JevContract.Analysis jev) {}
    @GetMapping("/api/v1/exceptions/{id}/context")
    public InvestigationResponse context(@PathVariable UUID id) {
        var context = investigations.get(id);
        // The read-only reconstruction transaction has completed before any network request.
        return new InvestigationResponse(context, jev.forException(context.exception()));
    }
}
