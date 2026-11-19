package com.example.demo.catalog.service;

import java.nio.file.Path;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

/**
 * Lưu ảnh sản phẩm do admin tải lên.
 *
 * Quy tắc bảo mật (mức Khá): giới hạn dung lượng, kiểm tra phần mở rộng VÀ MIME, KHÔNG dùng tên file gốc
 * (đổi thành UUID) nên không thể ghi đè hay chèn đường dẫn lạ vào thư mục upload.
 * Trả về URL công khai "/uploads/&lt;tên-file&gt;" để ghi vào product_images.image_url.
 */
public interface FileStorageService {

    public static final long MAX_IMAGE_SIZE = 5L * 1024 * 1024;

    Path getUploadDir();

    String storeImage(MultipartFile file);

    void deleteIfUploaded(String imageUrl);
}
