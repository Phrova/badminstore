package com.example.demo.shipping.service.impl;

import com.example.demo.shipping.config.GhnProperties;
import com.example.demo.shipping.dto.GhnSyncResult;
import com.example.demo.shipping.dto.GhnApiException;
import com.example.demo.shipping.dto.GhnCancelResult;
import com.example.demo.shipping.dto.GhnCreateOrderRequest;
import com.example.demo.shipping.dto.GhnOrderDetail;
import com.example.demo.shipping.dto.GhnOrderResult;
import com.example.demo.order.entity.Order;
import com.example.demo.order.entity.OrderItem;
import com.example.demo.payment.entity.Payment;
import com.example.demo.shipping.enums.GhnStatus;
import com.example.demo.order.enums.OrderStatus;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.NotFoundException;
import com.example.demo.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.example.demo.order.service.OrderService;
import com.example.demo.shipping.service.GhnShippingService;
import com.example.demo.shipping.service.GhnClient;
import com.example.demo.shipping.service.GhnCodCalculator;
import com.example.demo.shipping.service.ShippingStatusMapper;

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
@Service
@RequiredArgsConstructor
public class GhnShippingServiceImpl implements GhnShippingService {

    private static final Logger log = LoggerFactory.getLogger(GhnShippingServiceImpl.class);

    /** Các mã lý do huỷ GHN chấp nhận; mã lạ được quy về "khác". */
    private static final Set<String> CANCEL_REASON_CODES = Set.of(
            "GHN-CO001", "GHN-CO002", "GHN-CO003", "GHN-CANCEL-OTHER");
    private static final String DEFAULT_CANCEL_REASON_CODE = "GHN-CANCEL-OTHER";

    private final GhnClient ghnClient;
    private final GhnProperties ghnProperties;
    private final OrderRepository orderRepository;
    private final OrderService orderService;

    /** Đẩy đơn sang GHN và lưu mã vận đơn. */
    @Override
    @Transactional
    public GhnOrderResult pushOrder(Integer orderId) {
        Order order = requireOrder(orderId);
        if (!ghnClient.isShippingAvailable()) {
            throw new BusinessException("Chưa cấu hình đủ GHN để tạo vận đơn: cần bật GHN, có token và shop-id. "
                    + "Kiểm tra lại cấu hình app.ghn rồi thử lại.");
        }
        if (StringUtils.hasText(order.getGhnOrderCode())) {
            throw new BusinessException("Đơn " + order.getOrderCode() + " đã có vận đơn GHN "
                    + order.getGhnOrderCode() + " rồi.");
        }
        if (order.getOrderStatus() != OrderStatus.CONFIRMED && order.getOrderStatus() != OrderStatus.SHIPPING) {
            throw new BusinessException("Chỉ đẩy đơn sang GHN khi đơn đã được xác nhận (đã trừ kho). Đơn này đang ở \""
                    + order.getOrderStatus().getLabel() + "\".");
        }
        boolean newAddress = isNewAddress(order);
        if (order.getShippingDistrictId() == null && order.getShippingProvinceId() == null) {
            throw new BusinessException("Đơn này chưa có mã Quận/Huyện – Phường/Xã của GHN nên GHN không nhận. "
                    + "Khách cần chọn lại địa chỉ theo GHN (ở bước thanh toán) rồi tạo vận đơn.");
        }
        if (!StringUtils.hasText(order.getShippingWardCode())) {
            throw new BusinessException("Đơn này chưa có mã Phường/Xã của GHN nên GHN không nhận. "
                    + "Khách cần chọn lại địa chỉ theo GHN (ở bước thanh toán) rồi tạo vận đơn.");
        }
        if (newAddress && (order.getAddress() == null
                || !StringUtils.hasText(order.getAddress().getProvinceName())
                || !StringUtils.hasText(order.getAddress().getWardName()))) {
            throw new BusinessException("Đơn dùng địa chỉ 2 cấp mới nhưng thiếu tên Tỉnh/Thành hoặc Phường/Xã. "
                    + "Khách cần chọn lại địa chỉ theo GHN rồi tạo vận đơn.");
        }
        if (StringUtils.hasText(order.getReceiverPhone()) && order.getReceiverPhone().length() < 10) {
            throw new BusinessException("Số điện thoại người nhận chưa hợp lệ để tạo vận đơn GHN.");
        }

        GhnOrderResult result = ghnClient.createOrder(buildRequest(order));
        if (!StringUtils.hasText(result.orderCode())) {
            throw new BusinessException("GHN không trả về mã vận đơn. Vui lòng thử lại sau.");
        }
        order.setGhnOrderCode(result.orderCode());
        orderRepository.save(order);
        log.info("Đã tạo vận đơn GHN {} cho đơn {} (phí {} VND)",
                result.orderCode(), order.getOrderCode(), result.totalFee());
        return result;
    }

