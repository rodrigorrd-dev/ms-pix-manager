package br.com.pixmanager.core.domain.enums;

import java.time.LocalDate;

/** Periodicidade do Pix Agendado. Datas mensais saem SEMPRE da data inicial (31/01 → 28/02 → 31/03), nunca encadeadas. */
public enum Recurrence {
    ONCE, WEEKLY, BIWEEKLY, MONTHLY, BIMONTHLY, QUARTERLY, SEMIANNUAL, ANNUAL;

    /** Data da ocorrência de número {@code n} (1 = a primeira). Dia inexistente no mês cai no último dia do mês. */
    public LocalDate dateOf(LocalDate first, int sequenceNumber) {
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException("O número da ocorrência começa em 1.");
        }
        int index = sequenceNumber - 1;
        return switch (this) {
            case ONCE -> first;
            case WEEKLY -> first.plusWeeks(index);
            case BIWEEKLY -> first.plusWeeks(2L * index);
            case MONTHLY -> first.plusMonths(index);
            case BIMONTHLY -> first.plusMonths(2L * index);
            case QUARTERLY -> first.plusMonths(3L * index);
            case SEMIANNUAL -> first.plusMonths(6L * index);
            case ANNUAL -> first.plusYears(index);
        };
    }
}
