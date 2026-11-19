package com.example.demo.order.service;

import com.example.demo.order.dto.ManualOrderForm;
import com.example.demo.order.dto.OrderDetailView;
import com.example.demo.order.dto.OrderSummaryView;
import com.example.demo.order.entity.Order;
import com.example.demo.voucher.entity.Voucher;
import com.example.demo.order.enums.OrderStatus;
import com.example.demo.common.exception.BusinessException;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Vòng đời đơn hàng: xem đơn của khách, huỷ/hoàn trả, và (cho admin) đổi trạng thái + xác nhận thanh toán.
 *
 * Quy tắc bất di bất dịch (AGENTS.md mục 4):
 * – mỗi lần đổi trạng thái là 1 transaction, luôn ghi 1 dòng order_status_histories;
 * – máy trạng thái nằm ở enum OrderStatus, chuyển sai ⇒ BusinessException;
 * – PENDING → CONFIRMED mới TRỪ KHO (UPDATE có điều kiện, duyệt theo product_variant_id tăng dần);
 * – huỷ/hoàn trả ⇒ CỘNG LẠI KHO, trả lượt voucher; riêng hoàn trả đơn đã PAID ⇒ REFUNDED;
 * – mọi luồng GHI đều khoá bi quan dòng đơn trước khi đổi trạng thái, tránh 2 admin xác nhận trùng;
 * – stock_deducted luôn set cùng lúc với trạng thái để khớp ràng buộc CK_orders_stock.
 */
public interface OrderService {

    public static final int PAGE_SIZE = 10;

    public record StatusChangeResult(OrderStatus previous, OrderStatus current, boolean changed) {
    }

    Page<OrderSummaryView> myOrders(Integer userId, int pageNumber);

    OrderDetailView myOrderDetail(Integer userId, Integer orderId);

    void cancelByCustomer(Integer userId, Integer orderId, String reason);

    void returnByCustomer(Integer userId, Integer orderId, String reason);

    Page<OrderSummaryView> searchForAdmin(OrderStatus status, String keyword, int pageNumber);

    List<OrderSummaryView> searchAllForAdmin(OrderStatus status, String keyword);

    OrderDetailView adminOrderDetail(Integer orderId);

    void changeStatusByAdmin(Integer orderId, OrderStatus target, Integer adminUserId, String note);

    void changeStatusBySystem(Integer orderId, OrderStatus target, String note);

    StatusChangeResult changeStatusPath(Integer orderId, Function<OrderStatus, OrderStatus> targetResolver, Integer actorUserId, String note);

    void confirmPayment(Integer orderId, String transactionCode, Integer adminUserId);

    void replaceItem(Integer orderId, Integer itemId, Integer newVariantId);

    Order createManualOrder(ManualOrderForm form, Integer adminUserId);
}
