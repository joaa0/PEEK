package io.peek.core.propagation;

import java.util.UUID;

public interface ProductDestinationAdapter {
    record Request(UUID productId, String channel, String operation, long productVersion,
                   String snapshotJson, String externalId, boolean simulateFailure) {}
    record Result(String externalId, String errorCode, String errorMessage) {
        public boolean successful() { return errorCode == null; }
    }
    boolean supports(String channel);
    Result dispatch(Request request);
}
