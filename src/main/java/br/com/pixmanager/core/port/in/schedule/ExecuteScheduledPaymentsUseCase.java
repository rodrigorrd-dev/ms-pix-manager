package br.com.pixmanager.core.port.in.schedule;

/** Um ciclo do worker do Pix Agendado: dispara o que venceu, acompanha o desfecho das ordens e destrava o que ficou preso. */
public interface ExecuteScheduledPaymentsUseCase {

    Result runCycle();

    record Result(int started, int settled, int retried, int failed, int released) {}
}
