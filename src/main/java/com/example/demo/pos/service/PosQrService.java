package com.example.demo.pos.service;

import com.example.demo.pos.dto.PosQrRequest;
import com.example.demo.pos.dto.PosQrResult;

/**
 * QR chuyển khoản cho màn bán hàng tại quầy. Đơn chỉ được tạo khi bấm "Thu tiền", nên QR phải được dựng
 * TRƯỚC với một mã đơn cấp sẵn (nội dung chuyển khoản). Số tiền luôn lấy từ {@link CounterSaleService#priceCart}
 * (giá DB + voucher), không nhận từ trình duyệt; khi bán, mã đơn được kiểm tra lại (đúng định dạng + chưa dùng).
 */
public interface PosQrService {

    PosQrResult qrFor(PosQrRequest request);
}
