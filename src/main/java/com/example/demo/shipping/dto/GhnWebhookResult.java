package com.example.demo.shipping.dto;

import com.example.demo.order.enums.OrderStatus;

/**
 * Kết quả xử lý một webhook GHN.
 *
 * @param applied     có thay đổi trạng thái đơn hay không
 * @param message     thông báo ngắn (không chứa secret/token)
 * @param orderId     đơn hàng liên quan (null nếu không tìm thấy)
 * @param orderStatus trạng thái đơn sau xử lý (null nếu không tìm thấy)
 */
public record GhnWebhookResult(boolean applied, String message, Integer orderId, OrderStatus orderStatus) {
}
