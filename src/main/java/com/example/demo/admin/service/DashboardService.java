package com.example.demo.admin.service;

import com.example.demo.admin.dto.DashboardView;

/**
 * Thống kê cho trang Tổng quan của admin.
 *
 * QUY ƯỚC TÍNH (ghi rõ để khi bảo vệ giải thích được):
 * – "Doanh thu" chỉ tính đơn DELIVERED (tiền đã thu); đơn huỷ/hoàn trả không tính.
 * – "Số đơn" đếm theo ngày tạo, bỏ đơn đã huỷ (đơn hoàn trả vẫn tính vì đã từng bán).
 * – Biểu đồ gom số liệu trong Java từ danh sách dòng thô ⇒ không phụ thuộc hàm CAST ngày của SQL Server.
 */
public interface DashboardService {

    public static final int CHART_DAYS = 14;

    public static final int LOW_STOCK_THRESHOLD = 5;

    DashboardView load();
}
