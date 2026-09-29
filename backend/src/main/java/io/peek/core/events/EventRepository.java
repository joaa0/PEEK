package io.peek.core.events;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<EventEntity, UUID> {
    Optional<EventEntity> findBySourceAndExternalEventId(String source, String externalEventId);
    List<EventEntity> findBySkuOrderByOccurredAtAscReceivedAtAsc(String sku);
    List<EventEntity> findByProductIdOrderByOccurredAtAsc(UUID productId);
}
