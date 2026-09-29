package io.peek.core.orchestration;

import java.math.BigDecimal;
import java.util.UUID;

public interface OutboundCommandAdapter {
    record DispatchRequest(UUID commandId, int attemptNumber, UUID productId, UUID mappingId,
                           String channel, String externalProductId, String sku, String orderId,
                           String movementId, BigDecimal requestedQuantity, BigDecimal expectedStock,
                           boolean simulateFailure) {}
    record DispatchResult(boolean accepted, String externalRequestId, String errorCode, String errorMessage) {}

    CommandKind kind();
    DispatchResult dispatch(DispatchRequest request);
}
