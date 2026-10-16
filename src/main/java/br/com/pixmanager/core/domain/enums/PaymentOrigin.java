package br.com.pixmanager.core.domain.enums;

/** Agendamento e Pix Automático geram uma {@code PaymentOrder} na execução — mesmo caminho, mesmas regras (BR-PAY-06). */
public enum PaymentOrigin { IMMEDIATE, SCHEDULED, AUTOMATIC }
