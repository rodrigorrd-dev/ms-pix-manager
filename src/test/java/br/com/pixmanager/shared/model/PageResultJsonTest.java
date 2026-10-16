package br.com.pixmanager.shared.model;

import br.com.pixmanager.shared.model.dto.PageResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResultJsonTest {

    @Test
    @DisplayName("docs/05 §4.3: o JSON da listagem traz content, page, size, totalElements e totalPages")
    void totalPagesSaiNoJson() throws Exception {
        JsonNode json = new ObjectMapper().valueToTree(new PageResult<>(List.of("a", "b"), 0, 2, 5));
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("content", "page", "size", "totalElements", "totalPages");
        assertThat(json.get("totalPages").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("lista vazia e size 0 não dividem por zero")
    void vazia() {
        assertThat(new PageResult<String>(List.of(), 0, 0, 0).totalPages()).isZero();
        assertThat(new PageResult<String>(List.of(), 0, 20, 0).totalPages()).isZero();
    }
}
