package com.example.demo.admin.service.impl;

import com.example.demo.admin.dto.DashboardView;
import com.example.demo.admin.dto.TopProductRow;
import com.example.demo.order.dto.OrderSummaryView;
import com.example.demo.catalog.entity.ProductVariant;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import com.example.demo.admin.service.ExcelReportService;

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
@Service
public class ExcelReportServiceImpl implements ExcelReportService {

    private static final DateTimeFormatter EXPORT_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private static final String MONEY_FORMAT = "#,##0";
    private static final String DATE_TIME_FORMAT = "dd/MM/yyyy HH:mm";

    /** Tên file gợi ý cho controller – không chứa dấu tiếng Việt để tương thích trình duyệt. */
    @Override
    public String fileName(String prefix) {
        return prefix + "-" + LocalDateTime.now().format(FILE_TIME) + ".xlsx";
    }

    /** Báo cáo tổng quan: 5 sheet số liệu đang có trên Dashboard. */
    @Override
    public byte[] exportDashboard(DashboardView stats) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            Styles styles = createStyles(workbook);
            writeSummarySheet(workbook, stats, styles);
            writeRevenueSheet(workbook, stats, styles);
            writeStatusSheet(workbook, stats, styles);
            writeTopProductSheet(workbook, stats, styles);
            writeLowStockSheet(workbook, stats, styles);

