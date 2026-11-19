package com.example.demo.shipping.service.impl;

import com.example.demo.shipping.config.GhnProperties;
import com.example.demo.shipping.dto.GhnApiException;
import com.example.demo.shipping.dto.GhnCreateOrderRequest;
import com.example.demo.shipping.dto.GhnDistrict;
import com.example.demo.shipping.dto.GhnProvince;
import com.example.demo.shipping.dto.GhnWard;
import com.example.demo.user.entity.UserAddress;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.example.demo.shipping.service.GhnAddressService;
import com.example.demo.shipping.service.GhnClient;

/**
 * Bọc {@link GhnClient} thành API "an toàn cho giao diện": mọi lỗi mạng/GHN đều bị nuốt và
 * trả về kết quả rỗng, nhờ đó trang bán hàng KHÔNG bao giờ trắng trang vì GHN lỗi –
 * khách chỉ thấy dropdown trống và vẫn nhập địa chỉ bằng tay.
 */
@Service
@RequiredArgsConstructor
public class GhnAddressServiceImpl implements GhnAddressService {

    private static final Logger log = LoggerFactory.getLogger(GhnAddressServiceImpl.class);

    private final GhnClient ghnClient;
    private final GhnProperties ghnProperties;

    @Override
    public boolean isEnabled() {
        return ghnClient.isEnabled();
    }

    /** Đang gọi API GHN thật hay dùng dữ liệu địa chỉ lưu sẵn (để hiện nhãn trên giao diện). */
    @Override
    public boolean isMock() {
        return ghnClient.isMock();
    }

    /** Có đang dùng mô hình 2 cấp mới (Tỉnh → Phường/Xã) hay không. */
    @Override
    public boolean isNewAddressModel() {
        return ghnClient.isNewAddressModel();
    }

    /** Có đang trỏ tới GHN Production không (badge cảnh báo). */
    @Override
    public boolean isProduction() {
        return ghnClient.isProduction();
    }

    /** "STAGING"/"PRODUCTION" để hiển thị badge. */
    @Override
    public String getEnvironmentLabel() {
        return ghnClient.getEnvironmentLabel();
    }

    /** Tra cứu được địa chỉ (chỉ cần token GHN). */
    @Override
    public boolean isAvailable() {
        return ghnClient.isAddressAvailable();
    }

    /** Tính được phí/tạo được vận đơn (cần thêm shopId). */
    @Override
    public boolean isShippingAvailable() {
        return ghnClient.isShippingAvailable();
    }

    /** Kho lấy hàng đã khai báo (hiện trên giao diện để khách biết hàng đi từ đâu). */
    @Override
    public String warehouseAddress() {
        return ghnProperties.getFromAddress();
    }

    /**
     * Tỉnh/thành cho dropdown. Mô hình 2 cấp dùng endpoint v3 và trả về cùng cấu trúc
     * {@link GhnProvince#id()}/{@link GhnProvince#name()} để template không phải rẽ nhánh.
     */
    @Override
    public List<GhnProvince> provinces() {
        try {
            if (!ghnClient.isAddressAvailable()) {
                return List.of();
            }
            if (ghnClient.isNewAddressModel()) {
                return ghnClient.newProvinces().stream()
                        .map(province -> new GhnProvince(province.id(), province.name()))
                        .toList();
            }
            return ghnClient.provinces();
        } catch (GhnApiException ex) {
            log.warn("Không lấy được tỉnh/thành GHN: {}", ex.getMessage());
            return List.of();
        }
    }

    /** Quận/huyện chỉ có ở mô hình 3 cấp cũ; mô hình 2 cấp trả danh sách rỗng. */
    @Override
    public List<GhnDistrict> districts(Integer provinceId) {
        if (provinceId == null || ghnClient.isNewAddressModel()) {
            return List.of();
        }
        try {
            return ghnClient.isAddressAvailable() ? ghnClient.districts(provinceId) : List.of();
        } catch (GhnApiException ex) {
            log.warn("Không lấy được quận/huyện GHN: {}", ex.getMessage());
            return List.of();
        }
    }

    /** Phường/xã theo quận (mô hình 3 cấp cũ). */
    @Override
    public List<GhnWard> wards(Integer districtId) {
        if (districtId == null || ghnClient.isNewAddressModel()) {
            return List.of();
        }
        try {
            return ghnClient.isAddressAvailable() ? ghnClient.wards(districtId) : List.of();
        } catch (GhnApiException ex) {
            log.warn("Không lấy được phường/xã GHN: {}", ex.getMessage());
            return List.of();
        }
    }

    /** Phường/xã thuộc thẳng tỉnh (mô hình 2 cấp mới). */
    @Override
    public List<GhnWard> wardsByProvince(Integer provinceId) {
        if (provinceId == null || !ghnClient.isNewAddressModel()) {
            return List.of();
        }
        try {
            if (!ghnClient.isAddressAvailable()) {
                return List.of();
            }
            return ghnClient.newWards(provinceId).stream()
                    .map(ward -> new GhnWard(String.valueOf(ward.id()), 0, ward.name()))
                    .toList();
        } catch (GhnApiException ex) {
            log.warn("Không lấy được phường/xã GHN (2 cấp): {}", ex.getMessage());
            return List.of();
        }
    }

