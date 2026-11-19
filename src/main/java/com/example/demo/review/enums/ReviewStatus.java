package com.example.demo.review.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Trạng thái hiển thị của đánh giá sản phẩm – khớp CK_reviews_status. */
@Getter
@RequiredArgsConstructor
public enum ReviewStatus {

    VISIBLE("Đang hiển thị"),
    HIDDEN("Đã ẩn");

    private final String label;
}
