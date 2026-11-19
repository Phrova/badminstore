package com.example.demo.shipping.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Cấu hình tích hợp API GHN (Giao Hàng Nhanh) – đọc từ nhánh `app.ghn` trong application.yml.
 *
 * Mặc định TẮT (`enabled: false`) để dự án chạy được khi không có mạng/token:
 * khi tắt, địa chỉ nhập tay như cũ và phí ship dùng hằng số 30.000 ₫.
 * Bật lên thì:
 *  – Địa chỉ / thanh toán lấy địa chỉ trực tiếp từ GHN (2 cấp mới hoặc 3 cấp cũ);
 *  – phí vận chuyển tính theo API GHN (theo địa chỉ + khối lượng);
 *  – admin đẩy đơn sang GHN lấy mã vận đơn và đồng bộ trạng thái giao hàng về hệ thống.
 *
 * Token/ShopId KHÔNG hard-code trong repo: đặt qua biến môi trường GHN_TOKEN / GHN_SHOP_ID.
 * Mặc định chỉ cho phép Staging; muốn gọi Production phải đặt rõ `environment=production`
 * và `allow-production=true` để tránh vô tình tạo vận đơn thật.
 */
@ConfigurationProperties(prefix = "app.ghn")
@Getter
@Setter
public class GhnProperties {

    /** Bật/tắt toàn bộ tích hợp GHN. */
    private boolean enabled = false;

    /**
     * KHÔNG gọi mạng: địa chỉ Tỉnh–Huyện–Xã lấy từ dữ liệu GHN lưu sẵn (đầy đủ 63 tỉnh/thành,
     * xem {@link com.example.demo.shipping.service.GhnOfflineAddressData}), còn phí vận chuyển/vận đơn là số giả.
     */
    private boolean mock = false;

    /**
     * Môi trường GHN: STAGING (mặc định, không tạo vận đơn thật) hoặc PRODUCTION.
     * Nếu base URL trỏ tới online-gateway.ghn.vn thì cũng được coi là Production để bảo vệ.
     */
    private Environment environment = Environment.STAGING;

    /**
     * Chốt an toàn: chỉ khi bật cờ này mới cho phép gọi Production.
     * `environment=production` mà cờ này false ⇒ toàn bộ GHN bị vô hiệu hoá (app vẫn chạy bình thường).
     */
    private boolean allowProduction = false;

    /**
     * Mô hình địa chỉ dùng khi gọi GHN thật:
     *  – {@link AddressModel#AUTO}: có shopId ⇒ dùng mô hình 2 cấp mới, còn lại dùng 3 cấp cũ;
     *  – {@link AddressModel#NEW}: Tỉnh → Phường/Xã (GHN khuyến nghị từ 01/07/2025);
     *  – {@link AddressModel#LEGACY}: Tỉnh → Quận/Huyện → Phường/Xã.
     * Chế độ mock luôn dùng dữ liệu 3 cấp lưu sẵn vì bộ JSON offline hiện có theo mô hình cũ.
     */
    private AddressModel addressModel = AddressModel.LEGACY;

    /** Trạng thái GHN mà chế độ mock trả về khi đồng bộ (để demo ánh xạ trạng thái). */
    private String mockStatus = "delivering";

    /**
     * Địa chỉ API GHN. Mặc định là STAGING (dev-online-gateway) để không vô tình tạo đơn thật;
     * khi triển khai thật mới đổi sang online-gateway và bật `allow-production`.
     */
    private String baseUrl = "https://dev-online-gateway.ghn.vn/shiip/public-api";

    /**
     * Trang in vận đơn của GHN (không dùng chung tiền tố /shiip/public-api).
     * Token in chỉ được tạo khi admin bấm in và không lưu vào DB.
     */
    private String printBaseUrl = "https://dev-online-gateway.ghn.vn";

    /** Token GHN – KHÔNG hard-code trong repo: đặt qua biến môi trường GHN_TOKEN (hoặc application-local.yml). */
    private String token;

    /** ShopId lấy từ tài khoản GHN (bắt buộc cho master-data, tính phí và tạo vận đơn). */
    private Integer shopId;

    /** Kho lấy hàng tùy chọn; GHN mặc định dùng địa chỉ đã đăng ký của shop nếu bỏ trống. */
    private Integer fromDistrictId;
    private String fromWardCode = "";
    private String fromName = "BadStore";
    private String fromPhone = "0900000000";
    private String fromAddress = "Số 1 Đại Cồ Việt, Bách Khoa, Hà Nội";

    /** Khối lượng gói hàng mặc định (gram) – 1 cây vợt + hộp. */
    private int weightGram = 1500;
    private int lengthCm = 60;
    private int widthCm = 20;
    private int heightCm = 12;

