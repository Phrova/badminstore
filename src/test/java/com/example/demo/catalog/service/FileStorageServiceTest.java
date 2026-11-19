package com.example.demo.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.common.exception.BusinessException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import com.example.demo.catalog.service.impl.FileStorageServiceImpl;

/**
 * Kiểm thử giới hạn dung lượng ảnh upload (không cần CSDL).
 */
class FileStorageServiceTest {

    @TempDir
    Path uploadDir;

    @Test
    @DisplayName("Ảnh đúng 5 MB vẫn được lưu vào thư mục upload")
    void storesImageAtExactLimit() {
        FileStorageService service = new FileStorageServiceImpl(uploadDir.toString());
        MockMultipartFile file = new MockMultipartFile("file", "anh.png", "image/png",
                new byte[(int) FileStorageService.MAX_IMAGE_SIZE]);

        String url = service.storeImage(file);

        assertThat(url).startsWith("/uploads/").endsWith(".png");
        assertThat(Files.exists(uploadDir.resolve(url.substring("/uploads/".length())))).isTrue();
    }

    @Test
    @DisplayName("Ảnh lớn hơn 5 MB bị chặn với thông báo tiếng Việt")
    void rejectsImageOverLimit() {
        FileStorageService service = new FileStorageServiceImpl(uploadDir.toString());
        MockMultipartFile file = new MockMultipartFile("file", "anh.png", "image/png",
                new byte[(int) FileStorageService.MAX_IMAGE_SIZE + 1]);

        assertThatThrownBy(() -> service.storeImage(file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("tối đa 5 MB");
    }
}
