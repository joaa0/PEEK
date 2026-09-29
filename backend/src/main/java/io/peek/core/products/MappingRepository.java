package io.peek.core.products;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MappingRepository extends JpaRepository<MappingEntity, UUID> {
    List<MappingEntity> findByProductIdOrderByChannel(UUID productId);
    Optional<MappingEntity> findByProductIdAndChannel(UUID productId, String channel);
    Optional<MappingEntity> findByChannelAndExternalId(String channel, String externalId);
}
