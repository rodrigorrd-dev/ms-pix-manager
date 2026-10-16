package br.com.pixmanager.core.domain.enums;

import java.time.LocalDate;

/** Recorrência do Pix Agendado (BR-SCH-01). A data de cada ocorrência é calculada a partir da PRIMEIRA, nunca da anterior, para não derivar em fins de mês. */
public enum Recurrence {
    ONCE(0), WEEKLY(0), BIWEEKLY(0), MONTHLY(1), BIMONTHLY(2), QUARTERLY(3), SEMIANNUAL(6), ANNUAL(12);

    private final int months;

    Recurrence(int months) {
        this.months = months;
    }

    /** Data da ocorrência {@code sequenceNo} (1 = primeira). Mensais caem no último dia do mês quando o dia não existe (31/01 → 28/02). */
    public LocalDate dateOf(LocalDate first, int sequenceNo) {
        if (sequenceNo < 1) {
            throw new IllegalArgumentException("sequenceNo deve ser >= 1");
        }
        int n = sequenceNo - 1;
        return switch (this) {
            case ONCE -> first;
            case WEEKLY -> first.plusWeeks(n);
            case BIWEEKLY -> first.plusWeeks(2L * n);
            default -> first.plusMonths((long) months * n);
        };
    }
}
