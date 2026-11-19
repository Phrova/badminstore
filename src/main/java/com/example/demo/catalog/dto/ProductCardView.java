package com.example.demo.catalog.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * Dữ liệu 1 thẻ sản phẩm trên trang danh sách.
 * Được dựng bằng JPQL "select new …" (gộp giá thấp nhất + tổng tồn kho trong 1 truy vấn)
 * nên KHÔNG phải load từng variant cho từng sản phẩm (tránh N+1).
 *
 * Giá hiển thị = giá thấp nhất trong các biến thể đang bán; sản phẩm chưa có biến thể
 * hoặc đã hết hàng thì minPrice/totalStock là null/0 → template hiện "Liên hệ"/"Tạm hết hàng".
 * Các thông tin màu/trọng lượng/cỡ cán và % giảm được Service gắn thêm bằng MỘT truy vấn
 * biến thể cho cả trang (xem ProductService#attachVariantSummaries).
 */
@Getter
@Setter
public class ProductCardView {

    private final Integer id;
    private final String name;
    private final String productCode;
    private final String brandName;
    private final String categoryName;
    private final BigDecimal minPrice;
    private final BigDecimal minOriginalPrice;
    private final Long totalStock;
    private final Long variantCount;

    /** Ảnh đại diện – Service gắn thêm sau khi truy vấn ảnh của cả trang trong 1 lượt. */
    private String imageUrl;

    /** Giá cao nhất trong các biến thể đang bán – cùng nguồn với minPrice. */
    private BigDecimal maxPrice;

    /** Mức giảm cao nhất trong các biến thể đang bán (0 = không có khuyến mãi). */
    private int discountPercent;

    /** Các giá trị thực tế của biến thể đang bán, không hardcode. */
    private List<String> colors = List.of();
    private List<String> weightClasses = List.of();
    private List<String> gripSizes = List.of();

    public ProductCardView(Integer id, String name, String productCode, String brandName, String categoryName,
                           BigDecimal minPrice, BigDecimal minOriginalPrice, Long totalStock, Long variantCount) {
        this.id = id;
        this.name = name;
        this.productCode = productCode;
        this.brandName = brandName;
        this.categoryName = categoryName;
        this.minPrice = minPrice;
        this.minOriginalPrice = minOriginalPrice;
        this.totalStock = totalStock;
        this.variantCount = variantCount;
    }

    /**
     * Có khuyến mãi trên ít nhất một biến thể đang bán.
     * Ưu tiên mức giảm Service tính từ biến thể; nếu chưa gắn (hoặc dữ liệu cũ) thì
     * fallback theo giá gốc nhỏ nhất để không làm mất badge "Giảm giá" hiện có.
     */
    public boolean isDiscounted() {
        return discountPercent > 0
                || (minPrice != null && minOriginalPrice != null && minOriginalPrice.compareTo(minPrice) > 0);
    }

    /** Giá gốc hiển thị được cạnh giá bán thấp nhất (tránh gạch ngang một giá không khớp). */
    public boolean isOldPriceAvailable() {
        return minPrice != null && minOriginalPrice != null && minOriginalPrice.compareTo(minPrice) > 0;
    }

    /** Còn hàng để bán? */
    public boolean isAvailable() {
        return totalStock != null && totalStock > 0;
    }

    /** Các biến thể đang bán có nhiều mức giá khác nhau. */
    public boolean isPriceRange() {
        return minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) != 0;
    }

    public boolean hasColors() {
        return colors != null && !colors.isEmpty();
    }

    public String getWeightSummary() {
        return weightClasses == null ? "" : String.join(", ", weightClasses);
    }

    public String getGripSummary() {
        return gripSizes == null ? "" : String.join(", ", gripSizes);
    }
}
