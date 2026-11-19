package com.example.demo.catalog.dto;

import com.example.demo.catalog.entity.ProductVariant;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Một biến thể đã làm phẳng để trang chi tiết và JavaScript chọn phiên bản sử dụng.
 *
 * DTO chỉ chứa dữ liệu cần cho giao diện, không giữ quan hệ LAZY tới Product.
 * Nhờ vậy template vẫn chạy đúng khi spring.jpa.open-in-view = false và không vô tình
 * kéo theo truy vấn N+1 khi render danh sách biến thể.
 */
public record ProductVariantView(
        Integer id,
        String sku,
        String color,
        String weightClass,
        String gripSize,
        BigDecimal price,
        BigDecimal originalPrice,
        int stockQuantity,
        String imageUrl) {

    public static ProductVariantView from(ProductVariant variant) {
        return new ProductVariantView(
                variant.getId(),
                variant.getSku(),
                variant.getColor(),
                variant.getWeightClass(),
                variant.getGripSize(),
                variant.getPrice(),
                variant.getOriginalPrice(),
                variant.getStockQuantity(),
                variant.getImageUrl());
    }

    // Getter kiểu JavaBean để Thymeleaf/SpEL đọc được như DTO thường (record accessor vẫn giữ nguyên).
    public Integer getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getColor() {
        return color;
    }

    public String getWeightClass() {
        return weightClass;
    }

    public String getGripSize() {
        return gripSize;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getOriginalPrice() {
        return originalPrice;
    }

    public int getStockQuantity() {
        return stockQuantity;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public boolean isInStock() {
        return stockQuantity > 0;
    }

    public boolean isDiscounted() {
        return originalPrice != null && price != null && originalPrice.compareTo(price) > 0;
    }

    /** Phần trăm giảm đã làm tròn, dùng cho badge trên thẻ và khối mua. */
    public int getDiscountPercent() {
        if (!isDiscounted() || originalPrice.signum() <= 0) {
            return 0;
        }
        return originalPrice.subtract(price)
                .multiply(BigDecimal.valueOf(100))
                .divide(originalPrice, 0, RoundingMode.HALF_UP)
                .intValue();
    }

    /** Nhãn đầy đủ để đọc bằng screen reader và hiển thị ở bảng thông số. */
    public String getLabel() {
        return color + " · " + weightClass + " · " + gripSize;
    }

    /** Câu trạng thái kho dùng chung cho lần render đầu ở server và JavaScript. */
    public String getStockLabel() {
        if (stockQuantity <= 0) {
            return "Tạm hết hàng";
        }
        if (stockQuantity <= 3) {
            return "Sắp hết hàng – chỉ còn " + stockQuantity + " sản phẩm";
        }
        return "Còn " + stockQuantity + " sản phẩm";
    }
}
