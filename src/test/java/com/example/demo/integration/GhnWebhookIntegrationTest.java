package com.example.demo.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.order.entity.Order;
import com.example.demo.order.entity.OrderItem;
import com.example.demo.payment.entity.Payment;
import com.example.demo.catalog.entity.Product;
import com.example.demo.catalog.entity.ProductVariant;
import com.example.demo.user.entity.User;
import com.example.demo.order.enums.OrderStatus;
import com.example.demo.payment.enums.PaymentMethod;
import com.example.demo.payment.enums.PaymentStatus;
import com.example.demo.catalog.enums.ProductStatus;
import com.example.demo.user.enums.UserRole;
import com.example.demo.user.enums.UserStatus;
import com.example.demo.catalog.enums.VariantStatus;
import com.example.demo.catalog.repository.BrandRepository;
import com.example.demo.catalog.repository.CategoryRepository;
import com.example.demo.order.repository.OrderItemRepository;
import com.example.demo.order.repository.OrderRepository;
import com.example.demo.order.repository.OrderStatusHistoryRepository;
import com.example.demo.payment.repository.PaymentRepository;
import com.example.demo.catalog.repository.ProductRepository;
import com.example.demo.catalog.repository.ProductVariantRepository;
import com.example.demo.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Test tích hợp webhook GHN với MockMvc + SQL Server thật (không mock Security/DB):
 *  – endpoint không cần đăng nhập, không cần CSRF nhưng bắt buộc secret + đúng ShopID;
 *  – delivered/returned/cancel đi đúng máy trạng thái, cộng/trừ kho, cập nhật thanh toán;
 *  – sự kiện trùng/cũ/cảnh báo trả 2xx nhưng không đổi đơn;
 *  – các API khác vẫn yêu cầu đăng nhập như trước.
 *
 * Mỗi test tạo dữ liệu riêng và dọn sạch ở @AfterEach, không đụng đơn demo sẵn có.
 */
@SpringBootTest(properties = {
        "app.ghn.enabled=true",
        "app.ghn.mock=true",
        "app.ghn.shop-id=2546099",
        "app.ghn.webhook.enabled=true",
        "app.ghn.webhook.secret=webhook-integration-secret",
        "app.ghn.webhook.header-name=X-GHN-Signature",
        "app.ghn.sync-enabled=false"
})
@AutoConfigureMockMvc
class GhnWebhookIntegrationTest {

    private static final String WEBHOOK_PATH = "/api/webhooks/ghn/order-status";
    private static final String HEADER = "X-GHN-Signature";
    private static final String SECRET = "webhook-integration-secret";
    private static final long SHOP_ID = 2546099L;
    private static final String PASSWORD = "Secret123";
    private static final BigDecimal PRICE = new BigDecimal("1490000");
    private static final BigDecimal SHIPPING_FEE = new BigDecimal("30000");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderStatusHistoryRepository historyRepository;

    private User customer;
    private Product product;
    private ProductVariant variant;
    private int orderSequence;

