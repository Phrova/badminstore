package com.example.demo.shipping.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Kết quả tạo vận đơn/preview GHN: mã vận đơn + phí + thời gian giao dự kiến.
 * `fee` là chi tiết phí (nếu GHN trả về), `message_display` là thông điệp GHN dành cho shop.
 * Giữ constructor 5 tham số để tương thích dữ liệu mock/test cũ.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GhnOrderResult(
        @JsonProperty("order_code") String orderCode,
        @JsonProperty("total_fee") long totalFee,
        @JsonProperty("expected_delivery_time") String expectedDeliveryTime,
        @JsonProperty("sort_code") String sortCode,
        @JsonProperty("trans_type") String transType,
        @JsonProperty("fee") GhnFee fee,
        @JsonProperty("message_display") String messageDisplay) {

    public GhnOrderResult(String orderCode, long totalFee, String expectedDeliveryTime,
                          String sortCode, String transType) {
        this(orderCode, totalFee, expectedDeliveryTime, sortCode, transType, null, null);
    }
}
