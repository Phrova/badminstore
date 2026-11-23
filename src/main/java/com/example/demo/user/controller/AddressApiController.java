package com.example.demo.user.controller;

import com.example.demo.shipping.dto.GhnProvince;
import com.example.demo.shipping.dto.GhnDistrict;
import com.example.demo.shipping.dto.GhnWard;
import com.example.demo.shipping.service.GhnAddressService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.example.demo.common.exception.BusinessException;

/**
 * API nội bộ cho giao diện lấy dữ liệu địa chỉ GHN và phí vận chuyển tạm tính.
 *
 * Vì dữ liệu địa chỉ của GHN được lấy ở SERVER (có token, có cache) rồi mới trả cho trình duyệt,
 * token GHN không bao giờ lộ ra ngoài. GHN tắt/lỗi thì các API này trả `enabled: false` + danh sách rỗng
 * để giao diện tự chuyển sang nhập địa chỉ bằng tay.
 *
 * Hỗ trợ cả 2 mô hình:
 *  – 3 cấp cũ: /huyen?provinceId=..., /xa?districtId=...;
 *  – 2 cấp mới: /xa?provinceId=... (bỏ cấp quận/huyện).
 */
@RestController
@RequestMapping("/api/ghn")
@RequiredArgsConstructor
public class AddressApiController {

    private final GhnAddressService ghnAddressService;

    @GetMapping("/tinh")
    public Map<String, Object> provinces() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (GhnProvince province : ghnAddressService.provinces()) {
            items.add(option(province.id(), province.name()));
        }
        return payload(items);
    }

    @GetMapping("/huyen")
    public Map<String, Object> districts(@RequestParam(name = "provinceId", required = false) Integer provinceId) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (GhnDistrict district : ghnAddressService.districts(provinceId)) {
            items.add(option(district.id(), district.name()));
        }
        return payload(items);
    }

    @GetMapping("/xa")
    public Map<String, Object> wards(@RequestParam(name = "districtId", required = false) Integer districtId,
                                     @RequestParam(name = "provinceId", required = false) Integer provinceId) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (GhnWard ward : ghnAddressService.isNewAddressModel()
                ? ghnAddressService.wardsByProvince(provinceId)
                : ghnAddressService.wards(districtId)) {
            items.add(option(ward.code(), ward.name()));
        }
        return payload(items);
    }

    /** Phí vận chuyển tạm tính cho khách xem trước khi đặt hàng (server vẫn tính lại khi lưu đơn). */
    @GetMapping("/phi-ship")
    public Map<String, Object> fee(@RequestParam(name = "districtId", required = false) Integer districtId,
                                   @RequestParam(name = "provinceId", required = false) Integer provinceId,
                                   @RequestParam(name = "wardCode", required = false) String wardCode,
                                   @RequestParam(name = "provinceName", required = false) String provinceName,
                                   @RequestParam(name = "wardName", required = false) String wardName,
                                   @RequestParam(name = "addressDetail", required = false) String addressDetail,
                                   @RequestParam(name = "receiverName", required = false) String receiverName,
                                   @RequestParam(name = "receiverPhone", required = false) String receiverPhone,
                                   @RequestParam(name = "quantity", defaultValue = "1") int quantity,
                                   @RequestParam(name = "weight", required = false) Integer weight) {
        // Kiểm tra tham số TRƯỚC khi tính khối lượng: mọi giá trị ở đây do client gửi lên,
        // không được dùng để tính toán hay làm khoá cache trước khi đã chặn giá trị vô lý.
        if (quantity < 1 || quantity > 200) {
            throw new BusinessException("Số lượng không hợp lệ.");
        }
        if (weight != null && (weight < 1 || weight > 100_000)) {
            throw new BusinessException("Khối lượng không hợp lệ.");
        }
        if (districtId != null && districtId <= 0) {
            throw new BusinessException("Mã quận/huyện không hợp lệ.");
        }
        if (provinceId != null && provinceId <= 0) {
            throw new BusinessException("Mã tỉnh/thành không hợp lệ.");
        }
        // wardCode nằm trong khoá cache phí: giới hạn ký tự + độ dài để không ai
        // tạo vô hạn khoá cache khác nhau bằng cách đổi chuỗi tuỳ ý.
        String ward = trimToNull(wardCode);
        if (ward != null && !ward.matches("[A-Za-z0-9]{1,20}")) {
            throw new BusinessException("Mã phường/xã không hợp lệ.");
        }
        requireMaxLength(provinceName, 100, "Tên tỉnh/thành");
        requireMaxLength(wardName, 100, "Tên phường/xã");
        requireMaxLength(addressDetail, 255, "Địa chỉ chi tiết");
        requireMaxLength(receiverName, 100, "Tên người nhận");
        requireMaxLength(receiverPhone, 20, "Số điện thoại");

        int weightGram = weight == null ? ghnAddressService.shippingWeight(quantity) : weight;
        BigDecimal fee;
        if (districtId != null) {
            // Địa chỉ cũ vẫn dùng API tính phí theo quận/huyện.
            fee = ghnAddressService.feeOrNull(districtId, ward, weightGram);
        } else if (StringUtils.hasText(provinceName) && StringUtils.hasText(wardName)) {
            // Địa chỉ 2 cấp mới dùng preview đơn vì API fee cũ không nhận province/ward mới.
            fee = ghnAddressService.feeOrNull(provinceId, provinceName, ward, wardName,
                    addressDetail, receiverName, receiverPhone, weightGram);
        } else {
            fee = null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", ghnAddressService.isAvailable());
        result.put("shippingEnabled", ghnAddressService.isShippingAvailable());
        result.put("mock", ghnAddressService.isMock());
        result.put("newAddressModel", ghnAddressService.isNewAddressModel());
        result.put("fee", fee);
        result.put("weightGram", weightGram);
        result.put("warehouse", ghnAddressService.warehouseAddress());
        return result;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Giới hạn độ dài chuỗi client gửi kèm (khớp @Size trong AddressForm). */
    private void requireMaxLength(String value, int max, String label) {
        if (value != null && value.length() > max) {
            throw new BusinessException(label + " tối đa " + max + " ký tự.");
        }
    }

    private Map<String, Object> option(Object id, String name) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("id", id);
        option.put("name", name);
        return option;
    }

    private Map<String, Object> payload(List<Map<String, Object>> items) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", ghnAddressService.isAvailable());
        result.put("shippingEnabled", ghnAddressService.isShippingAvailable());
        result.put("mock", ghnAddressService.isMock());
        result.put("newAddressModel", ghnAddressService.isNewAddressModel());
        result.put("items", items);
        return result;
    }
}
