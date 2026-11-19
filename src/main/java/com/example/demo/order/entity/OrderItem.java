package com.example.demo.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Nationalized;
import com.example.demo.catalog.entity.ProductVariant;

/**
 * Dòng hàng của đơn (bảng order_items) – lưu SNAPSHOT tên/sku/màu/giá tại thời điểm mua
 * để sản phẩm đổi giá về sau không làm sai hoá đơn cũ.
 * Ràng buộc DB: subtotal = unit_price * quantity (CK_order_items_subtotal).
 */
@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** Biến thể đã mua – cũng là cơ sở để trừ/cộng kho. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_variant_id", nullable = false)
    private ProductVariant productVariant;

    @Nationalized
    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "sku", nullable = false, length = 50)
    private String sku;

    @Nationalized
    @Column(name = "color", nullable = false, length = 50)
    private String color;

    @Column(name = "weight_class", nullable = false, length = 10)
    private String weightClass;

    @Column(name = "grip_size", nullable = false, length = 10)
    private String gripSize;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "unit_price", nullable = false, precision = 18, scale = 0)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "subtotal", nullable = false, precision = 18, scale = 0)
    private BigDecimal subtotal = BigDecimal.ZERO;

    /** Tạo dòng hàng từ biến thể, chụp lại toàn bộ thông tin hiển thị của sản phẩm. */
    public static OrderItem from(ProductVariant variant, int quantity) {
        OrderItem item = new OrderItem();
        item.quantity = quantity;
        item.replaceVariant(variant);
        return item;
    }

    /**
     * Đổi dòng hàng sang biến thể khác (giữ nguyên số lượng) và chụp lại snapshot mới.
     * Dùng khi admin thay sản phẩm hết hàng bằng sản phẩm khác trong đơn PENDING.
     */
    public void replaceVariant(ProductVariant variant) {
        this.productVariant = variant;
        this.productName = variant.getProduct().getName();
        this.sku = variant.getSku();
        this.color = variant.getColor();
        this.weightClass = variant.getWeightClass();
        this.gripSize = variant.getGripSize();
        this.imageUrl = variant.getImageUrl();
        this.unitPrice = variant.getPrice();
        recalculateSubtotal();
    }

    /** subtotal luôn được tính ở server, không nhận từ form. */
    public void recalculateSubtotal() {
        subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    @PrePersist
    @PreUpdate
    void beforeSave() {
        recalculateSubtotal();
    }
}
