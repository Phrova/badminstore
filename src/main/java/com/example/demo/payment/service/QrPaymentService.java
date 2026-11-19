package com.example.demo.payment.service;

import com.example.demo.order.dto.OrderDetailView;
import com.example.demo.payment.dto.PaymentQrView;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * Dựng mã QR chuyển khoản VietQR cho đơn hàng (online) và hoá đơn quầy (POS).
 *
 * – Ảnh QR lấy từ dịch vụ VietQR (URL chứa mã ngân hàng, số tài khoản, số tiền, nội dung);
 * – số tiền = tổng tiền của đơn do server tính; nội dung chuyển khoản = mã đơn để admin đối chiếu;
 * – chưa cấu hình / cấu hình sai ⇒ trả rỗng, giao diện tự ẩn khối QR chứ không báo lỗi.
 * Không ghi gì vào CSDL: xác nhận đã nhận tiền vẫn do admin bấm (OrderService#confirmPayment).
 */
public interface QrPaymentService {

    boolean isAvailable();

    Optional<PaymentQrView> build(String orderCode, BigDecimal amount);

    Optional<PaymentQrView> forOrder(OrderDetailView detail);
}
