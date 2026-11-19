package com.example.demo.shipping.service.impl;

import com.example.demo.shipping.config.GhnProperties;
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
import com.example.demo.shipping.dto.GhnResponse;
import com.example.demo.shipping.dto.GhnWard;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import com.example.demo.shipping.service.GhnClient;

/**
 * Khách gọi API GHN (Giao Hàng Nhanh) – public API v2 (đơn hàng) và v3 (địa chỉ 2 cấp mới).
 *
 * Nguyên tắc an toàn cho demo:
 *  1. Mặc định TẮT: khi chưa có token, mọi chức năng GHN tự ẩn/ngưng, địa chỉ nhập tay như cũ.
 *  2. `app.ghn.mock: true` KHÔNG gọi mạng: địa chỉ lấy từ dữ liệu GHN lưu sẵn đầy đủ
 *     (xem {@link GhnOfflineAddressData}), phí vận chuyển/vận đơn là số giả để demo (xem {@link GhnMockData}).
 *  3. Dữ liệu Tỉnh–Huyện–Xã và phí ship được CACHE trong bộ nhớ (mặc định 10 phút)
 *     để một trang không gọi GHN quá nhiều lần.
 *  4. Mọi lỗi mạng/GHN từ chối đều bọc thành {@link GhnApiException} với thông báo tiếng Việt.
 *  5. Chỉ Staging được bật mặc định; Production bị chặn nếu chưa bật `app.ghn.allow-production`.
 *
 * Phần HTTP thuần nằm ở {@link GhnApiClient}; lớp này thêm cache, mock và các thao tác nghiệp vụ
 * của API GHN (nhưng không quyết định trạng thái đơn hàng).
 */
@Service
public class GhnClientImpl implements GhnClient {

    private static final Logger log = LoggerFactory.getLogger(GhnClientImpl.class);

    private final GhnProperties props;
    private final GhnApiClient api;
    private final Map<String, CacheEntry<?>> cache = new ConcurrentHashMap<>();

    /**
     * Constructor dùng cho Spring (cần @Autowired vì lớp có 2 constructor):
     * tự dựng RestClient (JDK HttpClient, có timeout theo cấu hình).
     */
    @Autowired
    public GhnClientImpl(GhnProperties props) {
        this(props, buildRestClient(props, RestClient.builder()));
        if (props.isGuardBlocked()) {
            log.error("GHN đang trỏ tới PRODUCTION nhưng app.ghn.allow-production=false – "
                    + "đã vô hiệu hoá GHN để tránh tạo vận đơn thật.");
        } else if (props.isEnabled()) {
            log.info("GHN: {} | môi trường={} | địa chỉ={} | shopId={} | token={}",
                    props.getBaseUrl(),
                    props.getEnvironmentLabel(),
                    props.isNewAddressModel() ? "2 cấp mới (v3)" : "3 cấp cũ",
                    props.getShopId() == null ? "chưa có" : props.getShopId(),
                    StringUtils.hasText(props.getToken()) ? "đã cấu hình" : "chưa có");
        }
    }

    /** Dùng cho unit test: truyền thẳng RestClient đã gắn MockRestServiceServer. */
    GhnClientImpl(GhnProperties props, RestClient restClient) {
        this.props = props;
        this.api = new GhnApiClient(props, restClient);
    }

