package io.peek.core.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.peek.core.products.ProductService;
import io.peek.core.orchestration.CommandConfirmationService;
import io.peek.core.shared.ConflictException;
import io.peek.core.shared.NotFoundException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class EventService {
    private final EventRepository repository;
    private final EventWriter writer;
    private final ProductService products;
    private final ObjectMapper json;
    private final Clock clock;
    private final CommandConfirmationService confirmations;

    public EventService(EventRepository repository, EventWriter writer, ProductService products, ObjectMapper json,
                        Clock clock, CommandConfirmationService confirmations) {
        this.repository = repository;
        this.writer = writer;
        this.products = products;
        this.json = json;
        this.clock = clock;
        this.confirmations = confirmations;
    }

    public record IngestResult(NormalizedEvent event, boolean created) {}

    public IngestResult ingest(EventInput input) {
        EventType type = EventValidation.validate(input);
        String hash = hash(input);
        var existing = repository.findBySourceAndExternalEventId(input.source().trim(), input.externalEventId().trim());
        if (existing.isPresent()) {
            IngestResult result = duplicate(existing.get(), hash);
            confirmations.accept(result.event());
            return result;
        }

        UUID productId = input.productId();
        String sku = input.sku() == null || input.sku().isBlank() ? null : input.sku().trim();
        if (input.externalProductId() != null && !input.externalProductId().isBlank()) {
            var mapping = products.resolve(input.source(), input.externalProductId());
            if (productId != null && !productId.equals(mapping.productId())) throw new ConflictException("Product and mapping disagree");
            productId = mapping.productId();
        }
        if (productId != null) {
            var product = products.get(productId);
            if (sku != null && !sku.equals(product.sku())) throw new ConflictException("SKU and product disagree");
            sku = product.sku();
        } else if (sku != null) {
            productId = products.bySku(sku).map(ProductService.ProductView::id).orElse(null);
        }
        EventEntity entity = new EventEntity();
        entity.id = UUID.randomUUID();
        entity.source = input.source().trim();
        entity.externalEventId = input.externalEventId().trim();
        entity.type = type;
        entity.occurredAt = input.occurredAt().truncatedTo(ChronoUnit.MICROS);
        entity.receivedAt = clock.instant();
        entity.productId = productId;
        entity.sku = sku;
        entity.externalProductId = input.externalProductId();
        entity.orderId = input.orderId();
        entity.invoiceId = input.invoiceId();
        entity.receiptId = input.receiptId();
        entity.movementId = input.movementId();
        entity.quantity = input.quantity();
        entity.stockAfter = input.stockAfter();
        entity.confirmed = Boolean.TRUE.equals(input.confirmed());
        entity.metadataJson = jsonText(input.metadata() == null ? Map.of() : new TreeMap<>(input.metadata()));
        entity.payloadHash = hash;
        EventEntity saved;
        try {
            saved = writer.append(entity);
        } catch (DataIntegrityViolationException race) {
            return repository.findBySourceAndExternalEventId(entity.source, entity.externalEventId)
                .map(found -> {
                    IngestResult result = duplicate(found, hash);
                    confirmations.accept(result.event());
                    return result;
                })
                .orElseThrow(() -> race);
        }
        return new IngestResult(toDomain(saved), true);
    }

    public NormalizedEvent get(UUID id) {
        return repository.findById(id).map(this::toDomain).orElseThrow(() -> new NotFoundException("Event not found"));
    }

    public List<NormalizedEvent> forSku(String sku) {
        return repository.findBySkuOrderByOccurredAtAscReceivedAtAsc(sku).stream().map(this::toDomain).toList();
    }

    private IngestResult duplicate(EventEntity found, String hash) {
        if (!found.payloadHash.equals(hash)) throw new ConflictException("Source and externalEventId already identify a different event");
        return new IngestResult(toDomain(found), false);
    }

    private NormalizedEvent toDomain(EventEntity e) {
        try {
            Map<String, String> metadata = json.readValue(e.metadataJson, new TypeReference<>() {});
            return new NormalizedEvent(e.id, e.source, e.externalEventId, e.type, e.occurredAt,
                e.receivedAt, e.productId, e.sku, e.externalProductId, e.orderId, e.invoiceId,
                e.receiptId, e.movementId, e.quantity, e.stockAfter, e.confirmed, metadata);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Stored event metadata is invalid", error);
        }
    }

    private String hash(EventInput input) {
        try {
            EventInput ordered = new EventInput(input.source().trim(), input.externalEventId().trim(), input.type(),
                input.occurredAt(), input.productId(), input.sku(), input.externalProductId(), input.orderId(),
                input.invoiceId(), input.receiptId(), input.movementId(), input.quantity(), input.stockAfter(),
                input.confirmed(), input.metadata() == null ? Map.of() : new TreeMap<>(input.metadata()));
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(ordered));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException | JsonProcessingException error) {
            throw new IllegalStateException("Unable to fingerprint event", error);
        }
    }

    private String jsonText(Map<String, String> value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("Invalid metadata", error); }
    }
}
