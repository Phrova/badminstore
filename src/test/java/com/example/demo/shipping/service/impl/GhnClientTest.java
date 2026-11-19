package com.example.demo.shipping.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.demo.shipping.config.GhnProperties;
import com.example.demo.shipping.service.GhnClient;
import com.example.demo.shipping.dto.GhnApiException;
import com.example.demo.shipping.dto.GhnAvailableService;
import com.example.demo.shipping.dto.GhnCancelResult;
import com.example.demo.shipping.dto.GhnCreateOrderRequest;
import com.example.demo.shipping.dto.GhnFee;
import com.example.demo.shipping.dto.GhnOrderDetail;
import com.example.demo.shipping.dto.GhnOrderResult;
import com.example.demo.shipping.dto.GhnProvince;
import com.example.demo.shipping.dto.GhnWard;
import com.example.demo.shipping.enums.GhnStatus;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Kiểm thử lớp gọi API GHN: đúng đường dẫn/header/tham số, có cache (không gọi lại liên tục),
 * tự chuyển sang POST khi GHN từ chối GET phường/xã, và mọi lỗi đều thành {@link GhnApiException}
 * với thông báo tiếng Việt để giao diện không bao giờ vỡ.
 *
 * Không gọi mạng thật: dùng MockRestServiceServer gắn vào RestClient.Builder.
 */
class GhnClientTest {

    private GhnProperties properties;
    private MockRestServiceServer server;
    private GhnClient client;

