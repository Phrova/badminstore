package com.example.demo.user.controller;

import com.example.demo.user.dto.AddressForm;
import com.example.demo.user.service.AddressService;
import com.example.demo.auth.service.CustomUserDetails;
import com.example.demo.shipping.service.GhnAddressService;
import jakarta.validation.Valid;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Địa chỉ giao hàng của khách. Mọi thao tác ghi theo Post-Redirect-Get + flash message.
 * Form thêm địa chỉ validate 2 lớp và hiển thị lỗi cạnh từng ô ngay tại chỗ.
 */
@Controller
@RequestMapping("/dia-chi")
@RequiredArgsConstructor
public class AddressController {

    /** Chỉ quay về các trang nội bộ này sau khi thao tác (chặn open-redirect). */
    private static final Set<String> ALLOWED_NEXT = Set.of("/dia-chi", "/thanh-toan");

    private final AddressService addressService;
    private final GhnAddressService ghnAddressService;

    @GetMapping
    public String list(@AuthenticationPrincipal CustomUserDetails currentUser, Model model) {
        model.addAttribute("addresses", addressService.list(currentUser.getUserId()));
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new AddressForm());
        }
        prepareGhnModel(model);
        return "shop/addresses";
    }

    @PostMapping("/them")
    public String create(@AuthenticationPrincipal CustomUserDetails currentUser,
                         @Valid @ModelAttribute("form") AddressForm form,
                         BindingResult bindingResult,
                         @RequestParam(name = "next", required = false) String next,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        rejectIncompleteGhnLocation(form, bindingResult);
        if (bindingResult.hasErrors()) {
            // Chưa ghi CSDL nên hiển thị lại form kèm lỗi, không cần redirect.
            model.addAttribute("addresses", addressService.list(currentUser.getUserId()));
            model.addAttribute("openForm", true);
            prepareGhnModel(model);
            return "shop/addresses";
        }

        addressService.create(currentUser.getUserId(), form);
        redirectAttributes.addFlashAttribute("successMessage",
                "Đã lưu địa chỉ của \"" + form.getReceiverName().trim() + "\".");
        return "redirect:" + safeNext(next);
    }

    @PostMapping("/mac-dinh")
    public String setDefault(@AuthenticationPrincipal CustomUserDetails currentUser,
                             @RequestParam("addressId") Integer addressId,
                             @RequestParam(name = "next", required = false) String next,
                             RedirectAttributes redirectAttributes) {
        addressService.setDefault(currentUser.getUserId(), addressId);
        redirectAttributes.addFlashAttribute("successMessage", "Đã đặt làm địa chỉ mặc định.");
        return "redirect:" + safeNext(next);
    }

    @PostMapping("/xoa")
    public String delete(@AuthenticationPrincipal CustomUserDetails currentUser,
                         @RequestParam("addressId") Integer addressId,
                         @RequestParam(name = "next", required = false) String next,
                         RedirectAttributes redirectAttributes) {
        addressService.delete(currentUser.getUserId(), addressId);
        redirectAttributes.addFlashAttribute("successMessage", "Đã xoá địa chỉ giao hàng.");
        return "redirect:" + safeNext(next);
    }

    /**
     * Dữ liệu cho khối địa chỉ: danh sách Tỉnh/Thành của GHN + các cờ bật/tắt.
     * `ghnReady` = GHN bật VÀ lấy được danh sách tỉnh/thành ⇒ form dùng dropdown Tỉnh–Huyện–Xã
     * của GHN; ngược lại (GHN tắt hoặc API đang lỗi) khách nhập địa chỉ bằng tay để không bị
     * chặn thêm địa chỉ/đặt hàng.
     */
    private void prepareGhnModel(Model model) {
        model.addAttribute("ghnProvinces", ghnAddressService.provinces());
        model.addAttribute("ghnEnabled", ghnAddressService.isAvailable());
        model.addAttribute("ghnReady", isGhnReady());
        model.addAttribute("ghnShippingEnabled", ghnAddressService.isShippingAvailable());
        model.addAttribute("ghnMock", ghnAddressService.isMock());
        model.addAttribute("ghnNewAddress", ghnAddressService.isNewAddressModel());
    }

    /** GHN bật và trả về được danh sách tỉnh/thành (dữ liệu ở server đã có cache nên gọi lại rất nhẹ). */
    private boolean isGhnReady() {
        return ghnAddressService.isAvailable() && !ghnAddressService.provinces().isEmpty();
    }

    /**
     * Đang dùng được dữ liệu GHN thì địa chỉ phải chọn đủ địa danh từ dropdown GHN:
     *  – mô hình 3 cấp: Tỉnh → Quận/Huyện → Phường/Xã;
     *  – mô hình 2 cấp mới: Tỉnh → Phường/Xã (bỏ cấp quận/huyện).
     * Đây là lỗi của tầng form nên báo ngay cạnh ô chọn; mã còn được {@link AddressService} kiểm tra
     * lại lần nữa (có tồn tại thật + khớp tên địa danh) trước khi ghi CSDL.
     */
    private void rejectIncompleteGhnLocation(AddressForm form, BindingResult bindingResult) {
        if (!isGhnReady()) {
            return;
        }
        if (form.getProvinceId() == null) {
            bindingResult.rejectValue("provinceId", "ghn.province", "Vui lòng chọn tỉnh/thành phố.");
        }
        if (!ghnAddressService.isNewAddressModel() && form.getDistrictId() == null) {
            bindingResult.rejectValue("districtId", "ghn.district", "Vui lòng chọn quận/huyện.");
        }
        if (!StringUtils.hasText(form.getWardCode())) {
            bindingResult.rejectValue("wardCode", "ghn.ward", "Vui lòng chọn phường/xã.");
        }
    }

    private String safeNext(String next) {
        return (next != null && ALLOWED_NEXT.contains(next)) ? next : "/dia-chi";
    }
}
