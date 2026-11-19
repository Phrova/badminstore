package com.example.demo.order.service.impl;

import com.example.demo.order.dto.ManualOrderForm;
import com.example.demo.order.dto.ManualOrderLineForm;
import com.example.demo.order.dto.OrderDetailView;
import com.example.demo.order.dto.OrderSummaryView;
import com.example.demo.order.entity.Order;
import com.example.demo.order.entity.OrderItem;
import com.example.demo.order.entity.OrderStatusHistory;
import com.example.demo.payment.entity.Payment;
import com.example.demo.catalog.entity.ProductVariant;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserAddress;
import com.example.demo.voucher.entity.Voucher;
import com.example.demo.order.enums.OrderSource;
import com.example.demo.order.enums.OrderStatus;
import com.example.demo.payment.enums.PaymentMethod;
import com.example.demo.payment.enums.PaymentStatus;
import com.example.demo.catalog.enums.ProductStatus;
import com.example.demo.user.enums.UserRole;
import com.example.demo.catalog.enums.VariantStatus;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.NotFoundException;
import com.example.demo.order.repository.OrderRepository;
import com.example.demo.order.repository.OrderStatusHistoryRepository;
import com.example.demo.payment.repository.PaymentRepository;
import com.example.demo.catalog.repository.ProductVariantRepository;
import com.example.demo.user.repository.UserAddressRepository;
import com.example.demo.user.repository.UserRepository;
import com.example.demo.common.util.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.example.demo.voucher.service.VoucherService;
import com.example.demo.order.service.OrderService;
import com.example.demo.order.service.OrderCodeGenerator;

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
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final ProductVariantRepository productVariantRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final UserAddressRepository addressRepository;
    private final VoucherService voucherService;
    private final OrderCodeGenerator orderCodeGenerator;

    // ==================== phía KHÁCH ====================

    @Override
    @Transactional(readOnly = true)
    public Page<OrderSummaryView> myOrders(Integer userId, int pageNumber) {
        return orderRepository.findMyOrderSummaries(userId, pageRequest(pageNumber));
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDetailView myOrderDetail(Integer userId, Integer orderId) {
        return toDetailView(requireOwnOrder(userId, orderId));
    }

    /** Khách tự huỷ đơn khi đơn còn PENDING/CONFIRMED (đang giao thì phải gọi hotline). */
    @Override
    @Transactional
    public void cancelByCustomer(Integer userId, Integer orderId, String reason) {
        Order order = requireOwnOrderForUpdate(userId, orderId);
        if (order.getOrderStatus() == OrderStatus.SHIPPING) {
            throw new BusinessException("Đơn hàng đang trên đường giao nên không thể tự huỷ. "
                    + "Bạn gọi hotline 1900 0000 để được hỗ trợ nhé.");
        }
        String note = requireReason(reason, "huỷ đơn");
        changeStatus(order, OrderStatus.CANCELLED, userRepository.getReferenceById(userId),
                "Khách huỷ đơn: " + note);
    }

    /** Khách gửi yêu cầu hoàn trả 100% cho đơn đã giao. */
    @Override
    @Transactional
    public void returnByCustomer(Integer userId, Integer orderId, String reason) {
        Order order = requireOwnOrderForUpdate(userId, orderId);
        String note = requireReason(reason, "hoàn trả");
        changeStatus(order, OrderStatus.RETURNED, userRepository.getReferenceById(userId),
                "Khách yêu cầu hoàn trả: " + note);
    }

    // ==================== phía ADMIN ====================

    @Override
    @Transactional(readOnly = true)
    public Page<OrderSummaryView> searchForAdmin(OrderStatus status, String keyword, int pageNumber) {
        String normalizedKeyword = normalizeKeyword(keyword);
        return orderRepository.findOrderSummariesForAdmin(status, normalizedKeyword, pageRequest(pageNumber));
    }

    /** Lấy TOÀN BỘ đơn khớp bộ lọc để xuất Excel (không phân trang). */
    @Override
    @Transactional(readOnly = true)
    public List<OrderSummaryView> searchAllForAdmin(OrderStatus status, String keyword) {
        String normalizedKeyword = normalizeKeyword(keyword);
        return orderRepository.findOrderSummariesForAdmin(status, normalizedKeyword, Pageable.unpaged())
                .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDetailView adminOrderDetail(Integer orderId) {
        Order order = orderRepository.findDetailForAdmin(orderId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy đơn hàng cần xem."));
        return toDetailView(order);
    }

    /** Admin đổi trạng thái đơn (xác nhận, giao hàng, giao thành công, huỷ, hoàn trả). */
    @Override
    @Transactional
    public void changeStatusByAdmin(Integer orderId, OrderStatus target, Integer adminUserId, String note) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy đơn hàng cần cập nhật."));
        changeStatus(order, target, userRepository.getReferenceById(adminUserId), trimToNull(note));
    }

    /**
     * HỆ THỐNG tự đổi trạng thái – dùng cho việc đồng bộ vận đơn GHN hoặc job định kỳ.
     * Người thực hiện ưu tiên lấy admin tạo đơn, nếu không có thì lấy một tài khoản ADMIN đầu tiên
     * (cột changed_by cho phép NULL nhưng ghi được admin nào thì timeline rõ ràng hơn).
     */
    @Override
    @Transactional
    public void changeStatusBySystem(Integer orderId, OrderStatus target, String note) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy đơn hàng cần cập nhật."));
        changeStatus(order, target, systemActor(order), trimToNull(note));
    }

    /**
     * Áp dụng trạng thái đích theo ĐÚNG máy trạng thái, tự đi qua các bước trung gian
     * (ví dụ CONFIRMED → DELIVERED phải qua SHIPPING). Toàn bộ các bước nằm trong MỘT transaction
     * và chỉ khoá đơn một lần, nên không thể ghi dở dang nếu một bước thất bại.
     *
     * {@code targetResolver} được gọi SAU KHI đã khoá đơn, nên quyết định dựa trên trạng thái mới nhất
     * (tránh race: đọc PENDING ngoài transaction rồi admin xác nhận trước khi ta khoá).
     * Trả về null nghĩa là sự kiện không cần/không được phép đổi trạng thái (no-op, không ghi lịch sử).
     *
     * Dùng cho webhook GHN: sự kiện trùng (đã ở trạng thái đích) là no-op;
     * trạng thái đích không tới được ném {@link BusinessException} và transaction rollback.
     */
    @Override
    @Transactional
    public StatusChangeResult changeStatusPath(Integer orderId,
                                               Function<OrderStatus, OrderStatus> targetResolver,
                                               Integer actorUserId, String note) {
        if (targetResolver == null) {
            throw new BusinessException("Trạng thái đích không hợp lệ.");
        }
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy đơn hàng cần cập nhật."));
        OrderStatus previous = order.getOrderStatus();
        OrderStatus target = targetResolver.apply(previous);
        if (target == null || previous == target) {
            return new StatusChangeResult(previous, previous, false);
        }

        List<OrderStatus> path = previous.pathTo(target);
        if (path.isEmpty()) {
            throw new BusinessException("Không thể chuyển đơn từ \"" + previous.getLabel()
                    + "\" sang \"" + target.getLabel() + "\".");
        }

        User actor = actorUserId == null ? systemActor(order) : userRepository.getReferenceById(actorUserId);
        OrderStatus current = previous;
        for (int index = 0; index < path.size(); index++) {
            OrderStatus step = path.get(index);
            boolean lastStep = index == path.size() - 1;
            String stepNote = lastStep || note == null ? note : note + " (bước trung gian)";
            changeStatus(order, step, actor, trimToNull(stepNote));
            current = step;
        }
        return new StatusChangeResult(previous, current, true);
    }

    private User systemActor(Order order) {
        if (order.getCreatedBy() != null) {
            return order.getCreatedBy();
        }
        return userRepository.findByRoleOrderByCreatedAtDesc(UserRole.ADMIN).stream().findFirst().orElse(null);
    }

    /** Admin xác nhận đã nhận tiền (chuyển khoản/tiền mặt). */
    @Override
    @Transactional
    public void confirmPayment(Integer orderId, String transactionCode, Integer adminUserId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy đơn hàng cần xác nhận thanh toán."));
        Payment payment = order.getPayment();
        if (payment == null) {
            throw new BusinessException("Đơn hàng này chưa có thông tin thanh toán.");
        }
        if (payment.getPaymentStatus() == PaymentStatus.PAID) {
            throw new BusinessException("Đơn hàng này đã được xác nhận thanh toán trước đó.");
        }
        if (order.getOrderStatus().isFinal()) {
            throw new BusinessException("Đơn hàng đã huỷ hoặc đã hoàn trả nên không thể xác nhận thanh toán.");
        }

        payment.markPaid(userRepository.getReferenceById(adminUserId));
        payment.setTransactionCode(trimToNull(transactionCode));
        paymentRepository.save(payment);
    }

    /**
     * Đổi một dòng hàng sang biến thể khác khi đơn còn PENDING (kho CHƯA bị trừ).
     *
     * Quy tắc an toàn:
     * – chỉ đơn PENDING và thanh toán còn PENDING: không sửa đơn đã trừ kho/đã thu tiền;
     * – biến thể thay thế phải đang bán, thuộc sản phẩm đang bán/chưa xoá và đủ tồn kho;
     * – giữ nguyên số lượng, cập nhật snapshot và tính lại tạm tính/giảm giá/tổng tiền ở server;
     * – phí vận chuyển giữ nguyên như lúc đặt; payment.amount cập nhật theo tổng mới;
     * – không đụng kho: kho chỉ trừ khi xác nhận, và {@link #deductStock} sẽ dùng biến thể mới.
     */
    @Override
    @Transactional
    public void replaceItem(Integer orderId, Integer itemId, Integer newVariantId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy đơn hàng cần đổi sản phẩm."));
        if (order.getOrderStatus() != OrderStatus.PENDING) {
            throw new BusinessException("Chỉ đổi được sản phẩm khi đơn đang chờ xác nhận.");
        }

        Payment payment = order.getPayment();
        if (payment != null && payment.getPaymentStatus() != PaymentStatus.PENDING) {
            throw new BusinessException("Đơn đã ghi nhận thanh toán nên không thể đổi sản phẩm. "
                    + "Hãy huỷ đơn và tạo đơn mới với sản phẩm thay thế.");
        }

        OrderItem item = order.getItems().stream()
                .filter(line -> line.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Không tìm thấy dòng hàng cần đổi trong đơn."));

        ProductVariant replacement = productVariantRepository.findById(newVariantId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy sản phẩm thay thế."));
        if (replacement.getId().equals(item.getProductVariant().getId())) {
            throw new BusinessException("Sản phẩm thay thế phải khác sản phẩm hiện tại.");
        }
        // Cộng dồn nhu cầu nếu các dòng khác trong đơn đã dùng cùng biến thể thay thế
        int neededQuantity = item.getQuantity() + order.getItems().stream()
                .filter(line -> !line.getId().equals(item.getId()))
                .filter(line -> line.getProductVariant().getId().equals(replacement.getId()))
                .mapToInt(OrderItem::getQuantity)
                .sum();
        requireReplacementSellable(replacement, neededQuantity);

        // Tính trước tạm tính mới để kiểm tra voucher; chỉ sửa dòng hàng khi mọi điều kiện đã đạt
        BigDecimal newLineSubtotal = replacement.getPrice()
                .multiply(BigDecimal.valueOf(item.getQuantity()));
        BigDecimal newSubtotal = order.getItems().stream()
                .map(line -> line.getId().equals(item.getId()) ? newLineSubtotal : line.getSubtotal())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Voucher voucher = order.getVoucher();
        BigDecimal newDiscount = BigDecimal.ZERO;
        if (voucher != null) {
            if (newSubtotal.compareTo(voucher.getMinOrderValue()) < 0) {
                throw new BusinessException("Không thể đổi: tạm tính mới " + Money.format(newSubtotal)
                        + " thấp hơn mức tối thiểu " + Money.format(voucher.getMinOrderValue())
                        + " của voucher " + voucher.getCode() + ". Hãy chọn sản phẩm khác.");
            }
            newDiscount = voucherService.discountOf(voucher, newSubtotal);
        }

        item.replaceVariant(replacement);
        order.setDiscountAmount(newDiscount);
        order.recalculateTotals();
        orderRepository.save(order);
        if (payment != null) {
            payment.setAmount(order.getTotalAmount());
            paymentRepository.save(payment);
        }
    }

    /** Chặn biến thể thay thế đã ngừng bán, thuộc sản phẩm đã xoá hoặc không đủ tồn kho. */
    private void requireReplacementSellable(ProductVariant variant, int quantity) {
        if (variant.getProduct().isDeleted() || variant.getProduct().getStatus() != ProductStatus.ACTIVE) {
            throw new BusinessException("Sản phẩm \"" + variant.getProduct().getName()
                    + "\" đang ngừng bán nên không thể chọn làm sản phẩm thay thế.");
        }
        if (variant.getStatus() != VariantStatus.ACTIVE) {
            throw new BusinessException("SKU " + variant.getSku()
                    + " đang ngừng bán nên không thể chọn làm sản phẩm thay thế.");
        }
        if (variant.getStockQuantity() < quantity) {
            throw new BusinessException("SKU " + variant.getSku() + " chỉ còn " + variant.getStockQuantity()
                    + " sản phẩm, không đủ cho số lượng " + quantity + " trong đơn.");
        }
    }

    // ==================== ADMIN TẠO ĐƠN THỦ CÔNG ====================

    /**
     * Tạo đơn thủ công (bán tại quầy / khách đặt qua điện thoại) theo AGENTS.md mục 4.6:
     * {@code order_source = MANUAL}, {@code created_by = admin}, {@code user_id} có thể NULL (khách vãng lai),
     * và có thể xác nhận ngay (trừ kho luôn) trong cùng transaction.
     *
     * An toàn dữ liệu: kiểm tra đủ tồn kho TRƯỚC khi ghi, và nếu xác nhận ngay thì việc trừ kho vẫn
     * dùng UPDATE có điều kiện ở {@link #changeStatus} nên không thể bán quá kho.
     */
    @Override
    @Transactional
    public Order createManualOrder(ManualOrderForm form, Integer adminUserId) {
        User admin = userRepository.getReferenceById(adminUserId);

        // 1) Gom dòng hàng theo SKU (nhập trùng SKU thì cộng dồn vào một dòng)
        Map<Integer, ProductVariant> variantById = new LinkedHashMap<>();
        Map<Integer, Integer> quantityByVariantId = new LinkedHashMap<>();
        int rowNumber = 0;
        for (ManualOrderLineForm line : form.getLines()) {
            rowNumber++;
            if (line == null || !StringUtils.hasText(line.getSku())) {
                continue;   // ô trống: bỏ qua để admin không phải xoá hết các dòng thừa
            }
            String sku = line.getSku().trim();
            int quantity = line.getQuantity() == null ? 0 : line.getQuantity();
            if (quantity < 1) {
                throw new BusinessException("Dòng " + rowNumber + ": số lượng phải từ 1 trở lên.");
            }
            int lineNumber = rowNumber;   // bản sao final để dùng trong lambda báo lỗi
            ProductVariant variant = productVariantRepository.findBySku(sku)
                    .orElseThrow(() -> new BusinessException(
                            "Dòng " + lineNumber + ": không tìm thấy SKU \"" + sku + "\" trong hệ thống."));
            if (variant.getStatus() != VariantStatus.ACTIVE) {
                throw new BusinessException("Dòng " + rowNumber + ": SKU " + variant.getSku()
                        + " đang ở trạng thái ngừng bán, hãy kích hoạt lại trước khi bán.");
            }
            variantById.put(variant.getId(), variant);
            quantityByVariantId.merge(variant.getId(), quantity, Integer::sum);
        }
        if (quantityByVariantId.isEmpty()) {
            throw new BusinessException("Đơn thủ công phải có ít nhất 1 sản phẩm: hãy nhập SKU và số lượng.");
        }

        List<OrderItem> items = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : quantityByVariantId.entrySet()) {
            ProductVariant variant = variantById.get(entry.getKey());
            int quantity = entry.getValue();
            if (variant.getStockQuantity() < quantity) {
                throw new BusinessException("SKU " + variant.getSku() + " chỉ còn " + variant.getStockQuantity()
                        + " sản phẩm nhưng đơn cần " + quantity + ".");
            }
            items.add(OrderItem.from(variant, quantity));
        }

        // 2) Khách hàng: theo email (nếu admin nhập) hoặc khách vãng lai
        User customer = null;
        if (StringUtils.hasText(form.getCustomerEmail())) {
            String email = form.getCustomerEmail().trim();
            customer = userRepository.findByEmail(email)
                    .orElseThrow(() -> new BusinessException(
                            "Không tìm thấy tài khoản khách hàng với email \"" + email + "\"."));
        }

        // 3) Thông tin nhận hàng: để trống thì lấy từ tài khoản khách và địa chỉ mặc định của khách
        String receiverName = trimToNull(form.getReceiverName());
        String receiverPhone = trimToNull(form.getReceiverPhone());
        String shippingAddress = trimToNull(form.getShippingAddress());
        if (customer != null) {
            if (receiverName == null) {
                receiverName = customer.getFullName();
            }
            if (receiverPhone == null) {
                receiverPhone = trimToNull(customer.getPhone());
            }
            if (shippingAddress == null) {
                Integer customerId = customer.getId();
                shippingAddress = addressRepository
                        .findFirstByUserIdAndDefaultAddressTrueAndDeletedFalse(customerId)
                        .map(UserAddress::getFullAddress)
                        .orElse(null);
            }
        }
        if (receiverName == null) {
            throw new BusinessException("Vui lòng nhập tên người nhận hàng.");
        }
        if (receiverPhone == null) {
            throw new BusinessException("Vui lòng nhập số điện thoại người nhận hàng.");
        }
        if (shippingAddress == null) {
            throw new BusinessException("Vui lòng nhập địa chỉ giao hàng "
                    + "(khách này chưa có địa chỉ mặc định trong Địa chỉ).");
        }

        PaymentMethod paymentMethod = parseManualPaymentMethod(form.getPaymentMethod());
        BigDecimal shippingFee = form.getShippingFee() == null ? BigDecimal.ZERO : form.getShippingFee();
        if (shippingFee.signum() < 0 || shippingFee.compareTo(new BigDecimal("1000000")) > 0) {
            throw new BusinessException("Phí vận chuyển phải trong khoảng 0 – 1.000.000 ₫.");
        }
        shippingFee = shippingFee.setScale(0, RoundingMode.HALF_UP);

        Order order = new Order();
        String requestedCode = trimToNull(form.getOrderCode());
        order.setOrderCode(requestedCode == null
                ? orderCodeGenerator.next()
                : orderCodeGenerator.validatedOrNext(requestedCode));
        order.setOrderSource(OrderSource.MANUAL);
        order.setCreatedBy(admin);
        order.setUser(customer);
        order.setReceiverName(receiverName);
        order.setReceiverPhone(receiverPhone);
        order.setShippingAddress(shippingAddress);
        order.setOrderStatus(OrderStatus.PENDING);
        order.setStockDeducted(false);
        order.setNote(trimToNull(form.getNote()));
        items.forEach(order::addItem);

        BigDecimal subtotal = items.stream()
                .map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal discount = BigDecimal.ZERO;
        Voucher voucher = null;
        if (StringUtils.hasText(form.getVoucherCode())) {
            voucher = voucherService.requireUsable(form.getVoucherCode(), subtotal);
            discount = voucherService.discountOf(voucher, subtotal);
        }
        order.setSubtotal(subtotal);
        order.setShippingFee(shippingFee);
        order.setDiscountAmount(discount);
        order.setVoucher(voucher);
        order.setTotalAmount(subtotal.add(shippingFee).subtract(discount));

        Order saved = orderRepository.save(order);
        if (voucher != null) {
            voucherService.consume(voucher);
        }

        Payment payment = new Payment();
        payment.setOrder(saved);
        payment.setPaymentMethod(paymentMethod);
        // Bán tại quầy thu tiền ngay ⇒ ghi nhận đã thanh toán luôn; COD/chuyển khoản thì còn chờ.
        boolean paidAtCounter = paymentMethod == PaymentMethod.CASH;
        payment.setPaymentStatus(paidAtCounter ? PaymentStatus.PAID : PaymentStatus.PENDING);
        payment.setAmount(saved.getTotalAmount());
        if (paidAtCounter) {
            payment.markPaid(admin);
        }
        paymentRepository.save(payment);
        saved.setPayment(payment);   // giữ liên kết hai chiều trong bộ nhớ cho người gọi

        historyRepository.save(OrderStatusHistory.record(saved, null, OrderStatus.PENDING, admin,
                customer == null ? "Admin tạo đơn thủ công (khách mua tại quầy)" : "Admin tạo đơn thủ công"));

        // 4) Xác nhận ngay: trừ kho + thêm 1 dòng lịch sử, tất cả trong transaction này
        if (form.isConfirmNow()) {
            changeStatus(saved, OrderStatus.CONFIRMED, admin, "Admin xác nhận ngay khi tạo đơn");
        }
        return saved;
    }

    /** Đơn thủ công cho phép cả tiền mặt tại quầy (CASH), khác đơn online. */
    private PaymentMethod parseManualPaymentMethod(String rawMethod) {
        String value = trimToNull(rawMethod);
        if (value == null) {
            throw new BusinessException("Vui lòng chọn phương thức thanh toán cho đơn hàng.");
        }
        return switch (value.toUpperCase()) {
            case "CASH" -> PaymentMethod.CASH;
            case "COD" -> PaymentMethod.COD;
            case "BANK_TRANSFER" -> PaymentMethod.BANK_TRANSFER;
            default -> throw new BusinessException("Phương thức thanh toán không hợp lệ.");
        };
    }

    // ==================== phần dùng chung ====================

    /**
     * Đổi trạng thái đơn theo đúng máy trạng thái, kèm trừ/cộng kho, cập nhật thanh toán
     * và ghi lịch sử. Chạy trong transaction của phương thức gọi (mỗi lần đổi = 1 transaction).
     */
    private void changeStatus(Order order, OrderStatus target, User actor, String note) {
        OrderStatus current = order.getOrderStatus();
        if (current == target) {
            throw new BusinessException("Đơn hàng đang ở trạng thái \"" + target.getLabel() + "\" rồi.");
        }
        if (!current.canTransitionTo(target)) {
            throw new BusinessException("Không thể chuyển đơn từ \"" + current.getLabel()
                    + "\" sang \"" + target.getLabel() + "\".");
        }

        // 1) Kho: xác nhận đơn ⇒ trừ kho; huỷ/hoàn trả ⇒ cộng lại kho + trả lượt voucher
        if (target == OrderStatus.CONFIRMED) {
            deductStock(order);
        } else if (target == OrderStatus.CANCELLED || target == OrderStatus.RETURNED) {
            restoreStock(order);
            Voucher voucher = order.getVoucher();
            voucherService.release(voucher == null ? null : voucher.getId());
            order.setCancelReason(note);
        }

        // 2) Thanh toán: COD giao thành công ⇒ đã thanh toán; hoàn trả đơn đã PAID ⇒ hoàn tiền
        Payment payment = order.getPayment();
        if (payment != null) {
            if (target == OrderStatus.DELIVERED && payment.getPaymentMethod() == PaymentMethod.COD
                    && payment.getPaymentStatus() == PaymentStatus.PENDING) {
                payment.markPaid(actor);
            } else if (target == OrderStatus.RETURNED && payment.getPaymentStatus() == PaymentStatus.PAID) {
                payment.setPaymentStatus(PaymentStatus.REFUNDED);
            }
            paymentRepository.save(payment);
        }

        // 3) Trạng thái + cờ trừ kho (khớp CK_orders_stock)
        order.setOrderStatus(target);
        order.setStockDeducted(target.isStockDeducted());
        orderRepository.save(order);

        // 4) Mỗi lần đổi trạng thái BẮT BUỘC ghi 1 dòng lịch sử
        historyRepository.save(OrderStatusHistory.record(order, current, target, actor, note));
    }

    /** Trừ kho theo product_variant_id TĂNG DẦN (tránh deadlock khi 2 đơn cùng lúc); thiếu hàng ⇒ rollback. */
    private void deductStock(Order order) {
        for (OrderItem item : sortedItems(order)) {
            int updated = productVariantRepository.deductStock(item.getProductVariant().getId(), item.getQuantity());
            if (updated == 0) {
                throw new BusinessException("Không đủ tồn kho cho SKU " + item.getSku() + " (" + item.getProductName()
                        + ") nên chưa thể xác nhận đơn. Bạn kiểm tra lại kho hoặc huỷ đơn này nhé.");
            }
        }
    }

    /** Cộng lại kho – chỉ chạy khi đơn từng trừ kho (CONFIRMED/SHIPPING/DELIVERED). */
    private void restoreStock(Order order) {
        if (!order.isStockDeducted()) {
            return;
        }
        for (OrderItem item : sortedItems(order)) {
            productVariantRepository.restoreStock(item.getProductVariant().getId(), item.getQuantity());
        }
    }

    private List<OrderItem> sortedItems(Order order) {
        return order.getItems().stream()
                .sorted(Comparator.comparing(item -> item.getProductVariant().getId()))
                .toList();
    }

    private Order requireOwnOrder(Integer userId, Integer orderId) {
        return orderRepository.findDetailForUser(orderId, userId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy đơn hàng này trong tài khoản của bạn."));
    }

    /** Bản có khoá bi quan – dùng cho huỷ/hoàn trả để hai thao tác đồng thời không cùng đọc PENDING. */
    private Order requireOwnOrderForUpdate(Integer userId, Integer orderId) {
        return orderRepository.findOwnByIdForUpdate(orderId, userId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy đơn hàng này trong tài khoản của bạn."));
    }

    private OrderDetailView toDetailView(Order order) {
        List<OrderStatusHistory> timeline = historyRepository.findTimeline(order.getId());
        Voucher voucher = order.getVoucher();
        String voucherLabel = voucher == null ? null : voucher.getCode() + " – " + voucherService.describe(voucher);
        return new OrderDetailView(order, timeline, voucherLabel);
    }

    private String requireReason(String reason, String action) {
        String trimmed = trimToNull(reason);
        if (trimmed == null || trimmed.length() < 10) {
            throw new BusinessException("Vui lòng nhập lý do " + action + " (ít nhất 10 ký tự) để BadStore hỗ trợ bạn tốt hơn.");
        }
        return trimmed;
    }

    private String normalizeKeyword(String keyword) {
        String trimmed = trimToNull(keyword);
        return trimmed == null ? null : trimmed;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private PageRequest pageRequest(int pageNumber) {
        return PageRequest.of(Math.max(pageNumber, 1) - 1, PAGE_SIZE);
    }
}
