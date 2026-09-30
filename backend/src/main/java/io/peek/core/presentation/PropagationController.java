package io.peek.core.presentation;

import io.peek.core.propagation.PropagationService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class PropagationController {
    private final PropagationService propagation;
    public PropagationController(PropagationService propagation) { this.propagation = propagation; }
    @PostMapping("/api/v1/products/{id}/propagations")
    public List<PropagationService.CommandView> start(@PathVariable UUID id,
        @RequestBody PropagationService.Input input) { return propagation.start(id, input); }
    @GetMapping("/api/v1/products/{id}/propagations")
    public List<PropagationService.CommandView> list(@PathVariable UUID id) { return propagation.list(id); }
    @GetMapping("/api/v1/product-propagations/{id}")
    public PropagationService.CommandView get(@PathVariable UUID id) { return propagation.get(id); }
    @PostMapping("/api/v1/product-propagations/{id}/retry")
    public PropagationService.CommandView retry(@PathVariable UUID id,
        @RequestBody PropagationService.RetryInput input) { return propagation.retry(id, input); }
}
