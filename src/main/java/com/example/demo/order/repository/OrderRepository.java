package com.example.demo.order.repository;

import com.example.demo.admin.dto.RevenueRow;
import com.example.demo.admin.dto.StatusCountRow;
import com.example.demo.admin.dto.TopProductRow;
import com.example.demo.user.dto.UserOrderStatRow;
import com.example.demo.order.dto.OrderSummaryView;
import com.example.demo.order.entity.Order;
import com.example.demo.order.enums.OrderStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Truy vấn đơn hàng. */
public interface OrderRepository extends JpaRepository<Order, Integer> {

    Optional<Order> findByOrderCode(String orderCode);

    /**
     * Tìm đơn theo mã vận đơn GHN. Schema chuẩn có unique filtered index UX_orders_ghn_code,
     * nhưng vẫn trả List để phát hiện dữ liệu bất thường ở DB cũ và từ chối thay vì đoán sai đơn.
     */
    List<Order> findAllByGhnOrderCode(String ghnOrderCode);

    Optional<Order> findByIdAndUserId(Integer id, Integer userId);

    List<Order> findByUserIdOrderByCreatedAtDesc(Integer userId);

    /** Số đơn của một khách (dùng cho trang tài khoản). */
    long countByUserId(Integer userId);

    Page<Order> findByUserIdOrderByCreatedAtDesc(Integer userId, Pageable pageable);

