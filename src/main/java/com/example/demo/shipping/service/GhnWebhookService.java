package com.example.demo.shipping.service;

import com.example.demo.shipping.dto.GhnWebhookPayload;
import com.example.demo.shipping.dto.GhnWebhookResult;
import org.springframework.stereotype.Service;

/**
 * NGHIỆP VỤ WEBHOOK GHN – nhận trạng thái vận đơn do GHN đẩy sang.
 *
 * Nguyên tắc an toàn:
 *  – Chỉ nhận khi webhook đã bật, có secret và shop-id (fail closed);
 *  – ShopID trong payload phải khớp cấu hình;
 *  – Tìm đơn theo mã vận đơn GHN, không tin mã đơn từ trình duyệt;
 *  – Chỉ {@link ShippingStatusMapper} quyết định trạng thái đơn đích;
 *  – Sự kiện trùng/cũ là no-op (2xx) để GHN không retry vô hạn;
 *  – Không gọi API GHN trong luồng webhook, tránh biến webhook thành proxy ra ngoài.
 *
 * Việc đổi trạng thái (kho, voucher, thanh toán, lịch sử) nằm trong transaction của
 * {@link OrderService#changeStatusPath}; service này cố ý không mở transaction bao ngoài
 * để lỗi nghiệp vụ không đầu độc transaction và để GHN nhận đúng mã 4xx/5xx.
 */
public interface GhnWebhookService {

    GhnWebhookResult handle(GhnWebhookPayload payload);
}