    /** 2 = chuyển phát thương mại điện tử (mặc định của GHN), 5 = hàng nhẹ. */
    private int serviceTypeId = 2;

    /** 1 = shop trả phí vận chuyển, 2 = người nhận trả (dùng cho đơn COD). */
    private int paymentTypeId = 1;

    /** Giá trị khai giá mặc định (VND) để GHN bồi thường khi mất hàng. */
    private long insuranceValue = 500000;

    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 5000;

    /** Thời gian cache dữ liệu Tỉnh–Huyện–Xã và phí ship (giây) để không gọi API liên tục. */
    private long cacheTtlSeconds = 600;

    /** Tự động đồng bộ trạng thái các đơn đang giao mỗi `syncDelayMs` (mặc định TẮT). */
    private boolean syncEnabled = false;
    private long syncDelayMs = 600000;

    /** Cấu hình nhận webhook trạng thái vận đơn từ GHN. */
    private Webhook webhook = new Webhook();

    /** Các mô hình địa chỉ GHN hỗ trợ. */
    public enum AddressModel {
        LEGACY,
        NEW,
        AUTO
    }

    /** Môi trường API GHN. */
    public enum Environment {
        STAGING,
        PRODUCTION
    }

    /** Cấu hình webhook: chỉ bật khi đã có URL công khai và secret do GHN cấp. */
    @Getter
    @Setter
    public static class Webhook {

        private boolean enabled = false;

        /** Secret dùng để so khớp header; KHÔNG log giá trị này. */
        private String secret;

        /** Tên header GHN gửi kèm secret (có thể đổi theo cấu hình webhook trên GHN). */
        private String headerName = "X-GHN-Signature";
    }

    /**
     * Có dùng mô hình 2 cấp mới không. Chế độ mock luôn dùng dữ liệu 3 cấp lưu sẵn; khi gọi API thật,
     * AUTO chỉ chọn mô hình mới nếu đã có shopId vì API master-data v3 của GHN bắt buộc header này.
     */
    public boolean isNewAddressModel() {
        if (mock) {
            return false;
        }
        return addressModel == AddressModel.NEW
                || (addressModel == AddressModel.AUTO && shopId != null);
    }

    /**
     * Base URL đang trỏ tới Production (online-gateway), kể cả khi quên đặt `environment`.
     * dev-online-gateway là Staging nên không bị chặn.
     */
    public boolean isProductionUrl() {
        if (!StringUtils.hasText(baseUrl)) {
            return false;
        }
        return baseUrl.contains("online-gateway.ghn.vn")
                && !baseUrl.contains("dev-online-gateway.ghn.vn");
    }

    /** Có đang chạy ở môi trường Production không (theo cấu hình hoặc theo base URL). */
    public boolean isProduction() {
        return environment == Environment.PRODUCTION || isProductionUrl();
    }

    /** Bị chặn vì trỏ Production nhưng chưa bật `allow-production`. */
    public boolean isGuardBlocked() {
        return enabled && !mock && isProduction() && !allowProduction;
    }

    /** GHN thực sự được phép hoạt động (đã bật và không bị guard chặn). */
    public boolean isOperational() {
        return enabled && !isGuardBlocked();
    }

    /**
     * Webhook chỉ được nhận khi GHN đang bật, webhook bật, có secret và shopId.
     * Thiếu bất kỳ điều kiện nào ⇒ từ chối (fail closed), không bao giờ nhận request không xác thực.
     */
    public boolean isWebhookReady() {
        return enabled
                && webhook != null
                && webhook.isEnabled()
                && StringUtils.hasText(webhook.getSecret())
                && shopId != null;
    }

    /** Tên header chứa secret; cấu hình trống thì dùng mặc định của GHN. */
    public String getWebhookHeaderName() {
        return webhook != null && StringUtils.hasText(webhook.getHeaderName())
                ? webhook.getHeaderName().trim()
                : "X-GHN-Signature";
    }

    /** Nhãn ngắn để hiển thị badge trên giao diện quản trị. */
    public String getEnvironmentLabel() {
        return isProduction() ? "PRODUCTION" : "STAGING";
    }

    /**
     * Đủ điều kiện tra cứu địa chỉ: bật GHN + có token.
     * GHN yêu cầu thêm header ShopId cho API master-data; app gửi kèm khi đã cấu hình.
     */
    public boolean isAddressConfigured() {
        return isOperational() && StringUtils.hasText(token);
    }

    /**
     * Đủ điều kiện tính phí/tạo vận đơn: cần token + shopId.
     * Kho lấy hàng (from-district-id/from-ward-code) là tùy chọn vì GHN mặc định lấy địa chỉ shop;
     * nếu có cấu hình thì app gửi kèm để tính phí chính xác hơn.
     */
    public boolean isShippingConfigured() {
        return isAddressConfigured() && shopId != null;
    }
}
