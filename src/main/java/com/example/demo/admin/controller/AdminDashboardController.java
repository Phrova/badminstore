package com.example.demo.admin.controller;

import com.example.demo.admin.service.DashboardService;
import com.example.demo.admin.service.ExcelReportService;
import com.example.demo.common.util.ExcelResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Trang Tổng quan của admin: thẻ số liệu + biểu đồ Chart.js + danh sách bán chạy/sắp hết hàng.
 * Mọi số liệu do DashboardService tính từ CSDL, không nhận từ client.
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final DashboardService dashboardService;
    private final ExcelReportService excelReportService;

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("stats", dashboardService.load());
        model.addAttribute("adminMenu", "dashboard");
        return "admin/dashboard";
    }

    /** Xuất báo cáo tổng quan ra Excel – dùng đúng số liệu đang hiển thị trên Dashboard. */
    @GetMapping("/bao-cao/tong-quan")
    public ResponseEntity<byte[]> exportDashboard() {
        byte[] content = excelReportService.exportDashboard(dashboardService.load());
        return ExcelResponse.attachment(content, excelReportService.fileName("bao-cao-tong-quan"));
    }
}
