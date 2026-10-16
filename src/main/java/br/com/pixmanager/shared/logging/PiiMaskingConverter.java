package br.com.pixmanager.shared.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Regra de conversão {@code %maskedMsg} do logback: substitui {@code %msg} para nenhum documento sair em claro. */
public class PiiMaskingConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return PiiMasker.maskText(super.convert(event));
    }
}
