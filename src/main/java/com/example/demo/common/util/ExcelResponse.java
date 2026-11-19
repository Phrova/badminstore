package com.example.demo.common.util;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Tạo response tải file Excel (.xlsx) thống nhất cho các màn hình admin. */
public final class ExcelResponse {

    public static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private ExcelResponse() {
    }

    public static ResponseEntity<byte[]> attachment(byte[] content, String fileName) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName)
                .build();
        return ResponseEntity.ok()
                .contentType(XLSX)
                .contentLength(content.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(content);
    }
}
