package com.example.demo.admin.service;

import com.example.demo.admin.dto.DashboardView;
import com.example.demo.order.dto.OrderSummaryView;
import java.util.List;

/**
 * Tạo workbook Excel (.xlsx) cho các báo cáo của admin.
 *
 * <p>Dữ liệu đầu vào là DTO đã tính sẵn ở {@link DashboardService}/{@link OrderService};
 * lớp này chỉ trình bày, không tự truy vấn hay tính lại nghiệp vụ. Nhờ vậy số liệu trên
 * file Excel khớp với số liệu đang hiển thị trên Dashboard.</p>
 *
 * <p>Quy ước số liệu giữ nguyên như màn hình:</p>
 * – Doanh thu Dashboard dùng {@code orders.total_amount} của đơn DELIVERED (gồm ship, trừ voucher);
 * – Doanh thu sản phẩm bán chạy dùng {@code order_items.subtotal} (chưa gồm ship, chưa trừ voucher).
 */
public interface ExcelReportService {

    String fileName(String prefix);

    byte[] exportDashboard(DashboardView stats);

    byte[] exportOrders(List<OrderSummaryView> orders, String filterDescription);
}
