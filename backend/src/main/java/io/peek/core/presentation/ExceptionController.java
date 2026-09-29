package io.peek.core.presentation;

import io.peek.core.exceptions.ExceptionCode;
import io.peek.core.exceptions.ExceptionService;
import io.peek.core.exceptions.ExceptionService.ExceptionView;
import io.peek.core.exceptions.ExceptionStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exceptions")
public class ExceptionController {
    private final ExceptionService exceptions;
    public ExceptionController(ExceptionService exceptions) { this.exceptions = exceptions; }
    public record ResolutionInput(String note) {}

    @GetMapping
    public List<ExceptionView> list(@RequestParam(required = false) ExceptionStatus status,
                                    @RequestParam(required = false) ExceptionCode code) {
        return exceptions.list(status, code);
    }
    @GetMapping("/{id}")
    public ExceptionView get(@PathVariable UUID id) { return exceptions.get(id); }
    @PostMapping("/{id}/resolve")
    public ExceptionView resolve(@PathVariable UUID id, @RequestBody ResolutionInput input) {
        return exceptions.resolve(id, input == null ? null : input.note());
    }
}
