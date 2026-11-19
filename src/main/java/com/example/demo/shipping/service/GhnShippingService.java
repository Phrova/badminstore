package com.example.demo.shipping.service;

import com.example.demo.shipping.dto.GhnSyncResult;
import com.example.demo.shipping.dto.GhnCancelResult;
import com.example.demo.shipping.dto.GhnOrderResult;
import com.example.demo.order.entity.Order;
import com.example.demo.shipping.enums.GhnStatus;
import java.math.BigDecimal;
import com.example.demo.order.service.OrderService;

/**
 * NGHIỆP VỤ VẬN CHUYỂN GHN cho đơn hàng:
 *  1. Đẩy đơn (đã xác nhận, đã trừ kho) sang GHN để lấy MÃ VẬN ĐƠN → lưu vào orders.ghn_order_code.
 *  2. Đồng bộ TRẠNG THÁI vận đơn về hệ thống theo {@link ShippingStatusMapper}:
 *     GHN giao xong ⇒ đơn DELIVERED; GHN hoàn hàng CHỈ tự RETURNED khi đơn đã giao;
 *     ngoại lệ/mất/hư hỏng/tiêu hủy ⇒ giữ nguyên đơn và cảnh báo admin.
 *  3. Huỷ vận đơn GHN; chỉ khi GHN xác nhận huỷ thành công mới huỷ đơn shop.
 *
 * Mọi thay đổi trạng thái đều đi qua OrderService nên vẫn đúng máy trạng thái, vẫn trừ/cộng kho,
 * trả lượt voucher và ghi order_status_histories (bắt buộc theo AGENTS.md mục 4).
 */
public interface GhnShippingService {

    public static final int PAYMENT_TYPE_BUYER_PAYS = GhnCodCalculator.PAYMENT_TYPE_BUYER_PAYS;

    GhnOrderResult pushOrder(Integer orderId);

    GhnSyncResult syncStatus(Integer orderId, Integer actorUserId);

    GhnSyncResult applyStatus(Integer orderId, String orderCode, GhnStatus ghnStatus, String source, Integer actorUserId);

    GhnCancelResult cancelOrder(Integer orderId, String reason, String reasonCode, Integer actorUserId);

    BigDecimal estimatedFee(Order order);

    int weightOf(Order order);
}
