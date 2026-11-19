package com.flashopify.flashsale.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Mirrors {@code CampaignUpsertRequest}. Time rules (start in the future, end after start) and
 * duplicate products are checked by the service, which reports them as {@code fieldErrors}.
 */
public record CampaignUpsertRequest(
    @NotBlank @Size(max = 255) String name,
    @NotNull Instant startTime,
    @NotNull Instant endTime,
    @NotEmpty @Size(max = 100) List<@NotNull @Valid CampaignItemRequest> items) {}
