package com.example.demo.cart.service;

import com.example.demo.cart.dto.CartLine;
import com.example.demo.cart.dto.CartView;

/**
 * Nghiệp vụ giỏ hàng (bảng cart_items – mỗi biến thể chỉ có 1 dòng/khách).
 *
 * Nguyên tắc kiểm tra số lượng (tiêu chí "validate chính xác số lượng" mức Khá):
 * – luôn đọc lại tồn kho từ DB ở MỌI thao tác (thêm/sửa), không tin số lượng từ client;
 * – số lượng 1 dòng từ 1..{@value #MAX_QUANTITY_PER_LINE} và không vượt tồn kho hiện tại;
 * – khi cộng dồn số lượng cũ + số lượng mới vẫn phải ≤ tồn kho.
 */
public interface CartService {

    public static final int MAX_QUANTITY_PER_LINE = 20;

    /** Xoá nhiều dòng đã chọn trong MỘT transaction; bỏ qua id không thuộc giỏ của khách. */
    int removeItems(Integer userId, java.util.List<Integer> cartItemIds);

    CartView getCart(Integer userId);

    int countItems(Integer userId);

    CartLine addToCart(Integer userId, Integer variantId, Integer quantity);

    CartLine addToCart(Integer userId, Integer productId, Integer variantId, Integer quantity);

    CartLine updateQuantity(Integer userId, Integer cartItemId, Integer quantity);

    void removeItem(Integer userId, Integer cartItemId);

    int clearCart(Integer userId);
}
