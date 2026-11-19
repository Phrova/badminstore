package com.example.demo.shipping.service;

import com.example.demo.shipping.dto.GhnApiException;
import com.example.demo.shipping.dto.GhnAvailableService;
import com.example.demo.shipping.dto.GhnCancelResult;
import com.example.demo.shipping.dto.GhnCreateOrderRequest;
import com.example.demo.shipping.dto.GhnDistrict;
import com.example.demo.shipping.dto.GhnFee;
import com.example.demo.shipping.dto.GhnNewProvince;
import com.example.demo.shipping.dto.GhnNewWard;
import com.example.demo.shipping.dto.GhnOrderDetail;
import com.example.demo.shipping.dto.GhnOrderResult;
import com.example.demo.shipping.dto.GhnPrintToken;
import com.example.demo.shipping.dto.GhnProvince;
import com.example.demo.shipping.dto.GhnWard;
import java.util.List;

/**
 * Khách gọi API GHN (Giao Hàng Nhanh) – public API v2 (đơn hàng) và v3 (địa chỉ 2 cấp mới).
 *
 * Nguyên tắc an toàn cho demo:
 *  1. Mặc định TẮT: khi chưa có token, mọi chức năng GHN tự ẩn/ngưng, địa chỉ nhập tay như cũ.
 *  2. `app.ghn.mock: true` KHÔNG gọi mạng: địa chỉ lấy từ dữ liệu GHN lưu sẵn đầy đủ
 *     (xem `GhnOfflineAddressData`), phí vận chuyển/vận đơn là số giả để demo (xem `GhnMockData`).
 *  3. Dữ liệu Tỉnh–Huyện–Xã và phí ship được CACHE trong bộ nhớ (mặc định 10 phút)
 *     để một trang không gọi GHN quá nhiều lần.
 *  4. Mọi lỗi mạng/GHN từ chối đều bọc thành {@link GhnApiException} với thông báo tiếng Việt.
 *  5. Chỉ Staging được bật mặc định; Production bị chặn nếu chưa bật `app.ghn.allow-production`.
 *
 * Phần HTTP thuần nằm ở `GhnApiClient` (package `shipping.service.impl`); implementation thêm cache,
 * mock và các thao tác nghiệp vụ của API GHN (nhưng không quyết định trạng thái đơn hàng).
 */
public interface GhnClient {

    boolean isEnabled();

    boolean isMock();

    boolean isProduction();

    String getEnvironmentLabel();

    boolean isNewAddressModel();

    boolean isAddressAvailable();

    boolean isShippingAvailable();

    List<GhnProvince> provinces();

    List<GhnNewProvince> newProvinces();

    List<GhnDistrict> districts(int provinceId);

    List<GhnWard> wards(int districtId);

    List<GhnNewWard> newWards(int provinceId);

    GhnFee fee(int toDistrictId, String toWardCode, int weightGram);

    GhnOrderResult previewFee(GhnCreateOrderRequest request);

    GhnOrderResult createOrder(GhnCreateOrderRequest request);

    GhnOrderDetail orderDetail(String orderCode);

    GhnCancelResult cancelOrder(String orderCode, String reason, String reasonCode);

    GhnPrintToken printToken(List<String> orderCodes);

    List<GhnAvailableService> availableServices(int toDistrictId);

    void clearCache();
}
