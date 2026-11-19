package com.example.demo.shipping.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Vỏ phản hồi chung của API GHN v2: {"code":200,"message":"Success","data":...}.
 * `code` khác 200 nghĩa là GHN từ chối (hết hạn token, sai shopId, thiếu tham số...).
 * Một số lỗi nghiệp vụ còn có `code_message` mô tả chi tiết hơn.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GhnResponse<T>(
        @JsonProperty("code") int code,
        @JsonProperty("message") String message,
        @JsonProperty("code_message") String codeMessage,
        @JsonProperty("data") T data) {

    public boolean success() {
        return code == 200;
    }

    /** Thông điệp lỗi ưu tiên code_message rồi mới tới message. */
    public String errorMessage() {
        if (codeMessage != null && !codeMessage.isBlank()) {
            return codeMessage;
        }
        return message == null || message.isBlank() ? "không rõ lý do (mã " + code + ")" : message;
    }

    /** Lấy dữ liệu, nếu thất bại thì ném lỗi nghiệp vụ với thông báo GHN trả về. */
    public T requireData() {
        if (!success() || data == null) {
            throw new GhnApiException("GHN trả về lỗi " + code + ": " + errorMessage(),
                    null, code, codeMessage, false, null);
        }
        return data;
    }
}
