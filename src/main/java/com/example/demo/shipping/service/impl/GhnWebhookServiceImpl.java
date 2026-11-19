package com.example.demo.shipping.service.impl;

import com.example.demo.shipping.config.GhnProperties;
import com.example.demo.shipping.dto.GhnSyncResult;
import com.example.demo.shipping.dto.GhnWebhookPayload;
import com.example.demo.shipping.dto.GhnWebhookResult;
import com.example.demo.order.entity.Order;
import com.example.demo.shipping.enums.GhnStatus;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.shipping.exception.GhnWebhookException;
import com.example.demo.common.exception.NotFoundException;
import com.example.demo.order.repository.OrderRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.example.demo.shipping.service.GhnWebhookService;
import com.example.demo.shipping.service.GhnShippingService;

/**
 * NGHIỆP VỤ WEBHOOK GHN – nhận trạng thái vận đơn do GHN đẩy sang.
 *
 * Nguyên tắc an toàn:
 *  – Chỉ nhận khi webhook đã bật, có secret và shop-id (fail closed);
 *  – ShopID trong payload phải khớp cấu hình;
 *  – Tìm đơn theo mã vận đơn GHN, không tin mã đơn từ trình duyệt;
 *  – Chỉ {@link ShippingStatusMapper} quyết định trạng thái đơn đích;
 *  – Sự kiện trùng/cũ là no-op (2xx) để GHN không retry vô hạn;
 *  – Không gọi API GHN trong luồng webhook, tránh biến webhook thành proxy ra ngoài.
 *
 * Việc đổi trạng thái (kho, voucher, thanh toán, lịch sử) nằm trong transaction của
 * {@link OrderService#changeStatusPath}; service này cố ý không mở transaction bao ngoài
 * để lỗi nghiệp vụ không đầu độc transaction và để GHN nhận đúng mã 4xx/5xx.
 */
@Service
@RequiredArgsConstructor
public class GhnWebhookServiceImpl implements GhnWebhookService {

    private static final Logger log = LoggerFactory.getLogger(GhnWebhookServiceImpl.class);

    private final GhnProperties ghnProperties;
    private final OrderRepository orderRepository;
    private final GhnShippingService ghnShippingService;

    @Override
    public GhnWebhookResult handle(GhnWebhookPayload payload) {
        if (payload == null) {
            throw new GhnWebhookException(HttpStatus.BAD_REQUEST, "Payload rỗng.");
        }
        if (!ghnProperties.isWebhookReady()) {
            throw new GhnWebhookException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Webhook GHN chưa được cấu hình đầy đủ.");
        }
        validateShopId(payload.shopId());

        String ghnOrderCode = trimToNull(payload.orderCode());
        String clientOrderCode = trimToNull(payload.clientOrderCode());
        if (ghnOrderCode == null && clientOrderCode == null) {
            throw new GhnWebhookException(HttpStatus.BAD_REQUEST,
                    "Thiếu OrderCode/ClientOrderCode.");
        }
        if (!StringUtils.hasText(payload.status())) {
            throw new GhnWebhookException(HttpStatus.BAD_REQUEST, "Thiếu Status.");
        }

        GhnStatus ghnStatus = payload.statusEnum();
        if (ghnStatus == GhnStatus.UNKNOWN) {
            // GHN có thể thêm mã mới; không tự đoán trạng thái, chỉ ghi log để admin biết.
            log.warn("Webhook GHN bỏ qua trạng thái không xác định '{}' (OrderCode={}, ClientOrderCode={}).",
                    payload.status(), ghnOrderCode, clientOrderCode);
            return new GhnWebhookResult(false, "Bỏ qua trạng thái GHN không xác định.", null, null);
        }

        Order order = findOrder(ghnOrderCode, clientOrderCode);
        validateCodeConsistency(order, ghnOrderCode, clientOrderCode);

        // applyStatus khoá đơn rồi mới tính trạng thái đích, nên không dùng trạng thái đọc ngoài transaction
        // để quyết định (tránh bỏ lỡ sự kiện khi admin vừa xác nhận đơn trong lúc webhook đang xử lý).
        try {
            GhnSyncResult result = ghnShippingService.applyStatus(
                    order.getId(), order.getGhnOrderCode(), ghnStatus, "Webhook GHN", null);
            if (result.changed()) {
                log.info("Webhook GHN {} cho đơn {}: → {}.", ghnStatus, order.getOrderCode(),
                        result.orderStatus());
            } else {
                log.info("Webhook GHN {} cho đơn {}: {}", ghnStatus, order.getOrderCode(), result.message());
            }
            return new GhnWebhookResult(result.changed(), result.message(), order.getId(), result.orderStatus());
        } catch (BusinessException ex) {
            // Sự kiện cũ/không còn hợp lệ: đơn đã ở trạng thái mới hơn, không được lùi trạng thái.
            log.warn("Webhook GHN {} cho đơn {} bị bỏ qua (trùng/cũ/không hợp lệ): {}",
                    ghnStatus, order.getOrderCode(), ex.getMessage());
            return new GhnWebhookResult(false, "Bỏ qua sự kiện: " + ex.getMessage(), order.getId(), null);
        } catch (NotFoundException ex) {
            throw new GhnWebhookException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng.");
        }
    }

    private void validateShopId(Long payloadShopId) {
        if (payloadShopId == null || ghnProperties.getShopId() == null
                || payloadShopId.longValue() != ghnProperties.getShopId().longValue()) {
            throw new GhnWebhookException(HttpStatus.FORBIDDEN, "ShopID không hợp lệ.");
        }
    }

    private Order findOrder(String ghnOrderCode, String clientOrderCode) {
        if (ghnOrderCode != null) {
            List<Order> matches = orderRepository.findAllByGhnOrderCode(ghnOrderCode);
            if (matches.size() > 1) {
                // Schema chuẩn có unique index; nếu DB cũ thiếu index thì không đoán đơn để tránh đổi nhầm.
                throw new GhnWebhookException(HttpStatus.CONFLICT,
                        "Có nhiều đơn cùng mã vận đơn GHN.");
            }
            if (matches.size() == 1) {
                return matches.get(0);
            }
            // Có OrderCode mà không tìm thấy: có thể transaction tạo vận đơn chưa commit.
            // Trả 404 để GHN retry, không fallback sang ClientOrderCode (tránh ghép nhầm đơn).
            throw new GhnWebhookException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng.");
        }
        return orderRepository.findByOrderCode(clientOrderCode)
                .orElseThrow(() -> new GhnWebhookException(HttpStatus.NOT_FOUND,
                        "Không tìm thấy đơn hàng."));
    }

    private void validateCodeConsistency(Order order, String ghnOrderCode, String clientOrderCode) {
        if (ghnOrderCode != null && !ghnOrderCode.equals(order.getGhnOrderCode())) {
            throw new GhnWebhookException(HttpStatus.CONFLICT,
                    "Mã vận đơn không khớp đơn hàng.");
        }
        if (clientOrderCode != null && !clientOrderCode.equals(order.getOrderCode())) {
            throw new GhnWebhookException(HttpStatus.CONFLICT,
                    "Mã đơn hàng không khớp.");
        }
        if (ghnOrderCode == null && !StringUtils.hasText(order.getGhnOrderCode())) {
            // Chỉ có ClientOrderCode mà đơn chưa từng đẩy sang GHN: không đủ căn cứ để đổi trạng thái.
            throw new GhnWebhookException(HttpStatus.CONFLICT,
                    "Đơn chưa có mã vận đơn GHN.");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
