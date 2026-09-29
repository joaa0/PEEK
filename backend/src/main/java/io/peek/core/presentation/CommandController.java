package io.peek.core.presentation;

import io.peek.core.orchestration.CommandKind;
import io.peek.core.orchestration.CommandService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/commands")
public class CommandController {
    private final CommandService service;
    public CommandController(CommandService service) { this.service = service; }

    @PostMapping("/inventory-sync")
    public ResponseEntity<CommandService.CommandView> inventory(@RequestBody CommandService.CommandInput input) {
        return created(service.create(CommandKind.INVENTORY_SYNC, input));
    }
    @PostMapping("/fiscal")
    public ResponseEntity<CommandService.CommandView> fiscal(@RequestBody CommandService.CommandInput input) {
        return created(service.create(CommandKind.FISCAL, input));
    }
    @GetMapping("/{id}")
    public CommandService.CommandView get(@PathVariable UUID id) { return service.get(id); }
    public record RetryInput(String idempotencyKey, boolean simulateFailure) {}
    @PostMapping("/{id}/retry")
    public CommandService.CommandView retry(@PathVariable UUID id, @RequestBody RetryInput input) {
        if (input == null) throw new IllegalArgumentException("Retry input is required");
        return service.retry(id, input.idempotencyKey(), input.simulateFailure());
    }
    private ResponseEntity<CommandService.CommandView> created(CommandService.CreateResult result) {
        URI location = URI.create("/api/v1/commands/" + result.command().id());
        return result.created() ? ResponseEntity.created(location).body(result.command())
            : ResponseEntity.ok().location(location).body(result.command());
    }
}
