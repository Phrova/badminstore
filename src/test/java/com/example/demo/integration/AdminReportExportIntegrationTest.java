package com.example.demo.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.user.entity.User;
import com.example.demo.user.enums.UserRole;
import com.example.demo.user.enums.UserStatus;
import com.example.demo.user.repository.UserRepository;
import com.example.demo.common.util.ExcelResponse;
import java.io.ByteArrayInputStream;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Kiểm tra endpoint tải Excel thật qua MockMvc: quyền admin, header tải file và workbook đọc lại được. */
@SpringBootTest
@AutoConfigureMockMvc
class AdminReportExportIntegrationTest {

    private static final String PASSWORD = "Secret123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User admin;
    private MockHttpSession adminSession;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        admin = new User();
        admin.setEmail("admin.export." + suffix + "@example.com");
        admin.setPasswordHash(passwordEncoder.encode(PASSWORD));
        admin.setFullName("Quản trị viên xuất báo cáo");
        admin.setRole(UserRole.ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        admin = userRepository.save(admin);

        adminSession = new MockHttpSession();
        mockMvc.perform(post("/dang-nhap").with(csrf())
                        .param("email", admin.getEmail())
                        .param("password", PASSWORD)
                        .session(adminSession))
                .andExpect(status().is3xxRedirection());
    }

    @AfterEach
    void cleanUp() {
        if (admin != null && userRepository.existsById(admin.getId())) {
            userRepository.delete(admin);
        }
    }

    @Test
    @DisplayName("Xuất báo cáo tổng quan: trả file .xlsx đọc được, đủ sheet")
    void exportDashboard_returnsReadableWorkbook() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/bao-cao/tong-quan").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(content().contentTypeCompatibleWith(ExcelResponse.XLSX))
                .andReturn();

        assertWorkbook(result, workbook -> {
            assertThat(workbook.getSheet("Tổng quan")).isNotNull();
            assertThat(workbook.getSheet("Doanh thu 14 ngày")).isNotNull();
            assertThat(workbook.getSheet("Trạng thái đơn")).isNotNull();
            assertThat(workbook.getSheet("Sản phẩm bán chạy")).isNotNull();
            assertThat(workbook.getSheet("Sắp hết hàng")).isNotNull();
        });
    }

    @Test
    @DisplayName("Xuất danh sách đơn hàng: giữ bộ lọc trạng thái trong file")
    void exportOrders_keepsStatusFilter() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/don-hang/xuat-excel")
                        .param("status", "PENDING")
                        .param("keyword", "BS")
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(content().contentTypeCompatibleWith(ExcelResponse.XLSX))
                .andReturn();

        assertWorkbook(result, workbook -> {
            Sheet sheet = workbook.getSheet("Đơn hàng");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue())
                    .contains("Chờ xác nhận")
                    .contains("BS");
        });
    }

    private void assertWorkbook(MvcResult result, WorkbookAssertion assertion) throws Exception {
        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes).isNotEmpty();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertion.check(workbook);
        }
    }

    @FunctionalInterface
    private interface WorkbookAssertion {
        void check(XSSFWorkbook workbook) throws Exception;
    }
}
