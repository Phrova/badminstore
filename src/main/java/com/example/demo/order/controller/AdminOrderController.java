package com.example.demo.order.controller;

import com.example.demo.order.dto.ManualOrderForm;
import com.example.demo.shipping.dto.GhnSyncResult;
import com.example.demo.shipping.dto.GhnApiException;
import com.example.demo.shipping.dto.GhnCancelResult;
import com.example.demo.shipping.dto.GhnOrderResult;
import com.example.demo.order.dto.OrderDetailView;
import com.example.demo.order.dto.OrderSummaryView;
import com.example.demo.order.entity.Order;
import com.example.demo.order.entity.OrderItem;
import com.example.demo.catalog.entity.ProductVariant;
import com.example.demo.user.entity.User;
import com.example.demo.order.enums.OrderStatus;
import com.example.demo.payment.enums.PaymentMethod;
import com.example.demo.payment.enums.PaymentStatus;
import com.example.demo.user.enums.UserRole;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.NotFoundException;
import com.example.demo.catalog.repository.ProductVariantRepository;
import com.example.demo.user.repository.UserRepository;
import com.example.demo.auth.service.CustomUserDetails;
import com.example.demo.admin.service.ExcelReportService;
import com.example.demo.shipping.service.GhnAddressService;
import com.example.demo.shipping.service.GhnShippingService;
import com.example.demo.order.service.OrderService;
import com.example.demo.payment.service.QrPaymentService;
import com.example.demo.common.util.ExcelResponse;
import com.example.demo.common.util.Money;
import com.example.demo.common.util.Pagination;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Quản lý đơn hàng (admin): danh sách có lọc trạng thái + tìm kiếm, chi tiết có timeline,
 * đổi trạng thái theo máy trạng thái, xác nhận thanh toán, và nút xác nhận nhanh ở danh sách.
 *
 * Mọi thao tác ghi đều là POST + xác nhận SweetAlert2 + flash message + redirect (Post-Redirect-Get).
 * Nghiệp vụ (trừ/cộng kho, voucher, payment) nằm ở OrderService – controller chỉ điều hướng.
 */
@Controller
@RequestMapping("/admin/don-hang")
@RequiredArgsConstructor
public class AdminOrderController {

    private static final int PAGE_LINK_WINDOW = 5;

    /** Giới hạn số gợi ý hiển thị trong datalist của form tạo đơn thủ công. */
    private static final int MAX_SUGGESTIONS = 100;

    private final OrderService orderService;
    private final ProductVariantRepository productVariantRepository;
    private final UserRepository userRepository;
    private final GhnShippingService ghnShippingService;
    private final GhnAddressService ghnAddressService;
    private final QrPaymentService qrPaymentService;
    private final ExcelReportService excelReportService;

    @GetMapping
    public String list(@RequestParam(name = "status", required = false) OrderStatus status,
                       @RequestParam(name = "keyword", required = false) String keyword,
                       @RequestParam(name = "page", defaultValue = "1") int page,
                       Model model) {
        Page<OrderSummaryView> orders = orderService.searchForAdmin(status, keyword, page);
        if (orders.getTotalPages() > 0 && page > orders.getTotalPages()) {
            orders = orderService.searchForAdmin(status, keyword, orders.getTotalPages());
        }

        model.addAttribute("orders", orders);
        model.addAttribute("statuses", OrderStatus.values());
        model.addAttribute("statusFilter", status);
        model.addAttribute("keyword", keyword == null ? "" : keyword);
        model.addAttribute("pageNumbers", pageNumbers(orders));
        model.addAttribute("adminMenu", "orders");
        return "admin/orders";
    }

