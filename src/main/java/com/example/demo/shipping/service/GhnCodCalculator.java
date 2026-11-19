package com.example.demo.shipping.service;

import com.example.demo.payment.enums.PaymentMethod;
import com.example.demo.payment.enums.PaymentStatus;
import com.example.demo.common.exception.BusinessException;
import java.math.BigDecimal;

/**
 * Tính tiền thu hộ (COD) và loại phí vận chuyển gửi sang GHN.
 *
 * Quy tắc đã được duyệt:
 *  – Chỉ hỗ trợ COD toàn phần hoặc đã trả đủ online; thanh toán một phần bị chặn;
 *  – Mặc định shop trả phí vận chuyển (payment_type_id = 1), COD chỉ thu tiền hàng;
 *  – Nếu cấu hình buộc người nhận trả phí ship (payment_type_id = 2) thì COD thu cả phí ship;
 *  – Tối đa 50.000.000 ₫ tiền thu hộ.
 *
 * Không phụ thuộc entity/DB để test được mọi tổ hợp.
 */
public final class GhnCodCalculator {

    public static final int PAYMENT_TYPE_SHOP_PAYS = 1;
    public static final int PAYMENT_TYPE_BUYER_PAYS = 2;
    public static final long MAX_COD_AMOUNT = 50_000_000L;

    private GhnCodCalculator() {
    }

    /**
     * @param subtotal          tiền hàng trước giảm giá
     * @param discountAmount    tiền voucher
     * @param shippingFee       phí vận chuyển của đơn
     * @param method            phương thức thanh toán (COD/chuyển khoản/tiền mặt)
     * @param status            trạng thái thanh toán
     * @param paidAmount        số tiền của payment; CHỈ dùng khi status = PAID
     *                          (PENDING nghĩa là chưa thu đồng nào)
     * @param buyerPaysShipping true nếu cấu hình GHN cho người nhận trả phí ship
     */
    public static CodDecision decide(BigDecimal subtotal, BigDecimal discountAmount, BigDecimal shippingFee,
                                     PaymentMethod method, PaymentStatus status, BigDecimal paidAmount,
                                     boolean buyerPaysShipping) {
        BigDecimal goods = nonNegative(nz(subtotal).subtract(nz(discountAmount)));
        BigDecimal shipping = nonNegative(nz(shippingFee));
        BigDecimal total = goods.add(shipping);

        BigDecimal paid = status == PaymentStatus.PAID
                ? (paidAmount == null ? total : nonNegative(paidAmount))
                : BigDecimal.ZERO;

        if (status == PaymentStatus.PAID && paid.compareTo(total) < 0) {
            throw new BusinessException("Đơn đã ghi nhận thanh toán nhưng số tiền nhỏ hơn tổng đơn ("
                    + format(paid) + " / " + format(total)
                    + "). Không thể tạo vận đơn khi thanh toán chưa đủ.");
        }

        boolean collectCod = method == PaymentMethod.COD && status != PaymentStatus.PAID;
        if (!collectCod) {
            return new CodDecision(PAYMENT_TYPE_SHOP_PAYS, 0L, goods.longValue(), total.longValue(),
                    paid.longValue(), total.subtract(paid).longValue());
        }

        BigDecimal codAmount = buyerPaysShipping ? total : goods;
        if (codAmount.compareTo(BigDecimal.valueOf(MAX_COD_AMOUNT)) > 0) {
            throw new BusinessException("Tiền thu hộ " + format(codAmount) + " vượt hạn mức "
                    + format(BigDecimal.valueOf(MAX_COD_AMOUNT))
                    + " ₫ của GHN. Vui lòng tách đơn hoặc yêu cầu khách trả trước.");
        }

        int paymentType = buyerPaysShipping ? PAYMENT_TYPE_BUYER_PAYS : PAYMENT_TYPE_SHOP_PAYS;
        return new CodDecision(paymentType, codAmount.longValue(), goods.longValue(), total.longValue(),
                paid.longValue(), codAmount.longValue());
    }

    /** Kết quả tính COD để dựng request GHN. */
    public record CodDecision(int paymentTypeId,
                              long codAmount,
                              long goodsAmount,
                              long totalAmount,
                              long paidAmount,
                              long remainingAmount) {

        public boolean collectOnDelivery() {
            return codAmount > 0;
        }
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private static String format(BigDecimal amount) {
        return String.format("%,.0f", amount).replace(',', '.') + " ₫";
    }
}
