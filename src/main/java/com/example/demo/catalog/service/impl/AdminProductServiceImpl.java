package com.example.demo.catalog.service.impl;

import com.example.demo.catalog.dto.BulkPriceForm;
import com.example.demo.catalog.dto.VariantBulkForm;
import com.example.demo.catalog.dto.ProductForm;
import com.example.demo.catalog.dto.ProductRow;
import com.example.demo.catalog.dto.VariantForm;
import com.example.demo.catalog.entity.Brand;
import com.example.demo.catalog.entity.Category;
import com.example.demo.catalog.entity.Product;
import com.example.demo.catalog.entity.ProductImage;
import com.example.demo.catalog.entity.ProductVariant;
import com.example.demo.catalog.enums.BulkPriceMode;
import com.example.demo.catalog.enums.ProductStatus;
import com.example.demo.catalog.enums.VariantStatus;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.NotFoundException;
import com.example.demo.catalog.repository.BrandRepository;
import com.example.demo.catalog.repository.CategoryRepository;
import com.example.demo.order.repository.OrderItemRepository;
import com.example.demo.catalog.repository.ProductImageRepository;
import com.example.demo.catalog.repository.ProductRepository;
import com.example.demo.catalog.repository.ProductVariantRepository;
import com.example.demo.common.util.ColorNames;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import org.springframework.web.multipart.MultipartFile;

import com.example.demo.catalog.service.AdminProductService;
import com.example.demo.catalog.service.FileStorageService;

/**
 * Quản trị danh mục bán hàng: sản phẩm, biến thể (giá + tồn kho) và bộ ảnh.
 *
 * Nguyên tắc:
 * – xoá sản phẩm là XOÁ MỀM (is_deleted = 1) để không phá dữ liệu đơn hàng cũ;
 * – biến thể đã xuất hiện trong đơn hàng thì KHÔNG cho xoá, chỉ được "ngừng bán";
 * – mọi luật của BadStore.sql (CK/UQ) đều được kiểm tra trước và báo lỗi tiếng Việt dễ hiểu;
 * – ảnh upload đi qua FileStorageService (giới hạn 5 MB, kiểm tra MIME + đổi tên file).
 */
@Service
@RequiredArgsConstructor
public class AdminProductServiceImpl implements AdminProductService {

    private static final Set<String> WEIGHT_CLASSES = Set.of("2U", "3U", "4U", "5U");
    private static final Set<String> GRIP_SIZES = Set.of("G3", "G4", "G5", "G6");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final BigDecimal MAX_DISCOUNT_PERCENT = BigDecimal.valueOf(90);
    private static final int MAX_BULK_VARIANTS = 40;
    private static final BigDecimal MAX_VARIANT_PRICE = new BigDecimal("100000000");
    private static final int MAX_VARIANT_STOCK = 100_000;

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ProductImageRepository productImageRepository;
    private final OrderItemRepository orderItemRepository;
    private final BrandRepository brandRepository;
    private final CategoryRepository categoryRepository;
    private final FileStorageService fileStorageService;

    // ==================== SẢN PHẨM ====================

