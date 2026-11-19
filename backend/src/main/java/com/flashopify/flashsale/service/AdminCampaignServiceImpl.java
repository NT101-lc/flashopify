package com.flashopify.flashsale.service;

import com.flashopify.common.exception.BusinessException;
import com.flashopify.common.exception.ErrorCode;
import com.flashopify.common.exception.ErrorResponse.FieldError;
import com.flashopify.common.web.PageResponse;
import com.flashopify.flashsale.domain.Campaign;
import com.flashopify.flashsale.domain.CampaignItem;
import com.flashopify.flashsale.domain.CampaignSearchCriteria;
import com.flashopify.flashsale.domain.CampaignSort;
import com.flashopify.flashsale.domain.CampaignStatus;
import com.flashopify.flashsale.dto.AdminCampaignDetailResponse;
import com.flashopify.flashsale.dto.AdminCampaignItemResponse;
import com.flashopify.flashsale.dto.AdminCampaignSummaryResponse;
import com.flashopify.flashsale.dto.CampaignItemRequest;
import com.flashopify.flashsale.dto.CampaignUpsertRequest;
import com.flashopify.flashsale.mapper.CampaignMapper;
import com.flashopify.flashsale.mapper.FlashSaleItemMapper;
import com.flashopify.inventory.service.InventoryService;
import com.flashopify.product.dto.ProductSnapshot;
import com.flashopify.product.service.ProductQueryService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * See {@link AdminCampaignService} for the lock order. Writers that change an existing campaign
 * first read its items without a lock only to learn which product rows to lock; after locking the
 * campaign row they re-read the items with {@code FOR UPDATE} (the latest committed rows) and fail
 * with CONCURRENT_MODIFICATION if a concurrent update added a product that was not locked.
 */
@Service
@RequiredArgsConstructor
public class AdminCampaignServiceImpl implements AdminCampaignService {

  private final CampaignMapper campaignMapper;
  private final FlashSaleItemMapper itemMapper;
  private final ProductQueryService productQueryService;
  private final InventoryService inventoryService;
  private final FlashSaleProperties properties;
  private final Clock clock;

  @Override
  @Transactional(readOnly = true)
  public PageResponse<AdminCampaignSummaryResponse> list(
      CampaignStatus status, String sort, int page, int size) {
    CampaignSort campaignSort = CampaignSort.fromApi(sort);
    if (campaignSort == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Unsupported sort: " + sort);
    }
    CampaignSearchCriteria criteria =
        CampaignSearchCriteria.builder()
            .status(status)
            .sort(campaignSort)
            .offset((long) page * size)
            .limit(size)
            .build();
    long total = campaignMapper.count(criteria);
    if (total == 0) {
      return PageResponse.of(List.of(), page, size, 0);
    }
    List<AdminCampaignSummaryResponse> items =
        campaignMapper.search(criteria).stream().map(AdminCampaignSummaryResponse::from).toList();
    return PageResponse.of(items, page, size, total);
  }

  @Override
  @Transactional(readOnly = true)
  public AdminCampaignDetailResponse get(long campaignId) {
    return loadDetail(campaignId);
  }

  @Override
  @Transactional
  public AdminCampaignDetailResponse create(CampaignUpsertRequest request, long createdBy) {
    Window window = validate(request);
    Map<Long, ProductSnapshot> products =
        productQueryService.lockForShare(productIdsOf(request.items()));
    requireActive(request.items(), products);

    Campaign campaign =
        Campaign.builder()
            .name(request.name().trim())
            .startTime(window.start())
            .endTime(window.end())
            .createdBy(createdBy)
            .build();
    campaignMapper.insert(campaign);
    List<CampaignItemRequest> sorted = sortedByProduct(request.items());
    for (CampaignItemRequest r : sorted) {
      itemMapper.insert(toItem(campaign.getId(), r));
    }
    for (CampaignItemRequest r : sorted) {
      inventoryService.reserve(r.productId(), r.totalStock());
    }
    return loadDetail(campaign.getId());
  }

