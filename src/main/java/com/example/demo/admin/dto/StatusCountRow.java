package com.example.demo.admin.dto;

import com.example.demo.order.enums.OrderStatus;

/** Số đơn theo từng trạng thái – dùng vẽ biểu đồ tròn ở dashboard. */
public record StatusCountRow(OrderStatus orderStatus, Long total) {
}