    /** Xuất toàn bộ đơn khớp bộ lọc hiện tại ra Excel (không chỉ trang đang xem). */
    @GetMapping("/xuat-excel")
    public ResponseEntity<byte[]> exportExcel(
            @RequestParam(name = "status", required = false) OrderStatus status,
            @RequestParam(name = "keyword", required = false) String keyword) {
        List<OrderSummaryView> orders = orderService.searchAllForAdmin(status, keyword);
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        String filter = "Trạng thái: " + (status == null ? "Tất cả" : status.getLabel());
        if (!normalizedKeyword.isEmpty()) {
            filter += " · Từ khoá: " + normalizedKeyword;
        }
        byte[] content = excelReportService.exportOrders(orders, filter);
        return ExcelResponse.attachment(content, excelReportService.fileName("don-hang"));
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable("id") Integer id, Model model) {
        var detail = orderService.adminOrderDetail(id);
        model.addAttribute("detail", detail);
        qrPaymentService.forOrder(detail).ifPresent(qr -> model.addAttribute("paymentQr", qr));
        // Chỉ hiện các trạng thái mà máy trạng thái cho phép chuyển tới từ trạng thái hiện tại
        model.addAttribute("nextStatuses", detail.getOrder().getOrderStatus().nextStatuses());
        model.addAttribute("ghnEnabled", ghnAddressService.isAvailable());
        model.addAttribute("ghnShippingEnabled", ghnAddressService.isShippingAvailable());
        model.addAttribute("ghnMock", ghnAddressService.isMock());
        model.addAttribute("ghnProduction", ghnAddressService.isProduction());
        model.addAttribute("ghnEnvironment", ghnAddressService.getEnvironmentLabel());
        model.addAttribute("ghnFeeEstimate", ghnShippingService.estimatedFee(detail.getOrder()));
        model.addAttribute("ghnWeightGram", ghnShippingService.weightOf(detail.getOrder()));
        // Chỉ đổi được sản phẩm khi đơn chưa trừ kho và chưa ghi nhận thanh toán
        model.addAttribute("canSwapItems", canSwapItems(detail));
        model.addAttribute("hasShortage", hasShortage(detail));
        model.addAttribute("adminMenu", "orders");
        return "admin/order-detail";
    }

    /** Điều kiện hiển thị nút đổi sản phẩm – khớp với kiểm tra trong OrderService.replaceItem. */
    private boolean canSwapItems(OrderDetailView detail) {
        if (detail.getOrder().getOrderStatus() != OrderStatus.PENDING) {
            return false;
        }
        return detail.getPaymentStatus() == null || detail.getPaymentStatus() == PaymentStatus.PENDING;
    }

    /** Đơn PENDING có ít nhất một dòng vượt tồn kho – cần admin đổi sản phẩm trước khi xác nhận. */
    private boolean hasShortage(OrderDetailView detail) {
        return detail.getOrder().getOrderStatus() == OrderStatus.PENDING
                && detail.getOrder().getItems().stream()
                        .anyMatch(item -> item.getProductVariant().getStockQuantity() < item.getQuantity());
    }

    // ==================== ĐỔI SẢN PHẨM TRONG ĐƠN PENDING ====================

    /**
     * Màn hình chọn sản phẩm thay thế cho một dòng hàng (đơn còn PENDING).
     * Danh sách ứng viên lấy từ các biến thể đang bán, loại biến thể hiện tại;
     * biến thể không đủ tồn kho vẫn hiện nhưng bị khoá để admin thấy rõ lý do.
     */
    @GetMapping("/{id}/doi-san-pham/{itemId}")
    public String swapForm(@PathVariable("id") Integer id,
                           @PathVariable("itemId") Integer itemId,
                           @RequestParam(name = "keyword", required = false) String keyword,
                           Model model,
                           RedirectAttributes redirectAttributes) {
        OrderDetailView detail = orderService.adminOrderDetail(id);
        if (!canSwapItems(detail)) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Chỉ đổi được sản phẩm khi đơn đang chờ xác nhận và chưa ghi nhận thanh toán.");
            return "redirect:/admin/don-hang/" + id;
        }

        OrderItem item = detail.getOrder().getItems().stream()
                .filter(line -> line.getId().equals(itemId))
                .findFirst()
                .orElse(null);
        if (item == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không tìm thấy dòng hàng cần đổi trong đơn.");
            return "redirect:/admin/don-hang/" + id;
        }

        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        List<ProductVariant> candidates = productVariantRepository
                .searchActiveForSale(normalizedKeyword, PageRequest.of(0, MAX_SUGGESTIONS))
                .stream()
                .filter(variant -> !variant.getId().equals(item.getProductVariant().getId()))
                .toList();

