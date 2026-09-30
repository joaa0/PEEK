package io.peek.core.intelligence;

/** Domain-facing SPI. Transport, provider and model do not enter the JEV contract. */
public interface LlmClient {
    String interpret(JevContract.Input input);

    enum FailureReason { TIMEOUT, API_FAILURE, INVALID_RESPONSE }
    final class Failure extends RuntimeException {
        private final FailureReason reason;
        public Failure(FailureReason reason) { super(reason.name()); this.reason = reason; }
        public FailureReason reason() { return reason; }
    }
}
