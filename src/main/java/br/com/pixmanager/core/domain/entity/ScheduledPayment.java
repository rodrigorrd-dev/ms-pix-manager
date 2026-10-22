package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.core.domain.enums.Recurrence;
import br.com.pixmanager.core.domain.enums.ScheduleStatus;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.InvalidTransitionException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pix Agendado (lado pagador), único ou recorrente (BR-SCH-01). Só {@code KEY} e {@code MANUAL}: QR (estático ou dinâmico) tem prazo
 * próprio e não faz sentido agendado. O destino é guardado como o cliente informou e RESOLVIDO NO DIA da execução (DICT, limites e
 * saldo da data, não da criação).
 */
public record ScheduledPayment(UUID id, UUID payerAccountId, Recurrence recurrence, PaymentMethod method, PixKeyType pixKeyType,
                               String pixKey, String ispb, String branch, String account, String accountType, String name,
                               String document, Money amount, String payerMessage, LocalDate firstDate, LocalDate endDate,
                               Integer occurrencesTotal, ScheduleStatus status, boolean deviceRegistered, long version) {

    public static final int MAX_OCCURRENCES = 120;

    public ScheduledPayment {
        if (method != PaymentMethod.KEY && method != PaymentMethod.MANUAL) {
            throw invalid("Agendamento aceita apenas os métodos KEY e MANUAL.");
        }
        if (method == PaymentMethod.KEY && (pixKeyType == null || pixKey == null || pixKey.isBlank())) {
            throw invalid("Agendamento por chave exige o tipo e o valor da chave.");
        }
        if (method == PaymentMethod.MANUAL && (ispb == null || !ispb.matches("\\d{8}") || branch == null || account == null
                || accountType == null || name == null || name.isBlank())) {
            throw invalid("Agendamento manual exige ispb, agência, conta, tipo e nome do favorecido.");
        }
        if (amount == null || !amount.isPositive() || amount.exceedsBcbMaximum()) {
            throw invalid("O valor do agendamento deve ser maior que zero.");
        }
        if (payerMessage != null && payerMessage.length() > 140) {
            throw invalid("A mensagem ao favorecido aceita até 140 caracteres.");
        }
        if (recurrence == Recurrence.ONCE) {
            if (endDate != null || occurrencesTotal != null) {
                throw invalid("Agendamento único não aceita data final nem quantidade de ocorrências.");
            }
        } else {
            if ((endDate == null) == (occurrencesTotal == null)) {
                throw invalid("Agendamento recorrente exige exatamente um entre data final e quantidade de ocorrências.");
            }
            if (occurrencesTotal != null && (occurrencesTotal < 2 || occurrencesTotal > MAX_OCCURRENCES)) {
                throw invalid("A quantidade de ocorrências deve estar entre 2 e " + MAX_OCCURRENCES + ".");
            }
            if (endDate != null && endDate.isBefore(firstDate)) {
                throw invalid("A data final não pode ser anterior à data inicial.");
            }
        }
        if (occurrenceDates(recurrence, firstDate, endDate, occurrencesTotal).size() > MAX_OCCURRENCES) {
            throw invalid("O agendamento gera mais de " + MAX_OCCURRENCES + " ocorrências.");
        }
    }

    /** Novo agendamento: a primeira data precisa ser FUTURA (dia em America/Sao_Paulo, calculado por quem chama). */
    public static ScheduledPayment create(UUID id, UUID payerAccountId, Recurrence recurrence, PaymentMethod method, PixKeyType keyType,
                                          String key, String ispb, String branch, String account, String accountType, String name,
                                          String document, Money amount, String message, LocalDate firstDate, LocalDate endDate,
                                          Integer occurrencesTotal, LocalDate today, boolean deviceRegistered) {
        if (firstDate == null || !firstDate.isAfter(today)) {
            throw invalid("A data do agendamento deve ser posterior a hoje.");
        }
        return new ScheduledPayment(id, payerAccountId, recurrence, method, keyType, key, ispb, branch, account, accountType, name,
                document, amount, message, firstDate, endDate, occurrencesTotal, ScheduleStatus.ACTIVE, deviceRegistered, 0);
    }

    public List<LocalDate> occurrenceDates() {
        return occurrenceDates(recurrence, firstDate, endDate, occurrencesTotal);
    }

    private static List<LocalDate> occurrenceDates(Recurrence recurrence, LocalDate first, LocalDate end, Integer total) {
        if (recurrence == Recurrence.ONCE) {
            return List.of(first);
        }
        List<LocalDate> dates = new ArrayList<>();
        for (int sequenceNumber = 1; ; sequenceNumber++) {
            LocalDate dueDate = recurrence.dateOf(first, sequenceNumber);
            if (total != null ? sequenceNumber > total : dueDate.isAfter(end)) {
                return dates;
            }
            dates.add(dueDate);
            if (dates.size() > MAX_OCCURRENCES) {
                return dates;
            }
        }
    }

    public ScheduledPayment cancelled() {
        if (status != ScheduleStatus.ACTIVE) {
            throw new InvalidTransitionException(ErrorCodes.SCHEDULE_NOT_CANCELLABLE, "Agendamento " + status + " não pode ser cancelado.");
        }
        return withStatus(ScheduleStatus.CANCELLED);
    }

    public ScheduledPayment completed() {
        return status == ScheduleStatus.ACTIVE ? withStatus(ScheduleStatus.COMPLETED) : this;
    }

    private ScheduledPayment withStatus(ScheduleStatus scheduleStatus) {
        return new ScheduledPayment(id, payerAccountId, recurrence, method, pixKeyType, pixKey, ispb, branch, account, accountType, name,
                document, amount, payerMessage, firstDate, endDate, occurrencesTotal, scheduleStatus, deviceRegistered, version);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCodes.INVALID_SCHEDULE, message);
    }
}
