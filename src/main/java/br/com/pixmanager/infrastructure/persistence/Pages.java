package br.com.pixmanager.infrastructure.persistence;

import br.com.pixmanager.shared.model.dto.PageQuery;

import java.util.List;
import java.util.function.LongSupplier;

/** Total de uma listagem paginada por OFFSET sem o {@code count(*)} quando a própria página já o revela. */
public final class Pages {

    private Pages() {}

    /**
     * Página parcial (menos linhas que o tamanho pedido) e não vazia, ou primeira página vazia ⇒ o total é {@code offset + linhas}: nenhuma
     * linha existe depois dela. Caso contrário (página cheia, ou além do fim) só o {@code count(*)} responde.
     */
    public static long total(PageQuery page, List<?> content, LongSupplier count) {
        long offset = (long) page.page() * page.size();
        boolean lastPage = content.size() < page.size() && (!content.isEmpty() || page.page() == 0);
        return lastPage ? offset + content.size() : count.getAsLong();
    }
}