            workbook.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Không tạo được file Excel báo cáo tổng quan.", ex);
        }
    }

    /** Danh sách đơn hàng theo đúng bộ lọc trạng thái/từ khoá đang áp dụng. */
    @Override
    public byte[] exportOrders(List<OrderSummaryView> orders, String filterDescription) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            Styles styles = createStyles(workbook);
            List<OrderSummaryView> safeOrders = orders == null ? List.of() : orders;

            Sheet sheet = workbook.createSheet("Đơn hàng");
            setWidths(sheet, 6, 18, 22, 28, 28, 30, 24, 24, 10, 10, 18, 20);

            int headerRowIndex = writeHeading(sheet, "DANH SÁCH ĐƠN HÀNG",
                    "Bộ lọc: " + filterDescription + " · Thời điểm xuất: "
                            + LocalDateTime.now().format(EXPORT_TIME),
                    11, styles);
            writeHeader(sheet, headerRowIndex, styles,
                    "STT", "Mã đơn", "Nguồn", "Khách hàng", "Người nhận",
                    "Phương thức thanh toán", "Trạng thái thanh toán", "Trạng thái đơn",
                    "Số dòng", "Số lượng", "Tổng tiền (₫)", "Ngày đặt");

            int rowIndex = headerRowIndex + 1;
            long totalQuantity = 0;
            BigDecimal totalAmount = BigDecimal.ZERO;
            for (int i = 0; i < safeOrders.size(); i++) {
                OrderSummaryView order = safeOrders.get(i);
                long quantity = order.getItemQuantity() == null ? 0 : order.getItemQuantity();
                BigDecimal amount = order.getTotalAmount() == null ? BigDecimal.ZERO : order.getTotalAmount();
                totalQuantity += quantity;
                totalAmount = totalAmount.add(amount);

                Row row = sheet.createRow(rowIndex++);
                writeInteger(row, 0, i + 1, styles);
                writeText(row, 1, order.getOrderCode(), styles);
                writeText(row, 2, order.getOrderSource() == null ? "" : order.getOrderSource().getLabel(), styles);
                writeText(row, 3, order.getCustomerLabel(), styles);
                writeText(row, 4, order.getReceiverName(), styles);
                writeText(row, 5, order.getPaymentMethodLabel(), styles);
                writeText(row, 6, order.getPaymentStatusLabel(), styles);
                writeText(row, 7, order.getOrderStatus() == null ? "" : order.getOrderStatus().getLabel(), styles);
                writeLong(row, 8, order.getLineCount() == null ? 0 : order.getLineCount(), styles);
                writeLong(row, 9, quantity, styles);
                writeMoney(row, 10, amount, styles);
                writeDateTime(row, 11, order.getCreatedAt(), styles);
            }

            Row totalRow = sheet.createRow(rowIndex);
            writeBoldText(totalRow, 0, "Tổng cộng", styles);
            writeBoldLong(totalRow, 9, totalQuantity, styles);
            writeBoldMoney(totalRow, 10, totalAmount, styles);

            int noteRow = rowIndex + 2;
            writeNote(sheet, noteRow,
                    "Tổng tiền là total_amount: đã gồm phí vận chuyển và trừ giảm giá voucher. "
                            + "Đơn huỷ/hoàn trả vẫn xuất hiện nếu bộ lọc không loại trừ.",
                    11, styles);

            if (rowIndex > headerRowIndex + 1) {
                sheet.setAutoFilter(new CellRangeAddress(headerRowIndex, rowIndex - 1, 0, 11));
            }
            sheet.createFreezePane(0, headerRowIndex + 1);

            workbook.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Không tạo được file Excel danh sách đơn hàng.", ex);
        }
    }

    // ==================== từng sheet của Dashboard ====================

    private void writeSummarySheet(XSSFWorkbook workbook, DashboardView stats, Styles styles) {
        Sheet sheet = workbook.createSheet("Tổng quan");
        setWidths(sheet, 34, 22);

        int headerRowIndex = writeHeading(sheet, "BÁO CÁO TỔNG QUAN BÁN HÀNG",
                "Thời điểm xuất: " + LocalDateTime.now().format(EXPORT_TIME), 1, styles);
        writeHeader(sheet, headerRowIndex, styles, "Chỉ số", "Giá trị");

        int rowIndex = headerRowIndex + 1;
        writeLabelValue(sheet, rowIndex++, "Đơn chờ xác nhận", stats.getPendingOrders(), styles);
        writeLabelValue(sheet, rowIndex++, "Đơn tạo hôm nay", stats.getTodayOrders(), styles);
        writeLabelValue(sheet, rowIndex++, "Tổng khách hàng", stats.getTotalCustomers(), styles);
        writeLabelValue(sheet, rowIndex++, "Sản phẩm đang bán", stats.getActiveProducts(), styles);
        writeLabelValue(sheet, rowIndex++, "Biến thể đang bán", stats.getActiveVariants(), styles);
        writeLabelMoney(sheet, rowIndex++, "Doanh thu hôm nay (₫)", stats.getTodayRevenue(), styles);
        writeLabelMoney(sheet, rowIndex, "Doanh thu tháng này (₫)", stats.getMonthRevenue(), styles);

        writeNote(sheet, rowIndex + 2,
                "Doanh thu chỉ tính đơn đã giao thành công (DELIVERED), đã gồm phí vận chuyển "
                        + "và trừ giảm giá voucher.", 1, styles);
        sheet.createFreezePane(0, headerRowIndex + 1);
    }

    private void writeRevenueSheet(XSSFWorkbook workbook, DashboardView stats, Styles styles) {
        Sheet sheet = workbook.createSheet("Doanh thu 14 ngày");
        setWidths(sheet, 16, 20, 14);

        int headerRowIndex = writeHeading(sheet, "DOANH THU 14 NGÀY GẦN NHẤT",
                "Thời điểm xuất: " + LocalDateTime.now().format(EXPORT_TIME), 2, styles);
        writeHeader(sheet, headerRowIndex, styles, "Ngày (dd/MM)", "Doanh thu (₫)", "Số đơn");

        int rowIndex = headerRowIndex + 1;
        BigDecimal totalRevenue = BigDecimal.ZERO;
        long totalOrders = 0;
        List<BigDecimal> revenues = stats.getRevenueValues();
        List<Long> orderCounts = stats.getOrderCountValues();
        for (int i = 0; i < stats.getRevenueLabels().size(); i++) {
            BigDecimal revenue = i < revenues.size() && revenues.get(i) != null
                    ? revenues.get(i) : BigDecimal.ZERO;
            long count = i < orderCounts.size() && orderCounts.get(i) != null
                    ? orderCounts.get(i) : 0L;
            totalRevenue = totalRevenue.add(revenue);
            totalOrders += count;

            Row row = sheet.createRow(rowIndex++);
            writeText(row, 0, stats.getRevenueLabels().get(i), styles);
            writeMoney(row, 1, revenue, styles);
            writeLong(row, 2, count, styles);
        }

        Row totalRow = sheet.createRow(rowIndex);
        writeBoldText(totalRow, 0, "Tổng 14 ngày", styles);
        writeBoldMoney(totalRow, 1, totalRevenue, styles);
        writeBoldLong(totalRow, 2, totalOrders, styles);

        writeNote(sheet, rowIndex + 2,
                "Doanh thu chỉ tính đơn đã giao thành công (DELIVERED), đã gồm phí vận chuyển "
                        + "và trừ giảm giá voucher.", 2, styles);
        if (rowIndex > headerRowIndex + 1) {
            sheet.setAutoFilter(new CellRangeAddress(headerRowIndex, rowIndex - 1, 0, 2));
        }
        sheet.createFreezePane(0, headerRowIndex + 1);
    }

    private void writeStatusSheet(XSSFWorkbook workbook, DashboardView stats, Styles styles) {
        Sheet sheet = workbook.createSheet("Trạng thái đơn");
        setWidths(sheet, 26, 14);

        int headerRowIndex = writeHeading(sheet, "SỐ ĐƠN THEO TRẠNG THÁI",
                "Thời điểm xuất: " + LocalDateTime.now().format(EXPORT_TIME), 1, styles);
        writeHeader(sheet, headerRowIndex, styles, "Trạng thái", "Số đơn");

        int rowIndex = headerRowIndex + 1;
        long total = 0;
        List<Long> values = stats.getStatusValues();
        for (int i = 0; i < stats.getStatusLabels().size(); i++) {
            long value = i < values.size() && values.get(i) != null ? values.get(i) : 0L;
            total += value;

            Row row = sheet.createRow(rowIndex++);
            writeText(row, 0, stats.getStatusLabels().get(i), styles);
            writeLong(row, 1, value, styles);
        }

        Row totalRow = sheet.createRow(rowIndex);
        writeBoldText(totalRow, 0, "Tổng cộng", styles);
        writeBoldLong(totalRow, 1, total, styles);

        if (rowIndex > headerRowIndex + 1) {
            sheet.setAutoFilter(new CellRangeAddress(headerRowIndex, rowIndex - 1, 0, 1));
        }
        sheet.createFreezePane(0, headerRowIndex + 1);
    }

    private void writeTopProductSheet(XSSFWorkbook workbook, DashboardView stats, Styles styles) {
        Sheet sheet = workbook.createSheet("Sản phẩm bán chạy");
        setWidths(sheet, 8, 40, 14, 20);

        int headerRowIndex = writeHeading(sheet, "SẢN PHẨM BÁN CHẠY",
                "Thời điểm xuất: " + LocalDateTime.now().format(EXPORT_TIME), 3, styles);
        writeHeader(sheet, headerRowIndex, styles, "STT", "Sản phẩm", "Đã bán", "Doanh thu (₫)");

        int rowIndex = headerRowIndex + 1;
        long totalQuantity = 0;
        BigDecimal totalRevenue = BigDecimal.ZERO;
        for (int i = 0; i < stats.getTopProducts().size(); i++) {
            TopProductRow product = stats.getTopProducts().get(i);
            long quantity = product.quantity() == null ? 0 : product.quantity();
            BigDecimal revenue = product.revenue() == null ? BigDecimal.ZERO : product.revenue();
            totalQuantity += quantity;
            totalRevenue = totalRevenue.add(revenue);

            Row row = sheet.createRow(rowIndex++);
            writeInteger(row, 0, i + 1, styles);
            writeText(row, 1, product.productName(), styles);
            writeLong(row, 2, quantity, styles);
            writeMoney(row, 3, revenue, styles);
        }

        Row totalRow = sheet.createRow(rowIndex);
        writeBoldText(totalRow, 0, "Tổng top 5", styles);
        writeBoldLong(totalRow, 2, totalQuantity, styles);
        writeBoldMoney(totalRow, 3, totalRevenue, styles);

        writeNote(sheet, rowIndex + 2,
                "Chỉ tính đơn CONFIRMED, SHIPPING, DELIVERED. Doanh thu là tiền hàng "
                        + "(order_items.subtotal), chưa gồm phí ship và chưa trừ voucher.", 3, styles);
        if (rowIndex > headerRowIndex + 1) {
            sheet.setAutoFilter(new CellRangeAddress(headerRowIndex, rowIndex - 1, 0, 3));
        }
        sheet.createFreezePane(0, headerRowIndex + 1);
    }

    private void writeLowStockSheet(XSSFWorkbook workbook, DashboardView stats, Styles styles) {
        Sheet sheet = workbook.createSheet("Sắp hết hàng");
        setWidths(sheet, 8, 20, 40, 16, 14, 14, 14);

        int headerRowIndex = writeHeading(sheet, "BIẾN THỂ SẮP HẾT HÀNG",
                "Thời điểm xuất: " + LocalDateTime.now().format(EXPORT_TIME), 6, styles);
        writeHeader(sheet, headerRowIndex, styles,
                "STT", "SKU", "Sản phẩm", "Màu sắc", "Trọng lượng", "Cỡ cán", "Tồn kho");

        int rowIndex = headerRowIndex + 1;
        for (int i = 0; i < stats.getLowStockVariants().size(); i++) {
            ProductVariant variant = stats.getLowStockVariants().get(i);
            Row row = sheet.createRow(rowIndex++);
            writeInteger(row, 0, i + 1, styles);
            writeText(row, 1, variant.getSku(), styles);
            writeText(row, 2, variant.getProduct() == null ? "" : variant.getProduct().getName(), styles);
            writeText(row, 3, variant.getColor(), styles);
            writeText(row, 4, variant.getWeightClass(), styles);
            writeText(row, 5, variant.getGripSize(), styles);
            writeLong(row, 6, variant.getStockQuantity(), styles);
        }

        writeNote(sheet, rowIndex + 1,
                "Biến thể ACTIVE có tồn kho ≤ 5. Danh sách tối đa 5 dòng theo Dashboard.", 6, styles);
        if (rowIndex > headerRowIndex + 1) {
            sheet.setAutoFilter(new CellRangeAddress(headerRowIndex, rowIndex - 1, 0, 6));
        }
        sheet.createFreezePane(0, headerRowIndex + 1);
    }

    // ==================== tiện ích ghi ô ====================

    private int writeHeading(Sheet sheet, String title, String subtitle, int lastColumn, Styles styles) {
        Row titleRow = sheet.createRow(0);
        titleRow.setHeightInPoints(24);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(styles.title());
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, lastColumn));

        Row subtitleRow = sheet.createRow(1);
        Cell subtitleCell = subtitleRow.createCell(0);
        subtitleCell.setCellValue(subtitle);
        subtitleCell.setCellStyle(styles.note());
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, lastColumn));

        return 3;
    }

    private void writeHeader(Sheet sheet, int rowIndex, Styles styles, String... headers) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(30);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(styles.header());
        }
    }

    private void writeLabelValue(Sheet sheet, int rowIndex, String label, long value, Styles styles) {
        Row row = sheet.createRow(rowIndex);
        writeText(row, 0, label, styles);
        writeLong(row, 1, value, styles);
    }

    private void writeLabelMoney(Sheet sheet, int rowIndex, String label, BigDecimal value, Styles styles) {
        Row row = sheet.createRow(rowIndex);
        writeText(row, 0, label, styles);
        writeMoney(row, 1, value, styles);
    }

    private void writeText(Row row, int column, String value, Styles styles) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(styles.text());
    }

    private void writeBoldText(Row row, int column, String value, Styles styles) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(styles.bold());
    }

    private void writeInteger(Row row, int column, int value, Styles styles) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(styles.integer());
    }

    private void writeLong(Row row, int column, long value, Styles styles) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(styles.integer());
    }

    private void writeBoldLong(Row row, int column, long value, Styles styles) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(styles.bold());
    }

    private void writeMoney(Row row, int column, BigDecimal value, Styles styles) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? 0 : value.doubleValue());
        cell.setCellStyle(styles.money());
    }

    private void writeBoldMoney(Row row, int column, BigDecimal value, Styles styles) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? 0 : value.doubleValue());
        cell.setCellStyle(styles.moneyBold());
    }

    private void writeDateTime(Row row, int column, LocalDateTime value, Styles styles) {
        Cell cell = row.createCell(column);
        if (value == null) {
            cell.setBlank();
        } else {
            cell.setCellValue(value);
        }
        cell.setCellStyle(styles.dateTime());
    }

    private void writeNote(Sheet sheet, int rowIndex, String note, int lastColumn, Styles styles) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(48);
        Cell cell = row.createCell(0);
        cell.setCellValue(note);
        cell.setCellStyle(styles.note());
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, lastColumn));
    }

    private void setWidths(Sheet sheet, int... widths) {
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i] * 256);
        }
    }

    // ==================== style ====================

    private Styles createStyles(XSSFWorkbook workbook) {
        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 16);
        titleFont.setColor(IndexedColors.DARK_BLUE.getIndex());
        CellStyle title = workbook.createCellStyle();
        title.setFont(titleFont);
        title.setVerticalAlignment(VerticalAlignment.CENTER);

        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        CellStyle header = workbook.createCellStyle();
        header.setFont(headerFont);
        header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setAlignment(HorizontalAlignment.CENTER);
        header.setVerticalAlignment(VerticalAlignment.CENTER);
        header.setWrapText(true);
        header.setBorderBottom(BorderStyle.THIN);

        Font boldFont = workbook.createFont();
        boldFont.setBold(true);
        CellStyle bold = workbook.createCellStyle();
        bold.setFont(boldFont);
        bold.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle text = workbook.createCellStyle();
        text.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle integer = workbook.createCellStyle();
        integer.setAlignment(HorizontalAlignment.CENTER);
        integer.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle money = workbook.createCellStyle();
        money.setDataFormat(workbook.createDataFormat().getFormat(MONEY_FORMAT));
        money.setAlignment(HorizontalAlignment.RIGHT);
        money.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle moneyBold = workbook.createCellStyle();
        moneyBold.setFont(boldFont);
        moneyBold.setDataFormat(workbook.createDataFormat().getFormat(MONEY_FORMAT));
        moneyBold.setAlignment(HorizontalAlignment.RIGHT);
        moneyBold.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle dateTime = workbook.createCellStyle();
        dateTime.setDataFormat(workbook.createDataFormat().getFormat(DATE_TIME_FORMAT));
        dateTime.setAlignment(HorizontalAlignment.CENTER);
        dateTime.setVerticalAlignment(VerticalAlignment.CENTER);

        Font noteFont = workbook.createFont();
        noteFont.setItalic(true);
        noteFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        CellStyle note = workbook.createCellStyle();
        note.setFont(noteFont);
        note.setVerticalAlignment(VerticalAlignment.CENTER);
        note.setWrapText(true);

        return new Styles(title, header, bold, text, integer, money, moneyBold, dateTime, note);
    }

    private record Styles(CellStyle title, CellStyle header, CellStyle bold, CellStyle text,
                          CellStyle integer, CellStyle money, CellStyle moneyBold,
                          CellStyle dateTime, CellStyle note) {
    }
}
