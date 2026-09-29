package io.peek.core.events;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EventWriter {
    private final EventRepository repository;
    public EventWriter(EventRepository repository) { this.repository = repository; }
    @Transactional
    public EventEntity append(EventEntity event) { return repository.saveAndFlush(event); }
}
