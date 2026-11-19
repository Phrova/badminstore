package com.example.demo.shipping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.payment.enums.PaymentMethod;
import com.example.demo.payment.enums.PaymentStatus;
import com.example.demo.common.exception.BusinessException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Kiểm thử quy tắc COD đã được duyệt:
 * shop trả ship mặc định ⇒ chỉ thu tiền hàng; COD toàn phần; chặn trả một phần; trần 50 triệu.
 */
class GhnCodCalculatorTest {

    private static final BigDecimal SUBTOTAL = new BigDecimal("1490000");
    private static final BigDecimal DISCOUNT = BigDecimal.ZERO;
    private static final BigDecimal SHIPPING = new BigDecimal("30000");

    @Test
    @DisplayName("Đã trả đủ online ⇒ không thu COD, shop trả phí ship")
    void prepaid_hasNoCod() {
        GhnCodCalculator.CodDecision decision = GhnCodCalculator.decide(SUBTOTAL, DISCOUNT, SHIPPING,
                PaymentMethod.BANK_TRANSFER, PaymentStatus.PAID, new BigDecimal("1520000"), false);

        assertThat(decision.paymentTypeId()).isEqualTo(GhnCodCalculator.PAYMENT_TYPE_SHOP_PAYS);
        assertThat(decision.codAmount()).isZero();
        assertThat(decision.goodsAmount()).isEqualTo(1490000L);
        assertThat(decision.totalAmount()).isEqualTo(1520000L);
    }

    @Test
    @DisplayName("COD toàn phần, shop trả ship ⇒ COD chỉ thu tiền hàng (không thu 30.000 phí ship)")
    void codShopPaysShipping_collectsGoodsOnly() {
        GhnCodCalculator.CodDecision decision = GhnCodCalculator.decide(SUBTOTAL, DISCOUNT, SHIPPING,
                PaymentMethod.COD, PaymentStatus.PENDING, new BigDecimal("1520000"), false);

        assertThat(decision.paymentTypeId()).isEqualTo(GhnCodCalculator.PAYMENT_TYPE_SHOP_PAYS);
        assertThat(decision.codAmount()).isEqualTo(1490000L);
        assertThat(decision.remainingAmount()).isEqualTo(1490000L);
        assertThat(decision.collectOnDelivery()).isTrue();
    }

    @Test
    @DisplayName("COD toàn phần, khách trả ship ⇒ COD thu cả tiền hàng + phí ship")
    void codBuyerPaysShipping_collectsTotal() {
        GhnCodCalculator.CodDecision decision = GhnCodCalculator.decide(SUBTOTAL, DISCOUNT, SHIPPING,
                PaymentMethod.COD, PaymentStatus.PENDING, new BigDecimal("1520000"), true);

        assertThat(decision.paymentTypeId()).isEqualTo(GhnCodCalculator.PAYMENT_TYPE_BUYER_PAYS);
        assertThat(decision.codAmount()).isEqualTo(1520000L);
    }

    @Test
    @DisplayName("Miễn phí ship ⇒ COD chỉ còn tiền hàng")
    void freeShipping_collectsGoodsOnly() {
        GhnCodCalculator.CodDecision decision = GhnCodCalculator.decide(SUBTOTAL, DISCOUNT, BigDecimal.ZERO,
                PaymentMethod.COD, PaymentStatus.PENDING, SUBTOTAL, false);

        assertThat(decision.codAmount()).isEqualTo(1490000L);
        assertThat(decision.totalAmount()).isEqualTo(1490000L);
    }

    @Test
    @DisplayName("COD vượt 50.000.000 ₫ ⇒ chặn, yêu cầu tách đơn/trả trước")
    void codOverLimit_isRejected() {
        assertThatThrownBy(() -> GhnCodCalculator.decide(new BigDecimal("60000000"), BigDecimal.ZERO,
                SHIPPING, PaymentMethod.COD, PaymentStatus.PENDING, new BigDecimal("60030000"), false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("vượt hạn mức");
    }

    @Test
    @DisplayName("Đã ghi nhận PAID nhưng thiếu tiền ⇒ chặn (không hỗ trợ thanh toán một phần)")
    void partialPaid_isRejected() {
        assertThatThrownBy(() -> GhnCodCalculator.decide(SUBTOTAL, DISCOUNT, SHIPPING,
                PaymentMethod.COD, PaymentStatus.PAID, new BigDecimal("500000"), false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("nhỏ hơn tổng");
    }

    @Test
    @DisplayName("PENDING nghĩa là chưa thu đồng nào – payment.amount chỉ là số phải thu, không phải đã trả")
    void pendingIgnoresPaymentAmount() {
        GhnCodCalculator.CodDecision decision = GhnCodCalculator.decide(SUBTOTAL, DISCOUNT, SHIPPING,
                PaymentMethod.COD, PaymentStatus.PENDING, new BigDecimal("1520000"), false);

        assertThat(decision.codAmount()).isEqualTo(1490000L);
        assertThat(decision.paidAmount()).isZero();
    }

    @Test
    @DisplayName("Đơn không có payment ⇒ không thu COD, shop trả ship")
    void noPayment_hasNoCod() {
        GhnCodCalculator.CodDecision decision = GhnCodCalculator.decide(SUBTOTAL, DISCOUNT, SHIPPING,
                null, null, null, false);

        assertThat(decision.paymentTypeId()).isEqualTo(GhnCodCalculator.PAYMENT_TYPE_SHOP_PAYS);
        assertThat(decision.codAmount()).isZero();
    }
}
