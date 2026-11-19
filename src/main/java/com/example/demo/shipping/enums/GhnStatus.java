package com.example.demo.shipping.enums;

import java.util.Locale;
import lombok.Getter;

/**
 * Trạng thái vận đơn của GHN (API v2 /shipping-order/detail) và nhãn tiếng Việt.
 *
 * Enum này CHỈ mô tả trạng thái GHN. Việc quyết định có đổi trạng thái đơn của shop hay không
 * nằm ở {@link com.example.demo.shipping.service.ShippingStatusMapper} – tránh mỗi nơi tự suy diễn một kiểu.
 */
@Getter
public enum GhnStatus {

    READY_TO_PICK("Chờ lấy hàng"),
    PICKING("Đang lấy hàng"),
    MONEY_COLLECT_PICKING("Đang lấy hàng (thu tiền)"),
    PICKED("Đã lấy hàng"),
    STORING("Đã nhập kho GHN"),
    TRANSPORTING("Đang luân chuyển"),
    SORTING("Đang phân loại"),
    DELIVERING("Đang giao hàng"),
    MONEY_COLLECT_DELIVERING("Đang giao hàng (thu tiền)"),
    DELIVERED("Đã giao hàng"),
    DELIVERY_FAIL("Giao hàng thất bại"),
    WAITING_TO_RETURN("Chờ hoàn hàng"),
    RETURN_TRANSPORTING("Đang luân chuyển hoàn"),
    RETURN_SORTING("Đang phân loại hoàn"),
    RETURNING("Đang trả hàng"),
    RETURNED("Đã hoàn hàng"),
    RETURN_FAIL("Hoàn hàng thất bại"),
    RETURN("Hoàn hàng"),
    CANCEL("Đã huỷ vận đơn"),
    EXCEPTION("Vận đơn có ngoại lệ"),
    DAMAGE("Hàng bị hư hỏng"),
    LOST("Hàng bị mất"),
    SCRAP("Hàng bị tiêu hủy"),
    UNKNOWN("Không xác định");

    private final String label;

    GhnStatus(String label) {
        this.label = label;
    }

    /** Ghép chuỗi trạng thái GHN (ví dụ "money_collect_picking") sang enum, lạ thì trả UNKNOWN. */
    public static GhnStatus fromApi(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return UNKNOWN;
        }
        String normalized = rawStatus.trim().toUpperCase(Locale.ROOT);
        for (GhnStatus status : values()) {
            if (status.name().equals(normalized)) {
                return status;
            }
        }
        return UNKNOWN;
    }
}
