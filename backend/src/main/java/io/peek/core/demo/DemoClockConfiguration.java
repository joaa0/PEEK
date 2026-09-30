package io.peek.core.demo;

import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@Profile("demo")
@ConditionalOnProperty(name = "peek.demo.clock")
public class DemoClockConfiguration {
    @Bean @Primary
    Clock demoClock(@Value("${peek.demo.clock}") String value) {
        return Clock.fixed(Instant.parse(value), ZoneOffset.UTC);
    }
}
