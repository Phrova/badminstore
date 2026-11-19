package com.example.demo.shipping.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.demo.shipping.config.GhnProperties;
import com.example.demo.shipping.service.GhnClient;
import com.example.demo.shipping.service.GhnCodCalculator;
import com.example.demo.shipping.dto.GhnSyncResult;
import com.example.demo.shipping.dto.GhnCancelResult;
import com.example.demo.shipping.dto.GhnCreateOrderRequest;
import com.example.demo.shipping.dto.GhnOrderDetail;
import com.example.demo.shipping.dto.GhnOrderResult;
import com.example.demo.order.entity.Order;
import com.example.demo.order.entity.OrderItem;
import com.example.demo.payment.entity.Payment;
import com.example.demo.user.entity.UserAddress;
import com.example.demo.shipping.enums.GhnStatus;
import com.example.demo.order.enums.OrderStatus;
import com.example.demo.payment.enums.PaymentMethod;
import com.example.demo.payment.enums.PaymentStatus;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.example.demo.order.service.OrderService;

/**
 * Kiểm thử nghiệp vụ vận chuyển GHN (Mockito):
 *  – đẩy đơn sang GHN chỉ khi đơn đã xác nhận + có mã Quận/Huyện của GHN;
 *  – GHN báo "đã giao" khi đơn còn CONFIRMED thì phải đi qua SHIPPING (đúng máy trạng thái);
 *  – GHN báo hoàn hàng khi đang giao ⇒ KHÔNG tự huỷ, chỉ cảnh báo; đã giao rồi ⇒ hoàn trả;
 *  – COD chỉ thu tiền hàng (shop trả ship), thanh toán một phần bị chặn;
 *  – huỷ GHN chỉ đổi đơn khi GHN trả result=true.
 */
@ExtendWith(MockitoExtension.class)
class GhnShippingServiceTest {

    private static final Integer ORDER_ID = 12;
    private static final Integer ADMIN_ID = 3;

    @Mock
    private GhnClient ghnClient;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderService orderService;

    private GhnProperties ghnProperties;
    private GhnShippingServiceImpl ghnShippingService;

    @BeforeEach
    void setUp() {
        ghnProperties = new GhnProperties();
        ghnProperties.setEnabled(true);
        ghnProperties.setToken("token-test");
        ghnProperties.setShopId(123456);
        ghnProperties.setFromDistrictId(1442);
        ghnShippingService = new GhnShippingServiceImpl(ghnClient, ghnProperties, orderRepository, orderService);
    }

