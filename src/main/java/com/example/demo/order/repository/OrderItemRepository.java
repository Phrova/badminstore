package com.example.demo.order.repository;

import com.example.demo.order.entity.OrderItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Truy vấn dòng hàng của đơn. */
public interface OrderItemRepository extends JpaRepository<OrderItem, Integer> {

    /**
     * Lấy dòng hàng theo thứ tự product_variant_id TĂNG DẦN.
     * Quy ước bắt buộc khi trừ/cộng kho để mọi transaction khoá cùng một thứ tự ⇒ tránh deadlock.
     */
    List<OrderItem> findByOrderIdOrderByProductVariantIdAsc(Integer orderId);

    List<OrderItem> findByProductVariantId(Integer productVariantId);

    /** Biến thể đã từng vào đơn hàng chưa – có rồi thì cấm xoá để giữ lịch sử. */
    boolean existsByProductVariantId(Integer productVariantId);

    /**
     * Id các dòng đơn khách ĐÃ NHẬN HÀNG (DELIVERED) cho một sản phẩm – cơ sở để cho phép đánh giá.
     * Mỗi dòng đơn chỉ đánh giá được một lần (ràng buộc UNIQUE product_reviews.order_item_id).
     */
    @Query("""
            select oi.id from OrderItem oi
             where oi.order.user.id = :userId
               and oi.order.orderStatus = com.example.demo.order.enums.OrderStatus.DELIVERED
               and oi.productVariant.product.id = :productId
             order by oi.id asc
            """)
    List<Integer> findDeliveredItemIdsOf(@Param("userId") Integer userId, @Param("productId") Integer productId);
}
