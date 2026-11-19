package com.example.demo.shipping.service;

import com.example.demo.shipping.dto.GhnDistrict;
import com.example.demo.shipping.dto.GhnProvince;
import com.example.demo.shipping.dto.GhnWard;
import com.example.demo.user.entity.UserAddress;
import java.math.BigDecimal;
import java.util.List;

/**
 * Bọc {@link GhnClient} thành API "an toàn cho giao diện": mọi lỗi mạng/GHN đều bị nuốt và
 * trả về kết quả rỗng, nhờ đó trang bán hàng KHÔNG bao giờ trắng trang vì GHN lỗi –
 * khách chỉ thấy dropdown trống và vẫn nhập địa chỉ bằng tay.
 */
public interface GhnAddressService {

    public static final int EXTRA_WEIGHT_PER_ITEM = 300;

    boolean isEnabled();

    boolean isMock();

    boolean isNewAddressModel();

    boolean isProduction();

    String getEnvironmentLabel();

    boolean isAvailable();

    boolean isShippingAvailable();

    String warehouseAddress();

    List<GhnProvince> provinces();

    List<GhnDistrict> districts(Integer provinceId);

    List<GhnWard> wards(Integer districtId);

    List<GhnWard> wardsByProvince(Integer provinceId);

    int shippingWeight(int totalQuantity);

    BigDecimal feeOrNull(Integer districtId, String wardCode, int weightGram);

    BigDecimal feeOrNull(Integer provinceId, String provinceName, String wardCode, String wardName, String addressDetail, String receiverName, String receiverPhone, int weightGram);

    BigDecimal feeOrNull(UserAddress address, int weightGram);
}
