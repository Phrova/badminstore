package com.example.demo.shipping.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Một dịch vụ vận chuyển GHN khả dụng cho tuyến kho → khách (GET /v2/shipping-order/available-services). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GhnAvailableService(
        @JsonProperty("service_id") Integer serviceId,
        @JsonProperty("service_type_id") Integer serviceTypeId,
        @JsonProperty("short_name") String shortName,
        @JsonProperty("service_name") String serviceName) {
}
