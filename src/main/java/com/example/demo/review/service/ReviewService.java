package com.example.demo.review.service;

import com.example.demo.review.dto.ReviewSummary;
import com.example.demo.review.entity.ProductReview;
import java.util.Optional;
import org.springframework.data.domain.Page;

/**
 * Đánh giá sản phẩm (tính năng mức Khá).
 *
 * Luật nghiệp vụ: CHỈ khách đã mua và đã nhận hàng (đơn DELIVERED) mới được đánh giá,
 * mỗi dòng đơn (order_items) đánh giá đúng MỘT lần – khớp ràng buộc UNIQUE product_reviews.order_item_id
 * trong BadStore.sql. Điểm sao 1–5, nội dung tối đa 1000 ký tự.
 */
public interface ReviewService {

    public static final int PAGE_SIZE = 5;

    Page<ProductReview> visibleReviews(Integer productId, int pageNumber);

    ReviewSummary summaryOf(Integer productId);

    Optional<Integer> findReviewableOrderItemId(Integer productId, Integer userId);

    boolean hasPurchased(Integer productId, Integer userId);

    ProductReview addReview(Integer productId, Integer userId, byte rating, String comment);
}
