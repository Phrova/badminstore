package com.example.demo.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.checkout.dto.CheckoutForm;
import com.example.demo.order.entity.Order;
import com.example.demo.catalog.entity.Product;
import com.example.demo.catalog.entity.ProductVariant;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserAddress;
import com.example.demo.order.enums.OrderStatus;
import com.example.demo.catalog.enums.ProductStatus;
import com.example.demo.user.enums.UserRole;
import com.example.demo.user.enums.UserStatus;
import com.example.demo.catalog.enums.VariantStatus;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.catalog.repository.BrandRepository;
import com.example.demo.cart.repository.CartItemRepository;
import com.example.demo.catalog.repository.CategoryRepository;
import com.example.demo.order.repository.OrderItemRepository;
import com.example.demo.order.repository.OrderRepository;
import com.example.demo.order.repository.OrderStatusHistoryRepository;
import com.example.demo.payment.repository.PaymentRepository;
import com.example.demo.catalog.repository.ProductRepository;
import com.example.demo.catalog.repository.ProductVariantRepository;
import com.example.demo.user.repository.UserAddressRepository;
import com.example.demo.user.repository.UserRepository;
import com.example.demo.cart.service.CartService;
import com.example.demo.checkout.service.CheckoutService;
import com.example.demo.order.service.OrderService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Kiểm thử TRANH CHẤP ĐỒNG THỜI trên CSDL thật (không mock khoá):
 *
 * 1) Hai khách cùng đặt món cuối (tồn kho = 1): cả hai tạo được đơn PENDING vì checkout
 *    không giữ chỗ, nhưng khi xác nhận song song chỉ MỘT đơn trừ được kho; đơn còn lại
 *    nhận BusinessException và vẫn PENDING.
 * 2) Hai admin cùng xác nhận MỘT đơn: khoá bi quan dòng đơn khiến transaction thứ hai
 *    chờ rồi đọc lại trạng thái CONFIRMED, nên kho chỉ bị trừ đúng một lần.
 */
@SpringBootTest
class OrderConcurrencyIntegrationTest {

    private static final String PASSWORD = "Secret123";

    @Autowired
    private OrderService orderService;

    @Autowired
    private CartService cartService;

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private UserAddressRepository addressRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderStatusHistoryRepository historyRepository;

    private String suffix;
    private User admin;
    private User customer1;
    private User customer2;
    private Product product;
    private ProductVariant variant;
    private UserAddress address1;
    private UserAddress address2;

    private final List<Integer> createdOrderIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        suffix = String.valueOf(System.nanoTime());

        admin = userRepository.save(account("admin.dongbo." + suffix + "@example.com", UserRole.ADMIN));
        customer1 = userRepository.save(account("khach1.dongbo." + suffix + "@example.com", UserRole.CUSTOMER));
        customer2 = userRepository.save(account("khach2.dongbo." + suffix + "@example.com", UserRole.CUSTOMER));

        product = new Product();
        product.setProductCode("CNC-" + suffix.substring(suffix.length() - 8));
        product.setName("Vợt cầu lông chạy thử đồng thời");
        product.setBrand(brandRepository.findByDeletedFalseOrderByNameAsc().get(0));
        product.setCategory(categoryRepository.findByDeletedFalseOrderByNameAsc().get(0));
        product.setStatus(ProductStatus.ACTIVE);
        product = productRepository.save(product);

        variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("CNC-SKU-" + suffix.substring(suffix.length() - 8));
        variant.setColor("Đen vàng");
        variant.setWeightClass("4U");
        variant.setGripSize("G5");
        variant.setPrice(new BigDecimal("1490000"));
        variant.setStockQuantity(1);
        variant.setStatus(VariantStatus.ACTIVE);
        variant = productVariantRepository.save(variant);

