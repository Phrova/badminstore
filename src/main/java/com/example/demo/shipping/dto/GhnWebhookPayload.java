package com.example.demo.shipping.dto;

import com.example.demo.shipping.enums.GhnStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Payload webhook trạng thái vận đơn do GHN gửi.
 *
 * GHN dùng tên trường PascalCase (OrderCode, ClientOrderCode, Status, ShopID, Time, Type),
 * khác với API thường dùng snake_case. Bỏ qua trường lạ để GHN thêm trường mới không làm hỏng webhook.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GhnWebhookPayload(
        @JsonProperty("OrderCode") String orderCode,
        @JsonProperty("ClientOrderCode") String clientOrderCode,
        @JsonProperty("Status") String status,
        @JsonProperty("ShopID") Long shopId,
        @JsonProperty("Time") String time,
        @JsonProperty("Type") String type) {

    /** Trạng thái GHN đã quy về enum; mã lạ trả UNKNOWN. */
    @JsonIgnore
    public GhnStatus statusEnum() {
        return GhnStatus.fromApi(status);
    }
}
