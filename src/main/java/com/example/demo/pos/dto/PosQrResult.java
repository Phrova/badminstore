package com.example.demo.pos.dto;

import com.example.demo.payment.dto.PaymentQrView;
import java.math.BigDecimal;

/**
 * Kết quả dựng QR cho quầy. {@code available=false} kèm {@code message} khi chưa dựng được
 * (hoá đơn trống/còn dòng lỗi, hoặc chưa cấu hình tài khoản ngân hàng).
 */
public record PosQrResult(boolean available, String message, String orderCode,
                          BigDecimal amount, PaymentQrView qr) {

    public static PosQrResult unavailable(String message) {
        return new PosQrResult(false, message, null, null, null);
    }
}
