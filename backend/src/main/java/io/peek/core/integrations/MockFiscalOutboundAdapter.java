package io.peek.core.integrations;

import io.peek.core.orchestration.CommandKind;
import io.peek.core.orchestration.OutboundCommandAdapter;
import org.springframework.stereotype.Component;

@Component
public class MockFiscalOutboundAdapter implements OutboundCommandAdapter {
    @Override public CommandKind kind() { return CommandKind.FISCAL; }

    @Override
    public DispatchResult dispatch(DispatchRequest request) {
        if (request.simulateFailure()) {
            return new DispatchResult(false, null, "MOCK_FISCAL_DISPATCH_FAILURE",
                "Simulated fiscal adapter failure");
        }
        return new DispatchResult(true, "mock-fiscal-" + request.commandId() + "-" + request.attemptNumber(),
            null, null);
    }
}
