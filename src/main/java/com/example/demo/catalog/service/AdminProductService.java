package com.example.demo.catalog.service;

import com.example.demo.catalog.dto.BulkPriceForm;
import com.example.demo.catalog.dto.ProductForm;
import com.example.demo.catalog.dto.ProductRow;
import com.example.demo.catalog.dto.VariantBulkForm;
import com.example.demo.catalog.dto.VariantForm;
import com.example.demo.catalog.entity.Brand;
import com.example.demo.catalog.entity.Category;
import com.example.demo.catalog.entity.Product;
import com.example.demo.catalog.entity.ProductImage;
import com.example.demo.catalog.entity.ProductVariant;
import com.example.demo.catalog.enums.ProductStatus;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.web.multipart.MultipartFile;

/**
 * Quản trị danh mục bán hàng: sản phẩm, biến thể (giá + tồn kho) và bộ ảnh.
 *
 * Nguyên tắc:
 * – xoá sản phẩm là XOÁ MỀM (is_deleted = 1) để không phá dữ liệu đơn hàng cũ;
 * – biến thể đã xuất hiện trong đơn hàng thì KHÔNG cho xoá, chỉ được "ngừng bán";
 * – mọi luật của BadStore.sql (CK/UQ) đều được kiểm tra trước và báo lỗi tiếng Việt dễ hiểu;
 * – ảnh upload đi qua FileStorageService (giới hạn 5 MB, kiểm tra MIME + đổi tên file).
 */
public interface AdminProductService {

    public static final int PAGE_SIZE = 10;

    Page<ProductRow> search(String keyword, Integer brandId, Integer categoryId, ProductStatus status, int pageNumber);

    List<Brand> brands();

    List<Category> categories();

    Product requireProduct(Integer id);

    Product createProduct(ProductForm form);

    Product updateProduct(Integer id, ProductForm form);

    Product toggleProductStatus(Integer id);

    Product softDeleteProduct(Integer id);

    List<ProductVariant> variantsOf(Integer productId);

    ProductVariant requireVariantOf(Integer productId, Integer variantId);

    ProductVariant createVariant(Integer productId, VariantForm form);

    ProductVariant updateVariant(Integer productId, Integer variantId, VariantForm form);

    List<ProductVariant> createVariantsBulk(Integer productId, VariantBulkForm form);

    ProductVariant toggleVariantStatus(Integer productId, Integer variantId);

    void deleteVariant(Integer productId, Integer variantId);

    int deleteVariantsBulk(Integer productId);

    int updatePricesBulk(Integer productId, BulkPriceForm form);

    List<ProductImage> imagesOf(Integer productId);

    ProductImage addImage(Integer productId, MultipartFile file, boolean makePrimary);

    ProductImage makePrimary(Integer productId, Integer imageId);

    void deleteImage(Integer productId, Integer imageId);
}
