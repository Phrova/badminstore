package com.example.demo.shipping.dto;

/**
 * Lỗi khi gọi API GHN (mạng, token hết hạn, GHN từ chối nghiệp vụ...).
 *
 * Tầng controller bắt lỗi này và hiển thị thông báo tiếng Việt thân thiện cho người dùng.
 * Các trường bổ sung giúp phân biệt lỗi tạm thời (có thể retry) với lỗi cố định:
 *  – {@code httpStatus}: mã HTTP nếu GHN trả về lỗi HTTP (có thể null khi lỗi mạng);
 *  – {@code code}/{@code codeMessage}: mã và thông điệp trong envelope JSON của GHN;
 *  – {@code retryable}: true với timeout, 429, 5xx; false với 400/401/403.
 * Không bao giờ chứa token/shopId hay toàn bộ payload trong message.
 */
public class GhnApiException extends RuntimeException {

    private final Integer httpStatus;
    private final Integer code;
    private final String codeMessage;
    private final boolean retryable;

    public GhnApiException(String message) {
        this(message, null, null, null, false, null);
    }

    public GhnApiException(String message, Throwable cause) {
        this(message, null, null, null, false, cause);
    }

    public GhnApiException(String message, Integer httpStatus, Integer code, String codeMessage,
                           boolean retryable, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.code = code;
        this.codeMessage = codeMessage;
        this.retryable = retryable;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public Integer getCode() {
        return code;
    }

    public String getCodeMessage() {
        return codeMessage;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
