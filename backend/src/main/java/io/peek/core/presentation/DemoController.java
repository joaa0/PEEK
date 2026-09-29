package io.peek.core.presentation;

import io.peek.core.demo.DemoResetService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo")
@ConditionalOnProperty(prefix = "peek.demo", name = "reset-enabled", havingValue = "true")
public class DemoController {
    private final DemoResetService reset;

    public DemoController(DemoResetService reset) {
        this.reset = reset;
    }

    public record ResetRequest(String runId, String confirmation) {}

    @PostMapping("/reset")
    public DemoResetService.ResetResult reset(@RequestBody ResetRequest request) {
        if (request == null || !"RESET_DEMO".equals(request.confirmation())) {
            throw new IllegalArgumentException("confirmation must be RESET_DEMO");
        }
        return reset.reset(request.runId());
    }
}
