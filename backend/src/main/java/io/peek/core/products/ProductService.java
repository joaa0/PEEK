package io.peek.core.products;

import io.peek.core.shared.ConflictException;
import io.peek.core.shared.NotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {
    private final ProductRepository products;
    private final MappingRepository mappings;
    private final ProductAuditRepository productAudit;
    private final MappingAuditRepository mappingAudit;
    private final Clock clock;

    public ProductService(ProductRepository products, MappingRepository mappings,
                          ProductAuditRepository productAudit, MappingAuditRepository mappingAudit, Clock clock) {
        this.products = products;
        this.mappings = mappings;
        this.productAudit = productAudit;
        this.mappingAudit = mappingAudit;
        this.clock = clock;
    }

    public record ProductInput(String sku, String name, String description, BigDecimal price, String gtin, String category) {}
    public record ProductView(UUID id, String sku, String name, String description, BigDecimal price, String gtin,
                              String category, boolean active, Instant createdAt, Instant updatedAt, long version) {}
    public record MappingInput(String channel, String externalId, MappingStatus status) {}
    public record MappingView(UUID id, UUID productId, String channel, String externalId, MappingStatus status,
                              Instant createdAt, Instant updatedAt, long version) {}
    public record ProductAuditView(long revision, String operation, String sku, String name, String description,
                                   BigDecimal price, String gtin, String category, boolean active, Instant recordedAt) {}
    public record MappingAuditView(long revision, String operation, UUID productId, String channel,
                                   String externalId, MappingStatus status, Instant recordedAt) {}
    public record CreateResult<T>(T value, boolean created) {}

    @Transactional
    public CreateResult<ProductView> create(ProductInput input) {
        validate(input);
        String sku = input.sku().trim();
        var existing = products.findBySku(sku);
        if (existing.isPresent()) {
            ProductEntity value = existing.get();
            if (same(value, input)) return new CreateResult<>(view(value), false);
            throw new ConflictException("SKU already belongs to a different product representation");
        }
        ProductEntity product = new ProductEntity();
        product.id = UUID.randomUUID();
        product.sku = sku;
        assign(product, input);
        product.active = true;
        product.createdAt = clock.instant();
        product.updatedAt = product.createdAt;
        ProductEntity saved = products.saveAndFlush(product);
        audit(saved, "CREATED");
        return new CreateResult<>(view(saved), true);
    }

    @Transactional
    public ProductView update(UUID id, ProductInput input, long expectedVersion) {
        validate(input);
        ProductEntity product = require(id);
        if (product.version != expectedVersion) throw new ConflictException("Product version is stale");
        if (!product.sku.equals(input.sku().trim())) throw new IllegalArgumentException("SKU cannot be changed");
        if (!same(product, input)) {
            assign(product, input);
            product.updatedAt = clock.instant();
            products.flush();
            audit(product, "UPDATED");
        }
        return view(product);
    }

    /** Shared ordering boundary for evidence append and bounded inventory execution. */
    @Transactional
    public void lockForOperation(UUID id) { products.lockById(id).orElseThrow(() -> new NotFoundException("Product not found")); }

    @Transactional(readOnly = true)
    public ProductView get(UUID id) { return view(require(id)); }

    @Transactional(readOnly = true)
    public Optional<ProductView> bySku(String sku) { return products.findBySku(sku).map(ProductService::view); }

    @Transactional(readOnly = true)
    public List<ProductView> list() { return products.findAll().stream().map(ProductService::view).toList(); }

    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    @Transactional
    public CreateResult<MappingView> addMapping(UUID productId, MappingInput input) {
        products.lockById(productId).orElseThrow(() -> new NotFoundException("Product not found"));
        validate(input);
        String channel = input.channel().trim();
        String externalId = normalizedExternalId(input.externalId());
        var sameChannel = mappings.findByProductIdAndChannel(productId, channel);
        if (sameChannel.isPresent()) {
            MappingEntity mapping = sameChannel.get();
            if (Objects.equals(mapping.externalId, externalId) && mapping.status == input.status()) {
                return new CreateResult<>(view(mapping), false);
            }
            throw new ConflictException("Product already has a mapping for this channel");
        }
        if (externalId != null && mappings.findByChannelAndExternalId(channel, externalId).isPresent()) {
            throw new ConflictException("External identifier is already mapped in this channel");
        }
        MappingEntity mapping = new MappingEntity();
        mapping.id = UUID.randomUUID();
        mapping.productId = productId;
        mapping.channel = channel;
        mapping.externalId = externalId;
        mapping.status = input.status();
        mapping.createdAt = clock.instant();
        mapping.updatedAt = mapping.createdAt;
        MappingEntity saved = mappings.saveAndFlush(mapping);
        audit(saved, "CREATED");
        return new CreateResult<>(view(saved), true);
    }

    @Transactional
    public MappingView updateMapping(UUID mappingId, MappingInput input, long expectedVersion) {
        validate(input);
        MappingEntity mapping = requireMapping(mappingId);
        products.lockById(mapping.productId).orElseThrow();
        entityManager.refresh(mapping);
        if (mapping.version != expectedVersion) throw new ConflictException("Mapping version is stale");
        if (!mapping.channel.equals(input.channel().trim())) throw new IllegalArgumentException("Channel cannot be changed");
        String externalId = normalizedExternalId(input.externalId());
        if (externalId != null) {
            mappings.findByChannelAndExternalId(mapping.channel, externalId)
                .filter(found -> !found.id.equals(mapping.id))
                .ifPresent(found -> { throw new ConflictException("External identifier is already mapped in this channel"); });
        }
        if (!Objects.equals(mapping.externalId, externalId) || mapping.status != input.status()) {
            mapping.externalId = externalId;
            mapping.status = input.status();
            mapping.updatedAt = clock.instant();
            mappings.flush();
            audit(mapping, "UPDATED");
        }
        return view(mapping);
    }

    @Transactional(readOnly = true)
    public List<MappingView> mappings(UUID productId) {
        require(productId);
        return mappings.findByProductIdOrderByChannel(productId).stream().map(ProductService::view).toList();
    }

    @Transactional(readOnly = true)
    public MappingView resolve(String channel, String externalId) {
        if (blank(channel) || blank(externalId)) throw new IllegalArgumentException("channel and externalId are required");
        MappingEntity mapping = mappings.findByChannelAndExternalId(channel.trim(), externalId.trim())
            .orElseThrow(() -> new NotFoundException("Mapping not found"));
        if (mapping.status != MappingStatus.ACTIVE || !require(mapping.productId).active) {
            throw new ConflictException("Mapping or product is inactive");
        }
        return view(mapping);
    }

    @Transactional(readOnly = true)
    public List<ProductAuditView> productHistory(UUID productId) {
        require(productId);
        return productAudit.findByProductIdOrderByRevision(productId).stream()
            .map(row -> new ProductAuditView(row.revision, row.operation, row.sku, row.name, row.description,
                row.price, row.gtin, row.category, row.active, row.recordedAt)).toList();
    }

    @Transactional(readOnly = true)
    public List<MappingAuditView> mappingHistory(UUID mappingId) {
        requireMapping(mappingId);
        return mappingAudit.findByMappingIdOrderByRevision(mappingId).stream()
            .map(row -> new MappingAuditView(row.revision, row.operation, row.productId, row.channel,
                row.externalId, row.status, row.recordedAt)).toList();
    }

    private ProductEntity require(UUID id) { return products.findById(id).orElseThrow(() -> new NotFoundException("Product not found")); }
    private MappingEntity requireMapping(UUID id) { return mappings.findById(id).orElseThrow(() -> new NotFoundException("Mapping not found")); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalizedExternalId(String value) { return blank(value) ? null : value.trim(); }
    private static void validate(ProductInput input) {
        if (input == null || blank(input.sku()) || blank(input.name())) throw new IllegalArgumentException("sku and name are required");
        if (input.sku().length() > 100 || input.name().length() > 200) throw new IllegalArgumentException("sku or name exceeds maximum length");
        if (input.price() != null && input.price().signum() < 0) throw new IllegalArgumentException("price must be nonnegative");
        if (input.price() != null && (input.price().precision() > 15 || input.price().scale() > 2)) throw new IllegalArgumentException("price exceeds supported precision");
        if (input.gtin() != null && input.gtin().length() > 32) throw new IllegalArgumentException("gtin exceeds maximum length");
        if (input.category() != null && input.category().length() > 100) throw new IllegalArgumentException("category exceeds maximum length");
    }
    private static void validate(MappingInput input) {
        if (input == null || blank(input.channel()) || input.status() == null) throw new IllegalArgumentException("channel and status are required");
        if (input.channel().length() > 100) throw new IllegalArgumentException("channel exceeds maximum length");
        if (input.externalId() != null && input.externalId().length() > 200) throw new IllegalArgumentException("externalId exceeds maximum length");
        boolean pending = input.status() == MappingStatus.PENDING;
        if (pending == !blank(input.externalId())) throw new IllegalArgumentException("PENDING requires no externalId; other states require it");
    }
    private static boolean same(ProductEntity entity, ProductInput input) {
        return entity.sku.equals(input.sku().trim()) && entity.name.equals(input.name().trim())
            && Objects.equals(entity.description, input.description()) && samePrice(entity.price, input.price())
            && Objects.equals(entity.gtin, input.gtin()) && Objects.equals(entity.category, input.category());
    }
    private static boolean samePrice(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }
    private static void assign(ProductEntity entity, ProductInput input) {
        entity.name = input.name().trim();
        entity.description = input.description();
        entity.price = input.price();
        entity.gtin = input.gtin();
        entity.category = input.category();
    }
    private static ProductView view(ProductEntity p) {
        return new ProductView(p.id, p.sku, p.name, p.description, p.price, p.gtin, p.category,
            p.active, p.createdAt, p.updatedAt, p.version);
    }
    private static MappingView view(MappingEntity m) {
        return new MappingView(m.id, m.productId, m.channel, m.externalId, m.status, m.createdAt, m.updatedAt, m.version);
    }
    private void audit(ProductEntity product, String operation) {
        ProductAuditEntity row = new ProductAuditEntity();
        row.id = UUID.randomUUID(); row.productId = product.id; row.revision = product.version;
        row.operation = operation; row.sku = product.sku; row.name = product.name;
        row.description = product.description; row.price = product.price; row.gtin = product.gtin;
        row.category = product.category; row.active = product.active; row.recordedAt = clock.instant();
        productAudit.saveAndFlush(row);
    }
    private void audit(MappingEntity mapping, String operation) {
        MappingAuditEntity row = new MappingAuditEntity();
        row.id = UUID.randomUUID(); row.mappingId = mapping.id; row.productId = mapping.productId;
        row.revision = mapping.version; row.operation = operation; row.channel = mapping.channel;
        row.externalId = mapping.externalId; row.status = mapping.status; row.recordedAt = clock.instant();
        mappingAudit.saveAndFlush(row);
    }
}
