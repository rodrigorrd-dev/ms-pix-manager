package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.shared.model.document.Document;

/**
 * Favorecido do pagamento. {@code document} é o do DONO da conta/chave (do DICT ou informado) e é validado por dígito
 * verificador; {@code pixKey} é a chave normalizada (só em memória: a persistência grava apenas o índice cego); quando o chamador informa um documento esperado ({@code documentHint}), ele tem que bater (BR-PAY-04).
 */
public record PaymentDestination(PixKeyType keyType, String pixKey, Document document, String name, String ispb, String branch,
                                 String account, String accountType) {}
