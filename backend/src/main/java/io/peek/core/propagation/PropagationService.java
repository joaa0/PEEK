package io.peek.core.propagation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.peek.core.exceptions.ExceptionService.EvidenceView;
import io.peek.core.products.*;
import io.peek.core.shared.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PropagationService {
    private final ProductService products;
    private final ProductRepository productRows;
    private final PropagationCommandRepository commands;
    private final PropagationAttemptRepository attempts;
    private final List<ProductDestinationAdapter> adapters;
    private final ObjectMapper json;
    private final Clock clock;

    public PropagationService(ProductService products, ProductRepository productRows,
        PropagationCommandRepository commands, PropagationAttemptRepository attempts,
        List<ProductDestinationAdapter> adapters, ObjectMapper json, Clock clock) {
        this.products = products; this.productRows = productRows; this.commands = commands;
        this.attempts = attempts; this.adapters = adapters; this.json = json; this.clock = clock;
    }
    public record Target(String channel, boolean simulateFailure) {}
    public record Input(String idempotencyKey, long productVersion, List<Target> targets) {}
    public record RetryInput(String idempotencyKey, boolean simulateFailure) {}
    public record AttemptView(UUID id, int attemptNumber, String idempotencyKey, Instant dispatchedAt,
        Instant respondedAt, String result, String externalId, String errorCode, String errorMessage) {}
    public record CommandView(UUID id, UUID productId, UUID mappingId, String channel, String operation,
        String idempotencyKey, long productVersion, Instant requestedAt, String status, Instant completedAt,
        String externalId, List<AttemptView> attempts, List<EvidenceView> evidence) {}

    @Transactional
    public List<CommandView> start(UUID productId, Input input) {
        if (input == null) throw new IllegalArgumentException("Propagation input is required");
        validateKey(input.idempotencyKey());
        if (input.targets() == null || input.targets().isEmpty())
            throw new IllegalArgumentException("Select at least one destination");
        lock(productId);
        var product = products.get(productId);
        Set<String> channels = new HashSet<>();
        List<PropagationCommandEntity> rows = new ArrayList<>();
        // Validate the entire request before dispatching any selected target.
        for (Target target : input.targets()) {
            if (target == null || target.channel() == null || !channels.add(target.channel()))
                throw new IllegalArgumentException("Destinations must be unique");
            adapter(target.channel());
            var existing = commands.findByChannelAndIdempotencyKey(target.channel(), input.idempotencyKey());
            if (existing.isPresent()) {
                var row = existing.get();
                if (!row.productId.equals(productId) || row.productVersion != input.productVersion()
                    || row.simulateFailure != target.simulateFailure())
                    throw new ConflictException("Propagation key reused for a different request");
                rows.add(row);
            } else {
                if (product.version() != input.productVersion())
                    throw new ConflictException("Product version is stale; reload before propagating");
                var mapping = products.mappings(productId).stream()
                    .filter(m -> m.channel().equals(target.channel())).findFirst();
                if (mapping.isPresent() && mapping.get().status() == MappingStatus.INACTIVE)
                    throw new ConflictException("Destination mapping is inactive");
                rows.add(null);
            }
        }
        List<CommandView> results = new ArrayList<>();
        for (int index = 0; index < input.targets().size(); index++) {
            Target target = input.targets().get(index);
            PropagationCommandEntity row = rows.get(index);
            if (row == null) {
                var mapping = products.mappings(productId).stream()
                    .filter(m -> m.channel().equals(target.channel())).findFirst()
                    .orElseGet(() -> products.addMapping(productId,
                        new ProductService.MappingInput(target.channel(), null, MappingStatus.PENDING)).value());
                row = new PropagationCommandEntity();
                row.id = UUID.randomUUID(); row.productId = productId; row.mappingId = mapping.id();
                row.channel = target.channel(); row.operation = mapping.externalId() == null ? "CREATE" : "UPDATE";
                row.idempotencyKey = input.idempotencyKey(); row.productVersion = product.version();
                try { row.snapshotJson = json.writeValueAsString(product); }
                catch (JsonProcessingException error) { throw new IllegalStateException(error); }
                row.simulateFailure = target.simulateFailure(); row.requestedAt = clock.instant();
                row.status = "REQUESTED";
                commands.saveAndFlush(row);
                dispatch(row, input.idempotencyKey(), target.simulateFailure());
            }
            results.add(view(row));
        }
        return List.copyOf(results);
    }
    @Transactional
    public CommandView retry(UUID id, RetryInput input) {
        if (input == null) throw new IllegalArgumentException("Retry input is required");
        validateKey(input.idempotencyKey());
        lock(commands.productIdFor(id).orElseThrow(() -> new NotFoundException("Product propagation not found")));
        // Reload after obtaining the product lock, serializing starts and retries per product.
        var row = commands.findById(id).orElseThrow();
        var replay = attempts.findByCommandIdAndIdempotencyKey(id, input.idempotencyKey());
        if (replay.isPresent()) {
            if (replay.get().simulateFailure != input.simulateFailure())
                throw new ConflictException("Retry key reused for a different request");
            return view(row);
        }
        if (!row.status.equals("FAILED")) throw new ConflictException("Only failed propagation can be retried");
        if (products.get(row.productId).version() != row.productVersion)
            throw new ConflictException("Product changed; start a new propagation for the current version");
        dispatch(row, input.idempotencyKey(), input.simulateFailure());
        return view(row);
    }
    @Transactional(readOnly = true)
    public List<CommandView> list(UUID productId) {
        products.get(productId);
        return commands.findByProductIdOrderByRequestedAtDescIdDesc(productId).stream().map(this::view).toList();
    }
    @Transactional(readOnly = true)
    public CommandView get(UUID id) { return view(require(id)); }
    private void dispatch(PropagationCommandEntity row, String key, boolean fail) {
        var mapping = products.mappings(row.productId).stream().filter(m -> m.id().equals(row.mappingId))
            .findFirst().orElseThrow();
        if (mapping.status() == MappingStatus.INACTIVE) throw new ConflictException("Destination mapping is inactive");
        var attempt = new PropagationAttemptEntity();
        attempt.id = UUID.randomUUID(); attempt.commandId = row.id;
        attempt.attemptNumber = attempts.findByCommandIdOrderByAttemptNumber(row.id).size() + 1;
        attempt.idempotencyKey = key; attempt.simulateFailure = fail; attempt.dispatchedAt = clock.instant();
        var result = adapter(row.channel).dispatch(new ProductDestinationAdapter.Request(row.productId,
            row.channel, row.operation, row.productVersion, row.snapshotJson, mapping.externalId(), fail));
        attempt.respondedAt = clock.instant();
        attempt.result = result.successful() ? "SUCCEEDED" : "FAILED";
        attempt.externalId = result.externalId(); attempt.errorCode = result.errorCode();
        attempt.errorMessage = result.errorMessage();
        attempts.saveAndFlush(attempt);
        row.status = attempt.result; row.completedAt = attempt.respondedAt;
        if (result.successful()) {
            products.updateMapping(mapping.id(), new ProductService.MappingInput(row.channel,
                result.externalId(), MappingStatus.ACTIVE), mapping.version());
            row.externalId = result.externalId();
        }
        commands.saveAndFlush(row);
    }
    private CommandView view(PropagationCommandEntity row) {
        var history = attempts.findByCommandIdOrderByAttemptNumber(row.id).stream()
            .map(a -> new AttemptView(a.id, a.attemptNumber, a.idempotencyKey, a.dispatchedAt,
                a.respondedAt, a.result, a.externalId, a.errorCode, a.errorMessage)).toList();
        List<EvidenceView> evidence = new ArrayList<>();
        evidence.add(new EvidenceView(row.id, null, "PRODUCT_COMMAND", "peek", "Product version / operation",
            row.productVersion + " / " + row.operation + " / " + row.id, row.requestedAt));
        for (var a : history) {
            evidence.add(new EvidenceView(a.id(), null, "PRODUCT_ATTEMPT", row.channel,
                "Attempt " + a.attemptNumber() + " / " + a.idempotencyKey(),
                a.result() + " / " + (a.errorCode() == null ? a.externalId() : a.errorCode() + ": " + a.errorMessage()),
                a.respondedAt()));
        }
        return new CommandView(row.id, row.productId, row.mappingId, row.channel, row.operation,
            row.idempotencyKey, row.productVersion, row.requestedAt, row.status, row.completedAt,
            row.externalId, history, List.copyOf(evidence));
    }
    private ProductDestinationAdapter adapter(String channel) {
        return adapters.stream().filter(a -> a.supports(channel)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported simulated destination"));
    }
    private void lock(UUID productId) {
        productRows.lockById(productId).orElseThrow(() -> new NotFoundException("Product not found"));
    }
    private PropagationCommandEntity require(UUID id) {
        return commands.findById(id).orElseThrow(() -> new NotFoundException("Product propagation not found"));
    }
    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 200)
            throw new IllegalArgumentException("idempotencyKey is required (max 200 characters)");
    }
}
