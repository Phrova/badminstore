package com.example.demo.shipping.controller;

import com.example.demo.shipping.config.GhnProperties;
import com.example.demo.shipping.dto.GhnWebhookPayload;
import com.example.demo.shipping.dto.GhnWebhookResult;
import com.example.demo.shipping.exception.GhnWebhookException;
import com.example.demo.shipping.service.GhnWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint công khai duy nhất để GHN đẩy trạng thái vận đơn về hệ thống.
 *
 * <p>Bảo mật nhiều lớp, không dựa vào đăng nhập của người dùng:
 * <ol>
 *   <li>chỉ mở đúng POST {@code /api/webhooks/ghn/order-status} trong SecurityConfig;</li>
 *   <li>webhook phải được bật đầy đủ (enabled + secret + shop-id), nếu thiếu trả 503;</li>
 *   <li>secret trong header được so sánh constant-time với cấu hình, sai/thiếu trả 401;</li>
 *   <li>ShopID trong payload phải khớp cấu hình, sai trả 403;</li>
 *   <li>service chỉ tra đơn theo mã vận đơn đã lưu, không nhận id/trạng thái từ client.</li>
 * </ol>
 *
 * <p>Không trả token/secret/stack trace ra ngoài. Mã lỗi được chọn để GHN retry đúng:
 * 4xx = lỗi vĩnh viễn (sai secret/ShopID/payload/không tìm thấy đơn), 5xx = lỗi tạm thời.
 */
@RestController
@RequestMapping("/api/webhooks/ghn")
@RequiredArgsConstructor
public class GhnWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GhnWebhookController.class);

    private final GhnProperties ghnProperties;
    private final GhnWebhookService ghnWebhookService;

    @PostMapping("/order-status")
    public ResponseEntity<Map<String, Object>> orderStatus(
            @RequestBody(required = false) GhnWebhookPayload payload,
            HttpServletRequest request) {
        if (!ghnProperties.isWebhookReady()) {
            throw new GhnWebhookException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Webhook GHN chưa được cấu hình đầy đủ.");
        }
        verifySecret(request);

        GhnWebhookResult result = ghnWebhookService.handle(payload);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("applied", result.applied());
        body.put("message", result.message());
        return ResponseEntity.ok(body);
    }

    /**
     * So sánh constant-time để không lộ secret qua thời gian phản hồi.
     * Secret chỉ nằm trong biến môi trường/cấu hình, không bao giờ ghi log hay trả về client.
     */
    private void verifySecret(HttpServletRequest request) {
        String expected = ghnProperties.getWebhook().getSecret();
        String provided = request.getHeader(ghnProperties.getWebhookHeaderName());
        if (!StringUtils.hasText(provided)
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                        provided.getBytes(StandardCharsets.UTF_8))) {
            throw new GhnWebhookException(HttpStatus.UNAUTHORIZED, "Sai hoặc thiếu secret webhook.");
        }
    }

    @ExceptionHandler(GhnWebhookException.class)
    public ResponseEntity<Map<String, Object>> handleWebhookException(GhnWebhookException ex) {
        return error(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "Payload JSON không hợp lệ.");
    }

    /**
     * Lỗi còn lại (DB mất kết nối, lỗi bất ngờ) trả 500 để GHN retry.
     * Chỉ log thông tin kỹ thuật, không log payload/secret.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Lỗi xử lý webhook GHN: {}", ex.getClass().getSimpleName(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Không xử lý được webhook. Vui lòng thử lại sau.");
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
