package com.example.demo.checkout.service.impl;

import com.example.demo.checkout.dto.AppliedVoucher;
import com.example.demo.cart.dto.CartView;
import com.example.demo.checkout.dto.CheckoutForm;
import com.example.demo.checkout.dto.CheckoutSummary;
import com.example.demo.cart.entity.CartItem;
import com.example.demo.order.entity.Order;
import com.example.demo.order.entity.OrderItem;
import com.example.demo.order.entity.OrderStatusHistory;
import com.example.demo.payment.entity.Payment;
import com.example.demo.catalog.entity.Product;
import com.example.demo.catalog.entity.ProductVariant;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserAddress;
import com.example.demo.voucher.entity.Voucher;
import com.example.demo.order.enums.OrderSource;
import com.example.demo.order.enums.OrderStatus;
import com.example.demo.payment.enums.PaymentMethod;
import com.example.demo.payment.enums.PaymentStatus;
import com.example.demo.catalog.enums.ProductStatus;
import com.example.demo.catalog.enums.VariantStatus;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.cart.repository.CartItemRepository;
import com.example.demo.order.repository.OrderRepository;
import com.example.demo.order.repository.OrderStatusHistoryRepository;
import com.example.demo.payment.repository.PaymentRepository;
import com.example.demo.user.repository.UserRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.cart.service.CartService;
import com.example.demo.voucher.service.VoucherService;
import com.example.demo.user.service.AddressService;
import com.example.demo.order.service.OrderCodeGenerator;
import com.example.demo.shipping.service.GhnAddressService;
import com.example.demo.checkout.service.CheckoutService;

/**
 * Bước đặt hàng online: xem trước đơn và tạo đơn thật.
 *
 * Nguyên tắc (AGENTS.md mục 4.5):
 * – tất cả tiền (đơn giá, tạm tính, phí ship, giảm giá, tổng) đều tính lại từ DB, KHÔNG nhận từ form;
 * – kiểm tra biến thể + sản phẩm còn bán và còn đủ tồn kho ngay lúc đặt;
 * – trong 1 transaction: orders + order_items (snapshot) + payments + order_status_histories
 *   + tăng lượt voucher (atomic) + xoá cart_items;
 * – đơn mới ở PENDING, stock_deducted = 0 (kho chỉ bị trừ khi admin xác nhận đơn).
 */
@Service
@RequiredArgsConstructor
public class CheckoutServiceImpl implements CheckoutService {

    private final CartService cartService;
    private final CartItemRepository cartItemRepository;
    private final VoucherService voucherService;
    private final OrderCodeGenerator orderCodeGenerator;
    private final AddressService addressService;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final UserRepository userRepository;
    private final GhnAddressService ghnAddressService;

    /**
     * Xem trước đơn hàng cho trang thanh toán. Mã giảm giá sai KHÔNG ném lỗi mà
     * trả về {@code voucherError} để hiển thị ngay trên trang (URL do khách sửa được).
     */
    @Override
    @Transactional(readOnly = true)
    public CheckoutSummary preview(Integer userId, String rawVoucherCode) {
        return preview(userId, rawVoucherCode, null);
    }

    /**
     * Xem trước đơn theo ĐÚNG địa chỉ khách đang chọn: phí giao hàng lấy từ GHN ngay khi render
     * (không chờ JavaScript), nhờ vậy con số khách thấy khớp với phí server sẽ lưu lúc đặt hàng.
     * Địa chỉ không thuộc khách/đã xoá ⇒ dùng phí mặc định của shop như trước.
     */
    @Override
    @Transactional(readOnly = true)
    public CheckoutSummary preview(Integer userId, String rawVoucherCode, Integer addressId) {
        CartView cart = cartService.getCart(userId);
        String code = voucherService.normalizeCode(rawVoucherCode);

        AppliedVoucher applied = null;
        String voucherError = null;
        if (code != null && !cart.isEmpty()) {
            try {
                Voucher voucher = voucherService.requireUsable(code, cart.getSubtotal());
                applied = new AppliedVoucher(voucher.getCode(), voucher.getName(), voucherService.describe(voucher),
                        voucherService.discountOf(voucher, cart.getSubtotal()));
            } catch (BusinessException ex) {
                voucherError = ex.getMessage();
            }
        }

        UserAddress address = addressService.findOwned(userId, addressId).orElse(null);
        ShippingQuote quote = shippingQuote(cart.getSubtotal(), address, cart.getTotalQuantity());
        return new CheckoutSummary(cart, quote.fee(), FREE_SHIPPING_THRESHOLD, applied, voucherError, quote.fromGhn());
    }

