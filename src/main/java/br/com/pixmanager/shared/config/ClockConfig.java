package br.com.pixmanager.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** {@link Clock} injetável: o tempo nunca vem de {@code Instant.now()} solto (testes fixam o relógio). */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
