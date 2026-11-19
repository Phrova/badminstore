package com.example.demo.catalog.service.impl;

import com.example.demo.catalog.dto.FilterOptions;
import com.example.demo.catalog.dto.ProductCardView;
import com.example.demo.catalog.dto.ProductDetailView;
import com.example.demo.catalog.dto.ProductFilter;
import com.example.demo.catalog.dto.ProductVariantView;
import com.example.demo.catalog.entity.Product;
import com.example.demo.catalog.entity.ProductImage;
import com.example.demo.catalog.entity.ProductVariant;
import com.example.demo.catalog.enums.ProductSort;
import com.example.demo.catalog.enums.ProductStatus;
import com.example.demo.catalog.enums.VariantStatus;
import com.example.demo.common.exception.NotFoundException;
import com.example.demo.catalog.repository.BrandRepository;
import com.example.demo.catalog.repository.CategoryRepository;
import com.example.demo.catalog.repository.ProductImageRepository;
import com.example.demo.catalog.repository.ProductRepository;
import com.example.demo.catalog.repository.ProductVariantRepository;
import com.example.demo.common.util.ColorNames;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.catalog.service.ProductService;

/**
 * Nghiệp vụ xem sản phẩm phía khách: danh sách có tìm kiếm/lọc/sắp xếp/phân trang và trang chi tiết.
 * Chỉ trả về sản phẩm {@code status = ACTIVE} và chưa xoá mềm.
 */
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ProductImageRepository productImageRepository;
    private final BrandRepository brandRepository;
    private final CategoryRepository categoryRepository;

    /** Danh sách sản phẩm đang bán theo bộ lọc của khách (page truyền vào tính từ 1 cho dễ hiểu). */
    @Override
    @Transactional(readOnly = true)
    public Page<ProductCardView> search(ProductFilter filter, int pageNumber) {
        return search(filter, pageNumber, PAGE_SIZE);
    }

    /** Vài sản phẩm mới nhất cho trang chủ. */
    @Override
    @Transactional(readOnly = true)
    public List<ProductCardView> getFeatured(int limit) {
        return search(new ProductFilter(), 1, limit).getContent();
    }

    /** Trang chi tiết: sản phẩm + biến thể đang bán + bộ ảnh. Sản phẩm ẩn/đã xoá coi như không tồn tại (404). */
    @Override
    @Transactional(readOnly = true)
    public ProductDetailView getDetail(Integer id) {
        Product product = productRepository.findDetailById(id)
                .filter(item -> item.getStatus() == ProductStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException(
                        "Không tìm thấy sản phẩm bạn cần hoặc sản phẩm đã ngừng bán."));

        return new ProductDetailView(product,
                productVariantRepository.findByProductIdAndStatusOrderByColorAscWeightClassAscGripSizeAsc(
                        id, VariantStatus.ACTIVE).stream()
                        .map(ProductVariantView::from)
                        .toList(),
                productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(id));
    }

    /** Dữ liệu cho 2 ô chọn hãng/loại trên bộ lọc. */
    @Override
    @Transactional(readOnly = true)
    public FilterOptions getFilterOptions() {
        return new FilterOptions(brandRepository.findByDeletedFalseOrderByNameAsc(),
                categoryRepository.findByDeletedFalseOrderByNameAsc());
    }

    private Page<ProductCardView> search(ProductFilter filter, int pageNumber, int pageSize) {
        ProductFilter safe = normalize(filter);
        int requestedPage = Math.max(0, pageNumber - 1);

        Page<ProductCardView> result = query(safe, requestedPage, pageSize);
        // Khách sửa số trang trên URL vượt quá số trang thật (hoặc sản phẩm vừa bị xoá bớt)
        // ⇒ lùi về trang cuối thay vì hiện một trang trắng khó hiểu.
        if (result.getTotalElements() > 0 && requestedPage >= result.getTotalPages()) {
            result = query(safe, result.getTotalPages() - 1, pageSize);
        }
        attachImages(result);
        attachVariantSummaries(result);
        return result;
    }

    /** Một lượt gọi repository: gộp giá thấp nhất + tổng tồn kho của cả trang trong 1 câu SQL. */
    private Page<ProductCardView> query(ProductFilter filter, int pageIndex, int pageSize) {
        return productRepository.search(
                ProductStatus.ACTIVE,
                filter.getKeyword(),
                filter.getKeyword() == null ? null : "%" + filter.getKeyword().toLowerCase(Locale.ROOT) + "%",
                filter.getBrandId(),
                filter.getCategoryId(),
                filter.getMinPrice(),
                filter.getMaxPrice(),
                ProductSort.from(filter.getSort()).name(),
                PageRequest.of(Math.max(0, pageIndex), pageSize));
    }

    /**
     * Gắn ảnh đại diện cho mọi thẻ trong trang bằng MỘT truy vấn ảnh (không hỏi từng sản phẩm).
     * Ảnh đầu tiên theo thứ tự truy vấn là ảnh đại diện (is_primary trước, rồi sort_order).
     */
    private void attachImages(Page<ProductCardView> page) {
        List<ProductCardView> cards = page.getContent();
        if (cards.isEmpty()) {
            return;
        }
        List<Integer> ids = cards.stream().map(ProductCardView::getId).toList();
        Map<Integer, String> imageByProduct = new HashMap<>();
        for (ProductImage image : productImageRepository
                .findByProductIdInOrderByPrimaryImageDescSortOrderAscIdAsc(ids)) {
            imageByProduct.putIfAbsent(image.getProduct().getId(), image.getImageUrl());
        }
        cards.forEach(card -> card.setImageUrl(imageByProduct.getOrDefault(card.getId(), NO_IMAGE)));
    }

    /**
     * Gắn khoảng giá + màu + trọng lượng + cỡ cán cho cả trang bằng MỘT truy vấn biến thể.
     *
     * Chỉ lấy biến thể ACTIVE – đúng tập dữ liệu khách được phép chọn ở trang chi tiết.
     * Giá cao nhất và % giảm cũng tính trên chính tập này nên thẻ sản phẩm không bao giờ
     * hiển thị một tổ hợp hoặc mức giá không tồn tại trong CSDL.
     */
    private void attachVariantSummaries(Page<ProductCardView> page) {
        List<ProductCardView> cards = page.getContent();
        if (cards.isEmpty()) {
            return;
        }
        List<Integer> ids = cards.stream().map(ProductCardView::getId).toList();
        Map<Integer, List<ProductVariant>> variantsByProduct = productVariantRepository
                .findByProductIdInAndStatusOrderByColorAscWeightClassAscGripSizeAsc(ids, VariantStatus.ACTIVE)
                .stream()
                .collect(Collectors.groupingBy(variant -> variant.getProduct().getId()));

        for (ProductCardView card : cards) {
            List<ProductVariant> variants = variantsByProduct.getOrDefault(card.getId(), List.of());
            BigDecimal maxPrice = variants.stream()
                    .map(ProductVariant::getPrice)
                    .max(Comparator.naturalOrder())
                    .orElse(card.getMinPrice());

            Set<String> weights = new LinkedHashSet<>();
            Set<String> grips = new LinkedHashSet<>();
            Map<String, String> colors = new LinkedHashMap<>();
            int maxDiscount = 0;
            for (ProductVariant variant : variants) {
                // Gom màu theo khoá không phân biệt hoa/thường, nhưng hiển thị tên đã chuẩn hoá.
                String color = ColorNames.normalize(variant.getColor());
                if (color != null && !color.isEmpty()) {
                    colors.putIfAbsent(ColorNames.key(color), color);
                }
                weights.add(variant.getWeightClass());
                grips.add(variant.getGripSize());
                int percent = discountPercent(variant);
                if (percent > maxDiscount) {
                    maxDiscount = percent;
                }
            }

            card.setMaxPrice(maxPrice);
            card.setColors(List.copyOf(colors.values()));
            card.setWeightClasses(List.copyOf(weights));
            card.setGripSizes(List.copyOf(grips));
            card.setDiscountPercent(maxDiscount);
        }
    }

    /** % giảm của một biến thể, làm tròn giống nhãn hiển thị trên giao diện. */
    private int discountPercent(ProductVariant variant) {
        BigDecimal original = variant.getOriginalPrice();
        BigDecimal price = variant.getPrice();
        if (original == null || price == null || original.signum() <= 0 || original.compareTo(price) <= 0) {
            return 0;
        }
        return original.subtract(price)
                .multiply(BigDecimal.valueOf(100))
                .divide(original, 0, RoundingMode.HALF_UP)
                .intValue();
    }

    /**
     * Chuẩn hoá bộ lọc đọc từ query string (khách có thể sửa URL tuỳ ý):
     * bỏ khoảng trắng thừa, chặn số âm, tự đổi chỗ nếu min > max, giới hạn độ dài từ khoá.
     * Nhờ vậy không bao giờ trả lỗi 400 vì bộ lọc sai.
     */
    private ProductFilter normalize(ProductFilter filter) {
        ProductFilter safe = (filter == null) ? new ProductFilter() : filter;

        String keyword = safe.getKeyword();
        if (keyword != null) {
            keyword = keyword.trim();
            if (keyword.isEmpty()) {
                keyword = null;
            } else if (keyword.length() > 100) {
                keyword = keyword.substring(0, 100);
            }
        }
        safe.setKeyword(keyword);
        safe.setBrandId(positiveOrNull(safe.getBrandId()));
        safe.setCategoryId(positiveOrNull(safe.getCategoryId()));
        safe.setMinPrice(nonNegativeOrNull(safe.getMinPrice()));
        safe.setMaxPrice(nonNegativeOrNull(safe.getMaxPrice()));
        if (safe.getMinPrice() != null && safe.getMaxPrice() != null
                && safe.getMinPrice().compareTo(safe.getMaxPrice()) > 0) {
            BigDecimal swap = safe.getMinPrice();
            safe.setMinPrice(safe.getMaxPrice());
            safe.setMaxPrice(swap);
        }
        safe.setSort(ProductSort.from(safe.getSort()).name());
        return safe;
    }

    private Integer positiveOrNull(Integer value) {
        return (value == null || value <= 0) ? null : value;
    }

    private BigDecimal nonNegativeOrNull(BigDecimal value) {
        return (value == null || value.signum() < 0) ? null : value;
    }
}
