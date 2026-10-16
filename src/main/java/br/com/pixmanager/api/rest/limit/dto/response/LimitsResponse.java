package br.com.pixmanager.api.rest.limit.dto.response;

import java.util.List;
import java.util.UUID;

public record LimitsResponse(UUID accountId, List<LimitResponse> limits) {}
