package com.example.demo.shipping.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Lỗi xác thực/dữ liệu của webhook GHN, kèm HTTP status để controller trả đúng mã
 * cho cơ chế retry của GHN (4xx = không retry, 5xx = retry).
 */
@Getter
public class GhnWebhookException extends RuntimeException {

    private final HttpStatus status;

    public GhnWebhookException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
