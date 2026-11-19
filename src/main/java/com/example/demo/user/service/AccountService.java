package com.example.demo.user.service;

import com.example.demo.user.dto.ChangePasswordForm;
import com.example.demo.user.dto.ProfileForm;
import com.example.demo.user.entity.User;

/**
 * Nghiệp vụ tài khoản phía khách: xem/cập nhật hồ sơ và đổi mật khẩu.
 *
 * Mọi kiểm tra đều làm ở server: email duy nhất, họ tên không rỗng, mật khẩu hiện tại
 * phải đúng trước khi đổi, mật khẩu mới được băm BCrypt và không được trùng mật khẩu cũ.
 */
public interface AccountService {

    User requireUser(Integer userId);

    long countOrders(Integer userId);

    long countAddresses(Integer userId);

    User updateProfile(Integer userId, ProfileForm form);

    void changePassword(Integer userId, ChangePasswordForm form);
}