    Page<Order> findByOrderStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);

    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByOrderStatus(OrderStatus status);

    /**
     * Chi tiết đơn để HIỂN THỊ cho CHÍNH KHÁCH: fetch join sẵn items + biến thể + sản phẩm + thanh toán
     * + khách + voucher (open-in-view = false nên template chỉ đọc được dữ liệu đã nạp).
     */
    @Query("""
            select distinct o from Order o
                left join fetch o.items i
                left join fetch i.productVariant v
                left join fetch v.product p
                left join fetch o.payment
                left join fetch o.user
                left join fetch o.voucher
            where o.id = :id and o.user.id = :userId
            """)
    Optional<Order> findDetailForUser(@Param("id") Integer id, @Param("userId") Integer userId);

    /** Như trên nhưng cho ADMIN – xem được mọi đơn (kể cả đơn của khách vãng lai). */
    @Query("""
            select distinct o from Order o
                left join fetch o.items i
                left join fetch i.productVariant v
                left join fetch v.product p
                left join fetch o.payment
                left join fetch o.user
                left join fetch o.voucher
                left join fetch o.address
            where o.id = :id
            """)
    Optional<Order> findDetailForAdmin(@Param("id") Integer id);

    /**
     * KHOÁ BI QUAN dòng đơn cho các luồng GHI (xác nhận/huỷ/hoàn trả/xác nhận thanh toán).
     *
     * Vì sao cần: hai admin cùng bấm xác nhận một đơn sẽ cùng đọc PENDING rồi cùng trừ kho
     * nếu kho còn đủ. Khoá dòng đơn khiến transaction thứ hai phải chờ, sau đó đọc lại trạng thái
     * mới nhất và bị chặn bởi máy trạng thái thay vì trừ kho lần nữa.
     *
     * Chỉ dùng cho luồng ghi; các màn hình chỉ đọc vẫn dùng {@link #findDetailForAdmin(Integer)}
     * để tránh giữ khoá không cần thiết.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Integer id);

    /** Như {@link #findByIdForUpdate(Integer)} nhưng kèm điều kiện đơn thuộc đúng khách. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id and o.user.id = :userId")
    Optional<Order> findOwnByIdForUpdate(@Param("id") Integer id, @Param("userId") Integer userId);

    /** Danh sách đơn của 1 khách (dòng dữ liệu gọn, 2 truy vấn con đếm số dòng/số lượng). */
    @Query(value = """
            select new com.example.demo.order.dto.OrderSummaryView(o.id, o.orderCode, o.orderSource, o.orderStatus,
                o.totalAmount, o.createdAt, o.receiverName, u.email, p.paymentMethod, p.paymentStatus,
                (select count(i) from OrderItem i where i.order = o),
                (select coalesce(sum(i.quantity), 0) from OrderItem i where i.order = o))
            from Order o
                left join o.user u
                left join o.payment p
            where o.user.id = :userId
            order by o.createdAt desc, o.id desc
            """,
            countQuery = "select count(o) from Order o where o.user.id = :userId")
    Page<OrderSummaryView> findMyOrderSummaries(@Param("userId") Integer userId, Pageable pageable);

    /**
     * Danh sách đơn cho ADMIN: lọc theo trạng thái (null = tất cả) và từ khoá
     * (mã đơn / số điện thoại người nhận / email khách).
     */
    @Query(value = """
            select new com.example.demo.order.dto.OrderSummaryView(o.id, o.orderCode, o.orderSource, o.orderStatus,
                o.totalAmount, o.createdAt, o.receiverName, u.email, p.paymentMethod, p.paymentStatus,
                (select count(i) from OrderItem i where i.order = o),
                (select coalesce(sum(i.quantity), 0) from OrderItem i where i.order = o))
            from Order o
                left join o.user u
                left join o.payment p
            where (:status is null or o.orderStatus = :status)
                and (:keyword is null
                     or lower(o.orderCode) like lower(concat('%', :keyword, '%'))
                     or o.receiverPhone like concat('%', :keyword, '%')
                     or lower(u.email) like lower(concat('%', :keyword, '%')))
            order by o.createdAt desc, o.id desc
            """,
            countQuery = """
                    select count(o) from Order o left join o.user u
                    where (:status is null or o.orderStatus = :status)
                        and (:keyword is null
                             or lower(o.orderCode) like lower(concat('%', :keyword, '%'))
                             or o.receiverPhone like concat('%', :keyword, '%')
                             or lower(u.email) like lower(concat('%', :keyword, '%')))
                    """)
    Page<OrderSummaryView> findOrderSummariesForAdmin(@Param("status") OrderStatus status,
                                                      @Param("keyword") String keyword,
                                                      Pageable pageable);

    /** Dòng đơn thô kể từ mốc thời gian – Service gom theo ngày để vẽ biểu đồ dashboard. */
    @Query("""
            select new com.example.demo.admin.dto.RevenueRow(o.createdAt, o.totalAmount, o.orderStatus)
            from Order o
            where o.createdAt >= :from
            order by o.createdAt asc, o.id asc
            """)
    List<RevenueRow> findRowsSince(@Param("from") LocalDateTime from);

    /** Số đơn theo từng trạng thái bằng 1 câu truy vấn (thay vì 6 lần count). */
    @Query("""
            select new com.example.demo.admin.dto.StatusCountRow(o.orderStatus, count(o))
            from Order o
            group by o.orderStatus
            """)
    List<StatusCountRow> countByStatusGrouped();

    /**
     * Số đơn và tổng tiền đã mua của từng khách trong một trang quản lý người dùng.
     * Chỉ cộng tiền các đơn DELIVERED (đơn huỷ/hoàn trả không tính vào "đã mua").
     */
    @Query("""
            select new com.example.demo.user.dto.UserOrderStatRow(
                o.user.id,
                count(o.id),
                coalesce(sum(case when o.orderStatus = com.example.demo.order.enums.OrderStatus.DELIVERED
                                  then o.totalAmount else 0 end), 0))
            from Order o
            where o.user.id in :userIds
            group by o.user.id
            """)
    List<UserOrderStatRow> countOrdersByUserIds(@Param("userIds") Collection<Integer> userIds);

    /** Sản phẩm bán chạy: chỉ tính đơn đã xác nhận trở lên (bỏ đơn chờ, đơn huỷ, đơn hoàn trả). */
    @Query("""
            select new com.example.demo.admin.dto.TopProductRow(i.productName, sum(i.quantity), sum(i.subtotal))
            from OrderItem i
            where i.order.orderStatus in :statuses
            group by i.productName
            order by sum(i.quantity) desc
            """)
    List<TopProductRow> findTopProducts(@Param("statuses") Collection<OrderStatus> statuses, Pageable pageable);

    /** Đơn đang giao có mã vận đơn GHN – job định kỳ dùng để đồng bộ trạng thái vận đơn. */
    List<Order> findByGhnOrderCodeNotNullAndOrderStatusIn(Collection<OrderStatus> statuses);
}
