package io.peek.core.reconciliation;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Local demo driver for the existing deterministic engine; never invoked by MCP tools. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "peek.mcp.reconciliation.enabled", havingValue = "true")
public class LocalReconciliationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(LocalReconciliationScheduler.class);
    private final EvaluationService evaluator;
    private final Clock clock;
    public LocalReconciliationScheduler(EvaluationService evaluator, Clock clock) {
        this.evaluator = evaluator; this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${peek.mcp.reconciliation.interval-ms:1000}")
    public void evaluate() {
        try { evaluator.evaluate(clock.instant()); }
        catch (RuntimeException failure) { LOG.error("Local reconciliation failed; next evaluation will retry", failure); }
    }
}
