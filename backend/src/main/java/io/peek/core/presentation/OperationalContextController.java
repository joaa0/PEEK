package io.peek.core.presentation;

import io.peek.core.operational_state.OperationalContextService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OperationalContextController {
    private final OperationalContextService context;
    public OperationalContextController(OperationalContextService context) { this.context = context; }

    @GetMapping("/api/v1/products/{id}/context")
    public OperationalContextService.ContextView context(@PathVariable UUID id) {
        return context.forProduct(id);
    }
}
