package br.com.pixmanager.shared.logging;

import net.logstash.logback.mask.ValueMasker;

import com.fasterxml.jackson.core.JsonStreamContext;

/**
 * Equivalente JSON do {@code %maskedMsg}: o {@code LogstashEncoder} não usa pattern, então o {@link PiiMaskingConverter} não
 * vale nele. Aplicado a TODO valor string do evento (message, stack_trace, campos) via {@code MaskingJsonGeneratorDecorator}.
 */
public class PiiValueMasker implements ValueMasker {

    @Override
    public Object mask(JsonStreamContext context, Object value) {
        if (value instanceof CharSequence text) {
            String masked = PiiMasker.maskText(text.toString());
            return masked.contentEquals(text) ? null : masked;   // null = sem alteração
        }
        return null;
    }
}
