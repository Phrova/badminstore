package com.example.demo.shipping.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * Thân request tạo vận đơn GHN (POST /v2/shipping-order/create).
 * Tên field giữ đúng snake_case mà GHN yêu cầu.
 *
 * Ngoài người nhận/hàng hoá, GHN khuyến nghị gửi đủ kho lấy hàng (from_*) và địa chỉ hoàn (return_*)
 * để đơn không phụ thuộc vào địa chỉ mặc định của tài khoản shop.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GhnCreateOrderRequest {

    @JsonProperty("payment_type_id")
    private int paymentTypeId = 1;

    @JsonProperty("required_note")
    private String requiredNote = "KHONGCHOXEMHANG";

    @JsonProperty("client_order_code")
    private String clientOrderCode;

    @JsonProperty("from_name")
    private String fromName;

    @JsonProperty("from_phone")
    private String fromPhone;

    @JsonProperty("from_address")
    private String fromAddress;

    @JsonProperty("from_ward_code")
    private String fromWardCode;

    @JsonProperty("from_district_id")
    private Integer fromDistrictId;

    @JsonProperty("return_phone")
    private String returnPhone;

    @JsonProperty("return_address")
    private String returnAddress;

    @JsonProperty("return_ward_code")
    private String returnWardCode;

    @JsonProperty("return_district_id")
    private Integer returnDistrictId;

    @JsonProperty("to_name")
    private String toName;

    @JsonProperty("to_phone")
    private String toPhone;

    @JsonProperty("to_address")
    private String toAddress;

    @JsonProperty("to_ward_code")
    private String toWardCode;

    @JsonProperty("to_district_id")
    private Integer toDistrictId;

    /** Tên địa danh theo GHN: bắt buộc với mô hình 2 cấp và nên gửi kèm cả mô hình 3 cấp. */
    @JsonProperty("to_ward_name")
    private String toWardName;

    @JsonProperty("to_district_name")
    private String toDistrictName;

    @JsonProperty("to_province_name")
    private String toProvinceName;

    /** true = địa chỉ hệ 2 cấp mới (Tỉnh → Phường/Xã), false/null = hệ 3 cấp cũ. */
    @JsonProperty("is_new_to_address")
    private Boolean isNewToAddress;

    @JsonProperty("cod_amount")
    private long codAmount;

    /** Giá trị hàng hoá khai báo (VND) – dùng cho bồi thường, không phải tiền thu hộ. */
    @JsonProperty("order_value")
    private long orderValue;

    private String content = "Vợt cầu lông";

    private int weight = 1500;

    private int length = 60;

    private int width = 20;

    private int height = 12;

    @JsonProperty("insurance_value")
    private long insuranceValue = 500000;

    @JsonProperty("service_type_id")
    private int serviceTypeId = 2;

    private String note;

    private List<GhnOrderItem> items = new ArrayList<>();

    /** Một dòng hàng khai báo với GHN. */
    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GhnOrderItem {

        private String name;

        private String code;

        private int quantity = 1;

        private long price;

        private int weight = 1500;

        private int length = 60;

        private int width = 20;

        private int height = 12;

        public GhnOrderItem(String name, String code, int quantity, long price, int weight) {
            this(name, code, quantity, price, weight, 60, 20, 12);
        }

        public GhnOrderItem(String name, String code, int quantity, long price, int weight,
                            int length, int width, int height) {
            this.name = name;
            this.code = code;
            this.quantity = quantity;
            this.price = price;
            this.weight = weight;
            this.length = length;
            this.width = width;
            this.height = height;
        }
    }
}
