package br.com.pixmanager.core.port.in.automatic;

/** Ciclo do worker do Pix Automático: expira solicitações, dispara as tentativas do dia, acompanha as ordens e destrava o que ficou preso. */
public interface ExecuteAutomaticUseCase {

    Result runCycle();

    record Result(int expired, int started, int paid, int retried, int failed, int released) {}
}
