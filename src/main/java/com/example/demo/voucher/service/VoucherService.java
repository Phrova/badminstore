package com.example.demo.voucher.service;

import com.example.demo.voucher.dto.VoucherForm;
import com.example.demo.voucher.entity.Voucher;
import com.example.demo.voucher.enums.VoucherStatus;
import java.math.BigDecimal;
import org.springframework.data.domain.Page;

/**
 * Nghiệp vụ voucher giảm giá cho TỔNG hoá đơn.
 *
 * Quy tắc (theo AGENTS.md mục 4.7): kiểm tra mã tồn tại, đang ACTIVE, trong thời gian áp dụng,
 * còn lượt và đơn đạt giá trị tối thiểu; số tiền giảm tính ở server và không vượt quá tạm tính;
 * khi dùng thật thì tăng used_quantity bằng UPDATE atomic để hai khách không dùng quá số lượt.
 */
public interface VoucherService {

    public static final int PAGE_SIZE = 10;

    String normalizeCode(String rawCode);

    Voucher requireUsable(String rawCode, BigDecimal subtotal);

    BigDecimal discountOf(Voucher voucher, BigDecimal subtotal);

    String describe(Voucher voucher);

    void consume(Voucher voucher);

    void release(Integer voucherId);

    Page<Voucher> search(String keyword, VoucherStatus status, int pageNumber);

    Voucher requireVoucher(Integer id);

    Voucher create(VoucherForm form);

    Voucher update(Integer id, VoucherForm form);

    Voucher toggleStatus(Integer id);
}
