package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.constant.ErrorCodes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * Valor monetário em BRL, escala 2 ({@code numeric(12,2)}; BCB: {@code \d{1,10}\.\d{2}}). Nunca {@code double}.
 * Imutável; operações devolvem novo valor. Negativo só existe como resultado intermediário de {@link #minus}.
 */
public record Money(BigDecimal value) implements Comparable<Money> {

    public static final Money ZERO = new Money(BigDecimal.ZERO);
    private static final Pattern BCB_FORMAT = Pattern.compile("\\d{1,10}\\.\\d{2}");
    private static final BigDecimal MAX = new BigDecimal("9999999999.99");

    public Money {
        if (value == null) {
            throw new IllegalArgumentException("valor monetário é obrigatório");
        }
        value = value.setScale(2, RoundingMode.HALF_EVEN);
    }

    /** Mais de 2 casas decimais significativas é erro (nunca arredondar dinheiro em silêncio). */
    public static Money of(String amount) {
        return of(new BigDecimal(amount));
    }

    public static Money of(BigDecimal amount) {
        if (amount != null && amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("valor monetário com mais de 2 casas decimais");
        }
        return new Money(amount);
    }

    /** Formato do BCB ({@code 123.45}); qualquer outro ⇒ 400/422 sem eco do valor. */
    public static Money parseBcb(String amount) {
        if (amount == null || !BCB_FORMAT.matcher(amount).matches()) {
            throw new BusinessException(ErrorCodes.INVALID_VALUE_FORMAT, "Valor monetário inválido (esperado ddd.dd).");
        }
        return of(amount);
    }

    /** {@link #parseBcb} tolerante a ausência: campo opcional do request vira {@code null}. */
    public static Money parseBcbOrNull(String amount) {
        return amount == null ? null : parseBcb(amount);
    }

    /** {@link #of(BigDecimal)} tolerante a ausência: coluna {@code NULL} vira {@code null}. */
    public static Money ofNullable(BigDecimal amount) {
        return amount == null ? null : of(amount);
    }

    /** Valor numérico de um {@code Money} opcional (coluna/campo {@code NULL} quando ausente). */
    public static BigDecimal valueOrNull(Money money) {
        return money == null ? null : money.value();
    }

    /** Formato BCB de um {@code Money} opcional. */
    public static String bcbOrNull(Money money) {
        return money == null ? null : money.toBcb();
    }

    public Money plus(Money other) {
        return new Money(value.add(other.value));
    }

    public Money minus(Money other) {
        return new Money(value.subtract(other.value));
    }

    public Money min(Money other) {
        return compareTo(other) <= 0 ? this : other;
    }

    public boolean isPositive() {
        return value.signum() > 0;
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    public boolean isNegative() {
        return value.signum() < 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean exceedsBcbMaximum() {
        return value.compareTo(MAX) > 0;
    }

    @Override
    public int compareTo(Money other) {
        return value.compareTo(other.value);
    }

    /** {@code 123.45}, sempre com 2 casas (formato do BCB). */
    public String toBcb() {
        return value.toPlainString();
    }

    @Override
    public String toString() {
        return toBcb();
    }
}
