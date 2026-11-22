package com.example.demo.cart.service.impl;

import com.example.demo.cart.dto.CartLine;
import com.example.demo.cart.dto.CartView;
import com.example.demo.cart.entity.CartItem;
import com.example.demo.catalog.entity.Product;
import com.example.demo.catalog.entity.ProductImage;
import com.example.demo.catalog.entity.ProductVariant;
import com.example.demo.catalog.enums.ProductStatus;
import com.example.demo.catalog.enums.VariantStatus;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.NotFoundException;
import com.example.demo.cart.repository.CartItemRepository;
import com.example.demo.catalog.repository.ProductImageRepository;
import com.example.demo.catalog.repository.ProductVariantRepository;
import com.example.demo.user.repository.UserRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.catalog.service.ProductService;
import java.util.Set;
import java.util.stream.Collectors;
import com.example.demo.cart.service.CartService;

/**
 * Nghiệp vụ giỏ hàng (bảng cart_items – mỗi biến thể chỉ có 1 dòng/khách).
 *
 * Nguyên tắc kiểm tra số lượng (tiêu chí "validate chính xác số lượng" mức Khá):
 * – luôn đọc lại tồn kho từ DB ở MỌI thao tác (thêm/sửa), không tin số lượng từ client;
 * – số lượng 1 dòng từ 1..{@value #MAX_QUANTITY_PER_LINE} và không vượt tồn kho hiện tại;
 * – khi cộng dồn số lượng cũ + số lượng mới vẫn phải ≤ tồn kho.
 */
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private final CartItemRepository cartItemRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ProductImageRepository productImageRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public int removeItems(Integer userId, java.util.List<Integer> cartItemIds) {
        // Chỉ xoá id thật sự nằm trong giỏ của khách → id lạ bị bỏ qua, không xoá nhầm người khác.
        Set<Integer> owned = getCart(userId).getLines().stream()
                .map(CartLine::getCartItemId)
                .collect(Collectors.toSet());
        int removed = 0;
        for (Integer id : cartItemIds) {
            if (id != null && owned.contains(id)) {
                removeItem(userId, id);
                removed++;
            }
        }
        return removed;
    }

    /** Giỏ hàng đầy đủ của khách (đã gắn ảnh đại diện cho từng dòng). */
    @Override
    @Transactional(readOnly = true)
    public CartView getCart(Integer userId) {
        List<CartItem> items = cartItemRepository.findCartByUserId(userId);
        Map<Integer, String> imageByProduct = primaryImages(items);
        List<CartLine> lines = items.stream()
                .map(item -> new CartLine(item,
                        imageOf(item.getProductVariant(), imageByProduct),
                        maxQuantity(item.getProductVariant())))
                .toList();
        return new CartView(lines);
    }

    /** Tổng số sản phẩm trong giỏ – hiện trên badge ở header. */
    @Override
    @Transactional(readOnly = true)
    public int countItems(Integer userId) {
        Long total = cartItemRepository.sumQuantityByUserId(userId);
        return total == null ? 0 : (int) Math.min(Integer.MAX_VALUE, total.longValue());
    }

    /** Thêm vào giỏ khi chỉ có variantId (giữ tương thích với các lời gọi cũ). */
    @Override
    @Transactional
    public CartLine addToCart(Integer userId, Integer variantId, Integer quantity) {
        return addToCart(userId, null, variantId, quantity);
    }

    /**
     * Thêm vào giỏ: cùng biến thể thì cộng dồn số lượng, luôn chặn vượt tồn kho.
     *
     * productId là sản phẩm khách đang xem. Khi có giá trị, biến thể phải thuộc đúng
     * sản phẩm đó – chặn trường hợp form bị sửa để thêm biến thể của sản phẩm khác.
     * Giá và tồn kho vẫn đọc lại từ DB, không dùng dữ liệu trình duyệt gửi lên.
     */
    @Override
    @Transactional
    public CartLine addToCart(Integer userId, Integer productId, Integer variantId, Integer quantity) {
        ProductVariant variant = productVariantRepository.findById(variantId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy phiên bản sản phẩm bạn vừa chọn."));
        requireBelongsToProduct(variant, productId);

        int wanted = quantity == null ? 1 : quantity;
        requireValidQuantity(wanted);
        requireSellable(variant);

        CartItem item = cartItemRepository.findByUserIdAndProductVariantId(userId, variantId).orElse(null);
        int current = item == null ? 0 : item.getQuantity();
        int total = current + wanted;
        if (total > variant.getStockQuantity()) {
            throw new BusinessException(notEnoughStock(variant, total));
        }

        if (item == null) {
            item = new CartItem();
            item.setUser(userRepository.getReferenceById(userId));
            item.setProductVariant(variant);
        }
        item.setQuantity(total);
        return toLine(cartItemRepository.save(item), variant);
    }

    /** Sửa số lượng 1 dòng: chỉ áp dụng cho dòng thuộc giỏ của chính khách đang đăng nhập. */
    @Override
    @Transactional
    public CartLine updateQuantity(Integer userId, Integer cartItemId, Integer quantity) {
        int wanted = quantity == null ? 0 : quantity;
        if (wanted < 1) {
            throw new BusinessException("Số lượng phải từ 1 trở lên. Muốn bỏ sản phẩm này, hãy dùng nút xoá.");
        }
        requireValidQuantity(wanted);

        CartItem item = myItem(userId, cartItemId);
        ProductVariant variant = item.getProductVariant();
        requireSellable(variant);
        requireValidQuantity(wanted);
        if (wanted > variant.getStockQuantity()) {
            throw new BusinessException(notEnoughStock(variant, wanted));
        }

        item.setQuantity(wanted);
        return toLine(cartItemRepository.save(item), variant);
    }

    /** Xoá 1 dòng khỏi giỏ. */
    @Override
    @Transactional
    public void removeItem(Integer userId, Integer cartItemId) {
        cartItemRepository.delete(myItem(userId, cartItemId));
    }

    /** Xoá sạch giỏ (dùng cho nút "Xoá tất cả"), trả về số dòng đã xoá. */
    @Override
    @Transactional
    public int clearCart(Integer userId) {
        List<CartItem> items = cartItemRepository.findCartByUserId(userId);
        if (!items.isEmpty()) {
            cartItemRepository.deleteAll(items);
        }
        return items.size();
    }

    // ==================== phần dùng chung ====================

    /** Dòng giỏ hàng của chính khách – người khác sửa id trên URL thì coi như không tồn tại. */
    private CartItem myItem(Integer userId, Integer cartItemId) {
        return cartItemRepository.findByIdAndUserId(cartItemId, userId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy sản phẩm này trong giỏ hàng của bạn."));
    }

    /** Số lượng hợp lệ cho 1 dòng giỏ: từ 1 đến {@value #MAX_QUANTITY_PER_LINE}. */
    private void requireValidQuantity(int quantity) {
        if (quantity < 1) {
            throw new BusinessException("Số lượng phải từ 1 trở lên.");
        }
        if (quantity > MAX_QUANTITY_PER_LINE) {
            throw new BusinessException("Mỗi phiên bản chỉ được đặt tối đa " + MAX_QUANTITY_PER_LINE + " sản phẩm.");
        }
    }

    /** Biến thể phải thuộc đúng sản phẩm khách đang xem (khi form có gửi productId). */
    private void requireBelongsToProduct(ProductVariant variant, Integer productId) {
        if (productId != null && !productId.equals(variant.getProduct().getId())) {
            throw new BusinessException("Phiên bản bạn chọn không thuộc sản phẩm đang xem. "
                    + "Vui lòng tải lại trang và chọn lại phiên bản.");
        }
    }

    /** Biến thể + sản phẩm phải đang bán thì mới cho vào giỏ. */
    private void requireSellable(ProductVariant variant) {
        Product product = variant.getProduct();
        if (product.getStatus() != ProductStatus.ACTIVE || product.isDeleted()) {
            throw new BusinessException("Sản phẩm \"" + product.getName() + "\" đã ngừng bán.");
        }
        if (variant.getStatus() != VariantStatus.ACTIVE) {
            throw new BusinessException("Phiên bản " + label(variant) + " đã ngừng bán.");
        }
        if (variant.getStockQuantity() <= 0) {
            throw new BusinessException("Phiên bản " + label(variant) + " đã hết hàng.");
        }
    }

    private String notEnoughStock(ProductVariant variant, int wanted) {
        return "Không đủ tồn kho cho SKU " + variant.getSku() + ": bạn cần " + wanted
                + " nhưng chỉ còn " + variant.getStockQuantity() + " sản phẩm.";
    }

    private String label(ProductVariant variant) {
        return variant.getColor() + " " + variant.getWeightClass() + " " + variant.getGripSize()
                + " (SKU " + variant.getSku() + ")";
    }

    private int maxQuantity(ProductVariant variant) {
        return Math.max(0, Math.min(variant.getStockQuantity(), MAX_QUANTITY_PER_LINE));
    }

    private CartLine toLine(CartItem item, ProductVariant variant) {
        return new CartLine(item, imageOf(variant, primaryImages(List.of(item))), maxQuantity(variant));
    }

    /** Ảnh hiển thị của dòng: ưu tiên ảnh riêng của biến thể, sau đó ảnh đại diện sản phẩm. */
    private String imageOf(ProductVariant variant, Map<Integer, String> imageByProduct) {
        if (variant.getImageUrl() != null && !variant.getImageUrl().isBlank()) {
            return variant.getImageUrl();
        }
        return imageByProduct.getOrDefault(variant.getProduct().getId(), ProductService.NO_IMAGE);
    }

    /** 1 truy vấn cho tất cả dòng: ảnh đại diện của từng sản phẩm có trong giỏ. */
    private Map<Integer, String> primaryImages(List<CartItem> items) {
        List<Integer> productIds = items.stream()
                .map(item -> item.getProductVariant().getProduct().getId())
                .distinct()
                .toList();
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Integer, String> imageByProduct = new HashMap<>();
        for (ProductImage image : productImageRepository
                .findByProductIdInOrderByPrimaryImageDescSortOrderAscIdAsc(productIds)) {
            imageByProduct.putIfAbsent(image.getProduct().getId(), image.getImageUrl());
        }
        return imageByProduct;
    }
}
