package br.com.pixmanager.core.port.in.med;

/** Acompanha as devoluções MED em curso: devolvida ⇒ caso CLOSED; não realizada ⇒ volta a ACCEPTED para a operação decidir. */
public interface FollowMedCasesUseCase {

    int runCycle();
}
