package br.com.pixmanager.api.rest.med.dto.request;

import br.com.pixmanager.core.domain.enums.MedCaseType;
import br.com.pixmanager.core.domain.enums.MedRole;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class MedRequests {

    private MedRequests() {}

    public record Open(@NotNull MedRole role, @NotBlank @Pattern(regexp = "[a-zA-Z0-9]{32}") String endToEndId, @NotNull MedCaseType caseType,
                       @NotBlank @Size(max = 2000) String description, @Min(1) @Max(20) Integer layer) {}

    public record Note(@Size(max = 500) String note) {}

    public record Contestation(@NotBlank @Size(max = 2000) String reason) {}

    public record Decision(@NotNull Boolean accepted, @Size(max = 500) String note) {}
}
