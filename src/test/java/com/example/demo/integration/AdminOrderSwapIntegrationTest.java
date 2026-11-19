package com.example.demo.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.checkout.dto.CheckoutForm;
import com.example.demo.order.entity.Order;
import com.example.demo.payment.entity.Payment;
import com.example.demo.catalog.entity.Product;
import com.example.demo.catalog.entity.ProductVariant;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserAddress;
import com.example.demo.order.enums.OrderStatus;
import com.example.demo.payment.enums.PaymentStatus;
import com.example.demo.catalog.enums.ProductStatus;
import com.example.demo.user.enums.UserRole;
import com.example.demo.user.enums.UserStatus;
import com.example.demo.catalog.enums.VariantStatus;
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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Test tích hợp luồng admin đổi sản phẩm hết hàng sang biến thể khác cho đơn PENDING:
 * cập nhật snapshot/tổng tiền/payment, giữ nguyên kho đến lúc xác nhận, và các chốt chặn
 * (đơn đã xác nhận, đã thu tiền, biến thể thay thế thiếu hàng).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminOrderSwapIntegrationTest {

    private static final String PASSWORD = "Secret123";

    @Autowired
    private MockMvc mockMvc;

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

    @Autowired
    private CartService cartService;

    @Autowired
    private CheckoutService checkoutService;

    private String suffix;
    private User admin;
    private User customer;
    private Product currentProduct;
    private Product replacementProduct;
    private ProductVariant currentVariant;
    private ProductVariant replacementVariant;
    private UserAddress address;

    private final List<Integer> createdOrderIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        suffix = String.valueOf(System.nanoTime());

        admin = userRepository.save(account("admin.doi." + suffix + "@example.com", UserRole.ADMIN));
        customer = userRepository.save(account("khach.doi." + suffix + "@example.com", UserRole.CUSTOMER));

        currentProduct = productRepository.save(product("SWP-A-" + tail(), "Vợt cầu lông tạm hết hàng"));
        currentVariant = productVariantRepository.save(variant(currentProduct, "SWP-A-SKU-" + tail(),
                "1490000", 10));

        replacementProduct = productRepository.save(product("SWP-B-" + tail(), "Vợt cầu lông thay thế"));
        replacementVariant = productVariantRepository.save(variant(replacementProduct, "SWP-B-SKU-" + tail(),
                "1290000", 10));

        address = new UserAddress();
        address.setUser(customer);
        address.setReceiverName("Nguyễn Văn Đổi");
        address.setReceiverPhone("0903334444");
        address.setProvinceName("Hà Nội");
        address.setDistrictName("Quận Cầu Giấy");
        address.setWardName("Phường Dịch Vọng");
        address.setAddressDetail("45 Xuân Thủy");
        address.setDefaultAddress(true);
        address = addressRepository.save(address);
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
        cartItemRepository.deleteAll(cartItemRepository.findByUserIdOrderByCreatedAtDesc(customer.getId()));
        addressRepository.delete(address);
        productVariantRepository.delete(currentVariant);
        productVariantRepository.delete(replacementVariant);
        productRepository.delete(currentProduct);
        productRepository.delete(replacementProduct);
        userRepository.delete(customer);
        userRepository.delete(admin);
    }

    @Test
    @DisplayName("Đổi sản phẩm hết hàng: cập nhật dòng hàng/tổng tiền, xác nhận chỉ trừ kho biến thể mới")
    void swapItem_thenConfirmDeductsNewVariant() throws Exception {
        Order order = placeOrder(2);
        Integer itemId = firstItemId(order.getId());
        setStock(currentVariant.getId(), 0);   // mô phỏng sản phẩm đã bán hết sau khi khách đặt

        MockHttpSession adminSession = loginSession(admin);
        String swapUrl = "/admin/don-hang/" + order.getId() + "/doi-san-pham/" + itemId;

        // Chi tiết đơn phải nổi bật tình trạng thiếu hàng và có nút Đổi
        mockMvc.perform(get("/admin/don-hang/" + order.getId()).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Thiếu hàng")))
                .andExpect(content().string(containsString("không đủ tồn kho")))
                .andExpect(content().string(containsString("Đổi")));

        mockMvc.perform(get(swapUrl).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Đổi sản phẩm trong đơn")))
                .andExpect(content().string(containsString("Thiếu hàng")))
                .andExpect(content().string(containsString(replacementProduct.getName())))
                .andExpect(content().string(containsString(replacementVariant.getSku())));

        mockMvc.perform(post(swapUrl).with(csrf()).session(adminSession)
                        .param("variantId", String.valueOf(replacementVariant.getId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/don-hang/" + order.getId()))
                .andExpect(flash().attribute("successMessage", containsString("Đã đổi sản phẩm")));

        Order swapped = orderRepository.findById(order.getId()).orElseThrow();
        Order detailed = orderRepository.findDetailForAdmin(order.getId()).orElseThrow();
        assertThat(detailed.getItems()).hasSize(1);
        assertThat(detailed.getItems().get(0).getProductVariant().getId())
                .isEqualTo(replacementVariant.getId());
        assertThat(detailed.getItems().get(0).getSku()).isEqualTo(replacementVariant.getSku());
        assertThat(detailed.getItems().get(0).getSubtotal()).isEqualByComparingTo("2580000");
        assertThat(swapped.getSubtotal()).isEqualByComparingTo("2580000");
        assertThat(swapped.getShippingFee()).isEqualByComparingTo("30000");
        assertThat(swapped.getTotalAmount()).isEqualByComparingTo("2610000");
        assertThat(swapped.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(swapped.isStockDeducted()).isFalse();

        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(payment.getAmount()).isEqualByComparingTo("2610000");
        assertThat(currentStock()).isZero();
        assertThat(replacementStock()).isEqualTo(10);

        // Xác nhận đơn: chỉ biến thể MỚI bị trừ kho
        mockMvc.perform(post("/admin/don-hang/" + order.getId() + "/xac-nhan").with(csrf()).session(adminSession))
                .andExpect(status().is3xxRedirection());

        assertThat(currentStock()).isZero();
        assertThat(replacementStock()).isEqualTo(8);
        Order confirmed = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(confirmed.getOrderStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.isStockDeducted()).isTrue();
        assertThat(historyRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId())).hasSize(2);
    }

    @Test
    @DisplayName("Đơn đã xác nhận: chặn cả mở form lẫn gửi đổi sản phẩm")
    void swapRejectedWhenOrderConfirmed() throws Exception {
        Order order = placeOrder(1);
        Integer itemId = firstItemId(order.getId());
        MockHttpSession adminSession = loginSession(admin);

        mockMvc.perform(post("/admin/don-hang/" + order.getId() + "/xac-nhan").with(csrf()).session(adminSession))
                .andExpect(status().is3xxRedirection());

        String swapUrl = "/admin/don-hang/" + order.getId() + "/doi-san-pham/" + itemId;
        mockMvc.perform(get(swapUrl).session(adminSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/don-hang/" + order.getId()))
                .andExpect(flash().attribute("errorMessage", containsString("Chỉ đổi được sản phẩm")));

        mockMvc.perform(post(swapUrl).with(csrf()).session(adminSession)
                        .param("variantId", String.valueOf(replacementVariant.getId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(swapUrl))
                .andExpect(flash().attribute("errorMessage", containsString("Chỉ đổi được sản phẩm")));

        Order detailed = orderRepository.findDetailForAdmin(order.getId()).orElseThrow();
        assertThat(detailed.getItems().get(0).getProductVariant().getId()).isEqualTo(currentVariant.getId());
        assertThat(currentStock()).isEqualTo(9);
        assertThat(replacementStock()).isEqualTo(10);
    }

    @Test
    @DisplayName("Đơn đã ghi nhận thanh toán: chặn đổi sản phẩm")
    void swapRejectedWhenPaymentPaid() throws Exception {
        Order order = placeOrder(1);
        Integer itemId = firstItemId(order.getId());
        MockHttpSession adminSession = loginSession(admin);

        mockMvc.perform(post("/admin/don-hang/" + order.getId() + "/xac-nhan-thanh-toan")
                        .with(csrf()).session(adminSession)
                        .param("transactionCode", "FT-SWAP-01"))
                .andExpect(status().is3xxRedirection());

        String swapUrl = "/admin/don-hang/" + order.getId() + "/doi-san-pham/" + itemId;
        mockMvc.perform(post(swapUrl).with(csrf()).session(adminSession)
                        .param("variantId", String.valueOf(replacementVariant.getId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(swapUrl))
                .andExpect(flash().attribute("errorMessage", containsString("đã ghi nhận thanh toán")));

        Order detailed = orderRepository.findDetailForAdmin(order.getId()).orElseThrow();
        assertThat(detailed.getItems().get(0).getProductVariant().getId()).isEqualTo(currentVariant.getId());
        assertThat(paymentRepository.findByOrderId(order.getId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.PAID);
        assertThat(detailed.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("Biến thể thay thế thiếu tồn kho: báo rõ số còn lại, giữ nguyên đơn và tổng tiền")
    void swapRejectedWhenReplacementNotEnoughStock() throws Exception {
        Order order = placeOrder(2);
        Integer itemId = firstItemId(order.getId());
        setStock(replacementVariant.getId(), 1);

        MockHttpSession adminSession = loginSession(admin);
        String swapUrl = "/admin/don-hang/" + order.getId() + "/doi-san-pham/" + itemId;

        mockMvc.perform(get(swapUrl).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Không đủ số lượng")));

        mockMvc.perform(post(swapUrl).with(csrf()).session(adminSession)
                        .param("variantId", String.valueOf(replacementVariant.getId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(swapUrl))
                .andExpect(flash().attribute("errorMessage", containsString("chỉ còn 1 sản phẩm")));

        Order detailed = orderRepository.findDetailForAdmin(order.getId()).orElseThrow();
        assertThat(detailed.getItems().get(0).getProductVariant().getId()).isEqualTo(currentVariant.getId());
        assertThat(detailed.getSubtotal()).isEqualByComparingTo("2980000");
        assertThat(detailed.getTotalAmount()).isEqualByComparingTo("3010000");
        assertThat(paymentRepository.findByOrderId(order.getId()).orElseThrow().getAmount())
                .isEqualByComparingTo("3010000");
        assertThat(currentStock()).isEqualTo(10);
        assertThat(replacementStock()).isEqualTo(1);
    }

    private User account(String email, UserRole role) {
        User created = new User();
        created.setEmail(email);
        created.setPasswordHash(passwordEncoder.encode(PASSWORD));
        created.setFullName(role == UserRole.ADMIN ? "Quản trị viên test" : "Khách đổi sản phẩm");
        created.setRole(role);
        created.setStatus(UserStatus.ACTIVE);
        return created;
    }

    private Product product(String code, String name) {
        Product created = new Product();
        created.setProductCode(code);
        created.setName(name);
        created.setBrand(brandRepository.findByDeletedFalseOrderByNameAsc().get(0));
        created.setCategory(categoryRepository.findByDeletedFalseOrderByNameAsc().get(0));
        created.setStatus(ProductStatus.ACTIVE);
        return created;
    }

    private ProductVariant variant(Product product, String sku, String price, int stock) {
        ProductVariant created = new ProductVariant();
        created.setProduct(product);
        created.setSku(sku);
        created.setColor("Đỏ đen");
        created.setWeightClass("4U");
        created.setGripSize("G5");
        created.setPrice(new BigDecimal(price));
        created.setStockQuantity(stock);
        created.setStatus(VariantStatus.ACTIVE);
        return created;
    }

    private String tail() {
        return suffix.substring(suffix.length() - 8);
    }

    private MockHttpSession loginSession(User account) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/dang-nhap").with(csrf())
                        .param("email", account.getEmail())
                        .param("password", PASSWORD)
                        .session(session))
                .andExpect(status().is3xxRedirection());
        return session;
    }

    /** Đặt đơn COD đúng luồng khách dùng; checkout không trừ kho nên đơn còn PENDING. */
    private Order placeOrder(int quantity) {
        cartService.addToCart(customer.getId(), currentProduct.getId(), currentVariant.getId(), quantity);

        CheckoutForm form = new CheckoutForm();
        form.setAddressId(address.getId());
        form.setPaymentMethod("COD");
        form.setNote("Đơn dùng cho test đổi sản phẩm");

        Order order = checkoutService.placeOrder(customer.getId(), form);
        createdOrderIds.add(order.getId());
        return order;
    }

    private Integer firstItemId(Integer orderId) {
        return orderItemRepository.findByOrderIdOrderByProductVariantIdAsc(orderId).get(0).getId();
    }

    private void setStock(Integer variantId, int stock) {
        ProductVariant current = productVariantRepository.findById(variantId).orElseThrow();
        current.setStockQuantity(stock);
        productVariantRepository.saveAndFlush(current);
    }

    private int currentStock() {
        return productVariantRepository.findById(currentVariant.getId()).orElseThrow().getStockQuantity();
    }

    private int replacementStock() {
        return productVariantRepository.findById(replacementVariant.getId()).orElseThrow().getStockQuantity();
    }
}
