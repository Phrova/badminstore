package com.example.demo.shipping.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Tỉnh/Thành phố theo mô hình hành chính 2 cấp mới của GHN
 * (GET /v3/master-data/province/all).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GhnNewProvince(
        @JsonProperty("_id") int id,
        @JsonProperty("name") String name,
        @JsonProperty("parent_id") Integer parentId,
        @JsonProperty("status") int status) {
}