        address1 = addressRepository.save(address(customer1, "Khách Một", "0901111111"));
        address2 = addressRepository.save(address(customer2, "Khách Hai", "0902222222"));
    }

    @AfterEach
    void cleanUp() {
        for (Integer orderId : createdOrderIds) {
            if (orderRepository.existsById(orderId)) {
                historyRepository.deleteAll(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId));
                paymentRepository.findByOrderId(orderId).ifPresent(paymentRepository::delete);
                orderItemRepository.deleteAll(orderItemRepository.findByOrderIdOrderByProductVariantIdAsc(orderId));
                orderRepository.deleteById(orderId);
            }
        }
        cartItemRepository.deleteAll(cartItemRepository.findByUserIdOrderByCreatedAtDesc(customer1.getId()));
        cartItemRepository.deleteAll(cartItemRepository.findByUserIdOrderByCreatedAtDesc(customer2.getId()));
        addressRepository.delete(address1);
        addressRepository.delete(address2);
        productVariantRepository.delete(variant);
        productRepository.delete(product);
        userRepository.delete(customer1);
        userRepository.delete(customer2);
        userRepository.delete(admin);
    }

    private User account(String email, UserRole role) {
        User created = new User();
        created.setEmail(email);
        created.setPasswordHash(passwordEncoder.encode(PASSWORD));
        created.setFullName(role == UserRole.ADMIN ? "Quản trị viên test" : "Khách test");
        created.setRole(role);
        created.setStatus(UserStatus.ACTIVE);
        return created;
    }

    private UserAddress address(User owner, String receiverName, String phone) {
        UserAddress address = new UserAddress();
        address.setUser(owner);
        address.setReceiverName(receiverName);
        address.setReceiverPhone(phone);
        address.setProvinceName("Hà Nội");
        address.setDistrictName("Quận Cầu Giấy");
        address.setWardName("Phường Dịch Vọng");
        address.setAddressDetail("45 Xuân Thủy");
        address.setDefaultAddress(true);
        return address;
    }

    /** Đặt đơn online đúng luồng khách dùng: thêm giỏ → checkout COD. */
    private Order placeOnlineOrder(User customer, UserAddress address) {
        cartService.addToCart(customer.getId(), product.getId(), variant.getId(), 1);

        CheckoutForm form = new CheckoutForm();
        form.setAddressId(address.getId());
        form.setPaymentMethod("COD");
        form.setNote("Đơn dùng cho test đồng thời");

        Order order = checkoutService.placeOrder(customer.getId(), form);
        createdOrderIds.add(order.getId());
        return order;
    }

    private void setStock(int stock) {
        ProductVariant current = productVariantRepository.findById(variant.getId()).orElseThrow();
        current.setStockQuantity(stock);
        productVariantRepository.saveAndFlush(current);
    }

    private int currentStock() {
        return productVariantRepository.findById(variant.getId()).orElseThrow().getStockQuantity();
    }

    /**
     * Chạy các lần xác nhận trên nhiều thread, cùng nhả cờ một lúc để tạo tranh chấp thật.
     * Trả về kết quả từng lần để test kiểm tra đúng một thắng / một thua.
     */
    private List<Attempt> confirmConcurrently(List<Integer> orderIds) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(orderIds.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Attempt>> futures = new ArrayList<>();
            for (Integer orderId : orderIds) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        orderService.changeStatusByAdmin(orderId, OrderStatus.CONFIRMED, admin.getId(),
                                "Test xác nhận đồng thời");
                        return new Attempt(orderId, true, null);
                    } catch (Throwable ex) {
                        return new Attempt(orderId, false, ex);
                    }
                }));
            }

            start.countDown();

            List<Attempt> attempts = new ArrayList<>();
            for (Future<Attempt> future : futures) {
                attempts.add(future.get(15, TimeUnit.SECONDS));
            }
            return attempts;
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Hai khách tranh món cuối: chỉ một đơn xác nhận được, kho về 0, đơn còn lại vẫn PENDING")
    void twoCustomersCompeteForLastItem() throws Exception {
        Order first = placeOnlineOrder(customer1, address1);
        Order second = placeOnlineOrder(customer2, address2);

        // Checkout chỉ kiểm tra tồn kho, không giữ chỗ ⇒ cả hai đơn đều PENDING và kho vẫn là 1
        assertThat(first.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(second.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(currentStock()).isEqualTo(1);

        List<Attempt> attempts = confirmConcurrently(List.of(first.getId(), second.getId()));

        assertThat(attempts).filteredOn(Attempt::success).hasSize(1);
        Attempt failed = attempts.stream().filter(attempt -> !attempt.success()).findFirst().orElseThrow();
        assertThat(failed.error())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Không đủ tồn kho cho SKU " + variant.getSku());

        assertThat(currentStock()).isZero();

        Order reloadedFirst = orderRepository.findById(first.getId()).orElseThrow();
        Order reloadedSecond = orderRepository.findById(second.getId()).orElseThrow();
        Order confirmed = List.of(reloadedFirst, reloadedSecond).stream()
                .filter(order -> order.getOrderStatus() == OrderStatus.CONFIRMED)
                .findFirst().orElseThrow();
        Order pending = List.of(reloadedFirst, reloadedSecond).stream()
                .filter(order -> order.getOrderStatus() == OrderStatus.PENDING)
                .findFirst().orElseThrow();

        assertThat(confirmed.isStockDeducted()).isTrue();
        assertThat(pending.isStockDeducted()).isFalse();
        assertThat(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(confirmed.getId())).hasSize(2);
        assertThat(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(pending.getId())).hasSize(1);
    }

    @Test
    @DisplayName("Hai admin xác nhận cùng một đơn: khoá bi quan khiến kho chỉ bị trừ một lần")
    void twoAdminsConfirmSameOrder() throws Exception {
        setStock(2);
        Order order = placeOnlineOrder(customer1, address1);

        List<Attempt> attempts = confirmConcurrently(List.of(order.getId(), order.getId()));

        assertThat(attempts).filteredOn(Attempt::success).hasSize(1);
        Attempt failed = attempts.stream().filter(attempt -> !attempt.success()).findFirst().orElseThrow();
        assertThat(failed.error())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("đang ở trạng thái");

        // Nếu thiếu khoá: cả hai cùng đọc PENDING, cùng trừ 1 ⇒ kho còn 0 và có 2 dòng CONFIRMED.
        assertThat(currentStock()).isEqualTo(1);

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getOrderStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reloaded.isStockDeducted()).isTrue();
        assertThat(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId())).hasSize(2);
    }

    /** Kết quả một lần gọi xác nhận trong thread. */
    private record Attempt(Integer orderId, boolean success, Throwable error) {
    }
}
