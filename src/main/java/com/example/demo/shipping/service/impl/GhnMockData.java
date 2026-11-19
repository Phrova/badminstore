package com.example.demo.shipping.service.impl;

import com.example.demo.shipping.dto.GhnAvailableService;
import com.example.demo.shipping.dto.GhnCancelResult;
import com.example.demo.shipping.dto.GhnFee;
import com.example.demo.shipping.dto.GhnOrderDetail;
import com.example.demo.shipping.dto.GhnOrderResult;
import com.example.demo.shipping.dto.GhnPrintToken;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phí vận chuyển và vận đơn GIẢ để DEMO OFFLINE (bật bằng `app.ghn.mock: true`).
 *
 * Mục đích: thầy/cô xem được luồng tính phí và đồng bộ trạng thái vận đơn mà không cần
 * mạng/token GHN thật. Riêng dữ liệu địa chỉ Tỉnh–Huyện–Xã ở chế độ này là dữ liệu THẬT,
 * đầy đủ 63 tỉnh/thành (xem {@link GhnOfflineAddressData}), nên chọn địa chỉ không bị giới hạn.
 */
final class GhnMockData {

    private static final AtomicInteger ORDER_SEQ = new AtomicInteger(100000);

    private GhnMockData() {
    }

    /** Phí giả: nội thành (cùng quận với shop) rẻ hơn, hàng nặng hơn thì cộng thêm. */
    static GhnFee fee(int fromDistrictId, int toDistrictId, int weightGram) {
        boolean sameDistrict = fromDistrictId == toDistrictId;
        long service = sameDistrict ? 19000 : 32000;
        if (weightGram > 1000) {
            service += ((weightGram - 1000) / 500 + 1) * 2500L;
        }
        long insurance = 0;
        return new GhnFee(service + insurance, service, insurance, 0L, 0L, 0L);
    }

    static GhnOrderResult createOrder() {
        int seq = ORDER_SEQ.incrementAndGet();
        // Ghép thêm thời điểm tạo để mã mock không trùng giữa các lần khởi động lại ứng dụng
        // (cột orders.ghn_order_code có unique index và giới hạn 30 ký tự).
        String orderCode = "GHNMOCK" + System.currentTimeMillis() + String.format("%03d", seq % 1000);
        return new GhnOrderResult(orderCode, 34500, "2026-10-18T12:00:00Z", "MOCK" + (seq % 100), "1");
    }

    /** Chi tiết vận đơn giả: trạng thái lấy theo `app.ghn.mock-status` để demo ánh xạ trạng thái. */
    static GhnOrderDetail orderDetail(String orderCode, String mockStatus) {
        return new GhnOrderDetail(orderCode, mockStatus, 34500, "2026-10-18T12:00:00Z",
                "Khách GHN", "0900000000", "Địa chỉ theo GHN", "MOCK01");
    }

    /** Huỷ giả luôn thành công để demo luồng admin. */
    static GhnCancelResult cancelOrder(String orderCode) {
        return new GhnCancelResult(orderCode, true, "Huỷ vận đơn thành công (mock)");
    }

    /** Token in giả – không dùng để gọi GHN thật. */
    static GhnPrintToken printToken() {
        return new GhnPrintToken("GHNMOCKPRINT" + System.currentTimeMillis());
    }

    /** Một dịch vụ vận chuyển giả theo cấu hình service-type-id. */
    static List<GhnAvailableService> availableServices(int serviceTypeId) {
        return List.of(new GhnAvailableService(53320, serviceTypeId, "Chuẩn", "Chuyển phát chuẩn (mock)"));
    }
}
