package com.example.demo.shipping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.demo.shipping.config.GhnProperties;
import com.example.demo.shipping.dto.GhnSyncResult;
import com.example.demo.shipping.dto.GhnWebhookPayload;
import com.example.demo.shipping.dto.GhnWebhookResult;
import com.example.demo.order.entity.Order;
import com.example.demo.shipping.enums.GhnStatus;
import com.example.demo.order.enums.OrderStatus;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.shipping.exception.GhnWebhookException;
import com.example.demo.order.repository.OrderRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import com.example.demo.shipping.service.impl.GhnWebhookServiceImpl;

/**
 * Kiểm thử nghiệp vụ webhook GHN ở tầng service (không cần DB/Spring):
 * xác thực ShopID, tra đơn theo mã vận đơn, fallback ClientOrderCode, từ chối payload hỏng,
 * và chuyển kết quả đổi trạng thái từ GhnShippingService ra response.
 */
@ExtendWith(MockitoExtension.class)
class GhnWebhookServiceTest {

    private static final long SHOP_ID = 2546099L;
    private static final String SECRET = "secret-webhook-test";
    private static final Integer ORDER_ID = 100;
    private static final String ORDER_CODE = "BS260101000000123";
    private static final String GHN_CODE = "GHN-WEB-001";

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private GhnShippingService ghnShippingService;

    private GhnProperties properties;
    private GhnWebhookService service;

    @BeforeEach
    void setUp() {
        properties = new GhnProperties();
        properties.setEnabled(true);
        properties.setShopId((int) SHOP_ID);
        GhnProperties.Webhook webhook = new GhnProperties.Webhook();
        webhook.setEnabled(true);
        webhook.setSecret(SECRET);
        properties.setWebhook(webhook);
        service = new GhnWebhookServiceImpl(properties, orderRepository, ghnShippingService);
    }

