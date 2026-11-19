package com.example.demo.catalog.dto;

import com.example.demo.catalog.entity.Product;
import com.example.demo.catalog.entity.ProductImage;
import com.example.demo.common.util.ColorNames;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dữ liệu trang chi tiết sản phẩm: sản phẩm + danh sách biến thể + bộ ảnh.
 *
 * Biến thể được map sang {@link ProductVariantView} thay vì trả Entity ra template:
 * template chỉ đọc dữ liệu đã chuẩn bị, không phụ thuộc lazy-load và JavaScript cũng có
 * đúng bộ dữ liệu màu × trọng lượng × cỡ cán để lọc tổ hợp thật.
 *
 * Lưu ý: Product ở đây đã được fetch join brand/category (xem ProductRepository#findDetailById)
 * nên template đọc được tên hãng/loại mà không bị lỗi lazy-load (dự án đặt open-in-view = false).
 */
public record ProductDetailView(Product product, List<ProductVariantView> variants, List<ProductImage> images) {

    // Getter kiểu JavaBean để template đọc được như DTO thường.
    public Product getProduct() {
        return product;
    }

    public List<ProductVariantView> getVariants() {
        return variants;
    }

    public List<ProductImage> getImages() {
        return images;
    }

    /** Còn ít nhất 1 biến thể đang bán và còn tồn kho. */
    public boolean isPurchasable() {
        return variants.stream().anyMatch(ProductVariantView::isInStock);
    }

    /** Giá thấp nhất trong các biến thể đang bán (null nếu sản phẩm chưa có biến thể nào). */
    public BigDecimal getMinPrice() {
        return variants.stream()
                .map(ProductVariantView::price)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /** Giá cao nhất trong các biến thể đang bán. */
    public BigDecimal getMaxPrice() {
        return variants.stream()
                .map(ProductVariantView::price)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    /** Các phiên bản có giá khác nhau ⇒ hiển thị "từ X ₫ đến Y ₫" thay vì một giá duy nhất. */
    public boolean isPriceRange() {
        BigDecimal min = getMinPrice();
        BigDecimal max = getMaxPrice();
        return min != null && max != null && min.compareTo(max) != 0;
    }

    /** Có ít nhất 1 phiên bản đang giảm giá (giá gốc gạch ngang lớn hơn giá bán). */
    public boolean isDiscounted() {
        return variants.stream().anyMatch(ProductVariantView::isDiscounted);
    }

    /**
     * Phiên bản mặc định: ưu tiên bản còn hàng đầu tiên, sau đó mới tới bản hết hàng.
     * Luôn là một dòng có thật trong CSDL nên không tạo tổ hợp ảo.
     */
    public ProductVariantView getDefaultVariant() {
        return variants.stream()
                .filter(ProductVariantView::isInStock)
                .findFirst()
                .orElse(variants.isEmpty() ? null : variants.get(0));
    }

    /** Id phiên bản chọn sẵn – template dùng cho input hidden ban đầu. */
    public Integer getDefaultVariantId() {
        ProductVariantView variant = getDefaultVariant();
        return variant == null ? null : variant.id();
    }

    /** Tồn kho lớn nhất trong các phiên bản đang bán – giá trị max ban đầu của ô số lượng. */
    public int getMaxStock() {
        return variants.stream()
                .filter(ProductVariantView::isInStock)
                .mapToInt(ProductVariantView::stockQuantity)
                .max()
                .orElse(0);
    }

    /** Danh sách màu thực tế, gộp không phân biệt hoa/thường và giữ thứ tự ổn định. */
    public List<String> getColors() {
        Map<String, String> values = new LinkedHashMap<>();
        variants.forEach(variant -> {
            String color = ColorNames.normalize(variant.color());
            if (color != null && !color.isEmpty()) {
                values.putIfAbsent(ColorNames.key(color), color);
            }
        });
        return List.copyOf(values.values());
    }

    /** Danh sách trọng lượng thực tế của sản phẩm. */
    public List<String> getWeightClasses() {
        Set<String> values = new LinkedHashSet<>();
        variants.forEach(variant -> values.add(variant.weightClass()));
        return List.copyOf(values);
    }

    /** Danh sách cỡ cán thực tế của sản phẩm. */
    public List<String> getGripSizes() {
        Set<String> values = new LinkedHashSet<>();
        variants.forEach(variant -> values.add(variant.gripSize()));
        return List.copyOf(values);
    }

    /**
     * Ảnh đại diện của một màu: lấy ảnh của biến thể cùng màu đầu tiên có ảnh.
     * So sánh theo {@link ColorNames#key(String)} nên "Xanh", "xanh" và "XANH"
     * được coi là cùng một màu; nhờ vậy các biến thể cùng màu nhưng khác trọng lượng/
     * cỡ cán vẫn dùng chung ảnh dù mỗi biến thể không phải upload lại ảnh.
     */
    public String getColorImage(String color) {
        String wanted = ColorNames.key(color);
        if (wanted.isEmpty()) {
            return null;
        }
        return variants.stream()
                .filter(variant -> wanted.equals(ColorNames.key(variant.color())))
                .map(ProductVariantView::imageUrl)
                .filter(url -> url != null && !url.isBlank())
                .findFirst()
                .orElse(null);
    }

    /** Tổng tồn kho của một màu, gộp không phân biệt hoa/thường. */
    public int getColorStock(String color) {
        String wanted = ColorNames.key(color);
        if (wanted.isEmpty()) {
            return 0;
        }
        return variants.stream()
                .filter(variant -> wanted.equals(ColorNames.key(variant.color())))
                .mapToInt(ProductVariantView::stockQuantity)
                .sum();
    }
}
