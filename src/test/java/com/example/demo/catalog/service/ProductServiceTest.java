package com.example.demo.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.demo.catalog.dto.ProductCardView;
import com.example.demo.catalog.dto.ProductDetailView;
import com.example.demo.catalog.dto.ProductFilter;
import com.example.demo.catalog.entity.Product;
import com.example.demo.catalog.entity.ProductImage;
import com.example.demo.catalog.entity.ProductVariant;
import com.example.demo.catalog.enums.ProductStatus;
import com.example.demo.catalog.enums.VariantStatus;
import com.example.demo.common.exception.NotFoundException;
import com.example.demo.catalog.repository.BrandRepository;
import com.example.demo.catalog.repository.CategoryRepository;
import com.example.demo.catalog.repository.ProductImageRepository;
import com.example.demo.catalog.repository.ProductRepository;
import com.example.demo.catalog.repository.ProductVariantRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import com.example.demo.catalog.service.impl.ProductServiceImpl;

/**
 * Kiểm thử tầng nghiệp vụ của danh mục sản phẩm bằng Mockito (không cần CSDL):
 * tập trung vào các quy tắc "khách sửa URL thoải mái cũng không được lỗi".
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductVariantRepository productVariantRepository;

    @Mock
    private ProductImageRepository productImageRepository;

    @Mock
    private BrandRepository brandRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private ProductServiceImpl productService;

    @Test
    @DisplayName("Bộ lọc được chuẩn hoá: bỏ khoảng trắng, chặn số âm, mặc định sắp xếp 'Mới nhất'")
    void search_normalizesFilter() {
        when(productRepository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        ProductFilter filter = new ProductFilter();
        filter.setKeyword("   Astrox  ");
        filter.setBrandId(0);
        filter.setCategoryId(-3);
        filter.setMinPrice(new BigDecimal("-500"));

        productService.search(filter, 1);

        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keywordPattern = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> brandId = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<BigDecimal> minPrice = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> sort = ArgumentCaptor.forClass(String.class);

        verify(productRepository).search(eq(ProductStatus.ACTIVE), keyword.capture(), keywordPattern.capture(),
                brandId.capture(), any(), minPrice.capture(), any(), sort.capture(), any(Pageable.class));

        assertThat(keyword.getValue()).isEqualTo("Astrox");
        assertThat(keywordPattern.getValue()).isEqualTo("%astrox%");
        assertThat(brandId.getValue()).isNull();
        assertThat(minPrice.getValue()).isNull();
        assertThat(sort.getValue()).isEqualTo("NEWEST");
        // Bộ lọc đã được sửa tại chỗ để form hiển thị lại đúng giá trị đã dọn
        assertThat(filter.getKeyword()).isEqualTo("Astrox");
    }

    @Test
    @DisplayName("Nhập ngược khoảng giá (từ > đến) thì tự đổi chỗ thay vì trả về rỗng")
    void search_swapsInvertedPriceRange() {
        when(productRepository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        ProductFilter filter = new ProductFilter();
        filter.setMinPrice(new BigDecimal("5000000"));
        filter.setMaxPrice(new BigDecimal("1000000"));

        productService.search(filter, 1);

        ArgumentCaptor<BigDecimal> minPrice = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> maxPrice = ArgumentCaptor.forClass(BigDecimal.class);
        verify(productRepository).search(any(), any(), any(), any(), any(), minPrice.capture(), maxPrice.capture(),
                any(), any(Pageable.class));

        assertThat(minPrice.getValue()).isEqualByComparingTo("1000000");
        assertThat(maxPrice.getValue()).isEqualByComparingTo("5000000");
    }

    @Test
    @DisplayName("Tham số ?sort= lạ hoặc viết thường đều được chuẩn hoá về tên enum hợp lệ")
    void search_normalizesSortParameter() {
        when(productRepository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        ProductFilter unknown = new ProductFilter();
        unknown.setSort("; drop table products");
        productService.search(unknown, 1);

        ProductFilter lowerCase = new ProductFilter();
        lowerCase.setSort("price_asc");
        productService.search(lowerCase, 1);

        ArgumentCaptor<String> sort = ArgumentCaptor.forClass(String.class);
        verify(productRepository, org.mockito.Mockito.times(2))
                .search(any(), any(), any(), any(), any(), any(), any(), sort.capture(), any(Pageable.class));

        assertThat(sort.getAllValues()).containsExactly("NEWEST", "PRICE_ASC");
    }

    @Test
    @DisplayName("Từ khoá dài hơn 100 ký tự bị cắt bớt để không phá truy vấn")
    void search_truncatesLongKeyword() {
        when(productRepository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        ProductFilter filter = new ProductFilter();
        filter.setKeyword("a".repeat(150));

        productService.search(filter, 1);

        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        verify(productRepository).search(any(), keyword.capture(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class));
        assertThat(keyword.getValue()).hasSize(100);
    }

    @Test
    @DisplayName("Sản phẩm chưa có ảnh trong CSDL thì dùng ảnh mặc định no-image.svg")
    void search_usesPlaceholderWhenProductHasNoImage() {
        ProductCardView card = new ProductCardView(9, "Vợt A", "SP-9", "Yonex", "Tấn công",
                new BigDecimal("1000000"), null, 5L, 1L);
        when(productRepository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(card)));
        when(productImageRepository.findByProductIdInOrderByPrimaryImageDescSortOrderAscIdAsc(List.of(9)))
                .thenReturn(List.of());

        Page<ProductCardView> result = productService.search(new ProductFilter(), 1);

        assertThat(result.getContent()).singleElement()
                .satisfies(item -> assertThat(item.getImageUrl()).isEqualTo(ProductService.NO_IMAGE));
    }

    @Test
    @DisplayName("Ảnh đại diện (is_primary) được chọn làm ảnh thẻ sản phẩm")
    void search_picksPrimaryImageOfEachProduct() {
        ProductCardView card = new ProductCardView(7, "Vợt B", "SP-7", "Victor", "Cân bằng",
                new BigDecimal("2000000"), new BigDecimal("2500000"), 3L, 2L);
        when(productRepository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(card)));
        when(productImageRepository.findByProductIdInOrderByPrimaryImageDescSortOrderAscIdAsc(List.of(7)))
                .thenReturn(List.of(imageOf(7, "/uploads/anh-dai-dien.png"), imageOf(7, "/uploads/anh-phu.png")));

        Page<ProductCardView> result = productService.search(new ProductFilter(), 1);

        assertThat(result.getContent().get(0).getImageUrl()).isEqualTo("/uploads/anh-dai-dien.png");
        assertThat(result.getContent().get(0).isDiscounted()).isTrue();
    }

    @Test
    @DisplayName("Thẻ sản phẩm: khoảng giá, màu, trọng lượng, cỡ cán và % giảm lấy từ biến thể ACTIVE")
    void search_attachesVariantSummary() {
        ProductCardView card = new ProductCardView(9, "Vợt A", "SP-9", "Yonex", "Tấn công",
                new BigDecimal("1000000"), new BigDecimal("1200000"), 6L, 2L);
        when(productRepository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(card)));
        when(productImageRepository.findByProductIdInOrderByPrimaryImageDescSortOrderAscIdAsc(List.of(9)))
                .thenReturn(List.of());

        Product product = productOf(9);
        when(productVariantRepository.findByProductIdInAndStatusOrderByColorAscWeightClassAscGripSizeAsc(
                List.of(9), VariantStatus.ACTIVE))
                .thenReturn(List.of(
                        variantOf(product, 91, "Đỏ", "4U", "G5", "1000000", "1200000", 5, null),
                        variantOf(product, 92, "Xanh", "3U", "G4", "1500000", null, 1, null)));

        Page<ProductCardView> result = productService.search(new ProductFilter(), 1);
        ProductCardView summary = result.getContent().get(0);

        assertThat(summary.getColors()).containsExactly("Đỏ", "Xanh");
        assertThat(summary.getWeightClasses()).containsExactly("4U", "3U");
        assertThat(summary.getGripSizes()).containsExactly("G5", "G4");
        assertThat(summary.getMaxPrice()).isEqualByComparingTo("1500000");
        assertThat(summary.getDiscountPercent()).isEqualTo(17); // (1.200.000 - 1.000.000) / 1.200.000
        assertThat(summary.isPriceRange()).isTrue();
        assertThat(summary.isDiscounted()).isTrue();
    }

    @Test
    @DisplayName("Chi tiết: dựng đúng ma trận biến thể, chọn bản còn hàng đầu tiên và fallback ảnh theo màu")
    void getDetail_buildsVariantMatrixAndColorImageFallback() {
        Product product = productOf(1);
        when(productRepository.findDetailById(1)).thenReturn(Optional.of(product));

        ProductVariant red = variantOf(product, 11, "Đỏ", "4U", "G5", "1000000", "1200000", 0, null);
        ProductVariant blue = variantOf(product, 12, "Xanh", "4U", "G5", "1100000", null, 4, "/uploads/xanh.png");
        ProductVariant blueAlt = variantOf(product, 13, "Xanh", "3U", "G4", "900000", null, 3, null);
        when(productVariantRepository.findByProductIdAndStatusOrderByColorAscWeightClassAscGripSizeAsc(
                1, VariantStatus.ACTIVE)).thenReturn(List.of(red, blue, blueAlt));
        when(productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(1)).thenReturn(List.of());

        ProductDetailView detail = productService.getDetail(1);

        assertThat(detail.getVariants()).hasSize(3);
        assertThat(detail.getColors()).containsExactly("Đỏ", "Xanh");
        assertThat(detail.getWeightClasses()).containsExactly("4U", "3U");
        assertThat(detail.getGripSizes()).containsExactly("G5", "G4");
        assertThat(detail.getMinPrice()).isEqualByComparingTo("900000");
        assertThat(detail.getMaxPrice()).isEqualByComparingTo("1100000");
        assertThat(detail.isPriceRange()).isTrue();
        // Đỏ hết hàng ⇒ mặc định là Xanh 4U G5 (bản còn hàng đầu tiên)
        assertThat(detail.getDefaultVariantId()).isEqualTo(12);
        assertThat(detail.getMaxStock()).isEqualTo(4);
        // Biến thể Xanh 3U G4 không có ảnh riêng ⇒ dùng ảnh của biến thể cùng màu
        assertThat(detail.getColorImage("Xanh")).isEqualTo("/uploads/xanh.png");
        assertThat(detail.getColorStock("Xanh")).isEqualTo(7);
        assertThat(detail.getColorImage("Đỏ")).isNull();
    }

    @Test
    @DisplayName("Chi tiết: gộp màu không phân biệt hoa/thường, dùng chung ảnh và tồn kho")
    void getDetail_groupsColorCaseInsensitively() {
        Product product = productOf(1);
        when(productRepository.findDetailById(1)).thenReturn(Optional.of(product));

        ProductVariant blueUpper = variantOf(product, 21, "Xanh", "4U", "G5", "1000000", null, 4,
                "/uploads/xanh.png");
        ProductVariant blueLower = variantOf(product, 22, "  xanh  ", "3U", "G4", "900000", null, 3, null);
        ProductVariant red = variantOf(product, 23, "Đỏ", "4U", "G5", "950000", null, 2, null);
        when(productVariantRepository.findByProductIdAndStatusOrderByColorAscWeightClassAscGripSizeAsc(
                1, VariantStatus.ACTIVE)).thenReturn(List.of(blueUpper, blueLower, red));
        when(productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(1)).thenReturn(List.of());

        ProductDetailView detail = productService.getDetail(1);

        // "Xanh" và "  xanh  " là cùng một màu; tên hiển thị lấy bản chuẩn hoá đầu tiên.
        assertThat(detail.getColors()).containsExactly("Xanh", "Đỏ");
        assertThat(detail.getColorImage("XANH")).isEqualTo("/uploads/xanh.png");
        assertThat(detail.getColorStock("xanh")).isEqualTo(7);
    }

    @Test
    @DisplayName("Số trang vượt quá số trang thật thì tự lùi về trang cuối thay vì hiện trang trắng")
    void search_fallsBackToLastPageWhenPageOutOfRange() {
        ProductCardView card = new ProductCardView(9, "Vợt A", "SP-9", "Yonex", "Tấn công",
                new BigDecimal("1000000"), null, 5L, 1L);
        when(productRepository.search(any(), any(), any(), any(), any(), any(), any(), any(),
                eq(PageRequest.of(9, ProductService.PAGE_SIZE))))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(9, ProductService.PAGE_SIZE), 13));
        when(productRepository.search(any(), any(), any(), any(), any(), any(), any(), any(),
                eq(PageRequest.of(1, ProductService.PAGE_SIZE))))
                .thenReturn(new PageImpl<>(List.of(card), PageRequest.of(1, ProductService.PAGE_SIZE), 13));
        when(productImageRepository.findByProductIdInOrderByPrimaryImageDescSortOrderAscIdAsc(any()))
                .thenReturn(List.of());

        Page<ProductCardView> result = productService.search(new ProductFilter(), 10);

        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getContent()).singleElement()
                .satisfies(item -> assertThat(item.getName()).isEqualTo("Vợt A"));
    }

    @Test
    @DisplayName("Chi tiết sản phẩm không tồn tại thì ném NotFoundException (trang 404)")
    void getDetail_throwsWhenProductMissing() {
        when(productRepository.findDetailById(999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getDetail(999))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Không tìm thấy sản phẩm");
    }

    @Test
    @DisplayName("Sản phẩm đang ẩn/bản nháp không được xem như sản phẩm đang bán")
    void getDetail_throwsWhenProductNotActive() {
        Product hidden = new Product();
        hidden.setId(5);
        hidden.setStatus(ProductStatus.HIDDEN);
        when(productRepository.findDetailById(5)).thenReturn(Optional.of(hidden));

        assertThatThrownBy(() -> productService.getDetail(5)).isInstanceOf(NotFoundException.class);
    }

    private Product productOf(Integer id) {
        Product product = new Product();
        product.setId(id);
        product.setName("Vợt Test");
        product.setProductCode("TEST-" + id);
        product.setStatus(ProductStatus.ACTIVE);
        return product;
    }

    private ProductVariant variantOf(Product product, Integer id, String color, String weight, String grip,
                                     String price, String originalPrice, int stock, String imageUrl) {
        ProductVariant variant = new ProductVariant();
        variant.setId(id);
        variant.setProduct(product);
        variant.setSku("TEST-" + id);
        variant.setColor(color);
        variant.setWeightClass(weight);
        variant.setGripSize(grip);
        variant.setPrice(new BigDecimal(price));
        variant.setOriginalPrice(originalPrice == null ? null : new BigDecimal(originalPrice));
        variant.setStockQuantity(stock);
        variant.setImageUrl(imageUrl);
        variant.setStatus(VariantStatus.ACTIVE);
        return variant;
    }

    private ProductImage imageOf(Integer productId, String url) {
        Product product = new Product();
        product.setId(productId);
        ProductImage image = new ProductImage();
        image.setProduct(product);
        image.setImageUrl(url);
        return image;
    }
}
