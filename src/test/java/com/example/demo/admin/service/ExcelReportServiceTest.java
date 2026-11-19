package com.example.demo.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.admin.dto.DashboardView;
import com.example.demo.admin.dto.TopProductRow;
import com.example.demo.order.dto.OrderSummaryView;
import com.example.demo.catalog.entity.Product;
import com.example.demo.catalog.entity.ProductVariant;
import com.example.demo.order.enums.OrderSource;
import com.example.demo.order.enums.OrderStatus;
import com.example.demo.payment.enums.PaymentMethod;
import com.example.demo.payment.enums.PaymentStatus;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.example.demo.admin.service.impl.ExcelReportServiceImpl;

/** Kiểm tra cấu trúc và số liệu cơ bản của workbook Excel do {@link ExcelReportService} tạo. */
class ExcelReportServiceTest {

    private final ExcelReportService service = new ExcelReportServiceImpl();

    @Test
    @DisplayName("Báo cáo tổng quan: đủ 5 sheet, đúng số liệu tóm tắt và bảng doanh thu")
    void exportDashboard_createsAllSheets() throws IOException {
        DashboardView stats = new DashboardView(
                3, 5, 10, 20, 30,
                new BigDecimal("1234000"),
                new BigDecimal("5678000"),
                List.of("01/11", "02/11"),
                List.of(new BigDecimal("100000"), new BigDecimal("200000")),
                List.of(2L, 3L),
                List.of("Chờ xác nhận", "Đã huỷ"),
                List.of(4L, 1L),
                List.of(new TopProductRow("Vợt cầu lông A", 2L, new BigDecimal("3000000"))),
                List.of(lowStockVariant()));

        byte[] content = service.exportDashboard(stats);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(5);
            assertThat(workbook.getSheet("Tổng quan")).isNotNull();
            assertThat(workbook.getSheet("Doanh thu 14 ngày")).isNotNull();
            assertThat(workbook.getSheet("Trạng thái đơn")).isNotNull();
            assertThat(workbook.getSheet("Sản phẩm bán chạy")).isNotNull();
            assertThat(workbook.getSheet("Sắp hết hàng")).isNotNull();

            Sheet summary = workbook.getSheet("Tổng quan");
            assertThat(summary.getRow(0).getCell(0).getStringCellValue())
                    .contains("BÁO CÁO TỔNG QUAN BÁN HÀNG");
            assertThat(summary.getRow(3).getCell(0).getStringCellValue()).isEqualTo("Chỉ số");
            assertThat(summary.getRow(4).getCell(0).getStringCellValue()).isEqualTo("Đơn chờ xác nhận");
            assertThat(summary.getRow(4).getCell(1).getNumericCellValue()).isEqualTo(3d);
            assertThat(summary.getRow(9).getCell(1).getNumericCellValue()).isEqualTo(1_234_000d);
            assertThat(summary.getRow(10).getCell(1).getNumericCellValue()).isEqualTo(5_678_000d);

            Sheet revenue = workbook.getSheet("Doanh thu 14 ngày");
            assertThat(revenue.getRow(3).getCell(1).getStringCellValue()).isEqualTo("Doanh thu (₫)");
            assertThat(revenue.getRow(4).getCell(0).getStringCellValue()).isEqualTo("01/11");
            assertThat(revenue.getRow(4).getCell(1).getNumericCellValue()).isEqualTo(100_000d);
            assertThat(revenue.getRow(5).getCell(2).getNumericCellValue()).isEqualTo(3d);
            assertThat(revenue.getRow(6).getCell(0).getStringCellValue()).isEqualTo("Tổng 14 ngày");
            assertThat(revenue.getRow(6).getCell(1).getNumericCellValue()).isEqualTo(300_000d);

            Sheet topProducts = workbook.getSheet("Sản phẩm bán chạy");
            assertThat(topProducts.getRow(4).getCell(1).getStringCellValue()).isEqualTo("Vợt cầu lông A");
            assertThat(topProducts.getRow(4).getCell(2).getNumericCellValue()).isEqualTo(2d);
            assertThat(topProducts.getRow(4).getCell(3).getNumericCellValue()).isEqualTo(3_000_000d);

            Sheet lowStock = workbook.getSheet("Sắp hết hàng");
            assertThat(lowStock.getRow(4).getCell(1).getStringCellValue()).isEqualTo("SKU-LOW");
            assertThat(lowStock.getRow(4).getCell(6).getNumericCellValue()).isEqualTo(2d);
        }
    }

    @Test
    @DisplayName("Danh sách đơn hàng: có bộ lọc, đúng cột tiền/ngày và dòng tổng cộng")
    void exportOrders_writesFilterAndRows() throws IOException {
        OrderSummaryView order = new OrderSummaryView(
                1, "BS001", OrderSource.ONLINE, OrderStatus.DELIVERED,
                new BigDecimal("1234000"),
                LocalDateTime.of(2026, 11, 5, 10, 30),
                "Nguyễn Văn A", "a@example.com",
                PaymentMethod.COD, PaymentStatus.PENDING, 2L, 3L);

        byte[] content = service.exportOrders(List.of(order), "Trạng thái: Tất cả");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheet("Đơn hàng");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue())
                    .contains("Bộ lọc: Trạng thái: Tất cả");
            assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("Mã đơn");
            assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).isEqualTo("BS001");
            assertThat(sheet.getRow(4).getCell(3).getStringCellValue()).isEqualTo("a@example.com");
            assertThat(sheet.getRow(4).getCell(7).getStringCellValue()).isEqualTo("Giao thành công");
            assertThat(sheet.getRow(4).getCell(9).getNumericCellValue()).isEqualTo(3d);
            assertThat(sheet.getRow(4).getCell(10).getNumericCellValue()).isEqualTo(1_234_000d);
            assertThat(sheet.getRow(4).getCell(11).getDateCellValue()).isNotNull();
            assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("Tổng cộng");
            assertThat(sheet.getRow(5).getCell(10).getNumericCellValue()).isEqualTo(1_234_000d);
        }
    }

    private ProductVariant lowStockVariant() {
        Product product = new Product();
        product.setName("Vợt cầu lông B");

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("SKU-LOW");
        variant.setColor("Đỏ đen");
        variant.setWeightClass("4U");
        variant.setGripSize("G5");
        variant.setStockQuantity(2);
        return variant;
    }
}
