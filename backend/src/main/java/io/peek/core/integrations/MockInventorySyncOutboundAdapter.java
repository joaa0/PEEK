package io.peek.core.integrations;

import io.peek.core.orchestration.CommandKind;
import io.peek.core.orchestration.OutboundCommandAdapter;
import org.springframework.stereotype.Component;

@Component
public class MockInventorySyncOutboundAdapter implements OutboundCommandAdapter {
    @Override public CommandKind kind() { return CommandKind.INVENTORY_SYNC; }

    @Override
    public DispatchResult dispatch(DispatchRequest request) {
        if (request.simulateFailure()) {
            return new DispatchResult(false, null, "MOCK_INVENTORY_DISPATCH_FAILURE",
                "Simulated inventory adapter failure");
        }
        return new DispatchResult(true, "mock-inventory-" + request.commandId() + "-" + request.attemptNumber(),
            null, null);
    }
}
