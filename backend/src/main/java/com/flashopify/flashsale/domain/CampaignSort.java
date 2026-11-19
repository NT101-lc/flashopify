package com.flashopify.flashsale.domain;

import java.util.Arrays;
import lombok.Getter;

/** Whitelist of campaign sorts; the mapper picks a fixed SQL fragment per constant. */
@Getter
public enum CampaignSort {
  START_TIME_DESC("startTime,desc"),
  START_TIME_ASC("startTime,asc"),
  CREATED_AT_DESC("createdAt,desc");

  public static final String API_PATTERN = "startTime,desc|startTime,asc|createdAt,desc";

  private final String apiValue;

  CampaignSort(String apiValue) {
    this.apiValue = apiValue;
  }

  /** Parses an API value; null/blank gives the default, anything else not whitelisted is null. */
  public static CampaignSort fromApi(String value) {
    if (value == null || value.isBlank()) {
      return START_TIME_DESC;
    }
    return Arrays.stream(values()).filter(s -> s.apiValue.equals(value)).findFirst().orElse(null);
  }
}