    /**
     * Đồng bộ trạng thái vận đơn GHN về đơn của shop.
     * {@code actorUserId = null} nghĩa là job định kỳ tự chạy (lịch sử ghi "Hệ thống").
     */
    @Override
    @Transactional
    public GhnSyncResult syncStatus(Integer orderId, Integer actorUserId) {
        Order order = requireOrder(orderId);
        String orderCode = order.getGhnOrderCode();
        if (!StringUtils.hasText(orderCode)) {
            throw new BusinessException("Đơn chưa có vận đơn GHN. Hãy bấm \"Đẩy sang GHN\" trước.");
        }

        GhnOrderDetail detail = ghnClient.orderDetail(orderCode);
        GhnStatus ghnStatus = detail.statusEnum();
        String label = ghnStatus.getLabel();
        OrderStatus current = order.getOrderStatus();
        OrderStatus target = ShippingStatusMapper.suggestedOrderStatus(ghnStatus, current);

        if (target == null || target == current) {
            return new GhnSyncResult(orderCode, detail.status(), label, current, false,
                    ShippingStatusMapper.noChangeMessage(ghnStatus, current));
        }

        // Máy trạng thái đi từng bước (CONFIRMED → SHIPPING → DELIVERED) nên GHN báo "đã giao" khi đơn
        // còn ở CONFIRMED thì phải đi qua đủ các bước trung gian. Không bắt lỗi ở đây: nếu một bước
        // không hợp lệ thì BusinessException ném ra ngoài ⇒ cả transaction rollback, đơn giữ nguyên.
        List<OrderStatus> path = pathTo(current, target);
        if (path.isEmpty()) {
            return new GhnSyncResult(orderCode, detail.status(), label, current, false,
                    ShippingStatusMapper.noChangeMessage(ghnStatus, current));
        }

        OrderStatus lastApplied = current;
        for (int index = 0; index < path.size(); index++) {
            OrderStatus step = path.get(index);
            boolean lastStep = index == path.size() - 1;
            String note = lastStep
                    ? "Đồng bộ vận đơn GHN (" + orderCode + "): " + label
                    : "Đồng bộ vận đơn GHN (" + orderCode + "): cập nhật theo hành trình giao hàng";
            if (actorUserId == null) {
                orderService.changeStatusBySystem(orderId, step, note);
            } else {
                orderService.changeStatusByAdmin(orderId, step, actorUserId, note);
            }
            lastApplied = step;
        }
        return new GhnSyncResult(orderCode, detail.status(), label, lastApplied, true,
                "Đã cập nhật đơn sang \"" + lastApplied.getLabel() + "\" theo trạng thái GHN \"" + label + "\".");
    }

    /**
     * Áp dụng trạng thái GHN đã biết vào đơn mà KHÔNG gọi API GHN.
     *
     * Dùng cho webhook: service này khoá đơn rồi mới để {@link ShippingStatusMapper} quyết định
     * trạng thái đích dựa trên trạng thái mới nhất, sau đó đi đúng các bước của máy trạng thái.
     * Sự kiện trùng/cũ hoặc chỉ cảnh báo trả {@code changed = false}, không ghi thêm lịch sử.
     * Sự kiện không hợp lệ với máy trạng thái ném {@link BusinessException}; transaction rollback.
     */
    @Override
    @Transactional
    public GhnSyncResult applyStatus(Integer orderId, String orderCode, GhnStatus ghnStatus,
                                     String source, Integer actorUserId) {
        if (!StringUtils.hasText(orderCode)) {
            throw new BusinessException("Đơn chưa có vận đơn GHN nên không thể áp dụng trạng thái.");
        }

        GhnStatus status = ghnStatus == null ? GhnStatus.UNKNOWN : ghnStatus;
        String prefix = StringUtils.hasText(source) ? source : "Đồng bộ vận đơn GHN";
        String note = prefix + " (" + orderCode + "): " + status.getLabel();
        OrderService.StatusChangeResult change = orderService.changeStatusPath(
                orderId, current -> ShippingStatusMapper.suggestedOrderStatus(status, current), actorUserId, note);

        String rawStatus = status.name().toLowerCase(Locale.ROOT);
        if (!change.changed()) {
            String message = ShippingStatusMapper.isAlreadyAtStatus(status, change.current())
                    ? ShippingStatusMapper.duplicateMessage(change.current())
                    : ShippingStatusMapper.noChangeMessage(status, change.current());
            return new GhnSyncResult(orderCode, rawStatus, status.getLabel(), change.current(), false, message);
        }
        return new GhnSyncResult(orderCode, rawStatus, status.getLabel(), change.current(), true,
                "Đã cập nhật đơn sang \"" + change.current().getLabel() + "\" theo trạng thái GHN \""
                        + status.getLabel() + "\".");
    }