    /** Tạo đơn hàng online: kiểm tra giỏ + địa chỉ + tồn kho, lưu đơn và xoá giỏ trong 1 transaction. */
    @Override
    @Transactional
    public Order placeOrder(Integer userId, CheckoutForm form) {
        List<CartItem> cartItems = cartItemRepository.findCartByUserId(userId);
        if (cartItems.isEmpty()) {
            throw new BusinessException("Giỏ hàng của bạn đang trống nên chưa thể đặt hàng.");
        }

        UserAddress address = addressService.requireOwned(userId, form.getAddressId());
        PaymentMethod paymentMethod = parsePaymentMethod(form.getPaymentMethod());

        // Kiểm tra TẤT CẢ dòng hàng trước khi ghi bất cứ thứ gì: sản phẩm còn bán + còn đủ tồn kho.
        List<OrderItem> items = new ArrayList<>();
        for (CartItem cartItem : cartItems) {
            ProductVariant variant = cartItem.getProductVariant();
            requireSellable(variant, cartItem.getQuantity());
            items.add(OrderItem.from(variant, cartItem.getQuantity()));
        }

        User user = userRepository.getReferenceById(userId);
        Order order = new Order();
        order.setOrderCode(orderCodeGenerator.next());
        order.setOrderSource(OrderSource.ONLINE);
        order.setUser(user);
        order.setAddress(address);
        order.setReceiverName(address.getReceiverName());
        order.setReceiverPhone(address.getReceiverPhone());
        order.setShippingAddress(address.getFullAddress());
        order.setShippingProvinceId(address.getProvinceId());
        order.setShippingDistrictId(address.getDistrictId());
        order.setShippingWardCode(address.getWardCode());
        order.setOrderStatus(OrderStatus.PENDING);
        order.setStockDeducted(false);
        order.setNote(trimToNull(form.getNote()));
        items.forEach(order::addItem);

        BigDecimal subtotal = order.getItems().stream()
                .map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int totalQuantity = order.getItems().stream().mapToInt(OrderItem::getQuantity).sum();
        order.setSubtotal(subtotal);
        order.setShippingFee(shippingQuote(subtotal, address, totalQuantity).fee());

        BigDecimal discount = BigDecimal.ZERO;
        Voucher voucher = null;
        String code = voucherService.normalizeCode(form.getVoucherCode());
        if (code != null) {
            voucher = voucherService.requireUsable(code, subtotal);
            discount = voucherService.discountOf(voucher, subtotal);
        }
        order.setDiscountAmount(discount);
        order.setVoucher(voucher);
        order.setTotalAmount(subtotal.add(order.getShippingFee()).subtract(discount));

        Order saved = orderRepository.save(order);
        if (voucher != null) {
            voucherService.consume(voucher);
        }

        Payment payment = new Payment();
        payment.setOrder(saved);
        payment.setPaymentMethod(paymentMethod);
        payment.setPaymentStatus(PaymentStatus.PENDING);
        payment.setAmount(saved.getTotalAmount());
        paymentRepository.save(payment);

        orderStatusHistoryRepository.save(OrderStatusHistory.record(saved, null, OrderStatus.PENDING, user,
                "Khách đặt hàng trên website"));

        cartItemRepository.deleteAll(cartItems);
        return saved;
    }

    /**
     * Phí vận chuyển THẬT của một địa chỉ cụ thể:
     * – đơn đủ ngưỡng miễn phí ⇒ 0 ₫;
     * – địa chỉ có mã quận của GHN và GHN đang bật ⇒ phí theo API GHN (dữ liệu đã cache nên gọi lại rất nhẹ);
     * – còn lại (GHN tắt, địa chỉ nhập tay, GHN lỗi) ⇒ phí cố định của shop.
     */
    private ShippingQuote shippingQuote(BigDecimal subtotal, UserAddress address, int totalQuantity) {
        BigDecimal flatFee = CheckoutService.shippingFeeFor(subtotal);
        if (flatFee.signum() == 0 || address == null) {
            return new ShippingQuote(flatFee, false);
        }
        int weightGram = ghnAddressService.shippingWeight(totalQuantity);
        BigDecimal ghnFee = address.getDistrictId() != null
                ? ghnAddressService.feeOrNull(address.getDistrictId(), address.getWardCode(), weightGram)
                : ghnAddressService.feeOrNull(address, weightGram);
        return ghnFee == null ? new ShippingQuote(flatFee, false) : new ShippingQuote(ghnFee, true);
    }

    /** Phí ship đã chọn + cờ cho biết phí đó có đến từ GHN hay không (để giao diện gắn nhãn). */
    private record ShippingQuote(BigDecimal fee, boolean fromGhn) {
    }

    /** Đơn online chỉ dùng COD hoặc chuyển khoản (CASH dành cho đơn tạo tại quầy). */
    private PaymentMethod parsePaymentMethod(String rawMethod) {
        PaymentMethod method;
        try {
            method = PaymentMethod.valueOf(rawMethod == null ? "" : rawMethod.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Phương thức thanh toán không hợp lệ. Bạn chọn lại giúp mình nhé.");
        }
        if (method == PaymentMethod.CASH) {
            throw new BusinessException("Đơn đặt online chỉ hỗ trợ COD hoặc chuyển khoản ngân hàng.");
        }
        return method;
    }

    /** Kiểm tra lại lần cuối trước khi ghi đơn: sản phẩm còn bán và còn đủ tồn kho. */
    private void requireSellable(ProductVariant variant, int quantity) {
        Product product = variant.getProduct();
        if (product.getStatus() != ProductStatus.ACTIVE || product.isDeleted()) {
            throw new BusinessException("Sản phẩm \"" + product.getName()
                    + "\" đã ngừng bán. Bạn xoá sản phẩm này khỏi giỏ rồi đặt lại nhé.");
        }
        if (variant.getStatus() != VariantStatus.ACTIVE) {
            throw new BusinessException("Phiên bản " + variant.getColor() + " " + variant.getWeightClass() + " "
                    + variant.getGripSize() + " đã ngừng bán. Bạn xoá khỏi giỏ rồi đặt lại nhé.");
        }
        if (quantity < 1 || quantity > variant.getStockQuantity()) {
            throw new BusinessException("Không đủ tồn kho cho SKU " + variant.getSku() + ": bạn đặt " + quantity
                    + " nhưng hiện chỉ còn " + variant.getStockQuantity()
                    + " sản phẩm. Vui lòng cập nhật lại giỏ hàng.");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Tiện ích cho trang thanh toán: địa chỉ mặc định (nếu có) để tích sẵn. */
    @Override
    @Transactional(readOnly = true)
    public Optional<UserAddress> defaultAddress(Integer userId) {
        return addressService.defaultAddress(userId);
    }
}
