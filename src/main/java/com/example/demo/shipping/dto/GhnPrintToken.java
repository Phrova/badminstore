package com.example.demo.shipping.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token in vận đơn GHN (POST /v2/a5/gen-token).
 * Token chỉ dùng một lần cho URL in, hết hạn sau khoảng 30 phút và KHÔNG được lưu DB/log.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GhnPrintToken(@JsonProperty("token") String token) {
}