    /**
     * Huỷ vận đơn GHN và chỉ huỷ đơn shop khi GHN xác nhận thành công.
     *
     * Cố ý KHÔNG đánh dấu @Transactional: lời gọi GHN chạy ngoài transaction DB;
     * việc đổi trạng thái (cộng kho/trả voucher/history) nằm trong transaction của OrderService.
     */
    @Override
    public GhnCancelResult cancelOrder(Integer orderId, String reason, String reasonCode, Integer actorUserId) {
        Order order = requireOrder(orderId);
        String orderCode = order.getGhnOrderCode();
        if (!StringUtils.hasText(orderCode)) {
            throw new BusinessException("Đơn chưa có vận đơn GHN nên không thể huỷ.");
        }
        OrderStatus current = order.getOrderStatus();
        if (current == OrderStatus.DELIVERED) {
            throw new BusinessException("Đơn đã giao thành công nên không thể huỷ vận đơn GHN. "
                    + "Nếu khách trả hàng, hãy dùng chức năng hoàn trả.");
        }
        if (current.isFinal()) {
            throw new BusinessException("Đơn đang ở trạng thái \"" + current.getLabel()
                    + "\" nên không thể huỷ vận đơn GHN.");
        }
        if (!ghnClient.isShippingAvailable()) {
            throw new BusinessException("Chưa cấu hình đủ GHN để huỷ vận đơn: cần bật GHN, có token và shop-id.");
        }

        String normalizedReason = StringUtils.hasText(reason) ? reason.trim() : null;
        String normalizedReasonCode = normalizeCancelReasonCode(reasonCode);
        GhnCancelResult result = ghnClient.cancelOrder(orderCode, normalizedReason, normalizedReasonCode);
        if (!result.result()) {
            return result;
        }

        String note = "Huỷ vận đơn GHN (" + orderCode + ")"
                + (normalizedReason == null ? "" : ": " + normalizedReason);
        orderService.changeStatusByAdmin(orderId, OrderStatus.CANCELLED, actorUserId, note);
        return result;
    }

    /**
     * Đường đi ngắn nhất trong máy trạng thái từ {@code from} tới {@code to}
     * (rỗng = không tới được, ví dụ CONFIRMED → RETURNED vì phải giao xong mới hoàn trả được).
     */
    static List<OrderStatus> pathTo(OrderStatus from, OrderStatus to) {
        return from == null ? List.of() : from.pathTo(to);
    }

    /** Phí dự kiến GHN sẽ thu cho đơn này (null nếu GHN tắt hoặc đơn chưa có mã địa chỉ GHN). */
    @Override
    @Transactional(readOnly = true)
    public BigDecimal estimatedFee(Order order) {
        if (!ghnClient.isShippingAvailable()) {
            return null;
        }
        try {
            if (order.getShippingDistrictId() != null) {
                return ghnClient.fee(order.getShippingDistrictId(), order.getShippingWardCode(), weightOf(order))
                        .totalAmount();
            }
            if (isNewAddress(order)) {
                if (order.getAddress() == null) {
                    return null;
                }
                return BigDecimal.valueOf(ghnClient.previewFee(buildRequest(order)).totalFee());
            }
            return null;
        } catch (GhnApiException ex) {
            log.warn("Không lấy được phí GHN cho đơn {}: {}", order.getOrderCode(), ex.getMessage());
            return null;
        } catch (BusinessException ex) {
            log.warn("Không dựng được yêu cầu phí GHN cho đơn {}: {}", order.getOrderCode(), ex.getMessage());
            return null;
        }
    }

    /** Khối lượng kiện hàng của đơn: mỗi cây vợt một kiện, cộng theo cấu hình trong application.yml. */
    @Override
    public int weightOf(Order order) {
        int totalQuantity = order.getItems().stream().mapToInt(OrderItem::getQuantity).sum();
        int base = ghnProperties.getWeightGram();
        return base + Math.max(0, totalQuantity - 1) * 300;
    }

    // ------------------------------------------------------------------ nội bộ

