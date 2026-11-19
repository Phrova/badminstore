package com.example.demo.shipping.service;

import com.example.demo.shipping.enums.GhnShippingState;
import com.example.demo.shipping.enums.GhnStatus;
import com.example.demo.order.enums.OrderStatus;

/**
 * Ánh xạ trạng thái GHN sang nhóm hiển thị và ĐỀ XUẤT trạng thái đơn của shop.
 *
 * Quy tắc đã được duyệt (AGENTS.md mục 4 và báo cáo GHN):
 *  – {@code delivered} → DELIVERED khi đơn đang CONFIRMED/SHIPPING;
 *  – {@code returned} → RETURNED CHỈ khi đơn đã DELIVERED (đã giao rồi mới hoàn trả);
 *  – {@code cancel} → CANCELLED khi đơn chưa kết thúc;
 *  – delivery_fail/return_fail/exception/lost/damage/scrap: KHÔNG tự đổi trạng thái, chỉ cảnh báo.
 *
 * Mapper thuần, không gọi repository/OrderService, để test được toàn bộ 23 trạng thái.
 */
public final class ShippingStatusMapper {

    private ShippingStatusMapper() {
    }

    /** Nhóm hiển thị của một mã GHN; mã null/lạ trả UNKNOWN. */
    public static GhnShippingState stateOf(GhnStatus status) {
        if (status == null) {
            return GhnShippingState.UNKNOWN;
        }
        return switch (status) {
            case READY_TO_PICK -> GhnShippingState.WAITING_PICKUP;
            case PICKING, MONEY_COLLECT_PICKING -> GhnShippingState.PICKING;
            case PICKED, STORING, TRANSPORTING, SORTING -> GhnShippingState.IN_TRANSIT;
            case DELIVERING, MONEY_COLLECT_DELIVERING -> GhnShippingState.DELIVERING;
            case DELIVERED -> GhnShippingState.DELIVERED;
            case DELIVERY_FAIL -> GhnShippingState.DELIVERY_FAILED;
            case WAITING_TO_RETURN, RETURN_TRANSPORTING, RETURN_SORTING, RETURNING, RETURN ->
                    GhnShippingState.RETURNING;
            case RETURNED -> GhnShippingState.RETURNED;
            case RETURN_FAIL -> GhnShippingState.RETURN_FAILED;
            case CANCEL -> GhnShippingState.CANCELLED;
            case EXCEPTION -> GhnShippingState.EXCEPTION;
            case LOST -> GhnShippingState.LOST;
            case DAMAGE -> GhnShippingState.DAMAGED;
            case SCRAP -> GhnShippingState.SCRAPPED;
            case UNKNOWN -> GhnShippingState.UNKNOWN;
        };
    }

    /** Nhãn tiếng Việt của trạng thái GHN (ưu tiên nhãn chi tiết của GHN). */
    public static String labelOf(GhnStatus status) {
        return status == null ? GhnStatus.UNKNOWN.getLabel() : status.getLabel();
    }

    /** Trạng thái này cần admin chú ý (không tự động xử lý). */
    public static boolean isWarning(GhnStatus status) {
        return stateOf(status).isWarning();
    }

    /** Gợi ý hành động cho admin; không thay đổi dữ liệu. */
    public static String actionHint(GhnStatus status) {
        return stateOf(status).getActionHint();
    }

    /**
     * Trạng thái đơn nên chuyển tới, hoặc null nếu không được tự động đổi.
     * Chỉ trả về trạng thái hợp lệ theo máy trạng thái hiện tại; nơi gọi vẫn phải đi qua OrderService.
     */
    public static OrderStatus suggestedOrderStatus(GhnStatus ghnStatus, OrderStatus current) {
        if (ghnStatus == null || current == null) {
            return null;
        }
        return switch (ghnStatus) {
            case DELIVERED -> current == OrderStatus.CONFIRMED || current == OrderStatus.SHIPPING
                    ? OrderStatus.DELIVERED : null;
            case RETURNED -> current == OrderStatus.DELIVERED ? OrderStatus.RETURNED : null;
            case CANCEL -> current == OrderStatus.PENDING
                    || current == OrderStatus.CONFIRMED
                    || current == OrderStatus.SHIPPING ? OrderStatus.CANCELLED : null;
            default -> null;
        };
    }

    /** Đơn đã ở đúng trạng thái đích của sự kiện GHN này chưa (nhận diện webhook gửi trùng). */
    public static boolean isAlreadyAtStatus(GhnStatus ghnStatus, OrderStatus current) {
        if (ghnStatus == null || current == null) {
            return false;
        }
        return switch (ghnStatus) {
            case DELIVERED -> current == OrderStatus.DELIVERED;
            case RETURNED -> current == OrderStatus.RETURNED;
            case CANCEL -> current == OrderStatus.CANCELLED;
            default -> false;
        };
    }

    /** Thông báo khi GHN báo một trạng thái nhưng đơn không được tự động đổi. */
    public static String noChangeMessage(GhnStatus ghnStatus, OrderStatus current) {
        String label = ghnStatus == null ? GhnStatus.UNKNOWN.getLabel() : ghnStatus.getLabel();
        if (ghnStatus == GhnStatus.RETURNED && current != OrderStatus.DELIVERED) {
            return "GHN báo \"" + label + "\" nhưng đơn chưa giao thành công nên không tự hoàn trả/cộng kho. "
                    + actionHint(ghnStatus);
        }
        if (isWarning(ghnStatus)) {
            return "GHN báo \"" + label + "\" – đơn vẫn ở \"" + current.getLabel()
                    + "\". Cần kiểm tra thủ công: " + actionHint(ghnStatus);
        }
        return "GHN báo \"" + label + "\" – đơn vẫn ở \"" + current.getLabel() + "\", không cần đổi.";
    }

    /** Thông báo khi webhook gửi lại đúng sự kiện đã xử lý. */
    public static String duplicateMessage(OrderStatus current) {
        return "Đơn đã ở trạng thái \"" + current.getLabel() + "\" – bỏ qua sự kiện GHN trùng.";
    }
}