    /** Khối lượng tính phí theo số sản phẩm: 1 cây ≈ 1,5 kg, mỗi cây thêm 0,3 kg. */
    @Override
    public int shippingWeight(int totalQuantity) {
        int base = ghnProperties.getWeightGram();
        int extra = Math.max(0, totalQuantity - 1);
        return base + extra * EXTRA_WEIGHT_PER_ITEM;
    }

    /**
     * Phí vận chuyển GHN cho một quận. Trả về {@code null} khi GHN chưa bật/lỗi
     * để nơi gọi tự dùng phí mặc định của shop.
     */
    @Override
    public BigDecimal feeOrNull(Integer districtId, String wardCode, int weightGram) {
        if (districtId == null || !ghnClient.isShippingAvailable()) {
            return null;
        }
        try {
            long total = ghnClient.fee(districtId, wardCode, weightGram).total();
            return total <= 0 ? null : BigDecimal.valueOf(total);
        } catch (GhnApiException ex) {
            log.warn("Không tính được phí GHN cho quận {}: {}", districtId, ex.getMessage());
            return null;
        }
    }

    /**
     * Phí GHN cho địa chỉ 2 cấp mới. API tính phí cũ không nhận province/ward mới nên phải dùng
     * /v2/shipping-order/preview (cùng request với tạo đơn, không tạo vận đơn thật).
     */
    @Override
    public BigDecimal feeOrNull(Integer provinceId, String provinceName, String wardCode, String wardName,
                                String addressDetail, String receiverName, String receiverPhone, int weightGram) {
        if (provinceId == null || !StringUtils.hasText(wardCode)
                || !StringUtils.hasText(provinceName) || !StringUtils.hasText(wardName)
                || !ghnClient.isNewAddressModel() || !ghnClient.isShippingAvailable()) {
            return null;
        }
        try {
            GhnCreateOrderRequest request = new GhnCreateOrderRequest();
            request.setToName(StringUtils.hasText(receiverName) ? receiverName.trim() : "Khách BadStore");
            request.setToPhone(StringUtils.hasText(receiverPhone) ? receiverPhone.trim() : "0900000000");
            request.setToAddress(buildFullAddress(addressDetail, wardName, provinceName));
            request.setToWardCode(wardCode.trim());
            request.setToWardName(wardName.trim());
            request.setToProvinceName(provinceName.trim());
            request.setIsNewToAddress(true);
            // Gửi kèm kho lấy hàng/địa chỉ hoàn để GHN không phụ thuộc địa chỉ mặc định của shop.
            request.setFromName(ghnProperties.getFromName());
            request.setFromPhone(ghnProperties.getFromPhone());
            request.setFromAddress(ghnProperties.getFromAddress());
            request.setFromWardCode(ghnProperties.getFromWardCode());
            request.setFromDistrictId(ghnProperties.getFromDistrictId());
            request.setReturnPhone(ghnProperties.getFromPhone());
            request.setReturnAddress(ghnProperties.getFromAddress());
            request.setReturnWardCode(ghnProperties.getFromWardCode());
            request.setReturnDistrictId(ghnProperties.getFromDistrictId());
            request.setWeight(weightGram);
            request.setLength(ghnProperties.getLengthCm());
            request.setWidth(ghnProperties.getWidthCm());
            request.setHeight(ghnProperties.getHeightCm());
            request.setInsuranceValue(ghnProperties.getInsuranceValue());
            request.setServiceTypeId(ghnProperties.getServiceTypeId());
            request.setPaymentTypeId(1);
            request.setContent("Vợt cầu lông BadStore");
            long total = ghnClient.previewFee(request).totalFee();
            return total <= 0 ? null : BigDecimal.valueOf(total);
        } catch (GhnApiException ex) {
            log.warn("Không tính được phí GHN cho tỉnh {} / phường {}: {}", provinceId, wardCode, ex.getMessage());
            return null;
        }
    }

    /** Phí GHN cho một địa chỉ đã lưu: địa chỉ cũ dùng district, địa chỉ 2 cấp dùng preview. */
    @Override
    public BigDecimal feeOrNull(UserAddress address, int weightGram) {
        if (address == null) {
            return null;
        }
        if (address.getDistrictId() != null) {
            return feeOrNull(address.getDistrictId(), address.getWardCode(), weightGram);
        }
        return feeOrNull(address.getProvinceId(), address.getProvinceName(), address.getWardCode(),
                address.getWardName(), address.getAddressDetail(), address.getReceiverName(),
                address.getReceiverPhone(), weightGram);
    }

    private String buildFullAddress(String addressDetail, String wardName, String provinceName) {
        StringBuilder full = new StringBuilder();
        if (StringUtils.hasText(addressDetail)) {
            full.append(addressDetail.trim());
        }
        if (StringUtils.hasText(wardName)) {
            appendPart(full, wardName);
        }
        if (StringUtils.hasText(provinceName)) {
            appendPart(full, provinceName);
        }
        return full.toString();
    }

    private void appendPart(StringBuilder full, String value) {
        if (!full.isEmpty()) {
            full.append(", ");
        }
        full.append(value.trim());
    }
}