    private GhnCreateOrderRequest buildRequest(Order order) {
        GhnCreateOrderRequest request = new GhnCreateOrderRequest();
        request.setClientOrderCode(order.getOrderCode());
        request.setToName(order.getReceiverName());
        request.setToPhone(order.getReceiverPhone());
        request.setToAddress(order.getShippingAddress());

        if (isNewAddress(order)) {
            // Hệ 2 cấp mới: gửi mã + tên Phường/Xã và tên Tỉnh, KHÔNG gửi quận/huyện.
            request.setIsNewToAddress(true);
            request.setToWardCode(order.getShippingWardCode());
            request.setToProvinceName(order.getAddress().getProvinceName());
            request.setToWardName(order.getAddress().getWardName());
        } else {
            request.setToDistrictId(order.getShippingDistrictId());
            request.setToWardCode(order.getShippingWardCode());
            if (order.getAddress() != null) {
                // GHN yêu cầu tên địa danh khi is_new_to_address=false; gửi kèm nếu đơn có địa chỉ lưu.
                request.setToProvinceName(order.getAddress().getProvinceName());
                request.setToDistrictName(order.getAddress().getDistrictName());
                request.setToWardName(order.getAddress().getWardName());
            }
        }

        request.setFromName(ghnProperties.getFromName());
        request.setFromPhone(ghnProperties.getFromPhone());
        request.setFromAddress(ghnProperties.getFromAddress());
        request.setFromWardCode(ghnProperties.getFromWardCode());
        request.setFromDistrictId(ghnProperties.getFromDistrictId());
        request.setReturnPhone(ghnProperties.getFromPhone());
        request.setReturnAddress(ghnProperties.getFromAddress());
        request.setReturnWardCode(ghnProperties.getFromWardCode());
        request.setReturnDistrictId(ghnProperties.getFromDistrictId());

        request.setWeight(weightOf(order));
        request.setLength(ghnProperties.getLengthCm());
        request.setWidth(ghnProperties.getWidthCm());
        request.setHeight(ghnProperties.getHeightCm());
        request.setServiceTypeId(ghnProperties.getServiceTypeId());
        request.setNote(order.getNote());
        request.setContent("Vợt cầu lông BadStore");

        Payment payment = order.getPayment();
        GhnCodCalculator.CodDecision cod = GhnCodCalculator.decide(
                order.getSubtotal(),
                order.getDiscountAmount(),
                order.getShippingFee(),
                payment == null ? null : payment.getPaymentMethod(),
                payment == null ? null : payment.getPaymentStatus(),
                payment == null ? null : payment.getAmount(),
                ghnProperties.getPaymentTypeId() == PAYMENT_TYPE_BUYER_PAYS);
        request.setPaymentTypeId(cod.paymentTypeId());
        request.setCodAmount(cod.codAmount());
        request.setOrderValue(cod.goodsAmount());
        // Khai giá không vượt giá trị hàng và trần 5.000.000 ₫ của GHN.
        request.setInsuranceValue(Math.min(ghnProperties.getInsuranceValue(),
                Math.min(cod.goodsAmount(), 5_000_000L)));

        List<GhnCreateOrderRequest.GhnOrderItem> items = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            String name = item.getProductName() + " (" + item.getColor() + "/" + item.getWeightClass()
                    + "/" + item.getGripSize() + ")";
            int itemWeight = ghnProperties.getWeightGram() + Math.max(0, item.getQuantity() - 1) * 300;
            items.add(new GhnCreateOrderRequest.GhnOrderItem(name, item.getSku(), item.getQuantity(),
                    item.getUnitPrice().longValue(), itemWeight,
                    ghnProperties.getLengthCm(), ghnProperties.getWidthCm(), ghnProperties.getHeightCm()));
        }
        request.setItems(items);
        return request;
    }

    private String normalizeCancelReasonCode(String reasonCode) {
        if (!StringUtils.hasText(reasonCode)) {
            return DEFAULT_CANCEL_REASON_CODE;
        }
        String normalized = reasonCode.trim().toUpperCase(java.util.Locale.ROOT);
        return CANCEL_REASON_CODES.contains(normalized) ? normalized : DEFAULT_CANCEL_REASON_CODE;
    }

    /** Địa chỉ 2 cấp mới: có province nhưng không có district (đơn cũ vẫn giữ district). */
    private boolean isNewAddress(Order order) {
        return order.getShippingProvinceId() != null && order.getShippingDistrictId() == null;
    }

    private Order requireOrder(Integer orderId) {
        return orderRepository.findDetailForAdmin(orderId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy đơn hàng cần thao tác với GHN."));
    }
}
