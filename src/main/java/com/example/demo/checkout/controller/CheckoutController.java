package com.example.demo.checkout.controller;

import com.example.demo.cart.service.CartService;
import com.example.demo.checkout.dto.CheckoutForm;
import com.example.demo.checkout.dto.CheckoutSummary;
import com.example.demo.order.entity.Order;
import com.example.demo.user.entity.UserAddress;
import com.example.demo.payment.enums.PaymentMethod;
import com.example.demo.user.service.AddressService;
import com.example.demo.checkout.service.CheckoutService;
import com.example.demo.auth.service.CustomUserDetails;
import com.example.demo.shipping.service.GhnAddressService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Bước thanh toán: chọn địa chỉ + phương thức thanh toán + mã giảm giá, rồi đặt hàng.
 * Giá và tổng tiền luôn được CheckoutService tính lại từ DB.
 */
@Controller
@RequestMapping("/thanh-toan")
@RequiredArgsConstructor
public class CheckoutController {

    /** Đơn online chỉ dùng 2 phương thức này (CASH dành cho đơn admin tạo tại quầy). */
    private static final List<PaymentMethod> ONLINE_PAYMENT_METHODS =
            List.of(PaymentMethod.COD, PaymentMethod.BANK_TRANSFER);

    private final CheckoutService checkoutService;
    private final AddressService addressService;
    private final GhnAddressService ghnAddressService;
    private final CartService cartService;

    @GetMapping
    public String view(@AuthenticationPrincipal CustomUserDetails currentUser,
                       @RequestParam(name = "voucher", required = false) String voucherCode,
                       Model model,
                       RedirectAttributes redirectAttributes) {
        Integer userId = currentUser.getUserId();
        // Giỏ trống: chặn TRƯỚC khi tính voucher/phí GHN — không gọi API vô ích
        // và không có nguy cơ lỗi 500 trước khi kịp báo "giỏ trống".
        if (cartService.getCart(userId).isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Giỏ hàng của bạn đang trống nên chưa thể thanh toán.");
            return "redirect:/gio-hang";
        }
        UserAddress defaultAddress = checkoutService.defaultAddress(userId).orElse(null);
        Integer addressId = defaultAddress == null ? null : defaultAddress.getId();
        // Phí ship tính luôn theo địa chỉ mặc định để trang hiển thị đúng ngay từ lần render đầu.
        CheckoutSummary summary = checkoutService.preview(userId, voucherCode, addressId);

        model.addAttribute("summary", summary);
        model.addAttribute("addresses", addressService.list(userId));
        model.addAttribute("paymentMethods", ONLINE_PAYMENT_METHODS);
        prepareGhnModel(model, summary);

        if (!model.containsAttribute("form")) {
            CheckoutForm form = new CheckoutForm();
            if (defaultAddress != null) {
                form.setAddressId(defaultAddress.getId());
            }
            if (summary.voucher() != null) {
                form.setVoucherCode(summary.voucher().code());
            }
            model.addAttribute("form", form);
        }
        return "shop/checkout";
    }

    @PostMapping
    public String placeOrder(@AuthenticationPrincipal CustomUserDetails currentUser,
                             @Valid @ModelAttribute("form") CheckoutForm form,
                             BindingResult bindingResult,
                             Model model,
                             RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            // Chưa ghi CSDL: hiển thị lại trang kèm lỗi từng ô.
            Integer userId = currentUser.getUserId();
            CheckoutSummary summary = checkoutService.preview(userId, form.getVoucherCode(), form.getAddressId());
            model.addAttribute("summary", summary);
            model.addAttribute("addresses", addressService.list(userId));
            model.addAttribute("paymentMethods", ONLINE_PAYMENT_METHODS);
            prepareGhnModel(model, summary);
            return "shop/checkout";
        }

        Order order = checkoutService.placeOrder(currentUser.getUserId(), form);
        redirectAttributes.addFlashAttribute("successMessage",
                "Đặt hàng thành công! Mã đơn hàng của bạn là " + order.getOrderCode() + ".");
        return "redirect:/don-hang/" + order.getId();
    }

    /**
     * Thông tin GHN cho trang thanh toán: bật/tắt, khối lượng kiện hàng và tồn kho tạm tính
     * để JavaScript xem trước phí vận chuyển khi khách đổi địa chỉ (server vẫn là nơi tính phí thật).
     */
    private void prepareGhnModel(Model model, CheckoutSummary summary) {
        model.addAttribute("ghnEnabled", ghnAddressService.isAvailable());
        model.addAttribute("ghnShippingEnabled", ghnAddressService.isShippingAvailable());
        model.addAttribute("ghnMock", ghnAddressService.isMock());
        model.addAttribute("ghnNewAddress", ghnAddressService.isNewAddressModel());
        model.addAttribute("ghnWeightGram", ghnAddressService.shippingWeight(summary.getTotalQuantity()));
        model.addAttribute("ghnWarehouse", ghnAddressService.warehouseAddress());
        // Phí mặc định của shop (0 nếu đơn được miễn phí) – JavaScript dùng khi khách chọn địa chỉ
        // không có mã GHN, tách khỏi summary.shippingFee vì phí đó đã tính theo địa chỉ đang chọn.
        model.addAttribute("shopShippingFee", CheckoutService.shippingFeeFor(summary.getCart().getSubtotal()));
    }
}