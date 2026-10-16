package br.com.pixmanager.core.port.out.generator;

import java.util.UUID;

/** Gerador de ids injetável (testes usam um determinístico). */
public interface IdGeneratorPortOut {

    UUID newId();
}
