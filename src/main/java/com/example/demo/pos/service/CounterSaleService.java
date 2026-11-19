package com.example.demo.pos.service;

import com.example.demo.pos.dto.CounterSaleForm;
import com.example.demo.pos.dto.CounterSaleResult;
import com.example.demo.pos.dto.CustomerLookupView;
import com.example.demo.pos.dto.PosPriceRequest;
import com.example.demo.pos.dto.PosPriceResult;
import com.example.demo.pos.dto.PosProductView;
import com.example.demo.voucher.entity.Voucher;
import java.util.List;
import com.example.demo.order.service.OrderService;

/**
 * Nghiệp vụ BÁN HÀNG TẠI QUẦY (POS).
 *
 * Điểm quan trọng: quầy KHÔNG tự tính tiền riêng mà tái sử dụng nguyên luồng tạo đơn thủ công
 * của {@link OrderService#createManualOrder} – nhờ vậy đơn bán tại quầy vẫn:
 *   – là đơn MANUAL do admin tạo, khách có thể là khách vãng lai (user_id = NULL);
 *   – xác nhận ngay ⇒ TRỪ KHO bằng câu UPDATE có điều kiện (không thể bán quá tồn);
 *   – giảm giá bằng voucher (nếu quầy nhập) và ghi đủ lịch sử trạng thái;
 *   – thanh toán tiền mặt ⇒ đánh dấu PAID ngay, chuyển khoản ⇒ admin tích "đã nhận tiền".
 *
 * Riêng phần quầy: PHÍ GIAO HÀNG = 0 (khách xách hàng về), tiền khách đưa/tiền thừa tính ở server,
 * và khách quen được tra theo số điện thoại để gắn hoá đơn vào tài khoản của họ.
 */
public interface CounterSaleService {

    public static final int SEARCH_LIMIT = 20;

    public static final String WALK_IN_NAME = "Khách lẻ tại quầy";

    public static final String WALK_IN_PHONE = "0000000000";

    List<PosProductView> searchForPos(String keyword);

    CustomerLookupView lookupCustomer(String phone);

    PosPriceResult priceCart(PosPriceRequest request);

    CounterSaleResult sell(CounterSaleForm form, Integer adminUserId);
}
