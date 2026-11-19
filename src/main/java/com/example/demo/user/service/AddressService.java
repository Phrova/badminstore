package com.example.demo.user.service;

import com.example.demo.user.dto.AddressForm;
import com.example.demo.user.entity.UserAddress;
import java.util.List;
import java.util.Optional;

/**
 * Địa chỉ giao hàng của khách: thêm, đặt mặc định, xoá mềm.
 *
 * Lưu ý ràng buộc DB: UX_user_addresses_default – mỗi khách tối đa 1 địa chỉ mặc định,
 * nên mọi thao tác đổi mặc định đều theo thứ tự "bỏ cờ cũ TRƯỚC, đặt cờ mới SAU" trong 1 transaction.
 */
public interface AddressService {

    public static final int MAX_ADDRESSES = 5;

    List<UserAddress> list(Integer userId);

    Optional<UserAddress> defaultAddress(Integer userId);

    UserAddress requireOwned(Integer userId, Integer addressId);

    Optional<UserAddress> findOwned(Integer userId, Integer addressId);

    UserAddress create(Integer userId, AddressForm form);

    void setDefault(Integer userId, Integer addressId);

    void delete(Integer userId, Integer addressId);
}