    private Order order(OrderStatus status) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderCode("BS261016000001");
        order.setOrderStatus(status);
        order.setStockDeducted(status.isStockDeducted());
        order.setReceiverName("Nguyễn Văn A");
        order.setReceiverPhone("0987654321");
        order.setShippingAddress("123 Nguyễn Trãi");
        order.setSubtotal(new BigDecimal("1490000"));
        order.setShippingFee(new BigDecimal("30000"));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setTotalAmount(new BigDecimal("1520000"));
        return order;
    }

    private OrderItem item(Order order) {
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setSku("SKU-1");
        item.setProductName("Vợt Yonex Astrox 88D");
        item.setColor("Đen");
        item.setWeightClass("4U");
        item.setGripSize("G5");
        item.setUnitPrice(new BigDecimal("1490000"));
        item.setQuantity(1);
        item.setSubtotal(new BigDecimal("1490000"));
        order.getItems().add(item);
        return item;
    }

    private void stubOrder(Order order) {
        when(orderRepository.findDetailForAdmin(ORDER_ID)).thenReturn(Optional.of(order));
    }

    private void stubGhnStatus(Order order, String rawStatus) {
        order.setGhnOrderCode("GHN999888");
        when(ghnClient.orderDetail("GHN999888"))
                .thenReturn(new GhnOrderDetail("GHN999888", rawStatus, 34500, null, null, null, null, null));
    }

    @Test
    @DisplayName("Đường đi trong máy trạng thái: CONFIRMED ⇒ DELIVERED phải qua SHIPPING, không tới được RETURNED")
    void pathTo_followsStateMachine() {
        assertThat(GhnShippingServiceImpl.pathTo(OrderStatus.CONFIRMED, OrderStatus.DELIVERED))
                .containsExactly(OrderStatus.SHIPPING, OrderStatus.DELIVERED);
        assertThat(GhnShippingServiceImpl.pathTo(OrderStatus.SHIPPING, OrderStatus.DELIVERED))
                .containsExactly(OrderStatus.DELIVERED);
        assertThat(GhnShippingServiceImpl.pathTo(OrderStatus.CONFIRMED, OrderStatus.CANCELLED))
                .containsExactly(OrderStatus.CANCELLED);
        assertThat(GhnShippingServiceImpl.pathTo(OrderStatus.DELIVERED, OrderStatus.RETURNED))
                .containsExactly(OrderStatus.RETURNED);
        // Chưa giao thì không thể nhảy thẳng sang "hoàn trả" trong 1 bước, phải đi cả hành trình:
        assertThat(GhnShippingServiceImpl.pathTo(OrderStatus.PENDING, OrderStatus.RETURNED))
                .containsExactly(OrderStatus.CONFIRMED, OrderStatus.SHIPPING, OrderStatus.DELIVERED,
                        OrderStatus.RETURNED);
        // Đơn đã kết thúc thì không đi đâu được nữa
        assertThat(GhnShippingServiceImpl.pathTo(OrderStatus.CANCELLED, OrderStatus.DELIVERED)).isEmpty();
    }

    @Test
    @DisplayName("GHN báo ĐÃ GIAO khi đơn còn CONFIRMED ⇒ lần lượt chuyển SHIPPING rồi DELIVERED")
    void syncStatus_deliveredFromConfirmed_appliesEveryStep() {
        Order order = order(OrderStatus.CONFIRMED);
        stubOrder(order);
        stubGhnStatus(order, "delivered");

        GhnSyncResult result = ghnShippingService.syncStatus(ORDER_ID, ADMIN_ID);

        assertThat(result.changed()).isTrue();
        assertThat(result.orderStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(result.ghnStatusLabel()).isEqualTo("Đã giao hàng");
        assertThat(result.message()).contains("Giao thành công");
        verify(orderService).changeStatusByAdmin(eq(ORDER_ID), eq(OrderStatus.SHIPPING), eq(ADMIN_ID), anyString());
        verify(orderService).changeStatusByAdmin(eq(ORDER_ID), eq(OrderStatus.DELIVERED), eq(ADMIN_ID), anyString());
    }

    @Test
    @DisplayName("GHN mới ở bước lấy hàng ⇒ không cần đổi trạng thái đơn")
    void syncStatus_pickingMeansNoChange() {
        Order order = order(OrderStatus.CONFIRMED);
        stubOrder(order);
        stubGhnStatus(order, "picking");

        GhnSyncResult result = ghnShippingService.syncStatus(ORDER_ID, ADMIN_ID);

        assertThat(result.changed()).isFalse();
        assertThat(result.orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(result.message()).contains("không cần đổi");
        verify(orderService, never()).changeStatusByAdmin(any(), any(), any(), any());
    }

    @Test
    @DisplayName("GHN báo hoàn hàng khi đơn ĐANG GIAO ⇒ KHÔNG tự huỷ/cộng kho, chỉ cảnh báo admin")
    void syncStatus_returnWhileShipping_keepsOrder() {
        Order order = order(OrderStatus.SHIPPING);
        stubOrder(order);
        stubGhnStatus(order, "returned");

        GhnSyncResult result = ghnShippingService.syncStatus(ORDER_ID, ADMIN_ID);

        assertThat(result.changed()).isFalse();
        assertThat(result.orderStatus()).isEqualTo(OrderStatus.SHIPPING);
        assertThat(result.message()).contains("không tự hoàn trả");
        verify(orderService, never()).changeStatusByAdmin(any(), any(), any(), any());
    }

    @Test
    @DisplayName("GHN báo ngoại lệ/mất hàng ⇒ giữ nguyên đơn, chỉ cảnh báo")
    void syncStatus_exceptionDoesNotCancelOrder() {
        Order order = order(OrderStatus.SHIPPING);
        stubOrder(order);
        stubGhnStatus(order, "lost");

        GhnSyncResult result = ghnShippingService.syncStatus(ORDER_ID, ADMIN_ID);

        assertThat(result.changed()).isFalse();
        assertThat(result.orderStatus()).isEqualTo(OrderStatus.SHIPPING);
        assertThat(result.message()).contains("kiểm tra thủ công");
        verify(orderService, never()).changeStatusByAdmin(any(), any(), any(), any());
    }

    @Test
    @DisplayName("GHN báo hoàn hàng khi đơn ĐÃ GIAO ⇒ hoàn trả 100% (cộng kho, hoàn tiền)")
    void syncStatus_returnAfterDelivered_returnsOrder() {
        Order order = order(OrderStatus.DELIVERED);
        stubOrder(order);
        stubGhnStatus(order, "returned");

        GhnSyncResult result = ghnShippingService.syncStatus(ORDER_ID, ADMIN_ID);

        assertThat(result.changed()).isTrue();
        assertThat(result.orderStatus()).isEqualTo(OrderStatus.RETURNED);
    }

    @Test
    @DisplayName("Job định kỳ (không có admin) dùng changeStatusBySystem để ghi lịch sử là Hệ thống")
    void syncStatus_systemActorUsesSystemChange() {
        Order order = order(OrderStatus.SHIPPING);
        stubOrder(order);
        stubGhnStatus(order, "delivered");

        ghnShippingService.syncStatus(ORDER_ID, null);

        verify(orderService).changeStatusBySystem(eq(ORDER_ID), eq(OrderStatus.DELIVERED), anyString());
        verify(orderService, never()).changeStatusByAdmin(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Đơn chưa có mã vận đơn thì không đồng bộ được")
    void syncStatus_requiresShippingCode() {
        Order order = order(OrderStatus.CONFIRMED);
        stubOrder(order);

        assertThatThrownBy(() -> ghnShippingService.syncStatus(ORDER_ID, ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("chưa có vận đơn GHN");
    }

    @Test
    @DisplayName("Chỉ đẩy sang GHN khi đơn đã xác nhận (đã trừ kho)")
    void pushOrder_requiresConfirmedStatus() {
        Order order = order(OrderStatus.PENDING);
        stubOrder(order);
        when(ghnClient.isShippingAvailable()).thenReturn(true);

        assertThatThrownBy(() -> ghnShippingService.pushOrder(ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("đã được xác nhận");
    }

    @Test
    @DisplayName("Chưa khai báo shopId/kho lấy hàng ⇒ báo rõ thiếu cấu hình thay vì gọi GHN")
    void pushOrder_requiresShippingConfiguration() {
        Order order = order(OrderStatus.CONFIRMED);
        stubOrder(order);
        when(ghnClient.isShippingAvailable()).thenReturn(false);

        assertThatThrownBy(() -> ghnShippingService.pushOrder(ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Chưa cấu hình đủ GHN");
        verify(ghnClient, never()).createOrder(any());
    }

    @Test
    @DisplayName("Đơn thiếu mã Quận/Huyện của GHN (khách nhập địa chỉ tay) thì GHN không nhận")
    void pushOrder_requiresGhnDistrictCodes() {
        Order order = order(OrderStatus.CONFIRMED);
        stubOrder(order);
        when(ghnClient.isShippingAvailable()).thenReturn(true);

        assertThatThrownBy(() -> ghnShippingService.pushOrder(ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("chưa có mã Quận/Huyện");
    }

    @Test
    @DisplayName("Địa chỉ 2 cấp mới: vận đơn gửi mã + tên Phường/Xã, tên Tỉnh và bật is_new_to_address")
    void pushOrder_newAddressSendsNamesWithoutDistrict() {
        Order order = order(OrderStatus.CONFIRMED);
        order.setShippingProvinceId(1000001);
        order.setShippingWardCode("1003646");
        order.setShippingAddress("12 Lê Lợi, Phường Vũng Tàu, Hồ Chí Minh");
        UserAddress address = new UserAddress();
        address.setProvinceName("Hồ Chí Minh");
        address.setWardName("Phường Vũng Tàu");
        address.setAddressDetail("12 Lê Lợi");
        order.setAddress(address);
        item(order);
        stubOrder(order);
        when(ghnClient.isShippingAvailable()).thenReturn(true);
        when(ghnClient.createOrder(any(GhnCreateOrderRequest.class)))
                .thenReturn(new GhnOrderResult("GHN777666", 34500L, null, null, null));

        ghnShippingService.pushOrder(ORDER_ID);

        ArgumentCaptor<GhnCreateOrderRequest> captor = ArgumentCaptor.forClass(GhnCreateOrderRequest.class);
        verify(ghnClient).createOrder(captor.capture());
        GhnCreateOrderRequest request = captor.getValue();
        assertThat(request.getIsNewToAddress()).isTrue();
        assertThat(request.getToProvinceName()).isEqualTo("Hồ Chí Minh");
        assertThat(request.getToWardName()).isEqualTo("Phường Vũng Tàu");
        assertThat(request.getToWardCode()).isEqualTo("1003646");
        assertThat(request.getToDistrictId()).isNull();
    }

    @Test
    @DisplayName("Đẩy đơn thành công: gửi đúng người nhận/khối lượng/COD và LƯU mã vận đơn vào đơn")
    void pushOrder_savesOrderCode() {
        Order order = order(OrderStatus.CONFIRMED);
        order.setShippingDistrictId(1454);
        order.setShippingWardCode("1A0808");
        item(order);
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.COD);
        payment.setPaymentStatus(PaymentStatus.PENDING);
        payment.setAmount(order.getTotalAmount());
        order.setPayment(payment);
        stubOrder(order);
        when(ghnClient.isShippingAvailable()).thenReturn(true);
        when(ghnClient.createOrder(any(GhnCreateOrderRequest.class)))
                .thenReturn(new GhnOrderResult("GHN777666", 34500, null, null, null));

        GhnOrderResult result = ghnShippingService.pushOrder(ORDER_ID);

        assertThat(result.orderCode()).isEqualTo("GHN777666");
        assertThat(order.getGhnOrderCode()).isEqualTo("GHN777666");
        verify(orderRepository).save(order);

        ArgumentCaptor<GhnCreateOrderRequest> captor = ArgumentCaptor.forClass(GhnCreateOrderRequest.class);
        verify(ghnClient).createOrder(captor.capture());
        GhnCreateOrderRequest request = captor.getValue();
        assertThat(request.getClientOrderCode()).isEqualTo("BS261016000001");
        assertThat(request.getToName()).isEqualTo("Nguyễn Văn A");
        assertThat(request.getToDistrictId()).isEqualTo(1454);
        assertThat(request.getToWardCode()).isEqualTo("1A0808");
        // Mặc định shop trả phí ship: COD chỉ thu tiền hàng (1.490.000), không thu 30.000 phí ship.
        assertThat(request.getCodAmount()).isEqualTo(1490000L);
        assertThat(request.getOrderValue()).isEqualTo(1490000L);
        assertThat(request.getPaymentTypeId()).isEqualTo(GhnCodCalculator.PAYMENT_TYPE_SHOP_PAYS);
        assertThat(request.getFromDistrictId()).isEqualTo(1442);
        assertThat(request.getFromName()).isEqualTo("BadStore");
        assertThat(request.getWeight()).isEqualTo(1500);
        assertThat(request.getItems()).hasSize(1);
        assertThat(request.getItems().get(0).getCode()).isEqualTo("SKU-1");
    }

    @Test
    @DisplayName("Đơn đã trả đủ online ⇒ không thu COD, shop vẫn trả phí ship")
    void pushOrder_prepaidOrderHasNoCod() {
        Order order = order(OrderStatus.CONFIRMED);
        order.setShippingDistrictId(1454);
        order.setShippingWardCode("1A0808");
        item(order);
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        payment.setPaymentStatus(PaymentStatus.PAID);
        payment.setAmount(order.getTotalAmount());
        order.setPayment(payment);
        stubOrder(order);
        when(ghnClient.isShippingAvailable()).thenReturn(true);
        when(ghnClient.createOrder(any(GhnCreateOrderRequest.class)))
                .thenReturn(new GhnOrderResult("GHN777666", 34500, null, null, null));

        ghnShippingService.pushOrder(ORDER_ID);

        ArgumentCaptor<GhnCreateOrderRequest> captor = ArgumentCaptor.forClass(GhnCreateOrderRequest.class);
        verify(ghnClient).createOrder(captor.capture());
        assertThat(captor.getValue().getCodAmount()).isZero();
        assertThat(captor.getValue().getPaymentTypeId()).isEqualTo(GhnCodCalculator.PAYMENT_TYPE_SHOP_PAYS);
    }

    @Test
    @DisplayName("Đơn COD đã trả một phần ⇒ chặn tạo vận đơn, không gọi GHN")
    void pushOrder_partialPaymentIsRejected() {
        Order order = order(OrderStatus.CONFIRMED);
        order.setShippingDistrictId(1454);
        order.setShippingWardCode("1A0808");
        item(order);
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.COD);
        payment.setPaymentStatus(PaymentStatus.PAID);
        payment.setAmount(new BigDecimal("500000"));
        order.setPayment(payment);
        stubOrder(order);
        when(ghnClient.isShippingAvailable()).thenReturn(true);

        assertThatThrownBy(() -> ghnShippingService.pushOrder(ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("nhỏ hơn tổng");
        verify(ghnClient, never()).createOrder(any());
    }

    @Test
    @DisplayName("Huỷ vận đơn: GHN trả result=true ⇒ huỷ đơn shop qua OrderService")
    void cancelOrder_ghnSuccessCancelsOrder() {
        Order order = order(OrderStatus.SHIPPING);
        order.setGhnOrderCode("GHN999888");
        stubOrder(order);
        when(ghnClient.isShippingAvailable()).thenReturn(true);
        when(ghnClient.cancelOrder("GHN999888", "Khách đổi ý", "GHN-CO001"))
                .thenReturn(new GhnCancelResult("GHN999888", true, "Đã huỷ"));

        GhnCancelResult result = ghnShippingService.cancelOrder(ORDER_ID, "Khách đổi ý", "GHN-CO001", ADMIN_ID);

        assertThat(result.result()).isTrue();
        verify(orderService).changeStatusByAdmin(eq(ORDER_ID), eq(OrderStatus.CANCELLED), eq(ADMIN_ID), anyString());
    }

    @Test
    @DisplayName("Huỷ vận đơn: GHN trả result=false ⇒ giữ nguyên đơn, không cộng kho")
    void cancelOrder_ghnRejectsKeepsOrder() {
        Order order = order(OrderStatus.SHIPPING);
        order.setGhnOrderCode("GHN999888");
        stubOrder(order);
        when(ghnClient.isShippingAvailable()).thenReturn(true);
        when(ghnClient.cancelOrder("GHN999888", null, "GHN-CANCEL-OTHER"))
                .thenReturn(new GhnCancelResult("GHN999888", false, "Vận đơn đang giao, không thể huỷ"));

        GhnCancelResult result = ghnShippingService.cancelOrder(ORDER_ID, null, null, ADMIN_ID);

        assertThat(result.result()).isFalse();
        verify(orderService, never()).changeStatusByAdmin(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Đơn đã có mã vận đơn thì không đẩy lại (tránh tạo trùng vận đơn)")
    void pushOrder_rejectsDuplicate() {
        Order order = order(OrderStatus.CONFIRMED);
        order.setGhnOrderCode("GHN999888");
        stubOrder(order);
        when(ghnClient.isShippingAvailable()).thenReturn(true);

        assertThatThrownBy(() -> ghnShippingService.pushOrder(ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("đã có vận đơn GHN");
        verify(ghnClient, never()).createOrder(any());
    }

    @Test
    @DisplayName("Khối lượng kiện hàng: 1 cây 1.500g, mỗi cây thêm 300g")
    void weightOf_countsItems() {
        Order order = order(OrderStatus.CONFIRMED);
        item(order);
        OrderItem second = item(order);
        second.setQuantity(2);
        second.setSubtotal(new BigDecimal("2980000"));

        assertThat(ghnShippingService.weightOf(order)).isEqualTo(1500 + 2 * 300);
        assertThat(ghnProperties.getWeightGram()).isEqualTo(1500);
    }

    @Test
    @DisplayName("Webhook applyStatus: áp dụng delivered đã đổi trạng thái và ghi nguồn Webhook GHN")
    void applyStatus_delivered_appliesChange() {
        when(orderService.changeStatusPath(eq(ORDER_ID), any(), isNull(), anyString()))
                .thenReturn(new OrderService.StatusChangeResult(
                        OrderStatus.SHIPPING, OrderStatus.DELIVERED, true));

        GhnSyncResult result = ghnShippingService.applyStatus(
                ORDER_ID, "GHN999888", GhnStatus.DELIVERED, "Webhook GHN", null);

        assertThat(result.changed()).isTrue();
        assertThat(result.orderStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(result.message()).contains("Giao thành công");
        verify(orderService).changeStatusPath(eq(ORDER_ID), any(), isNull(), contains("Webhook GHN"));
    }

    @Test
    @DisplayName("Webhook applyStatus: sự kiện delivered trùng ⇒ no-op, không ghi lịch sử")
    void applyStatus_duplicate_isNoOp() {
        when(orderService.changeStatusPath(eq(ORDER_ID), any(), isNull(), anyString()))
                .thenReturn(new OrderService.StatusChangeResult(
                        OrderStatus.DELIVERED, OrderStatus.DELIVERED, false));

        GhnSyncResult result = ghnShippingService.applyStatus(
                ORDER_ID, "GHN999888", GhnStatus.DELIVERED, "Webhook GHN", null);

        assertThat(result.changed()).isFalse();
        assertThat(result.orderStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(result.message()).contains("trùng");
    }

    @Test
    @DisplayName("Webhook applyStatus: trạng thái cảnh báo (lost) ⇒ giữ đơn, chỉ cảnh báo")
    void applyStatus_warning_isNoOp() {
        when(orderService.changeStatusPath(eq(ORDER_ID), any(), isNull(), anyString()))
                .thenReturn(new OrderService.StatusChangeResult(
                        OrderStatus.SHIPPING, OrderStatus.SHIPPING, false));

        GhnSyncResult result = ghnShippingService.applyStatus(
                ORDER_ID, "GHN999888", GhnStatus.LOST, "Webhook GHN", null);

        assertThat(result.changed()).isFalse();
        assertThat(result.orderStatus()).isEqualTo(OrderStatus.SHIPPING);
        assertThat(result.message()).contains("kiểm tra thủ công");
    }

    @Test
    @DisplayName("Webhook applyStatus: đơn chưa có mã vận đơn GHN thì từ chối")
    void applyStatus_requiresGhnOrderCode() {
        assertThatThrownBy(() -> ghnShippingService.applyStatus(
                ORDER_ID, " ", GhnStatus.DELIVERED, "Webhook GHN", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("chưa có vận đơn GHN");
        verify(orderService, never()).changeStatusPath(any(), any(), any(), any());
    }
}