    private static RestClient buildRestClient(GhnProperties props, RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));
        return builder.requestFactory(factory).baseUrl(props.getBaseUrl()).build();
    }

    /** GHN có được bật và không bị chốt Production chặn không (UI dùng để ẩn/hiện khối GHN). */
    @Override
    public boolean isEnabled() {
        return props.isOperational();
    }

    /** GHN dùng được thật hay đang chạy chế độ mock. */
    @Override
    public boolean isMock() {
        return props.isMock();
    }

    /** Có đang trỏ tới Production không (badge cảnh báo trên giao diện quản trị). */
    @Override
    public boolean isProduction() {
        return props.isProduction();
    }

    /** "STAGING"/"PRODUCTION" để hiển thị badge. */
    @Override
    public String getEnvironmentLabel() {
        return props.getEnvironmentLabel();
    }

    /** Có dùng mô hình địa chỉ 2 cấp mới (Tỉnh → Phường/Xã) không. */
    @Override
    public boolean isNewAddressModel() {
        return props.isNewAddressModel();
    }

    /** Bật + (mock hoặc đã có token) = tra cứu được địa chỉ. */
    @Override
    public boolean isAddressAvailable() {
        return props.isOperational() && (props.isMock() || props.isAddressConfigured());
    }

    /** Bật + có token + shopId = tính phí/tạo vận đơn được. */
    @Override
    public boolean isShippingAvailable() {
        return props.isOperational() && (props.isMock() || props.isShippingConfigured());
    }

    /** Lấy danh sách Tỉnh/Thành phố theo mô hình 3 cấp cũ. */
    @Override
    public List<GhnProvince> provinces() {
        ensureAvailable("lấy danh sách tỉnh/thành phố");
        if (props.isMock()) {
            return GhnOfflineAddressData.provinces();
        }
        return cached("provinces", () -> api.getData("/master-data/province", Map.of(),
                new ParameterizedTypeReference<GhnResponse<List<GhnProvince>>>() {
                }, "lấy danh sách tỉnh/thành phố"));
    }

    /** Lấy danh sách Tỉnh/Thành phố theo mô hình 2 cấp mới (GHN khuyến nghị từ 01/07/2025). */
    @Override
    public List<GhnNewProvince> newProvinces() {
        ensureNewAddressAvailable("lấy danh sách tỉnh/thành phố (2 cấp)");
        return cached("newProvinces", () -> {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("offset", "0");
            query.put("limit", "200");
            List<GhnNewProvince> data = api.getData("/v3/master-data/province/all", query,
                    new ParameterizedTypeReference<GhnResponse<List<GhnNewProvince>>>() {
                    }, "lấy danh sách tỉnh/thành phố (2 cấp)");
            return data.stream()
                    .filter(province -> province.status() == 1)
                    .toList();
        });
    }

    /** Lấy danh sách Quận/Huyện theo tỉnh (mô hình 3 cấp cũ). */
    @Override
    public List<GhnDistrict> districts(int provinceId) {
        ensureAvailable("lấy danh sách quận/huyện");
        if (props.isMock()) {
            return GhnOfflineAddressData.districts(provinceId);
        }
        return cached("districts:" + provinceId, () -> api.getData("/master-data/district",
                Map.of("province_id", String.valueOf(provinceId)),
                new ParameterizedTypeReference<GhnResponse<List<GhnDistrict>>>() {
                }, "lấy danh sách quận/huyện"));
    }

    /** Lấy danh sách Phường/Xã theo quận (mô hình 3 cấp cũ). */
    @Override
    public List<GhnWard> wards(int districtId) {
        ensureAvailable("lấy danh sách phường/xã");
        if (props.isMock()) {
            return GhnOfflineAddressData.wards(districtId);
        }
        return cached("wards:" + districtId, () -> {
            try {
                return api.getData("/master-data/ward", Map.of("district_id", String.valueOf(districtId)),
                        new ParameterizedTypeReference<GhnResponse<List<GhnWard>>>() {
                        }, "lấy danh sách phường/xã");
            } catch (GhnApiException firstTry) {
                log.debug("GHN ward GET lỗi, thử lại bằng POST: {}", firstTry.getMessage());
                return api.postData("/master-data/ward", Map.of("district_id", districtId),
                        new ParameterizedTypeReference<GhnResponse<List<GhnWard>>>() {
                        }, "lấy danh sách phường/xã");
            }
        });
    }

    /**
     * Lấy Phường/Xã thuộc thẳng một Tỉnh/Thành (mô hình 2 cấp mới).
     * GHN trả HTTP 200 với data = null khi province_id sai, nên coi đó là danh sách rỗng.
     */
    @Override
    public List<GhnNewWard> newWards(int provinceId) {
        ensureNewAddressAvailable("lấy danh sách phường/xã (2 cấp)");
        return cached("newWards:" + provinceId, () -> {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("province_id", String.valueOf(provinceId));
            query.put("offset", "0");
            query.put("limit", "200");
            GhnResponse<List<GhnNewWard>> response = api.get(
                    "/v3/master-data/ward/all-by-province-id", query,
                    new ParameterizedTypeReference<GhnResponse<List<GhnNewWard>>>() {
                    }, "lấy danh sách phường/xã (2 cấp)");
            List<GhnNewWard> data = api.require(response, "lấy danh sách phường/xã (2 cấp)");
            if (data == null) {
                return List.of();
            }
            return data.stream()
                    .filter(ward -> ward.status() == 1)
                    .toList();
        });
    }

    /** Tính phí vận chuyển từ kho shop tới quận của khách. */
    @Override
    public GhnFee fee(int toDistrictId, String toWardCode, int weightGram) {
        ensureShippingConfig("tính phí vận chuyển");
        if (props.isMock()) {
            int from = props.getFromDistrictId() == null ? 0 : props.getFromDistrictId();
            return GhnMockData.fee(from, toDistrictId, weightGram);
        }
        String key = "fee:" + toDistrictId + ":" + toWardCode + ":" + weightGram;
        return cached(key, () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            if (props.getFromDistrictId() != null) {
                body.put("from_district_id", props.getFromDistrictId());
            }
            if (StringUtils.hasText(props.getFromWardCode())) {
                body.put("from_ward_code", props.getFromWardCode());
            }
            body.put("to_district_id", toDistrictId);
            if (StringUtils.hasText(toWardCode)) {
                body.put("to_ward_code", toWardCode);
            }
            body.put("service_type_id", props.getServiceTypeId());
            body.put("weight", weightGram);
            body.put("length", props.getLengthCm());
            body.put("width", props.getWidthCm());
            body.put("height", props.getHeightCm());
            body.put("insurance_value", props.getInsuranceValue());
            return api.postData("/v2/shipping-order/fee", body,
                    new ParameterizedTypeReference<GhnResponse<GhnFee>>() {
                    }, "tính phí vận chuyển");
        });
    }

    /**
     * Xem trước đơn để lấy phí vận chuyển cho địa chỉ 2 cấp mới.
     * API /v2/shipping-order/fee hiện chỉ nhận district_id/ward_code cũ, còn preview dùng chung
     * request với tạo đơn nên hỗ trợ đầy đủ is_new_to_address + tên tỉnh/phường.
     */
    @Override
    public GhnOrderResult previewFee(GhnCreateOrderRequest request) {
        ensureShippingConfig("tính phí vận chuyển");
        if (props.isMock()) {
            return GhnMockData.createOrder();
        }
        return api.postData("/v2/shipping-order/preview", request,
                new ParameterizedTypeReference<GhnResponse<GhnOrderResult>>() {
                }, "tính phí vận chuyển");
    }

    /** Đẩy đơn sang GHN để lấy mã vận đơn. */
    @Override
    public GhnOrderResult createOrder(GhnCreateOrderRequest request) {
        ensureShippingConfig("tạo vận đơn");
        if (props.isMock()) {
            return GhnMockData.createOrder();
        }
        return api.postData("/v2/shipping-order/create", request,
                new ParameterizedTypeReference<GhnResponse<GhnOrderResult>>() {
                }, "tạo vận đơn");
    }

    /** Xem chi tiết/trạng thái hiện tại của vận đơn (GHN dùng GET + query param). */
    @Override
    public GhnOrderDetail orderDetail(String orderCode) {
        ensureAvailable("xem trạng thái vận đơn");
        if (!StringUtils.hasText(orderCode)) {
            throw new GhnApiException("Thiếu mã vận đơn GHN cần tra cứu.");
        }
        if (props.isMock()) {
            return GhnMockData.orderDetail(orderCode, props.getMockStatus());
        }
        return api.getData("/v2/shipping-order/detail", Map.of("order_code", orderCode),
                new ParameterizedTypeReference<GhnResponse<GhnOrderDetail>>() {
                }, "xem trạng thái vận đơn");
    }

    /**
     * Hủy một vận đơn GHN. Chỉ trả kết quả GHN; việc đổi trạng thái đơn hàng do tầng nghiệp vụ quyết định.
     *
     * @param reasonCode mã lý do GHN (GHN-CO001...); để trống thì GHN dùng lý do mặc định
     */
    @Override
    public GhnCancelResult cancelOrder(String orderCode, String reason, String reasonCode) {
        ensureShippingConfig("huỷ vận đơn");
        if (!StringUtils.hasText(orderCode)) {
            throw new GhnApiException("Thiếu mã vận đơn GHN cần huỷ.");
        }
        if (props.isMock()) {
            return GhnMockData.cancelOrder(orderCode);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order_codes", List.of(orderCode));
        if (StringUtils.hasText(reasonCode)) {
            body.put("reason_code", reasonCode.trim());
        }
        if (StringUtils.hasText(reason)) {
            body.put("reason", reason.trim());
        }
        List<GhnCancelResult> data = api.postData("/v2/switch-status/cancel", body,
                new ParameterizedTypeReference<GhnResponse<List<GhnCancelResult>>>() {
                }, "huỷ vận đơn");
        return data.stream()
                .filter(item -> orderCode.equals(item.orderCode()))
                .findFirst()
                .orElseGet(() -> data.isEmpty()
                        ? new GhnCancelResult(orderCode, false, "GHN không trả kết quả huỷ vận đơn.")
                        : data.get(0));
    }

    /** Tạo token in vận đơn (chỉ dùng một lần, không lưu DB). */
    @Override
    public GhnPrintToken printToken(List<String> orderCodes) {
        ensureShippingConfig("tạo token in vận đơn");
        if (orderCodes == null || orderCodes.isEmpty()) {
            throw new GhnApiException("Chưa có mã vận đơn để in.");
        }
        if (props.isMock()) {
            return GhnMockData.printToken();
        }
        GhnPrintToken token = api.postData("/v2/a5/gen-token", Map.of("order_codes", orderCodes),
                new ParameterizedTypeReference<GhnResponse<GhnPrintToken>>() {
                }, "tạo token in vận đơn");
        if (!StringUtils.hasText(token.token())) {
            throw new GhnApiException("GHN không trả token in vận đơn. Vui lòng thử lại.");
        }
        return token;
    }

    /** Danh sách dịch vụ vận chuyển khả dụng từ kho shop tới quận của khách. */
    @Override
    public List<GhnAvailableService> availableServices(int toDistrictId) {
        ensureShippingConfig("lấy dịch vụ vận chuyển");
        if (props.isMock()) {
            return GhnMockData.availableServices(props.getServiceTypeId());
        }
        if (props.getFromDistrictId() == null) {
            throw new GhnApiException("Chưa khai báo kho lấy hàng (app.ghn.from-district-id) "
                    + "nên không lấy được dịch vụ vận chuyển.");
        }
        String key = "services:" + toDistrictId;
        return cached(key, () -> {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("shop_id", String.valueOf(props.getShopId()));
            query.put("from_district", String.valueOf(props.getFromDistrictId()));
            query.put("to_district", String.valueOf(toDistrictId));
            return api.getData("/v2/shipping-order/available-services", query,
                    new ParameterizedTypeReference<GhnResponse<List<GhnAvailableService>>>() {
                    }, "lấy dịch vụ vận chuyển");
        });
    }

    /** Xoá cache (gọi khi admin muốn nạp lại dữ liệu mới nhất từ GHN). */
    @Override
    public void clearCache() {
        cache.clear();
    }

    // ------------------------------------------------------------------ nội bộ

    private <T> T cached(String key, Supplier<T> loader) {
        long now = System.currentTimeMillis();
        CacheEntry<?> entry = cache.get(key);
        if (entry != null && entry.expiresAt() > now && entry.value() != null) {
            @SuppressWarnings("unchecked")
            T value = (T) entry.value();
            return value;
        }
        T value = call(key, loader);
        long ttlMillis = Math.max(1, props.getCacheTtlSeconds()) * 1000L;
        cache.put(key, new CacheEntry<>(value, now + ttlMillis));
        return value;
    }

    private <T> T call(String action, Supplier<T> loader) {
        try {
            return loader.get();
        } catch (GhnApiException ex) {
            throw ex;
        } catch (RestClientException ex) {
            log.warn("GHN lỗi khi {}: {}", action, ex.getMessage());
            throw new GhnApiException("Không gọi được API GHN (" + action
                    + "). Vui lòng kiểm tra kết nối mạng, token và shopId.", ex);
        }
    }

    private void ensureAvailable(String action) {
        if (!props.isEnabled()) {
            throw new GhnApiException("Tính năng GHN đang tắt (app.ghn.enabled = false).");
        }
        if (props.isGuardBlocked()) {
            throw new GhnApiException("GHN đang trỏ tới Production nhưng app.ghn.allow-production=false; "
                    + "đã chặn lời gọi để tránh tạo vận đơn thật. Không thể " + action + ".");
        }
        if (!props.isMock() && !props.isAddressConfigured()) {
            throw new GhnApiException("Chưa cấu hình GHN để " + action
                    + ": cần GHN_TOKEN (hoặc bật app.ghn.mock để demo offline).");
        }
    }

    /** API master-data v3 bắt buộc Token + ShopId. */
    private void ensureNewAddressAvailable(String action) {
        ensureAvailable(action);
        if (!props.isNewAddressModel()) {
            throw new GhnApiException("Mô hình địa chỉ 2 cấp của GHN đang tắt; không thể " + action + ".");
        }
        if (props.getShopId() == null) {
            throw new GhnApiException("Chưa khai báo shopId GHN (GHN_SHOP_ID) nên không thể " + action + ".");
        }
    }

    private void ensureShippingConfig(String action) {
        if (!props.isEnabled()) {
            throw new GhnApiException("Tính năng GHN đang tắt (app.ghn.enabled = false).");
        }
        if (props.isGuardBlocked()) {
            throw new GhnApiException("GHN đang trỏ tới Production nhưng app.ghn.allow-production=false; "
                    + "đã chặn lời gọi để tránh tạo vận đơn thật. Không thể " + action + ".");
        }
        if (props.isMock()) {
            return;
        }
        if (!props.isAddressConfigured()) {
            throw new GhnApiException("Chưa cấu hình token GHN (GHN_TOKEN) nên không thể " + action + ".");
        }
        if (props.getShopId() == null) {
            throw new GhnApiException("Chưa khai báo shopId GHN (app.ghn.shop-id) nên không thể " + action + ".");
        }
    }

    private record CacheEntry<T>(T value, long expiresAt) {
    }
}
