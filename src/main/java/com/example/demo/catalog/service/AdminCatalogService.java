package com.example.demo.catalog.service;

import com.example.demo.catalog.dto.BrandForm;
import com.example.demo.catalog.dto.BrandRow;
import com.example.demo.catalog.dto.CategoryForm;
import com.example.demo.catalog.dto.CategoryRow;
import com.example.demo.catalog.entity.Brand;
import com.example.demo.catalog.entity.Category;
import java.util.List;

/**
 * Quản trị danh mục nền của sản phẩm: hãng sản xuất (brands) và loại vợt (categories).
 *
 * Quy tắc:
 * – xoá là XOÁ MỀM (is_deleted = 1) để không phá liên kết với sản phẩm/đơn hàng cũ;
 * – không cho xoá hãng/loại đang được sản phẩm CHƯA xoá mềm sử dụng;
 * – tên không trùng trong các bản ghi chưa xoá mềm (khớp unique index có WHERE is_deleted = 0);
 * – logo hãng là tuỳ chọn, tải lên qua FileStorageService (giới hạn dung lượng + kiểm tra MIME).
 */
public interface AdminCatalogService {

    List<BrandRow> searchBrands(String keyword);

    Brand requireBrand(Integer id);

    Brand createBrand(BrandForm form);

    Brand updateBrand(Integer id, BrandForm form);

    Brand deleteBrand(Integer id);

    List<CategoryRow> searchCategories(String keyword);

    Category requireCategory(Integer id);

    Category createCategory(CategoryForm form);

    Category updateCategory(Integer id, CategoryForm form);

    Category deleteCategory(Integer id);
}
