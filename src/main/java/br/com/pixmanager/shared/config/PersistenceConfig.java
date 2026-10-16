package br.com.pixmanager.shared.config;

import br.com.pixmanager.shared.util.AuthenticatedClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.util.Optional;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware", dateTimeProviderRef = "auditingDateTimeProvider")
public class PersistenceConfig {

    @Bean
    AuditorAware<String> auditorAware() {
        return () -> Optional.of(AuthenticatedClient.clientId());      // "anonymous"/"system" fora de requisição (jobs/consumers)
    }

    @Bean
    DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }
}
