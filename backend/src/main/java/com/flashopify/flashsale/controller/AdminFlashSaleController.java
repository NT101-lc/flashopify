package com.flashopify.flashsale.controller;

import com.flashopify.common.security.CurrentUser;
import com.flashopify.common.web.PageResponse;
import com.flashopify.flashsale.domain.CampaignSort;
import com.flashopify.flashsale.domain.CampaignStatus;
import com.flashopify.flashsale.dto.AdminCampaignDetailResponse;
import com.flashopify.flashsale.dto.AdminCampaignSummaryResponse;
import com.flashopify.flashsale.dto.CampaignUpsertRequest;
import com.flashopify.flashsale.service.AdminCampaignService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin flash-sale campaigns; the ADMIN role is enforced for {@code /api/v1/admin/**}. */
@Validated
@RestController
@RequestMapping("/api/v1/admin/flash-sales")
@RequiredArgsConstructor
public class AdminFlashSaleController {

  private final AdminCampaignService service;

  @GetMapping
  public PageResponse<AdminCampaignSummaryResponse> list(
      @RequestParam(required = false) CampaignStatus status,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @RequestParam(required = false) @Pattern(regexp = CampaignSort.API_PATTERN) String sort) {
    return service.list(status, sort, page, size);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public AdminCampaignDetailResponse create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CampaignUpsertRequest request) {
    return service.create(request, CurrentUser.id(jwt));
  }

  @GetMapping("/{campaignId}")
  public AdminCampaignDetailResponse get(@PathVariable long campaignId) {
    return service.get(campaignId);
  }

  @PutMapping("/{campaignId}")
  public AdminCampaignDetailResponse update(
      @PathVariable long campaignId, @Valid @RequestBody CampaignUpsertRequest request) {
    return service.update(campaignId, request);
  }

  @PostMapping("/{campaignId}/cancel")
  public AdminCampaignSummaryResponse cancel(@PathVariable long campaignId) {
    return service.cancel(campaignId);
  }
}
