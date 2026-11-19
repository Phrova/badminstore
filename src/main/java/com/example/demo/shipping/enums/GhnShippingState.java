package com.example.demo.shipping.enums;

import lombok.Getter;

/**
 * Nhóm trạng thái vận chuyển đã chuẩn hoá từ 23 mã trạng thái GHN.
 *
 * Dùng cho giao diện/timeline và để quyết định cảnh báo. Enum này KHÔNG tự đổi đơn hàng;
 * {@link com.example.demo.shipping.service.ShippingStatusMapper} mới là nơi đề xuất trạng thái đơn.
 */
@Getter
public enum GhnShippingState {

    WAITING_PICKUP("Chờ lấy hàng", false, false, "Đơn đã sang GHN, đang chờ shipper lấy hàng."),
    PICKING("Đang lấy hàng", false, false, "Shipper đang tới lấy hàng."),
    IN_TRANSIT("Đang vận chuyển", false, false, "Hàng đang trên đường tới kho/khu vực giao."),
    DELIVERING("Đang giao hàng", false, false, "Shipper đang giao hàng cho khách."),
    DELIVERED("Đã giao hàng", true, false, "Ghi nhận đơn đã giao thành công."),
    DELIVERY_FAILED("Giao hàng thất bại", false, true,
            "Không tự huỷ đơn; liên hệ GHN/khách để giao lại hoặc quyết định huỷ."),
    RETURNING("Đang hoàn hàng", false, false, "GHN đang hoàn hàng về shop."),
    RETURNED("Đã hoàn hàng", true, false,
            "Chỉ tự hoàn trả khi đơn đã giao thành công; đơn chưa giao thì admin kiểm tra tay."),
    RETURN_FAILED("Hoàn hàng thất bại", false, true,
            "Hàng chưa về shop; liên hệ GHN để xử lý, không cộng kho."),
    CANCELLED("Đã huỷ vận đơn", true, false, "Huỷ đơn và cộng lại kho nếu vận đơn đã huỷ thành công."),
    EXCEPTION("Vận đơn có ngoại lệ", false, true, "Kiểm tra với GHN trước khi huỷ/hoàn trả."),
    LOST("Hàng bị mất", false, true, "Không tự huỷ; làm việc với GHN về bồi thường."),
    DAMAGED("Hàng bị hư hỏng", false, true, "Không tự huỷ; kiểm tra ảnh/biên bản với GHN."),
    SCRAPPED("Hàng bị tiêu hủy", false, true, "Không tự huỷ; kiểm tra biên bản tiêu hủy với GHN."),
    UNKNOWN("Không xác định", false, true, "Mã trạng thái lạ; kiểm tra trực tiếp trên GHN.");

    private final String label;
    private final boolean terminal;
    private final boolean warning;
    private final String actionHint;

    GhnShippingState(String label, boolean terminal, boolean warning, String actionHint) {
        this.label = label;
        this.terminal = terminal;
        this.warning = warning;
        this.actionHint = actionHint;
    }
}