    @Test
    @DisplayName("Webhook hợp lệ: tìm đơn theo mã GHN và áp dụng đúng trạng thái")
    void deliveredAppliesStatus() {
        Order order = order(OrderStatus.SHIPPING, GHN_CODE);
        when(orderRepository.findAllByGhnOrderCode(GHN_CODE)).thenReturn(List.of(order));
        when(ghnShippingService.applyStatus(ORDER_ID, GHN_CODE, GhnStatus.DELIVERED, "Webhook GHN", null))
                .thenReturn(new GhnSyncResult(GHN_CODE, "delivered", "Đã giao hàng",
                        OrderStatus.DELIVERED, true, "Đã cập nhật đơn."));

        GhnWebhookResult result = service.handle(payload(GHN_CODE, null, "delivered", SHOP_ID));

        assertThat(result.applied()).isTrue();
        assertThat(result.orderId()).isEqualTo(ORDER_ID);
        assertThat(result.orderStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(result.message()).contains("Đã cập nhật");
    }

    @Test
    @DisplayName("Sai ShopID ⇒ 403, không tra DB, không đổi đơn")
    void wrongShopIdIsForbidden() {
        assertThatThrownBy(() -> service.handle(payload(GHN_CODE, null, "delivered", SHOP_ID + 1)))
                .isInstanceOfSatisfying(GhnWebhookException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(orderRepository, ghnShippingService);
    }

    @Test
    @DisplayName("Thiếu ShopID trong payload ⇒ 403")
    void missingShopIdIsForbidden() {
        assertThatThrownBy(() -> service.handle(payload(GHN_CODE, null, "delivered", null)))
                .isInstanceOfSatisfying(GhnWebhookException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(orderRepository, ghnShippingService);
    }

    @Test
    @DisplayName("Webhook chưa cấu hình secret/shop-id ⇒ 503 (fail closed)")
    void notConfiguredIsUnavailable() {
        properties.getWebhook().setSecret("");

        assertThatThrownBy(() -> service.handle(payload(GHN_CODE, null, "delivered", SHOP_ID)))
                .isInstanceOfSatisfying(GhnWebhookException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(orderRepository, ghnShippingService);
    }

    @Test
    @DisplayName("Thiếu cả OrderCode và ClientOrderCode ⇒ 400")
    void missingOrderCodesIsBadRequest() {
        assertThatThrownBy(() -> service.handle(payload(null, null, "delivered", SHOP_ID)))
                .isInstanceOfSatisfying(GhnWebhookException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(orderRepository, ghnShippingService);
    }

    @Test
    @DisplayName("Thiếu Status ⇒ 400")
    void missingStatusIsBadRequest() {
        assertThatThrownBy(() -> service.handle(payload(GHN_CODE, null, " ", SHOP_ID)))
                .isInstanceOfSatisfying(GhnWebhookException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(orderRepository, ghnShippingService);
    }

    @Test
    @DisplayName("Trạng thái GHN lạ ⇒ bỏ qua an toàn (2xx), không đoán trạng thái đơn")
    void unknownGhnStatusIsIgnored() {
        GhnWebhookResult result = service.handle(payload(GHN_CODE, null, "ma-moi-cua-ghn", SHOP_ID));

        assertThat(result.applied()).isFalse();
        assertThat(result.message()).contains("không xác định");
        verifyNoInteractions(orderRepository, ghnShippingService);
    }

    @Test
    @DisplayName("Không tìm thấy đơn theo mã GHN ⇒ 404 để GHN retry")
    void unknownOrderIsNotFound() {
        when(orderRepository.findAllByGhnOrderCode(GHN_CODE)).thenReturn(List.of());

        assertThatThrownBy(() -> service.handle(payload(GHN_CODE, null, "delivered", SHOP_ID)))
                .isInstanceOfSatisfying(GhnWebhookException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(ghnShippingService, never()).applyStatus(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Nhiều đơn cùng mã GHN ⇒ 409, không đoán đơn để tránh đổi nhầm")
    void duplicateGhnCodeIsConflict() {
        when(orderRepository.findAllByGhnOrderCode(GHN_CODE))
                .thenReturn(List.of(order(OrderStatus.SHIPPING, GHN_CODE), order(OrderStatus.CONFIRMED, GHN_CODE)));

        assertThatThrownBy(() -> service.handle(payload(GHN_CODE, null, "delivered", SHOP_ID)))
                .isInstanceOfSatisfying(GhnWebhookException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(ghnShippingService, never()).applyStatus(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Chỉ có ClientOrderCode: fallback tìm theo mã đơn và vẫn dùng mã vận đơn đã lưu")
    void clientOrderCodeFallsBackSafely() {
        Order order = order(OrderStatus.SHIPPING, GHN_CODE);
        when(orderRepository.findByOrderCode(ORDER_CODE)).thenReturn(Optional.of(order));
        when(ghnShippingService.applyStatus(ORDER_ID, GHN_CODE, GhnStatus.DELIVERED, "Webhook GHN", null))
                .thenReturn(new GhnSyncResult(GHN_CODE, "delivered", "Đã giao hàng",
                        OrderStatus.DELIVERED, true, "ok"));

        GhnWebhookResult result = service.handle(payload(null, ORDER_CODE, "delivered", SHOP_ID));

        assertThat(result.applied()).isTrue();
        verify(orderRepository).findByOrderCode(ORDER_CODE);
        verify(orderRepository, never()).findAllByGhnOrderCode(anyString());
    }

    @Test
    @DisplayName("ClientOrderCode không khớp đơn tìm được ⇒ 409")
    void mismatchedClientCodeIsConflict() {
        Order order = order(OrderStatus.SHIPPING, GHN_CODE);
        when(orderRepository.findAllByGhnOrderCode(GHN_CODE)).thenReturn(List.of(order));

        assertThatThrownBy(() -> service.handle(payload(GHN_CODE, "BS-KHAC", "delivered", SHOP_ID)))
                .isInstanceOfSatisfying(GhnWebhookException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(ghnShippingService, never()).applyStatus(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Chỉ có ClientOrderCode nhưng đơn chưa từng đẩy GHN ⇒ 409, không đổi trạng thái")
    void clientCodeWithoutGhnCodeIsConflict() {
        Order order = order(OrderStatus.SHIPPING, null);
        when(orderRepository.findByOrderCode(ORDER_CODE)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.handle(payload(null, ORDER_CODE, "delivered", SHOP_ID)))
                .isInstanceOfSatisfying(GhnWebhookException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(ghnShippingService, never()).applyStatus(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Sự kiện cũ/không còn hợp lệ ⇒ 2xx bỏ qua, không làm lùi trạng thái")
    void staleEventIsSkipped() {
        Order order = order(OrderStatus.CANCELLED, GHN_CODE);
        when(orderRepository.findAllByGhnOrderCode(GHN_CODE)).thenReturn(List.of(order));
        when(ghnShippingService.applyStatus(eq(ORDER_ID), eq(GHN_CODE), eq(GhnStatus.DELIVERED),
                eq("Webhook GHN"), isNull()))
                .thenThrow(new BusinessException("Không thể chuyển đơn từ \"Đã huỷ\" sang \"Giao thành công\"."));

        GhnWebhookResult result = service.handle(payload(GHN_CODE, null, "delivered", SHOP_ID));

        assertThat(result.applied()).isFalse();
        assertThat(result.message()).contains("Bỏ qua sự kiện");
        assertThat(result.orderStatus()).isNull();
    }

    private GhnWebhookPayload payload(String orderCode, String clientOrderCode, String status, Long shopId) {
        return new GhnWebhookPayload(orderCode, clientOrderCode, status, shopId, "2026-11-07T00:00:00Z", "update");
    }

    private Order order(OrderStatus status, String ghnCode) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderCode(ORDER_CODE);
        order.setOrderStatus(status);
        order.setGhnOrderCode(ghnCode);
        return order;
    }
}
