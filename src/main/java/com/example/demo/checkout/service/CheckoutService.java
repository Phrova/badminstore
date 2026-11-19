package com.example.demo.checkout.service;

import com.example.demo.checkout.dto.CheckoutForm;
import com.example.demo.checkout.dto.CheckoutSummary;
import com.example.demo.order.entity.Order;
import com.example.demo.user.entity.UserAddress;
import com.example.demo.voucher.entity.Voucher;
import java.math.BigDecimal;
import java.util.Optional;

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
public interface CheckoutService {

    public static final BigDecimal SHIPPING_FEE = new BigDecimal("30000");

    public static final BigDecimal FREE_SHIPPING_THRESHOLD = new BigDecimal("5000000");

    CheckoutSummary preview(Integer userId, String rawVoucherCode);

    CheckoutSummary preview(Integer userId, String rawVoucherCode, Integer addressId);

    Order placeOrder(Integer userId, CheckoutForm form);

    Optional<UserAddress> defaultAddress(Integer userId);

    public static BigDecimal shippingFeeFor(BigDecimal subtotal) {
        return subtotal.compareTo(FREE_SHIPPING_THRESHOLD) >= 0 ? BigDecimal.ZERO : SHIPPING_FEE;
    }
}
