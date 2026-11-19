package com.example.demo.catalog.service;

import com.example.demo.catalog.dto.FilterOptions;
import com.example.demo.catalog.dto.ProductCardView;
import com.example.demo.catalog.dto.ProductDetailView;
import com.example.demo.catalog.dto.ProductFilter;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Nghiệp vụ xem sản phẩm phía khách: danh sách có tìm kiếm/lọc/sắp xếp/phân trang và trang chi tiết.
 * Chỉ trả về sản phẩm {@code status = ACTIVE} và chưa xoá mềm.
 */
public interface ProductService {

    public static final int PAGE_SIZE = 12;

    public static final String NO_IMAGE = "/images/no-image.svg";

    Page<ProductCardView> search(ProductFilter filter, int pageNumber);

    List<ProductCardView> getFeatured(int limit);

    ProductDetailView getDetail(Integer id);

    FilterOptions getFilterOptions();
}
