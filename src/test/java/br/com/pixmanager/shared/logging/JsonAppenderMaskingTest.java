package br.com.pixmanager.shared.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.OutputStreamAppender;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.mask.MaskingJsonGeneratorDecorator;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** O {@code %maskedMsg} só vale no pattern do CONSOLE; em hml/prd o JSON precisa do seu próprio mascaramento. */
class JsonAppenderMaskingTest {

    private static final String CPF = "529.982.247-25";

    @Test
    void plainLogstashEncoderLeaksDocument() {
        assertThat(logThrough(false)).contains(CPF);
    }

    @Test
    void maskingDecoratorHidesDocumentInMessageAndStackTrace() {
        String json = logThrough(true);
        assertThat(json).doesNotContain(CPF).doesNotContain("52998224725").contains("***.982.247-**");
    }

    @Test
    void realLogbackConfigMasksDocumentsInHmlProfile() {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        LoggingSystem system = LoggingSystem.get(getClass().getClassLoader());
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            system.initialize(contextFor("hml"), null, null);
            LoggerFactory.getLogger("real-config").error("devedor {} falhou", CPF);
        } finally {
            System.setOut(original);
            system.initialize(contextFor("local"), null, null);   // devolve o logging ao perfil de console
        }
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("***.982.247-**").doesNotContain(CPF);
    }

    private static LoggingInitializationContext contextFor(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        environment.setProperty("spring.application.name", "ms-pix-manager");
        return new LoggingInitializationContext(environment);
    }

    private static String logThrough(boolean masked) {
        LoggerContext loggerContext = new LoggerContext();
        loggerContext.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext(loggerContext);
        if (masked) {
            MaskingJsonGeneratorDecorator decorator = new MaskingJsonGeneratorDecorator();
            decorator.addValueMasker(new PiiValueMasker());
            decorator.start();
            encoder.setJsonGeneratorDecorator(decorator);
        }
        encoder.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(loggerContext);
        appender.setEncoder(encoder);
        appender.setOutputStream(out);
        appender.start();
        Logger logger = loggerContext.getLogger("teste");
        logger.addAppender(appender);
        logger.error("devedor {} falhou", CPF, new IllegalStateException("documento 52998224725 inválido"));
        appender.stop();
        return out.toString(StandardCharsets.UTF_8);
    }
}
