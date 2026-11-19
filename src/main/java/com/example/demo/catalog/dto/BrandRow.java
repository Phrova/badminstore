package com.example.demo.catalog.dto;

import com.example.demo.catalog.entity.Brand;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Một dòng trong bảng quản lý hãng: hãng + số sản phẩm đang dùng. */
@Getter
@RequiredArgsConstructor
public class BrandRow {

    private final Brand brand;
    private final long productCount;
}