    /** Danh sách sản phẩm cho admin: chỉ 3 truy vấn cho cả trang (sản phẩm, biến thể, ảnh). */
    @Override
    @Transactional(readOnly = true)
    public Page<ProductRow> search(String keyword, Integer brandId, Integer categoryId, ProductStatus status,
                                   int pageNumber) {
        String normalizedKeyword = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        Page<Product> products = productRepository.searchForAdmin(status, brandId, categoryId, normalizedKeyword,
                PageRequest.of(Math.max(0, pageNumber - 1), PAGE_SIZE));

        List<Integer> productIds = products.getContent().stream().map(Product::getId).toList();

        Map<Integer, List<ProductVariant>> variantsByProduct = productIds.isEmpty() ? Map.of()
                : productVariantRepository.findByProductIdIn(productIds).stream()
                        .collect(Collectors.groupingBy(variant -> variant.getProduct().getId()));

        Map<Integer, String> thumbnails = new HashMap<>();
        if (!productIds.isEmpty()) {
            for (ProductImage image : productImageRepository
                    .findByProductIdInOrderByPrimaryImageDescSortOrderAscIdAsc(productIds)) {
                thumbnails.putIfAbsent(image.getProduct().getId(), image.getImageUrl());
            }
        }

        return products.map(product -> toRow(product,
                variantsByProduct.getOrDefault(product.getId(), List.of()),
                thumbnails.get(product.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Brand> brands() {
        return brandRepository.findByDeletedFalseOrderByNameAsc();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Category> categories() {
        return categoryRepository.findByDeletedFalseOrderByNameAsc();
    }

    /** Sản phẩm cho khu quản trị: fetch join sẵn hãng + loại (open-in-view = false). */
    @Override
    @Transactional(readOnly = true)
    public Product requireProduct(Integer id) {
        return productRepository.findDetailById(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy sản phẩm cần xử lý."));
    }

    /** Thêm sản phẩm mới: mã tự viết hoa, không trùng (khớp UX_products_code). */
    @Override
    @Transactional
    public Product createProduct(ProductForm form) {
        String code = normalizeCode(form.getProductCode());
        if (productRepository.existsByProductCodeAndDeletedFalse(code)) {
            throw new BusinessException("Mã sản phẩm \"" + code + "\" đã tồn tại, bạn đặt mã khác nhé.");
        }
        Product product = new Product();
        product.setProductCode(code);
        product.setDeleted(false);
        applyProductForm(product, form);
        return productRepository.save(product);
    }

    @Override
    @Transactional
    public Product updateProduct(Integer id, ProductForm form) {
        Product product = requireProduct(id);
        String code = normalizeCode(form.getProductCode());
        if (!code.equals(product.getProductCode())
                && productRepository.existsByProductCodeAndIdNotAndDeletedFalse(code, id)) {
            throw new BusinessException("Mã sản phẩm \"" + code + "\" đã bị dùng bởi sản phẩm khác.");
        }
        product.setProductCode(code);
        applyProductForm(product, form);
        return productRepository.save(product);
    }

    /** Bật/ẩn sản phẩm: ACTIVE ⇄ HIDDEN (khách chỉ thấy ACTIVE). */
    @Override
    @Transactional
    public Product toggleProductStatus(Integer id) {
        Product product = requireProduct(id);
        product.setStatus(product.getStatus() == ProductStatus.ACTIVE ? ProductStatus.HIDDEN : ProductStatus.ACTIVE);
        return productRepository.save(product);
    }

    /** Xoá mềm: đặt is_deleted = 1, mọi truy vấn khách/admin khác đều lọc theo cờ này. */
    @Override
    @Transactional
    public Product softDeleteProduct(Integer id) {
        Product product = requireProduct(id);
        product.setDeleted(true);
        return productRepository.save(product);
    }

    // ==================== BIẾN THỂ ====================

    @Override
    @Transactional(readOnly = true)
    public List<ProductVariant> variantsOf(Integer productId) {
        requireProduct(productId);
        return productVariantRepository.findByProductIdOrderByColorAscWeightClassAscGripSizeAsc(productId);
    }

    /** Biến thể phải thuộc đúng sản phẩm đang mở – chặn sửa chéo sản phẩm qua URL. */
    @Override
    @Transactional(readOnly = true)
    public ProductVariant requireVariantOf(Integer productId, Integer variantId) {
        return requireVariant(productId, variantId);
    }

    @Override
    @Transactional
    public ProductVariant createVariant(Integer productId, VariantForm form) {
        // Validate dữ liệu form TRƯỚC khi truy vấn sản phẩm: dữ liệu sai không cần chạm CSDL.
        ProductVariant variant = new ProductVariant();
        variant.setSku(normalizeCode(form.getSku()));
        applyVariantForm(variant, form);
        variant.setProduct(requireProduct(productId));
        requireFreeSku(variant);
        requireFreeCombination(variant, false);
        // Chỉ lưu file sau khi mọi kiểm tra hợp lệ để tránh file rác khi form sai.
        applyVariantImage(variant, form, null);
        return productVariantRepository.save(variant);
    }

    @Override
    @Transactional
    public ProductVariant updateVariant(Integer productId, Integer variantId, VariantForm form) {
        ProductVariant variant = requireVariant(productId, variantId);
        String oldImageUrl = variant.getImageUrl();
        variant.setSku(normalizeCode(form.getSku()));
        applyVariantForm(variant, form);
        requireFreeSku(variant);
        requireFreeCombination(variant, true);
        applyVariantImage(variant, form, oldImageUrl);
        return productVariantRepository.save(variant);
    }

    /**
     * Ngừng bán / bán lại biến thể.
     */
    @Override
    @Transactional
    public ProductVariant toggleVariantStatus(Integer productId, Integer variantId) {
        ProductVariant variant = requireVariant(productId, variantId);
        variant.setStatus(variant.getStatus() == VariantStatus.ACTIVE ? VariantStatus.INACTIVE : VariantStatus.ACTIVE);
        return productVariantRepository.save(variant);
    }

    /**
     * Xoá biến thể: chỉ cho xoá những biến thể CHƯA từng vào đơn hàng
     * (order_items giữ lịch sử theo FK) – còn lại hướng dẫn admin chuyển sang "Ngừng bán".
     */
    @Override
    @Transactional
    public void deleteVariant(Integer productId, Integer variantId) {
        ProductVariant variant = requireVariant(productId, variantId);
        if (orderItemRepository.existsByProductVariantId(variantId)) {
            throw new BusinessException("Biến thể " + variant.getSku()
                    + " đã có trong đơn hàng nên không thể xoá. Hãy chuyển sang trạng thái \"Ngừng bán\".");
        }
        String imageUrl = variant.getImageUrl();
        productVariantRepository.delete(variant);
        deleteVariantImageIfUnused(imageUrl, variantId);
    }

    /**
     * Thêm NHIỀU biến thể trong một lần: kiểm tra TẤT CẢ các dòng trước khi lưu (all-or-nothing),
     * lỗi dòng nào báo đúng số dòng đó. Trùng SKU / trùng tổ hợp bị chặn cả trong lô lẫn dưới CSDL.
     */
    @Override
    @Transactional
    public List<ProductVariant> createVariantsBulk(Integer productId, VariantBulkForm form) {
        List<VariantForm> rows = (form == null || form.getRows() == null) ? List.of() : form.getRows();
        if (rows.isEmpty()) {
            throw new BusinessException("Chưa có dòng biến thể nào để thêm.");
        }
        if (rows.size() > MAX_BULK_VARIANTS) {
            throw new BusinessException("Tối đa " + MAX_BULK_VARIANTS
                    + " biến thể mỗi lần thêm. Bạn chia nhỏ danh sách giúp nhé.");
        }
        Product product = requireProduct(productId);

        Set<String> seenSkus = new HashSet<>();
        Set<String> seenCombinations = new HashSet<>();
        List<ProductVariant> prepared = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            VariantForm row = rows.get(i);
            String label = "Dòng " + (i + 1) + ": ";
            try {
                if (row == null) {
                    throw new BusinessException("dòng đang trống, hãy điền đủ thông tin hoặc xoá dòng này.");
                }
                ProductVariant variant = new ProductVariant();
                variant.setSku(normalizeCode(row.getSku()));
                applyVariantForm(variant, row);
                variant.setProduct(product);

                String combination = (variant.getColor() + "|" + variant.getWeightClass()
                        + "|" + variant.getGripSize()).toLowerCase(Locale.ROOT);
                if (!seenSkus.add(variant.getSku())) {
                    throw new BusinessException("SKU \"" + variant.getSku() + "\" bị lặp lại trong danh sách.");
                }
                if (!seenCombinations.add(combination)) {
                    throw new BusinessException("tổ hợp màu/trọng lượng/cỡ cán bị lặp lại trong danh sách.");
                }
                requireFreeSku(variant);
                requireFreeCombination(variant, false);
                String imageUrl = row.getImageUrl() == null ? "" : row.getImageUrl().trim();
                if (imageUrl.length() > 500) {
                    throw new BusinessException("Đường dẫn ảnh tối đa 500 ký tự.");
                }
                applyVariantImage(variant, row, null);
                prepared.add(variant);
            } catch (BusinessException ex) {
                throw new BusinessException(label + ex.getMessage());
            }
        }
        return productVariantRepository.saveAll(prepared);
    }

    /**
     * Xoá TẤT CẢ biến thể của sản phẩm trong một lần. Biến thể đã có trong đơn hàng
     * được GIỮ LẠI (luật cũ của deleteVariant) – admin chuyển "Ngừng bán" nếu muốn ẩn.
     */
    @Override
    @Transactional
    public int deleteVariantsBulk(Integer productId) {
        requireProduct(productId);
        List<ProductVariant> variants =
                productVariantRepository.findByProductIdOrderByColorAscWeightClassAscGripSizeAsc(productId);
        if (variants.isEmpty()) {
            throw new BusinessException("Sản phẩm này chưa có biến thể nào để xoá.");
        }
        int deleted = 0;
        for (ProductVariant variant : variants) {
            if (orderItemRepository.existsByProductVariantId(variant.getId())) {
                continue;
            }
            String imageUrl = variant.getImageUrl();
            productVariantRepository.delete(variant);
            deleteVariantImageIfUnused(imageUrl, variant.getId());
            deleted++;
        }
        if (deleted == 0) {
            throw new BusinessException("Không xoá được biến thể nào: tất cả đã có trong đơn hàng. "
                    + "Hãy chuyển sang \"Ngừng bán\" để ẩn khỏi trang khách.");
        }
        return deleted;
    }

    // ==================== CẬP NHẬT GIÁ HÀNG LOẠT ====================
    /**
     * Cập nhật giá cho NHIỀU biến thể được tích chọn (mục 13 – mức Khá, thay cho bảng promotion):
     * – DISCOUNT: lấy Giá gốc (hoặc giá đang bán) làm giá gốc, đặt giá bán = giá gốc giảm %;
     * – CLEAR_DISCOUNT: trả giá bán về Giá gốc rồi xoá Giá gốc.
     * Chỉ tác động lên biến thể THUỘC sản phẩm đang mở và giữ ràng buộc CK_variants_original (original ≥ price).
     *
     * @return số biến thể thực sự được cập nhật
     */
    @Override
    @Transactional
    public int updatePricesBulk(Integer productId, BulkPriceForm form) {
        requireProduct(productId);

        List<Integer> variantIds = form.getVariantIds() == null ? List.of() : form.getVariantIds().stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (variantIds.isEmpty()) {
            throw new BusinessException("Bạn hãy tích chọn ít nhất một biến thể để cập nhật giá.");
        }

        BulkPriceMode mode = form.getMode() == null ? BulkPriceMode.DISCOUNT : form.getMode();
        BigDecimal percent = form.getPercent() == null ? BigDecimal.ZERO : form.getPercent();
        if (mode == BulkPriceMode.DISCOUNT
                && (percent.signum() <= 0 || percent.compareTo(MAX_DISCOUNT_PERCENT) > 0)) {
            throw new BusinessException("Phần trăm giảm giá phải lớn hơn 0 và không quá "
                    + MAX_DISCOUNT_PERCENT.toPlainString() + "%. Bạn nhập lại giúp nhé.");
        }

        int updated = 0;
        for (Integer variantId : variantIds) {
            // findByIdAndProductId: id lạ / biến thể của sản phẩm khác thì bỏ qua, không lộ dữ liệu
            ProductVariant variant = productVariantRepository.findByIdAndProductId(variantId, productId)
                    .orElse(null);
            if (variant == null) {
                continue;
            }

            if (mode == BulkPriceMode.DISCOUNT) {
                BigDecimal base = variant.getOriginalPrice() != null ? variant.getOriginalPrice() : variant.getPrice();
                BigDecimal newPrice = base.multiply(BigDecimal.valueOf(100).subtract(percent))
                        .divide(HUNDRED, 0, RoundingMode.HALF_UP);
                newPrice = roundToThousand(newPrice);
                if (newPrice.compareTo(base) >= 0) {
                    continue;   // làm tròn lên bằng giá gốc ⇒ không còn ý nghĩa giảm giá
                }
                variant.setOriginalPrice(base);
                variant.setPrice(newPrice);
            } else {
                if (variant.getOriginalPrice() == null) {
                    continue;   // không có Giá gốc thì không có gì để bỏ
                }
                variant.setPrice(variant.getOriginalPrice());
                variant.setOriginalPrice(null);
            }

            productVariantRepository.save(variant);
            updated++;
        }

        if (updated == 0) {
            throw new BusinessException("Không có biến thể nào cần cập nhật: "
                    + (mode == BulkPriceMode.DISCOUNT
                    ? "phần trăm giảm quá nhỏ (giá làm tròn về giá cũ)."
                    : "các biến thể đã chọn chưa có Giá gốc."));
        }
        return updated;
    }

    /** Làm tròn giá về mệnh giá 1.000 ₫ cho dễ đọc. */
    private BigDecimal roundToThousand(BigDecimal value) {
        return value.divide(THOUSAND, 0, RoundingMode.HALF_UP).multiply(THOUSAND);
    }

    // ==================== ẢNH SẢN PHẨM ====================

    @Override
    @Transactional(readOnly = true)
    public List<ProductImage> imagesOf(Integer productId) {
        requireProduct(productId);
        return productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(productId);
    }

    /** Tải ảnh lên: ảnh đầu tiên tự thành ảnh chính; nếu chọn "đặt làm ảnh chính" thì thay ảnh chính cũ. */
    @Override
    @Transactional
    public ProductImage addImage(Integer productId, MultipartFile file, boolean makePrimary) {
        Product product = requireProduct(productId);
        String imageUrl = fileStorageService.storeImage(file);

        List<ProductImage> existing = productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(productId);
        boolean firstImage = existing.isEmpty();

        ProductImage image = new ProductImage();
        image.setProduct(product);
        image.setImageUrl(imageUrl);
        image.setSortOrder(existing.isEmpty() ? 0 : existing.get(existing.size() - 1).getSortOrder() + 1);
        image.setPrimaryImage(firstImage || makePrimary);
        ProductImage saved = productImageRepository.save(image);

        if (!firstImage && makePrimary) {
            // Bỏ cờ ảnh chính cũ SAU khi đã ghi ảnh mới (unique index UX_product_images_primary chỉ cho 1 dòng).
            productImageRepository.findFirstByProductIdAndPrimaryImageTrue(productId)
                    .filter(current -> !current.getId().equals(saved.getId()))
                    .ifPresent(current -> {
                        current.setPrimaryImage(false);
                        productImageRepository.save(current);
                    });
        }
        return saved;
    }

    /** Đặt ảnh làm ảnh chính: tắt ảnh chính cũ trước rồi bật ảnh mới. */
    @Override
    @Transactional
    public ProductImage makePrimary(Integer productId, Integer imageId) {
        ProductImage image = requireImage(productId, imageId);
        if (image.isPrimaryImage()) {
            return image;
        }
        productImageRepository.findFirstByProductIdAndPrimaryImageTrue(productId).ifPresent(current -> {
            current.setPrimaryImage(false);
            productImageRepository.saveAndFlush(current);
        });
        image.setPrimaryImage(true);
        return productImageRepository.save(image);
    }

    /** Gỡ ảnh: xoá bản ghi + file đã upload; nếu là ảnh chính thì ảnh kế tiếp lên thay. */
    @Override
    @Transactional
    public void deleteImage(Integer productId, Integer imageId) {
        ProductImage image = requireImage(productId, imageId);
        boolean wasPrimary = image.isPrimaryImage();
        String imageUrl = image.getImageUrl();

        productImageRepository.delete(image);
        productImageRepository.flush();   // nhả unique index trước khi gán ảnh chính mới

        if (wasPrimary) {
            productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(productId).stream().findFirst()
                    .ifPresent(next -> {
                        next.setPrimaryImage(true);
                        productImageRepository.save(next);
                    });
        }
        fileStorageService.deleteIfUploaded(imageUrl);
    }

    // ==================== HÀM NỘI BỘ ====================

    private ProductRow toRow(Product product, List<ProductVariant> variants, String thumbnailUrl) {
        List<ProductVariant> sellable = variants.stream()
                .filter(variant -> variant.getStatus() == VariantStatus.ACTIVE)
                .toList();
        BigDecimal minPrice = sellable.stream()
                .map(ProductVariant::getPrice)
                .min(Comparator.naturalOrder())
                .orElse(null);
        long totalStock = sellable.stream().mapToLong(ProductVariant::getStockQuantity).sum();
        return new ProductRow(product, minPrice, totalStock, sellable.size(), thumbnailUrl);
    }

    private String normalizeCode(String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty() || code.length() > 50) {
            throw new BusinessException("Mã (SKU/mã sản phẩm) phải có từ 1 đến 50 ký tự.");
        }
        return code;
    }

    private void applyProductForm(Product product, ProductForm form) {
        Brand brand = brandRepository.findByIdAndDeletedFalse(form.getBrandId())
                .orElseThrow(() -> new BusinessException("Hãng sản xuất không tồn tại hoặc đã bị xoá."));
        Category category = categoryRepository.findByIdAndDeletedFalse(form.getCategoryId())
                .orElseThrow(() -> new BusinessException("Loại (lối chơi) không tồn tại hoặc đã bị xoá."));

        product.setName(form.getName() == null ? "" : form.getName().trim());
        product.setBrand(brand);
        product.setCategory(category);
        product.setDescription(trimToNull(form.getDescription()));
        product.setLengthMm(form.getLengthMm());
        product.setBalancePointMm(form.getBalancePointMm());
        product.setBalanceType(form.getBalanceType());
        product.setShaftFlexibility(form.getShaftFlexibility());
        product.setMaxTensionKg(form.getMaxTensionKg());
        product.setFrameMaterial(trimToNull(form.getFrameMaterial()));
        product.setShaftMaterial(trimToNull(form.getShaftMaterial()));
        product.setStatus(form.getStatus() == null ? ProductStatus.ACTIVE : form.getStatus());
    }

    /** Chuẩn hoá + kiểm tra luật của biến thể rồi gán vào entity. */
    private void applyVariantForm(ProductVariant variant, VariantForm form) {
        // Chuẩn hoá màu ngay khi lưu để "Xanh", "xanh", "  XANH " không sinh thêm màu mới.
        String color = ColorNames.normalize(form.getColor());
        String weightClass = form.getWeightClass() == null ? "" : form.getWeightClass().trim().toUpperCase(Locale.ROOT);
        String gripSize = form.getGripSize() == null ? "" : form.getGripSize().trim().toUpperCase(Locale.ROOT);

        if (color == null || color.isEmpty()) {
            throw new BusinessException("Vui lòng nhập màu sắc của biến thể.");
        }
        if (!color.matches("[\\p{L}\\p{N} .\\-/]+")) {
            throw new BusinessException("Màu sắc chỉ gồm chữ, số, khoảng trắng và . - / – bỏ ký tự lạ như ? ! @ nhé.");
        }
        if (!WEIGHT_CLASSES.contains(weightClass)) {
            throw new BusinessException("Trọng lượng chỉ nhận 2U, 3U, 4U hoặc 5U.");
        }
        if (!GRIP_SIZES.contains(gripSize)) {
            throw new BusinessException("Cỡ cán chỉ nhận G3, G4, G5 hoặc G6.");
        }

        BigDecimal price = form.getPrice();
        if (price == null || price.signum() <= 0) {
            throw new BusinessException("Giá bán phải lớn hơn 0 ₫.");
        }
        price = price.setScale(0, RoundingMode.HALF_UP);
        if (price.compareTo(MAX_VARIANT_PRICE) > 0) {
            throw new BusinessException("Giá bán tối đa 100.000.000 ₫ – bạn nhập lại giúp nhé.");
        }

        BigDecimal originalPrice = form.getOriginalPrice();
        if (originalPrice != null) {
            originalPrice = originalPrice.setScale(0, RoundingMode.HALF_UP);
            if (originalPrice.compareTo(MAX_VARIANT_PRICE) > 0) {
                throw new BusinessException("Giá gốc tối đa 100.000.000 ₫ – bạn nhập lại giúp nhé.");
            }
            if (originalPrice.signum() == 0) {
                originalPrice = null;
            } else if (originalPrice.compareTo(price) < 0) {
                throw new BusinessException("Giá gốc phải lớn hơn hoặc bằng giá bán.");
            }
        }
        if (form.getStockQuantity() < 0) {
            throw new BusinessException("Tồn kho không được âm.");
        }
        if (form.getStockQuantity() > MAX_VARIANT_STOCK) {
            throw new BusinessException("Tồn kho tối đa " + MAX_VARIANT_STOCK + " chiếc mỗi biến thể.");
        }

        variant.setColor(color);
        variant.setWeightClass(weightClass);
        variant.setGripSize(gripSize);
        variant.setPrice(price);
        variant.setOriginalPrice(originalPrice);
        variant.setStockQuantity(form.getStockQuantity());
        variant.setStatus(form.getStatus() == null ? VariantStatus.ACTIVE : form.getStatus());
    }

    /**
     * Gán ảnh cho biến thể theo thứ tự ưu tiên:
     * 1. file mới tải lên (lưu qua FileStorageService, đổi tên UUID);
     * 2. đường dẫn ảnh nhập tay (giữ tương thích dữ liệu seed);
     * 3. nếu admin tích "gỡ ảnh" thì để trống.
     * Khi ảnh thay đổi, file upload cũ được xoá để không rác thư mục uploads.
     */
    private void applyVariantImage(ProductVariant variant, VariantForm form, String oldImageUrl) {
        String imageUrl = trimToNull(form.getImageUrl());
        if (form.getImageFile() != null && !form.getImageFile().isEmpty()) {
            imageUrl = fileStorageService.storeImage(form.getImageFile());
        } else if (form.isRemoveImage()) {
            imageUrl = null;
        }
        variant.setImageUrl(imageUrl);
        if (oldImageUrl != null && !oldImageUrl.equals(imageUrl)) {
            deleteVariantImageIfUnused(oldImageUrl, variant.getId());
        }
    }

    /**
     * Xoá file upload của biến thể, nhưng chỉ khi không còn ảnh sản phẩm / biến thể khác dùng cùng URL.
     * Admin có thể nhập tay URL của một ảnh đã upload cho biến thể; nếu xoá thẳng file thì
     * bộ ảnh sản phẩm cũng mất ảnh theo.
     */
    private void deleteVariantImageIfUnused(String imageUrl, Integer variantId) {
        if (imageUrl == null) {
            return;
        }
        if (productImageRepository.existsByImageUrl(imageUrl)
                || productVariantRepository.existsByImageUrlAndIdNot(imageUrl, variantId)) {
            return;
        }
        fileStorageService.deleteIfUploaded(imageUrl);
    }

    private void requireFreeSku(ProductVariant variant) {
        boolean taken = variant.getId() == null
                ? productVariantRepository.existsBySku(variant.getSku())
                : productVariantRepository.existsBySkuAndIdNot(variant.getSku(), variant.getId());
        if (taken) {
            throw new BusinessException("SKU \"" + variant.getSku() + "\" đã tồn tại ở một biến thể khác.");
        }
    }

    private void requireFreeCombination(ProductVariant variant, boolean updating) {
        Integer productId = variant.getProduct().getId();
        boolean duplicated = updating
                ? productVariantRepository.existsByProductIdAndColorAndWeightClassAndGripSizeAndIdNot(
                        productId, variant.getColor(), variant.getWeightClass(), variant.getGripSize(), variant.getId())
                : productVariantRepository.existsByProductIdAndColorAndWeightClassAndGripSize(
                        productId, variant.getColor(), variant.getWeightClass(), variant.getGripSize());
        if (duplicated) {
            throw new BusinessException("Sản phẩm đã có biến thể " + variant.getColor() + " – "
                    + variant.getWeightClass() + " – " + variant.getGripSize()
                    + ". Mỗi tổ hợp màu/trọng lượng/cỡ cán chỉ được tạo một lần.");
        }
    }

    private ProductVariant requireVariant(Integer productId, Integer variantId) {
        return productVariantRepository.findByIdAndProductId(variantId, productId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy biến thể cần xử lý."));
    }

    private ProductImage requireImage(Integer productId, Integer imageId) {
        return productImageRepository.findByIdAndProductId(imageId, productId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy ảnh cần xử lý."));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
