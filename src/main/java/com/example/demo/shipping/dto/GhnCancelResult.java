package com.example.demo.shipping.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Kết quả hủy một vận đơn GHN (POST /v2/switch-status/cancel).
 * GHN trả về mảng; app chỉ hủy từng đơn nên lấy phần tử đầu tiên.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GhnCancelResult(
        @JsonProperty("order_code") String orderCode,
        @JsonProperty("result") boolean result,
        @JsonProperty("message") String message) {
}