    private final List<Integer> createdOrderIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime());

        customer = new User();
        customer.setEmail("ghn.webhook." + suffix + "@example.com");
        customer.setPasswordHash(passwordEncoder.encode(PASSWORD));
        customer.setFullName("Khách webhook GHN");
        customer.setPhone("0901234567");
        customer.setRole(UserRole.CUSTOMER);
        customer.setStatus(UserStatus.ACTIVE);
        customer = userRepository.save(customer);

        product = new Product();
        product.setProductCode("WH-" + suffix.substring(suffix.length() - 8));
        product.setName("Vợt webhook GHN " + suffix.substring(suffix.length() - 4));
        product.setBrand(brandRepository.findByDeletedFalseOrderByNameAsc().get(0));
        product.setCategory(categoryRepository.findByDeletedFalseOrderByNameAsc().get(0));
        product.setStatus(ProductStatus.ACTIVE);
        product = productRepository.save(product);

        variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("WH-SKU-" + suffix.substring(suffix.length() - 8));
        variant.setColor("Xanh");
        variant.setWeightClass("4U");
        variant.setGripSize("G5");
        variant.setPrice(PRICE);
        variant.setStockQuantity(10);
        variant.setStatus(VariantStatus.ACTIVE);
        variant = productVariantRepository.save(variant);
    }

    @AfterEach
    void cleanUp() {
        for (Integer orderId : createdOrderIds) {
            historyRepository.deleteAll(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId));
            paymentRepository.findByOrderId(orderId).ifPresent(paymentRepository::delete);
            orderItemRepository.deleteAll(orderItemRepository.findByOrderIdOrderByProductVariantIdAsc(orderId));
            orderRepository.deleteById(orderId);
        }
        productVariantRepository.delete(variant);
        productRepository.delete(product);
        userRepository.delete(customer);
    }

    @Test
    @DisplayName("Webhook không cần đăng nhập/CSRF: delivered ⇒ đơn DELIVERED, COD PAID, không lộ secret")
    void delivered_marksOrderDeliveredAndPaid() throws Exception {
        Order order = createOrder(OrderStatus.SHIPPING, PaymentStatus.PENDING);

        mockMvc.perform(webhook(deliveredJson(order.getGhnOrderCode()))
                        .header(HEADER, SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(content().string(not(containsString(SECRET))));

        Order updated = orderRepository.findDetailForAdmin(order.getId()).orElseThrow();
        assertThat(updated.getOrderStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(updated.isStockDeducted()).isTrue();
        assertThat(paymentRepository.findByOrderId(order.getId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.PAID);
        assertThat(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId()))
                .singleElement()
                .satisfies(history -> {
                    assertThat(history.getToStatus()).isEqualTo(OrderStatus.DELIVERED);
                    assertThat(history.getNote()).contains("Webhook GHN");
                });
    }

    @Test
    @DisplayName("Thiếu hoặc sai secret ⇒ 401, đơn không đổi")
    void missingOrWrongSecret_isUnauthorized() throws Exception {
        Order order = createOrder(OrderStatus.SHIPPING, PaymentStatus.PENDING);

        mockMvc.perform(webhook(deliveredJson(order.getGhnOrderCode())))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString(SECRET))));

        mockMvc.perform(webhook(deliveredJson(order.getGhnOrderCode()))
                        .header(HEADER, SECRET + "-sai"))
                .andExpect(status().isUnauthorized());

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getOrderStatus())
                .isEqualTo(OrderStatus.SHIPPING);
        assertThat(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId())).isEmpty();
    }

    @Test
    @DisplayName("Sai ShopID trong payload ⇒ 403, đơn không đổi")
    void wrongShopId_isForbidden() throws Exception {
        Order order = createOrder(OrderStatus.SHIPPING, PaymentStatus.PENDING);

        mockMvc.perform(webhook(deliveredJson(order.getGhnOrderCode(), SHOP_ID + 1))
                        .header(HEADER, SECRET))
                .andExpect(status().isForbidden());

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getOrderStatus())
                .isEqualTo(OrderStatus.SHIPPING);
    }

    @Test
    @DisplayName("Payload JSON hỏng ⇒ 400, không đổi đơn")
    void malformedJson_isBadRequest() throws Exception {
        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HEADER, SECRET)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("cancel khi đơn CONFIRMED ⇒ CANCELLED, cộng lại kho đúng 1 lần")
    void cancel_restoresStock() throws Exception {
        Order order = createOrder(OrderStatus.CONFIRMED, PaymentStatus.PENDING);
        int stockBefore = productVariantRepository.findById(variant.getId()).orElseThrow().getStockQuantity();

        mockMvc.perform(webhook(statusJson(order.getGhnOrderCode(), "cancel"))
                        .header(HEADER, SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true));

        Order updated = orderRepository.findDetailForAdmin(order.getId()).orElseThrow();
        assertThat(updated.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(updated.isStockDeducted()).isFalse();
        assertThat(productVariantRepository.findById(variant.getId()).orElseThrow().getStockQuantity())
                .isEqualTo(stockBefore + 1);
    }

    @Test
    @DisplayName("returned khi đơn DELIVERED ⇒ RETURNED, cộng kho, hoàn tiền")
    void returned_afterDelivered_restoresStockAndRefunds() throws Exception {
        Order order = createOrder(OrderStatus.DELIVERED, PaymentStatus.PAID);
        int stockBefore = productVariantRepository.findById(variant.getId()).orElseThrow().getStockQuantity();

        mockMvc.perform(webhook(statusJson(order.getGhnOrderCode(), "returned"))
                        .header(HEADER, SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true));

        Order updated = orderRepository.findDetailForAdmin(order.getId()).orElseThrow();
        assertThat(updated.getOrderStatus()).isEqualTo(OrderStatus.RETURNED);
        assertThat(updated.isStockDeducted()).isFalse();
        assertThat(productVariantRepository.findById(variant.getId()).orElseThrow().getStockQuantity())
                .isEqualTo(stockBefore + 1);
        assertThat(paymentRepository.findByOrderId(order.getId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    @DisplayName("Webhook delivered gửi lặp ⇒ 2xx nhưng no-op, không ghi lịch sử lần hai")
    void duplicateDelivered_isIdempotent() throws Exception {
        Order order = createOrder(OrderStatus.SHIPPING, PaymentStatus.PENDING);
        String body = deliveredJson(order.getGhnOrderCode());

        mockMvc.perform(webhook(body).header(HEADER, SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true));

        mockMvc.perform(webhook(body).header(HEADER, SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.message", containsString("trùng")));

        assertThat(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId())).hasSize(1);
    }

    @Test
    @DisplayName("Sự kiện cũ (delivered tới sau khi đơn đã huỷ) ⇒ 2xx no-op, không lùi trạng thái")
    void staleDelivered_afterCancelled_isIgnored() throws Exception {
        Order order = createOrder(OrderStatus.CANCELLED, PaymentStatus.PENDING);

        mockMvc.perform(webhook(deliveredJson(order.getGhnOrderCode()))
                        .header(HEADER, SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false));

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getOrderStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId())).isEmpty();
    }

    @Test
    @DisplayName("Trạng thái cảnh báo lost ⇒ 2xx no-op, đơn vẫn SHIPPING, chỉ cảnh báo")
    void warningStatus_doesNotCancelOrder() throws Exception {
        Order order = createOrder(OrderStatus.SHIPPING, PaymentStatus.PENDING);

        mockMvc.perform(webhook(statusJson(order.getGhnOrderCode(), "lost"))
                        .header(HEADER, SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.message", containsString("kiểm tra thủ công")));

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getOrderStatus())
                .isEqualTo(OrderStatus.SHIPPING);
        assertThat(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId())).isEmpty();
    }

    @Test
    @DisplayName("Không tìm thấy đơn theo mã GHN ⇒ 404 để GHN retry")
    void unknownOrder_isNotFound() throws Exception {
        mockMvc.perform(webhook(statusJson("GHN-KHONG-TON-TAI", "delivered"))
                        .header(HEADER, SECRET))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("Chỉ mở đúng POST webhook; API GHN khác và trang admin vẫn yêu cầu đăng nhập")
    void onlyExactWebhookPostIsPublic() throws Exception {
        mockMvc.perform(get(WEBHOOK_PATH))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/api/ghn/tinh"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/admin/don-hang"))
                .andExpect(status().is3xxRedirection());
    }

    private MockHttpServletRequestBuilder webhook(String body) {
        return post(WEBHOOK_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String deliveredJson(String orderCode) {
        return deliveredJson(orderCode, SHOP_ID);
    }

    private String deliveredJson(String orderCode, long shopId) {
        return """
                {"OrderCode": "%s", "ClientOrderCode": null, "Status": "delivered",
                 "ShopID": %d, "Time": "2026-11-07T00:00:00Z", "Type": "update"}
                """.formatted(orderCode, shopId);
    }

    private String statusJson(String orderCode, String status) {
        return """
                {"OrderCode": "%s", "ClientOrderCode": null, "Status": "%s",
                 "ShopID": %d, "Time": "2026-11-07T00:00:00Z", "Type": "update"}
                """.formatted(orderCode, status, SHOP_ID);
    }

    /**
     * Tạo đơn trực tiếp trong DB với đúng ràng buộc CK_orders_stock/CK_orders_total.
     * Không dùng API thanh toán để test webhook độc lập với luồng đặt hàng.
     */
    private Order createOrder(OrderStatus status, PaymentStatus paymentStatus) {
        String suffix = String.valueOf(System.nanoTime());
        Order order = new Order();
        order.setOrderCode("WH" + suffix.substring(suffix.length() - 10) + (++orderSequence));
        order.setUser(customer);
        order.setReceiverName("Khách webhook GHN");
        order.setReceiverPhone("0901234567");
        order.setShippingAddress("12 Hàng Bài, Hoàn Kiếm, Hà Nội");
        order.setSubtotal(PRICE);
        order.setShippingFee(SHIPPING_FEE);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setTotalAmount(PRICE.add(SHIPPING_FEE));
        order.setOrderStatus(status);
        order.setStockDeducted(status.isStockDeducted());
        order.setGhnOrderCode("GHNWEB" + suffix.substring(suffix.length() - 12) + (++orderSequence));

        ProductVariant currentVariant = variant;
        OrderItem item = OrderItem.from(currentVariant, 1);
        order.addItem(item);

        Order saved = orderRepository.save(order);

        Payment payment = new Payment();
        payment.setOrder(saved);
        payment.setPaymentMethod(PaymentMethod.COD);
        payment.setPaymentStatus(paymentStatus);
        payment.setAmount(saved.getTotalAmount());
        if (paymentStatus == PaymentStatus.PAID) {
            // CK_payments_paid: PAID bắt buộc có paid_at
            payment.setPaidAt(LocalDateTime.now());
        }
        paymentRepository.save(payment);

        createdOrderIds.add(saved.getId());
        return saved;
    }
}
