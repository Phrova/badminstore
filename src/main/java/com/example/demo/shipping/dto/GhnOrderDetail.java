package com.example.demo.shipping.dto;

import com.example.demo.shipping.enums.GhnStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Chi tiết/trạng thái vận đơn GHN (GET /v2/shipping-order/detail?order_code=...).
 * Giữ constructor 8 tham số để không phá các test/nơi tạo dữ liệu mock cũ.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GhnOrderDetail(
        @JsonProperty("order_code") String orderCode,
        @JsonProperty("status") String status,
        @JsonProperty("total_fee") long totalFee,
        @JsonProperty("expected_delivery_time") String expectedDeliveryTime,
        @JsonProperty("to_name") String toName,
        @JsonProperty("to_phone") String toPhone,
        @JsonProperty("to_address") String toAddress,
        @JsonProperty("sort_code") String sortCode,
        @JsonProperty("client_order_code") String clientOrderCode,
        @JsonProperty("to_district_id") Integer toDistrictId,
        @JsonProperty("to_ward_code") String toWardCode,
        @JsonProperty("cod_amount") Long codAmount) {

    /** Dùng cho mock/test cũ chỉ có 8 trường cơ bản. */
    public GhnOrderDetail(String orderCode, String status, long totalFee, String expectedDeliveryTime,
                          String toName, String toPhone, String toAddress, String sortCode) {
        this(orderCode, status, totalFee, expectedDeliveryTime, toName, toPhone, toAddress, sortCode,
                null, null, null, null);
    }

    /** Trạng thái GHN đã quy về enum của hệ thống. */
    @JsonIgnore
    public GhnStatus statusEnum() {
        return GhnStatus.fromApi(status);
    }

    /** Nhãn tiếng Việt của trạng thái ("Đang giao hàng"...). */
    @JsonIgnore
    public String statusLabel() {
        return statusEnum().getLabel();
    }
}