        model.addAttribute("detail", detail);
        model.addAttribute("item", item);
        model.addAttribute("keyword", normalizedKeyword);
        model.addAttribute("candidates", candidates);
        model.addAttribute("adminMenu", "orders");
        return "admin/order-item-swap";
    }

    /** Thực hiện đổi dòng hàng sang biến thể đã chọn; mọi kiểm tra tiền/kho nằm trong service. */
    @PostMapping("/{id}/doi-san-pham/{itemId}")
    public String swapItem(@PathVariable("id") Integer id,
                           @PathVariable("itemId") Integer itemId,
                           @RequestParam(name = "variantId", required = false) Integer variantId,
                           RedirectAttributes redirectAttributes) {
        String swapUrl = "/admin/don-hang/" + id + "/doi-san-pham/" + itemId;
        if (variantId == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "Vui lòng chọn sản phẩm thay thế.");
            return "redirect:" + swapUrl;
        }
        try {
            orderService.replaceItem(id, itemId, variantId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đã đổi sản phẩm trong đơn. Tổng tiền được tính lại theo giá hiện tại.");
            return "redirect:/admin/don-hang/" + id;
        } catch (BusinessException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:" + swapUrl;
        } catch (NotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/don-hang/" + id;
        }
    }

    /** Đẩy đơn sang GHN để lấy mã vận đơn (đơn phải đã xác nhận – đã trừ kho). */
    @PostMapping("/{id}/ghn/tao-van-don")
    public String pushToGhn(@PathVariable("id") Integer id, RedirectAttributes redirectAttributes) {
        try {
            GhnOrderResult result = ghnShippingService.pushOrder(id);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đã tạo vận đơn GHN " + result.orderCode() + ", phí vận chuyển "
                            + Money.format(BigDecimal.valueOf(result.totalFee())) + ".");
        } catch (BusinessException | GhnApiException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không tạo được vận đơn GHN: " + ex.getMessage());
        }
        return "redirect:/admin/don-hang/" + id;
    }

    /** Kéo trạng thái vận đơn từ GHN về đơn của shop (giao xong ⇒ DELIVERED, hoàn hàng ⇒ RETURNED/CANCELLED). */
    @PostMapping("/{id}/ghn/dong-bo")
    public String syncGhn(@PathVariable("id") Integer id,
                          @AuthenticationPrincipal CustomUserDetails admin,
                          RedirectAttributes redirectAttributes) {
        try {
            GhnSyncResult result = ghnShippingService.syncStatus(id, admin.getUserId());
            if (result.changed()) {
                redirectAttributes.addFlashAttribute("successMessage", result.message());
            } else {
                redirectAttributes.addFlashAttribute("successMessage", "Đã kiểm tra GHN: " + result.message());
            }
        } catch (BusinessException | GhnApiException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không đồng bộ được trạng thái GHN: "
                    + ex.getMessage());
        }
        return "redirect:/admin/don-hang/" + id;
    }

    /** Huỷ vận đơn GHN; chỉ khi GHN xác nhận thành công mới huỷ đơn shop và cộng lại kho. */
    @PostMapping("/{id}/ghn/huy-van-don")
    public String cancelGhn(@PathVariable("id") Integer id,
                            @RequestParam(name = "reason", required = false) String reason,
                            @RequestParam(name = "reasonCode", required = false) String reasonCode,
                            @AuthenticationPrincipal CustomUserDetails admin,
                            RedirectAttributes redirectAttributes) {
        try {
            GhnCancelResult result = ghnShippingService.cancelOrder(id, reason, reasonCode, admin.getUserId());
            if (result.result()) {
                redirectAttributes.addFlashAttribute("successMessage",
                        "Đã huỷ vận đơn GHN " + result.orderCode() + ". Đơn hàng đã được huỷ và cộng lại kho.");
            } else {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "GHN chưa huỷ được vận đơn: "
                                + (result.message() == null ? "không rõ lý do" : result.message()));
            }
        } catch (BusinessException | GhnApiException ex) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Không huỷ được vận đơn GHN: " + ex.getMessage());
        }
        return "redirect:/admin/don-hang/" + id;
    }

    /** Xác nhận nhanh từ danh sách: PENDING → CONFIRMED (kèm trừ kho). */
    @PostMapping("/{id}/xac-nhan")
    public String quickConfirm(@PathVariable("id") Integer id,
                               @AuthenticationPrincipal CustomUserDetails admin,
                               RedirectAttributes redirectAttributes) {
        orderService.changeStatusByAdmin(id, OrderStatus.CONFIRMED, admin.getUserId(),
                "Admin xác nhận đơn từ danh sách");
        redirectAttributes.addFlashAttribute("successMessage",
                "Đã xác nhận đơn và trừ kho. Đơn chuyển sang trạng thái \"Đã xác nhận\".");
        return "redirect:/admin/don-hang/" + id;
    }

    /** Đổi trạng thái đơn (xác nhận / giao hàng / giao thành công / huỷ / hoàn trả). */
    @PostMapping("/{id}/trang-thai")
    public String changeStatus(@PathVariable("id") Integer id,
                               @RequestParam("target") OrderStatus target,
                               @RequestParam(name = "note", required = false) String note,
                               @AuthenticationPrincipal CustomUserDetails admin,
                               RedirectAttributes redirectAttributes) {
        orderService.changeStatusByAdmin(id, target, admin.getUserId(), note);
        redirectAttributes.addFlashAttribute("successMessage",
                "Đã chuyển đơn sang trạng thái \"" + target.getLabel() + "\".");
        return "redirect:/admin/don-hang/" + id;
    }

    /** Xác nhận đã nhận tiền (chuyển khoản / tiền mặt tại quầy). */
    @PostMapping("/{id}/xac-nhan-thanh-toan")
    public String confirmPayment(@PathVariable("id") Integer id,
                                 @RequestParam(name = "transactionCode", required = false) String transactionCode,
                                 @AuthenticationPrincipal CustomUserDetails admin,
                                 RedirectAttributes redirectAttributes) {
        orderService.confirmPayment(id, transactionCode, admin.getUserId());
        redirectAttributes.addFlashAttribute("successMessage", "Đã xác nhận thanh toán cho đơn hàng.");
        return "redirect:/admin/don-hang/" + id;
    }

    // ==================== TẠO ĐƠN THỦ CÔNG ====================

    /** Form tạo đơn thủ công (bán tại quầy / khách đặt qua điện thoại). */
    @GetMapping("/tao-don")
    public String createForm(Model model) {
        prepareCreateModel(model);
        model.addAttribute("form", new ManualOrderForm());
        return "admin/order-create";
    }

    @PostMapping("/tao-don")
    public String create(@ModelAttribute("form") ManualOrderForm form,
                         Model model,
                         @AuthenticationPrincipal CustomUserDetails admin,
                         RedirectAttributes redirectAttributes) {
        try {
            Order order = orderService.createManualOrder(form, admin.getUserId());
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đã tạo đơn thủ công " + order.getOrderCode() + ".");
            return "redirect:/admin/don-hang/" + order.getId();
        } catch (BusinessException ex) {
            // Lỗi nghiệp vụ (SKU sai, thiếu tồn kho, thiếu địa chỉ…) ⇒ hiện lại form kèm lý do,
            // giữ nguyên dữ liệu admin vừa nhập để sửa ngay tại chỗ.
            model.addAttribute("form", form);
            model.addAttribute("formError", ex.getMessage());
            prepareCreateModel(model);
            return "admin/order-create";
        }
    }

    /** Dữ liệu gợi ý cho form tạo đơn: SKU đang bán và email khách hàng (datalist). */
    private void prepareCreateModel(Model model) {
        List<ProductVariant> variants = productVariantRepository.findActiveWithProduct();
        model.addAttribute("variants", variants.size() > MAX_SUGGESTIONS
                ? variants.subList(0, MAX_SUGGESTIONS) : variants);

        List<User> customers = userRepository.findByRoleOrderByCreatedAtDesc(UserRole.CUSTOMER);
        model.addAttribute("customers", customers.size() > MAX_SUGGESTIONS
                ? customers.subList(0, MAX_SUGGESTIONS) : customers);

        model.addAttribute("paymentMethods", List.of(PaymentMethod.CASH, PaymentMethod.COD, PaymentMethod.BANK_TRANSFER));
        model.addAttribute("adminMenu", "orders");
    }

    /** Cửa sổ số trang – dùng tiện ích chung để mọi trang quản trị hiển thị giống nhau. */
    private List<Integer> pageNumbers(Page<?> result) {
        return Pagination.pageNumbers(result, PAGE_LINK_WINDOW);
    }
}