    @BeforeEach
    void setUp() {
        properties = new GhnProperties();
        properties.setEnabled(true);
        properties.setMock(false);
        properties.setToken("token-test");
        properties.setShopId(123456);
        properties.setFromDistrictId(1442);
        properties.setBaseUrl("http://ghn.test");

        RestClient.Builder builder = RestClient.builder().baseUrl("http://ghn.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GhnClientImpl(properties, builder.build());
    }

    @Test
    @DisplayName("Lấy tỉnh/thành: đúng endpoint + header Token, và kết quả được CACHE (không gọi lần 2)")
    void provinces_callsApiOnceThenUsesCache() {
        server.expect(requestTo("http://ghn.test/master-data/province"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Token", "token-test"))
                .andExpect(header("ShopId", "123456"))
                .andRespond(withSuccess("""
                        {"code":200,"message":"Success",
                         "data":[{"ProvinceID":201,"ProvinceName":"Hà Nội"},
                                 {"ProvinceID":202,"ProvinceName":"TP. Hồ Chí Minh"}]}
                        """, MediaType.APPLICATION_JSON));

        List<GhnProvince> first = client.provinces();
        List<GhnProvince> second = client.provinces();

        assertThat(first).hasSize(2);
        assertThat(first.get(0).id()).isEqualTo(201);
        assertThat(first.get(0).name()).isEqualTo("Hà Nội");
        // Lần gọi thứ hai phải lấy từ cache ⇒ mock không nhận thêm request nào.
        assertThat(second).hasSize(2);
        server.verify();
    }

    @Test
    @DisplayName("Lấy phường/xã: GHN từ chối GET thì tự thử lại bằng POST như tài liệu v2")
    void wards_fallsBackToPostWhenGetFails() {
        server.expect(requestTo("http://ghn.test/master-data/ward?district_id=1442"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());
        server.expect(requestTo("http://ghn.test/master-data/ward"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.district_id").value(1442))
                .andRespond(withSuccess("""
                        {"code":200,"message":"Success",
                         "data":[{"WardCode":"1A0808","DistrictID":1442,"WardName":"Phường Bách Khoa"}]}
                        """, MediaType.APPLICATION_JSON));

        List<GhnWard> wards = client.wards(1442);

        assertThat(wards).hasSize(1);
        assertThat(wards.get(0).code()).isEqualTo("1A0808");
        assertThat(wards.get(0).name()).isEqualTo("Phường Bách Khoa");
        server.verify();
    }

    @Test
    @DisplayName("Base URL có /shiip/public-api vẫn giữ đúng tiền tố khi gọi endpoint tương đối")
    void baseUrlWithPath_keepsApiPrefix() {
        GhnProperties basePathProperties = new GhnProperties();
        basePathProperties.setEnabled(true);
        basePathProperties.setMock(false);
        basePathProperties.setToken("token-test");
        basePathProperties.setShopId(123456);
        basePathProperties.setBaseUrl("http://ghn.test/shiip/public-api");

        RestClient.Builder builder = RestClient.builder().baseUrl("http://ghn.test/shiip/public-api");
        MockRestServiceServer basePathServer = MockRestServiceServer.bindTo(builder).build();
        GhnClient basePathClient = new GhnClientImpl(basePathProperties, builder.build());

        basePathServer.expect(requestTo("http://ghn.test/shiip/public-api/master-data/province"))
                .andRespond(withSuccess("""
                        {"code":200,"message":"Success",
                         "data":[{"ProvinceID":201,"ProvinceName":"Hà Nội"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(basePathClient.provinces()).hasSize(1);
        basePathServer.verify();
    }

    @Test
    @DisplayName("Địa chỉ 2 cấp mới: gọi API v3 tỉnh/thành kèm Token + ShopId và lọc trạng thái hoạt động")
    void newProvinces_usesV3EndpointAndShopHeader() {
        properties.setAddressModel(GhnProperties.AddressModel.NEW);

        server.expect(requestTo("http://ghn.test/v3/master-data/province/all?offset=0&limit=200"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Token", "token-test"))
                .andExpect(header("ShopId", "123456"))
                .andRespond(withSuccess("""
                        {"code":200,"message":"Success",
                         "data":[{"_id":1000001,"name":"Hồ Chí Minh","parent_id":1,"status":1},
                                 {"_id":1000002,"name":"Tỉnh cũ","parent_id":1,"status":2}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.isNewAddressModel()).isTrue();
        assertThat(client.newProvinces())
                .extracting(province -> province.id() + ":" + province.name())
                .containsExactly("1000001:Hồ Chí Minh");
        server.verify();
    }

    @Test
    @DisplayName("Địa chỉ 2 cấp mới: province_id sai (data = null) trả danh sách rỗng thay vì lỗi")
    void newWards_treatsNullDataAsEmpty() {
        properties.setAddressModel(GhnProperties.AddressModel.NEW);

        server.expect(requestTo("http://ghn.test/v3/master-data/ward/all-by-province-id"
                        + "?province_id=9999999&offset=0&limit=200"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("ShopId", "123456"))
                .andRespond(withSuccess("""
                        {"code":200,"message":"Success","data":null}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.newWards(9999999)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("Địa chỉ 2 cấp mới: lấy phường/xã theo tỉnh, lọc bản ghi ngừng hoạt động và cache kết quả")
    void newWards_usesV3EndpointAndFiltersInactive() {
        properties.setAddressModel(GhnProperties.AddressModel.NEW);

        server.expect(requestTo("http://ghn.test/v3/master-data/ward/all-by-province-id"
                        + "?province_id=1000001&offset=0&limit=200"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Token", "token-test"))
                .andExpect(header("ShopId", "123456"))
                .andRespond(withSuccess("""
                        {"code":200,"message":"Success",
                         "data":[{"_id":1003646,"name":"Phường Vũng Tàu","parent_id":1000001,"status":1},
                                 {"_id":1003647,"name":"Phường cũ","parent_id":1000001,"status":2}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.newWards(1000001))
                .extracting(ward -> ward.id() + ":" + ward.name())
                .containsExactly("1003646:Phường Vũng Tàu");
        // Lần thứ hai lấy từ cache, không phát sinh request mới.
        assertThat(client.newWards(1000001)).hasSize(1);
        server.verify();
    }

    @Test
    @DisplayName("Tính phí vận chuyển: gửi đúng kho lấy hàng + quận nhận + khối lượng")
    void fee_sendsWarehouseAndWeight() {
        server.expect(requestTo("http://ghn.test/v2/shipping-order/fee"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Token", "token-test"))
                .andExpect(header("ShopId", "123456"))
                .andExpect(jsonPath("$.from_district_id").value(1442))
                .andExpect(jsonPath("$.to_district_id").value(1454))
                .andExpect(jsonPath("$.to_ward_code").value("1A0808"))
                .andExpect(jsonPath("$.weight").value(1800))
                .andExpect(jsonPath("$.service_type_id").value(2))
                .andRespond(withSuccess("""
                        {"code":200,"message":"Success",
                         "data":{"total":34500,"service_fee":30000,"insurance_fee":4500}}
                        """, MediaType.APPLICATION_JSON));

        GhnFee fee = client.fee(1454, "1A0808", 1800);

        assertThat(fee.total()).isEqualTo(34500);
        assertThat(fee.totalAmount()).isEqualByComparingTo("34500");
        server.verify();
    }

    @Test
    @DisplayName("Tính phí địa chỉ 2 cấp mới: preview gửi to_ward_code + is_new_to_address và đọc total_fee")
    void previewFee_postsNewAddressRequest() {
        GhnCreateOrderRequest request = new GhnCreateOrderRequest();
        request.setClientOrderCode("BS261107000001");
        request.setToName("Nguyễn Văn A");
        request.setToPhone("0901234567");
        request.setToAddress("12 Lê Lợi, Phường Vũng Tàu, Hồ Chí Minh");
        request.setToWardCode("1003646");
        request.setToWardName("Phường Vũng Tàu");
        request.setToProvinceName("Hồ Chí Minh");
        request.setIsNewToAddress(true);
        request.setWeight(1800);

        server.expect(requestTo("http://ghn.test/v2/shipping-order/preview"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Token", "token-test"))
                .andExpect(header("ShopId", "123456"))
                .andExpect(jsonPath("$.to_ward_code").value("1003646"))
                .andExpect(jsonPath("$.to_province_name").value("Hồ Chí Minh"))
                .andExpect(jsonPath("$.is_new_to_address").value(true))
                .andRespond(withSuccess("""
                        {"code":200,"message":"Success",
                         "data":{"total_fee":25000,"expected_delivery_time":"2026-11-08T10:00:00Z"}}
                        """, MediaType.APPLICATION_JSON));

        GhnOrderResult result = client.previewFee(request);

        assertThat(result.totalFee()).isEqualTo(25000);
        assertThat(result.expectedDeliveryTime()).isEqualTo("2026-11-08T10:00:00Z");
        server.verify();
    }

    @Test
    @DisplayName("Tạo vận đơn: gửi nguyên thân request và đọc được mã vận đơn GHN trả về")
    void createOrder_returnsOrderCode() {
        server.expect(requestTo("http://ghn.test/v2/shipping-order/create"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.client_order_code").value("BS261016000001"))
                .andExpect(jsonPath("$.cod_amount").value(1490000))
                .andExpect(jsonPath("$.payment_type_id").value(2))
                .andRespond(withSuccess("""
                        {"code":200,"message":"Success",
                         "data":{"order_code":"GHN999888","total_fee":34500}}
                        """, MediaType.APPLICATION_JSON));

        GhnCreateOrderRequest request = new GhnCreateOrderRequest();
        request.setClientOrderCode("BS261016000001");
        request.setToName("Nguyễn Văn A");
        request.setToPhone("0987654321");
        request.setToAddress("123 Nguyễn Trãi");
        request.setToDistrictId(1454);
        request.setToWardCode("1A0808");
        request.setPaymentTypeId(2);
        request.setCodAmount(1490000L);

        GhnOrderResult result = client.createOrder(request);

        assertThat(result.orderCode()).isEqualTo("GHN999888");
        assertThat(result.totalFee()).isEqualTo(34500);
        server.verify();
    }

    @Test
    @DisplayName("Xem vận đơn: dùng GET + query order_code và quy đúng trạng thái GHN")
    void orderDetail_mapsStatus() {
        server.expect(requestTo("http://ghn.test/v2/shipping-order/detail?order_code=GHN999888"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Token", "token-test"))
                .andRespond(withSuccess("""
                        {"code":200,"message":"Success",
                         "data":{"order_code":"GHN999888","status":"delivered","total_fee":34500,
                                 "client_order_code":"BS261016000001"}}
                        """, MediaType.APPLICATION_JSON));

        GhnOrderDetail detail = client.orderDetail("GHN999888");

        assertThat(detail.orderCode()).isEqualTo("GHN999888");
        assertThat(detail.statusEnum()).isEqualTo(GhnStatus.DELIVERED);
        assertThat(detail.statusLabel()).isEqualTo("Đã giao hàng");
        assertThat(detail.clientOrderCode()).isEqualTo("BS261016000001");
        server.verify();
    }

    @Test
    @DisplayName("Xem vận đơn thiếu mã ⇒ báo lỗi rõ ràng, không gọi GHN")
    void orderDetail_requiresOrderCode() {
        assertThatThrownBy(() -> client.orderDetail("  "))
                .isInstanceOf(GhnApiException.class)
                .hasMessageContaining("Thiếu mã vận đơn");
        server.verify();
    }

    @Test
    @DisplayName("Huỷ vận đơn: gửi đúng order_codes + lý do và đọc kết quả result=true")
    void cancelOrder_sendsOrderCodesAndReason() {
        server.expect(requestTo("http://ghn.test/v2/switch-status/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Token", "token-test"))
                .andExpect(jsonPath("$.order_codes[0]").value("GHN999888"))
                .andExpect(jsonPath("$.reason_code").value("GHN-CO001"))
                .andExpect(jsonPath("$.reason").value("Khách đổi ý"))
                .andRespond(withSuccess("""
                        {"code":200,"message":"Success",
                         "data":[{"order_code":"GHN999888","result":true,"message":"Đã huỷ"}]}
                        """, MediaType.APPLICATION_JSON));

        GhnCancelResult result = client.cancelOrder("GHN999888", "Khách đổi ý", "GHN-CO001");

        assertThat(result.orderCode()).isEqualTo("GHN999888");
        assertThat(result.result()).isTrue();
        assertThat(result.message()).isEqualTo("Đã huỷ");
        server.verify();
    }

    @Test
    @DisplayName("In vận đơn: gọi /v2/a5/gen-token và trả token in")
    void printToken_returnsToken() {
        server.expect(requestTo("http://ghn.test/v2/a5/gen-token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.order_codes[0]").value("GHN999888"))
                .andRespond(withSuccess("""
                        {"code":200,"message":"Success","data":{"token":"print-token-123"}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.printToken(List.of("GHN999888")).token()).isEqualTo("print-token-123");
        server.verify();
    }

    @Test
    @DisplayName("Dịch vụ khả dụng: gửi đúng shop_id/from_district/to_district và đọc danh sách")
    void availableServices_mapsList() {
        server.expect(requestTo("http://ghn.test/v2/shipping-order/available-services"
                        + "?shop_id=123456&from_district=1442&to_district=1454"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":200,"message":"Success",
                         "data":[{"service_id":53320,"service_type_id":2,
                                  "short_name":"Chuẩn","service_name":"Chuyển phát chuẩn"}]}
                        """, MediaType.APPLICATION_JSON));

        List<GhnAvailableService> services = client.availableServices(1454);

        assertThat(services).hasSize(1);
        assertThat(services.get(0).serviceId()).isEqualTo(53320);
        assertThat(services.get(0).serviceName()).isEqualTo("Chuyển phát chuẩn");
        server.verify();
    }

    @Test
    @DisplayName("GHN từ chối (code khác 200) ⇒ GhnApiException kèm lý do GHN trả về")
    void rejectedRequest_throwsGhnApiExceptionWithReason() {
        server.expect(requestTo("http://ghn.test/master-data/province"))
                .andRespond(withSuccess("""
                        {"code":401,"message":"Token is not valid","data":null}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::provinces)
                .isInstanceOf(GhnApiException.class)
                .hasMessageContaining("Token is not valid");
    }

    @Test
    @DisplayName("Lỗi mạng/5xx ⇒ GhnApiException thông báo tiếng Việt, đánh dấu retryable")
    void serverError_throwsGhnApiException() {
        server.expect(requestTo("http://ghn.test/master-data/province"))
                .andRespond(withServerError());

        assertThatThrownBy(client::provinces)
                .isInstanceOfSatisfying(GhnApiException.class, ex -> {
                    assertThat(ex.getMessage()).contains("GHN từ chối");
                    assertThat(ex.getHttpStatus()).isEqualTo(500);
                    assertThat(ex.isRetryable()).isTrue();
                });
    }

    @Test
    @DisplayName("HTTP 401 ⇒ GhnApiException có httpStatus/code_message và KHÔNG retryable")
    void unauthorized_throwsNonRetryableWithDetails() {
        server.expect(requestTo("http://ghn.test/master-data/province"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":401,"message":"Token is not valid","code_message":"Token đã hết hạn"}
                                """));

        assertThatThrownBy(client::provinces)
                .isInstanceOfSatisfying(GhnApiException.class, ex -> {
                    assertThat(ex.getHttpStatus()).isEqualTo(401);
                    assertThat(ex.getCode()).isEqualTo(401);
                    assertThat(ex.getCodeMessage()).isEqualTo("Token đã hết hạn");
                    assertThat(ex.isRetryable()).isFalse();
                });
    }

    @Test
    @DisplayName("Chưa cấu hình token/shopId ⇒ báo rõ chưa bật GHN thay vì gọi API lỗi")
    void notConfigured_throwsGhnApiException() {
        GhnProperties disabled = new GhnProperties();
        GhnClient disabledClient = new GhnClientImpl(disabled, RestClient.builder().build());

        assertThat(disabledClient.isAddressAvailable()).isFalse();
        assertThat(disabledClient.isShippingAvailable()).isFalse();
        assertThatThrownBy(disabledClient::provinces)
                .isInstanceOf(GhnApiException.class)
                .hasMessageContaining("đang tắt");
    }

    @Test
    @DisplayName("Chỉ có token (chưa có shopId/kho) vẫn tra cứu được địa chỉ, nhưng không tính phí GHN")
    void tokenOnly_enablesAddressLookupNotShipping() {
        GhnProperties addressOnly = new GhnProperties();
        addressOnly.setEnabled(true);
        addressOnly.setMock(false);
        addressOnly.setToken("token-test");
        addressOnly.setBaseUrl("http://ghn.test");

        RestClient.Builder builder = RestClient.builder().baseUrl("http://ghn.test");
        MockRestServiceServer addressServer = MockRestServiceServer.bindTo(builder).build();
        GhnClient addressClient = new GhnClientImpl(addressOnly, builder.build());

        assertThat(addressClient.isAddressAvailable()).isTrue();
        assertThat(addressClient.isShippingAvailable()).isFalse();

        addressServer.expect(requestTo("http://ghn.test/master-data/province"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":200,"message":"Success",
                         "data":[{"ProvinceID":201,"ProvinceName":"Hà Nội"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(addressClient.provinces()).hasSize(1);
        assertThatThrownBy(() -> addressClient.fee(1454, "1A0808", 1500))
                .isInstanceOf(GhnApiException.class)
                .hasMessageContaining("shopId");
        addressServer.verify();
    }

    @Test
    @DisplayName("Chế độ ngoại tuyến: địa chỉ là dữ liệu GHN lưu sẵn ĐẦY ĐỦ (63 tỉnh), phí/vận đơn mới là số giả")
    void mockMode_servesBuiltInDataWithoutHttp() {
        GhnProperties mockProperties = new GhnProperties();
        mockProperties.setEnabled(true);
        mockProperties.setMock(true);

        GhnClient mockClient = new GhnClientImpl(mockProperties, RestClient.builder().build());

        assertThat(mockClient.isAddressAvailable()).isTrue();
        assertThat(mockClient.isShippingAvailable()).isTrue();
        assertThat(mockClient.isMock()).isTrue();
        assertThat(mockClient.provinces()).isNotEmpty();
        assertThat(mockClient.districts(mockClient.provinces().get(0).id())).isNotEmpty();
        // Toàn bộ dữ liệu: 63 tỉnh/thành, Hà Nội 30 quận/huyện, Hoàn Kiếm 18 phường/xã (mã thật của GHN)
        assertThat(mockClient.provinces()).hasSize(63);
        assertThat(mockClient.districts(201)).hasSize(30);
        assertThat(mockClient.wards(1489)).extracting(GhnWard::name).contains("Phường Phúc Tân");
        assertThat(mockClient.wards(1489)).extracting(GhnWard::code).contains("1A0216");
        assertThat(mockClient.fee(1454, "1A0808", 1500).total()).isPositive();
        assertThat(mockClient.createOrder(new GhnCreateOrderRequest()).orderCode()).startsWith("GHNMOCK");
        assertThat(mockClient.orderDetail("GHNMOCK1").orderCode()).isEqualTo("GHNMOCK1");
        server.verify();
    }
}
