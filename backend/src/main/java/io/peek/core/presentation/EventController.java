package io.peek.core.presentation;

import io.peek.core.events.EventInput;
import io.peek.core.events.EventService;
import io.peek.core.events.NormalizedEvent;
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
@RequestMapping("/api/v1/events")
public class EventController {
    private final EventService events;
    public EventController(EventService events) { this.events = events; }

    @PostMapping
    public ResponseEntity<NormalizedEvent> ingest(@RequestBody EventInput input) {
        var result = events.ingest(input);
        URI location = URI.create("/api/v1/events/" + result.event().id());
        return result.created() ? ResponseEntity.created(location).body(result.event())
            : ResponseEntity.ok().location(location).body(result.event());
    }

    @GetMapping("/{id}")
    public NormalizedEvent get(@PathVariable UUID id) { return events.get(id); }
}
