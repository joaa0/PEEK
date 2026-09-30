package io.peek.core.propagation;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PropagationCommandRepository extends JpaRepository<PropagationCommandEntity, UUID> {
    @org.springframework.data.jpa.repository.Query("select c.productId from PropagationCommandEntity c where c.id = :id")
    Optional<UUID> productIdFor(@org.springframework.data.repository.query.Param("id") UUID id);
    Optional<PropagationCommandEntity> findByChannelAndIdempotencyKey(String channel, String idempotencyKey);
    List<PropagationCommandEntity> findByProductIdOrderByRequestedAtDescIdDesc(UUID productId);
}