  @Override
  @Transactional
  public AdminCampaignDetailResponse update(long campaignId, CampaignUpsertRequest request) {
    Window window = validate(request);
    Set<Long> lockIds = new HashSet<>(productIdsOf(request.items()));
    itemMapper.findByCampaignId(campaignId).forEach(i -> lockIds.add(i.getProductId()));
    Map<Long, ProductSnapshot> products = productQueryService.lockForShare(lockIds);

    Campaign campaign = lockCampaign(campaignId);
    if (!isEditable(campaign)) {
      throw new BusinessException(
          ErrorCode.CAMPAIGN_NOT_EDITABLE, "Campaign is warmed up, running or closed");
    }
    List<CampaignItem> current = lockItems(campaignId, lockIds);
    requireActive(request.items(), products);

    campaign.setName(request.name().trim());
    campaign.setStartTime(window.start());
    campaign.setEndTime(window.end());
    campaignMapper.updateHeader(campaign);

    // Item writes first, then inventory in ascending product order (lock order).
    Map<Long, CampaignItem> remaining = new HashMap<>();
    current.forEach(i -> remaining.put(i.getProductId(), i));
    Map<Long, Integer> deltas = new TreeMap<>();
    for (CampaignItemRequest r : sortedByProduct(request.items())) {
      CampaignItem existing = remaining.remove(r.productId());
      if (existing == null) {
        itemMapper.insert(toItem(campaignId, r));
        deltas.put(r.productId(), r.totalStock());
      } else {
        // Before warm-up nothing is sold, so the reservation equals totalStock.
        deltas.put(r.productId(), r.totalStock() - existing.getTotalStock());
        existing.setSalePrice(r.salePrice());
        existing.setTotalStock(r.totalStock());
        existing.setLimitPerUser(r.limitOrDefault());
        itemMapper.update(existing);
      }
    }
    for (CampaignItem removed : remaining.values()) {
      itemMapper.deleteById(removed.getId());
      deltas.put(removed.getProductId(), -removed.getTotalStock());
    }
    deltas.forEach(
        (productId, delta) -> {
          if (delta > 0) {
            inventoryService.reserve(productId, delta);
          } else if (delta < 0) {
            inventoryService.release(productId, -delta);
          }
        });
    return loadDetail(campaignId);
  }

  @Override
  @Transactional
  public AdminCampaignSummaryResponse cancel(long campaignId) {
    Set<Long> lockIds = new HashSet<>();
    itemMapper.findByCampaignId(campaignId).forEach(i -> lockIds.add(i.getProductId()));
    // Products are locked only to respect the global lock order before inventory is touched.
    productQueryService.lockForShare(lockIds);

    Campaign campaign = lockCampaign(campaignId);
    if (campaign.getStatus() != CampaignStatus.SCHEDULED
        && campaign.getStatus() != CampaignStatus.RUNNING) {
      throw new BusinessException(
          ErrorCode.CAMPAIGN_NOT_EDITABLE, "Campaign is already " + campaign.getStatus());
    }
    List<CampaignItem> items = lockItems(campaignId, lockIds);
    campaignMapper.updateStatus(campaignId, CampaignStatus.CANCELLED);
    for (CampaignItem item : items) {
      // TODO(FLS-016): for a RUNNING campaign, `sold` lives in Redis until the end-of-campaign
      // sync; drain the Redis stock atomically and release that amount instead.
      int unsold = item.getTotalStock() - item.getSold();
      if (unsold > 0) {
        inventoryService.release(item.getProductId(), unsold);
      }
    }
    return AdminCampaignSummaryResponse.from(campaignMapper.findSummaryById(campaignId));
  }

  /** SCHEDULED and strictly before the warm-up moment ({@code startTime - warmUpLead}). */
  boolean isEditable(Campaign campaign) {
    Instant warmUpAt = campaign.getStartTime().minus(properties.warmUpLead());
    return campaign.getStatus() == CampaignStatus.SCHEDULED && clock.instant().isBefore(warmUpAt);
  }

  private Campaign lockCampaign(long campaignId) {
    Campaign campaign = campaignMapper.lockById(campaignId);
    if (campaign == null) {
      throw new BusinessException(ErrorCode.CAMPAIGN_NOT_FOUND, "Campaign not found");
    }
    return campaign;
  }

