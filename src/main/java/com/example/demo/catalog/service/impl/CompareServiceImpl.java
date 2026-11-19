package com.example.demo.catalog.service.impl;

import com.example.demo.catalog.dto.CompareItem;
import com.example.demo.catalog.entity.Product;
import com.example.demo.catalog.entity.ProductImage;
import com.example.demo.catalog.entity.ProductVariant;
import com.example.demo.catalog.enums.ProductStatus;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.NotFoundException;
import com.example.demo.catalog.repository.ProductImageRepository;
import com.example.demo.catalog.repository.ProductRepository;
import com.example.demo.catalog.repository.ProductVariantRepository;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.catalog.service.CompareService;

/**
 * So sánh sản phẩm (tính năng mức Khá) – danh sách chọn nằm trong SESSION, không ghi CSDL:
 * khách vãng lai vẫn so sánh được, tối đa {@value #MAX_ITEMS} sản phẩm.
 */
@Service
@RequiredArgsConstructor
public class CompareServiceImpl implements CompareService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ProductImageRepository productImageRepository;

    /** Danh sách id đang lưu trong session (luôn trả về bản sao, không sửa trực tiếp attribute). */
    @Override
    public List<Integer> currentIds(HttpSession session) {
        Object attribute = session.getAttribute(SESSION_KEY);
        if (attribute instanceof List<?> list) {
            return list.stream()
                    .filter(Integer.class::isInstance)
                    .map(Integer.class::cast)
                    .toList();
        }
        return List.of();
    }

    /** Số sản phẩm đang so sánh – header hiển thị badge này ở mọi trang. */
    @Override
    public int count(HttpSession session) {
        return currentIds(session).size();
    }

    @Override
    public AddResult add(HttpSession session, Integer productId) {
        Product product = productRepository.findDetailById(productId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy sản phẩm cần so sánh."));
        if (product.getStatus() != ProductStatus.ACTIVE) {
            throw new BusinessException("Sản phẩm \"" + product.getName() + "\" đang ngừng bán nên không thể so sánh.");
        }

        List<Integer> ids = new ArrayList<>(currentIds(session));
        if (ids.contains(productId)) {
            return new AddResult(false, "Sản phẩm \"" + product.getName() + "\" đã có trong danh sách so sánh.");
        }
        if (ids.size() >= MAX_ITEMS) {
            throw new BusinessException("Chỉ so sánh tối đa " + MAX_ITEMS
                    + " sản phẩm cùng lúc. Bạn hãy bỏ bớt một sản phẩm rồi thêm lại nhé.");
        }

        ids.add(productId);
        session.setAttribute(SESSION_KEY, ids);
        return new AddResult(true, "Đã thêm \"" + product.getName() + "\" vào danh sách so sánh.");
    }

    @Override
    public void remove(HttpSession session, Integer productId) {
        List<Integer> ids = new ArrayList<>(currentIds(session));
        if (ids.remove(productId)) {
            session.setAttribute(SESSION_KEY, ids);
        }
    }

    @Override
    public void clear(HttpSession session) {
        session.removeAttribute(SESSION_KEY);
    }

    /**
     * Bảng so sánh theo ĐÚNG THỨ TỰ khách đã thêm: sản phẩm + hãng/loại (fetch join),
     * ảnh đại diện và số liệu biến thể – tổng cộng 3 truy vấn cho cả bảng.
     */
    @Override
    @Transactional(readOnly = true)
    public List<CompareItem> compareItems(HttpSession session) {
        List<Integer> ids = currentIds(session);
        if (ids.isEmpty()) {
            return List.of();
        }

        List<Product> products = productRepository.findDetailByIdIn(ids);
        Map<Integer, String> thumbnails = new HashMap<>();
        for (ProductImage image : productImageRepository
                .findByProductIdInOrderByPrimaryImageDescSortOrderAscIdAsc(ids)) {
            thumbnails.putIfAbsent(image.getProduct().getId(), image.getImageUrl());
        }
        Map<Integer, List<ProductVariant>> variantsByProduct = productVariantRepository.findByProductIdIn(ids).stream()
                .collect(Collectors.groupingBy(variant -> variant.getProduct().getId()));

        List<CompareItem> items = new ArrayList<>();
        for (Integer id : ids) {
            products.stream()
                    .filter(product -> product.getId().equals(id))
                    .findFirst()
                    .ifPresent(product -> items.add(CompareItem.of(product,
                            variantsByProduct.getOrDefault(id, List.of()), thumbnails.get(id))));
        }
        return items;
    }
}
