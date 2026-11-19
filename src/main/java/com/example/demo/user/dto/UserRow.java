package com.example.demo.user.dto;

import com.example.demo.user.entity.User;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Một dòng trong bảng quản lý người dùng: tài khoản + số đơn + tổng tiền đã mua (đơn DELIVERED). */
@Getter
@RequiredArgsConstructor
public class UserRow {

    private final User user;
    private final long orderCount;
    private final BigDecimal totalSpent;
}