  /** Locking re-read of the items; every product they reference must already be locked. */
  private List<CampaignItem> lockItems(long campaignId, Set<Long> lockedProductIds) {
    List<CampaignItem> items = itemMapper.lockByCampaignId(campaignId);
    for (CampaignItem item : items) {
      if (!lockedProductIds.contains(item.getProductId())) {
        throw new BusinessException(
            ErrorCode.CONCURRENT_MODIFICATION, "Campaign was changed by someone else, retry");
      }
    }
    return items;
  }

  private Window validate(CampaignUpsertRequest request) {
    List<FieldError> errors = new ArrayList<>();
    // DATETIME has no fractional seconds; truncate instead of letting MySQL round.
    Instant start = request.startTime().truncatedTo(ChronoUnit.SECONDS);
    Instant end = request.endTime().truncatedTo(ChronoUnit.SECONDS);
    if (!start.isAfter(clock.instant())) {
      errors.add(new FieldError("startTime", "must be in the future", request.startTime()));
    }
    if (!end.isAfter(start)) {
      errors.add(new FieldError("endTime", "must be after startTime", request.endTime()));
    }
    Set<Long> seen = new HashSet<>();
    List<CampaignItemRequest> items = request.items();
    for (int i = 0; i < items.size(); i++) {
      Long productId = items.get(i).productId();
      if (!seen.add(productId)) {
        errors.add(
            new FieldError("items[" + i + "].productId", "duplicate product in items", productId));
      }
    }
    if (!errors.isEmpty()) {
      throw BusinessException.validation(errors);
    }
    return new Window(start, end);
  }

  private static void requireActive(
      List<CampaignItemRequest> items, Map<Long, ProductSnapshot> products) {
    for (CampaignItemRequest r : items) {
      ProductSnapshot p = products.get(r.productId());
      if (p == null) {
        throw new BusinessException(
            ErrorCode.PRODUCT_NOT_FOUND, "Product " + r.productId() + " not found");
      }
      if (!p.isActive()) {
        throw new BusinessException(
            ErrorCode.PRODUCT_NOT_AVAILABLE, "Product " + r.productId() + " is " + p.status());
      }
    }
  }

  private AdminCampaignDetailResponse loadDetail(long campaignId) {
    Campaign campaign = campaignMapper.findSummaryById(campaignId);
    if (campaign == null) {
      throw new BusinessException(ErrorCode.CAMPAIGN_NOT_FOUND, "Campaign not found");
    }
    List<CampaignItem> items = itemMapper.findByCampaignId(campaignId);
    Map<Long, ProductSnapshot> products =
        productQueryService.findByIds(items.stream().map(CampaignItem::getProductId).toList());
    List<AdminCampaignItemResponse> responses =
        items.stream().map(i -> toResponse(i, products.get(i.getProductId()))).toList();
    return AdminCampaignDetailResponse.of(campaign, responses);
  }

  private static AdminCampaignItemResponse toResponse(CampaignItem i, ProductSnapshot p) {
    // TODO(FLS-016): overlay the live Redis stock once the campaign is warmed up.
    int remaining = i.getTotalStock() - i.getSold();
    return new AdminCampaignItemResponse(
        i.getId(),
        i.getProductId(),
        p == null ? null : p.name(),
        p == null ? null : p.slug(),
        p == null ? null : p.thumbnailUrl(),
        p == null ? null : p.price(),
        i.getSalePrice(),
        i.getTotalStock(),
        remaining,
        i.getLimitPerUser(),
        i.getSold());
  }

  private static CampaignItem toItem(long campaignId, CampaignItemRequest r) {
    return CampaignItem.builder()
        .campaignId(campaignId)
        .productId(r.productId())
        .salePrice(r.salePrice())
        .totalStock(r.totalStock())
        .limitPerUser(r.limitOrDefault())
        .build();
  }

  private static Set<Long> productIdsOf(List<CampaignItemRequest> items) {
    Set<Long> ids = new TreeSet<>();
    items.forEach(i -> ids.add(i.productId()));
    return ids;
  }

  private static List<CampaignItemRequest> sortedByProduct(List<CampaignItemRequest> items) {
    return items.stream().sorted(Comparator.comparing(CampaignItemRequest::productId)).toList();
  }

  private record Window(Instant start, Instant end) {}
}
