package com.example.demo.shipping.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shipping.enums.GhnShippingState;
import com.example.demo.shipping.enums.GhnStatus;
import com.example.demo.order.enums.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Kiểm thử ánh xạ trạng thái GHN: đủ 23 mã, nhóm hiển thị/cảnh báo và quy tắc tự đổi đơn.
 * Mapper thuần nên không cần Spring/Mockito.
 */
class ShippingStatusMapperTest {

    @Test
    @DisplayName("Ánh xạ đủ 23 trạng thái GHN sang nhóm hiển thị, không bỏ sót mã nào")
    void mapsEveryGhnStatus() {
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.READY_TO_PICK))
                .isEqualTo(GhnShippingState.WAITING_PICKUP);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.PICKING))
                .isEqualTo(GhnShippingState.PICKING);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.MONEY_COLLECT_PICKING))
                .isEqualTo(GhnShippingState.PICKING);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.PICKED))
                .isEqualTo(GhnShippingState.IN_TRANSIT);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.STORING))
                .isEqualTo(GhnShippingState.IN_TRANSIT);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.TRANSPORTING))
                .isEqualTo(GhnShippingState.IN_TRANSIT);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.SORTING))
                .isEqualTo(GhnShippingState.IN_TRANSIT);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.DELIVERING))
                .isEqualTo(GhnShippingState.DELIVERING);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.MONEY_COLLECT_DELIVERING))
                .isEqualTo(GhnShippingState.DELIVERING);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.DELIVERED))
                .isEqualTo(GhnShippingState.DELIVERED);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.DELIVERY_FAIL))
                .isEqualTo(GhnShippingState.DELIVERY_FAILED);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.WAITING_TO_RETURN))
                .isEqualTo(GhnShippingState.RETURNING);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.RETURN_TRANSPORTING))
                .isEqualTo(GhnShippingState.RETURNING);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.RETURN_SORTING))
                .isEqualTo(GhnShippingState.RETURNING);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.RETURNING))
                .isEqualTo(GhnShippingState.RETURNING);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.RETURN))
                .isEqualTo(GhnShippingState.RETURNING);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.RETURNED))
                .isEqualTo(GhnShippingState.RETURNED);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.RETURN_FAIL))
                .isEqualTo(GhnShippingState.RETURN_FAILED);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.CANCEL))
                .isEqualTo(GhnShippingState.CANCELLED);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.EXCEPTION))
                .isEqualTo(GhnShippingState.EXCEPTION);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.DAMAGE))
                .isEqualTo(GhnShippingState.DAMAGED);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.LOST))
                .isEqualTo(GhnShippingState.LOST);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.SCRAP))
                .isEqualTo(GhnShippingState.SCRAPPED);
        assertThat(ShippingStatusMapper.stateOf(GhnStatus.UNKNOWN))
                .isEqualTo(GhnShippingState.UNKNOWN);

        // Mọi enum GHN đều phải có nhóm – thêm mã mới mà quên mapper sẽ làm test này đỏ.
        assertThat(GhnStatus.values()).allSatisfy(status ->
                assertThat(ShippingStatusMapper.stateOf(status)).isNotNull());
    }

    @Test
    @DisplayName("Chỉ delivered/returned/cancel được tự đổi đơn, đúng điều kiện máy trạng thái")
    void suggestsOnlyApprovedTransitions() {
        assertThat(ShippingStatusMapper.suggestedOrderStatus(GhnStatus.DELIVERED, OrderStatus.CONFIRMED))
                .isEqualTo(OrderStatus.DELIVERED);
        assertThat(ShippingStatusMapper.suggestedOrderStatus(GhnStatus.DELIVERED, OrderStatus.SHIPPING))
                .isEqualTo(OrderStatus.DELIVERED);
        assertThat(ShippingStatusMapper.suggestedOrderStatus(GhnStatus.DELIVERED, OrderStatus.PENDING))
                .isNull();

        assertThat(ShippingStatusMapper.suggestedOrderStatus(GhnStatus.RETURNED, OrderStatus.DELIVERED))
                .isEqualTo(OrderStatus.RETURNED);
        // Đang giao mà GHN báo hoàn hàng: không tự huỷ/hoàn trả, admin kiểm tra tay.
        assertThat(ShippingStatusMapper.suggestedOrderStatus(GhnStatus.RETURNED, OrderStatus.SHIPPING))
                .isNull();

        assertThat(ShippingStatusMapper.suggestedOrderStatus(GhnStatus.CANCEL, OrderStatus.CONFIRMED))
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(ShippingStatusMapper.suggestedOrderStatus(GhnStatus.CANCEL, OrderStatus.DELIVERED))
                .isNull();

        assertThat(ShippingStatusMapper.suggestedOrderStatus(GhnStatus.DELIVERY_FAIL, OrderStatus.SHIPPING))
                .isNull();
        assertThat(ShippingStatusMapper.suggestedOrderStatus(GhnStatus.RETURN_FAIL, OrderStatus.SHIPPING))
                .isNull();
        assertThat(ShippingStatusMapper.suggestedOrderStatus(GhnStatus.EXCEPTION, OrderStatus.SHIPPING))
                .isNull();
        assertThat(ShippingStatusMapper.suggestedOrderStatus(GhnStatus.LOST, OrderStatus.SHIPPING))
                .isNull();
        assertThat(ShippingStatusMapper.suggestedOrderStatus(GhnStatus.DAMAGE, OrderStatus.SHIPPING))
                .isNull();
        assertThat(ShippingStatusMapper.suggestedOrderStatus(GhnStatus.SCRAP, OrderStatus.SHIPPING))
                .isNull();
    }

    @Test
    @DisplayName("Ngoại lệ/mất/hư hỏng/tiêu hủy/hoàn thất bại đều là cảnh báo, không tự xử lý")
    void warningStatesDoNotChangeOrder() {
        assertThat(ShippingStatusMapper.isWarning(GhnStatus.EXCEPTION)).isTrue();
        assertThat(ShippingStatusMapper.isWarning(GhnStatus.LOST)).isTrue();
        assertThat(ShippingStatusMapper.isWarning(GhnStatus.DAMAGE)).isTrue();
        assertThat(ShippingStatusMapper.isWarning(GhnStatus.SCRAP)).isTrue();
        assertThat(ShippingStatusMapper.isWarning(GhnStatus.RETURN_FAIL)).isTrue();
        assertThat(ShippingStatusMapper.isWarning(GhnStatus.DELIVERY_FAIL)).isTrue();
        assertThat(ShippingStatusMapper.isWarning(GhnStatus.DELIVERED)).isFalse();
        assertThat(ShippingStatusMapper.actionHint(GhnStatus.SCRAP)).contains("tiêu hủy");
    }

    @Test
    @DisplayName("Mã lạ/status null trả UNKNOWN, không làm vỡ luồng đồng bộ")
    void unknownStatusIsSafe() {
        assertThat(GhnStatus.fromApi("scrap")).isEqualTo(GhnStatus.SCRAP);
        assertThat(GhnStatus.fromApi("ma-la")).isEqualTo(GhnStatus.UNKNOWN);
        assertThat(ShippingStatusMapper.stateOf(null)).isEqualTo(GhnShippingState.UNKNOWN);
        assertThat(ShippingStatusMapper.labelOf(null)).isEqualTo("Không xác định");
    }

    @Test
    @DisplayName("Nhận diện webhook trùng: chỉ delivered/returned/cancel mới có trạng thái đích tương ứng")
    void detectsDuplicateEvents() {
        assertThat(ShippingStatusMapper.isAlreadyAtStatus(GhnStatus.DELIVERED, OrderStatus.DELIVERED)).isTrue();
        assertThat(ShippingStatusMapper.isAlreadyAtStatus(GhnStatus.RETURNED, OrderStatus.RETURNED)).isTrue();
        assertThat(ShippingStatusMapper.isAlreadyAtStatus(GhnStatus.CANCEL, OrderStatus.CANCELLED)).isTrue();
        assertThat(ShippingStatusMapper.isAlreadyAtStatus(GhnStatus.DELIVERED, OrderStatus.SHIPPING)).isFalse();
        assertThat(ShippingStatusMapper.isAlreadyAtStatus(GhnStatus.LOST, OrderStatus.SHIPPING)).isFalse();
        assertThat(ShippingStatusMapper.duplicateMessage(OrderStatus.DELIVERED)).contains("trùng");
    }

    @Test
    @DisplayName("Thông báo no-op phân biệt hoàn hàng sớm, cảnh báo và trạng thái bình thường")
    void noChangeMessagesExplainWhy() {
        assertThat(ShippingStatusMapper.noChangeMessage(GhnStatus.RETURNED, OrderStatus.SHIPPING))
                .contains("không tự hoàn trả");
        assertThat(ShippingStatusMapper.noChangeMessage(GhnStatus.LOST, OrderStatus.SHIPPING))
                .contains("kiểm tra thủ công");
        assertThat(ShippingStatusMapper.noChangeMessage(GhnStatus.DELIVERING, OrderStatus.SHIPPING))
                .contains("không cần đổi");
    }
}
