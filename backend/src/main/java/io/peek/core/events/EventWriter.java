package io.peek.core.events;

import io.peek.core.products.ProductRepository;
import io.peek.core.orchestration.CommandConfirmationService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EventWriter {
    private final EventRepository repository;
    private final ProductRepository products;
    private final CommandConfirmationService confirmations;
    public EventWriter(EventRepository repository, ProductRepository products, CommandConfirmationService confirmations) {
        this.repository = repository; this.products = products; this.confirmations = confirmations;
    }
    @Transactional
    public EventEntity append(EventEntity event) {
        if (event.productId != null) products.lockById(event.productId).orElseThrow();
        EventEntity saved = repository.saveAndFlush(event);
        // Event append and command confirmation commit atomically under the same product lock.
        confirmations.accept(new NormalizedEvent(saved.id, saved.source, saved.externalEventId, saved.type,
            saved.occurredAt, saved.receivedAt, saved.productId, saved.sku, saved.externalProductId,
            saved.orderId, saved.invoiceId, saved.receiptId, saved.movementId, saved.quantity,
            saved.stockAfter, saved.confirmed, java.util.Map.of()));
        return saved;
    }
}
