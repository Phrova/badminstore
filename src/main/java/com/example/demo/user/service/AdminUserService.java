package com.example.demo.user.service;

import com.example.demo.user.dto.AdminUserForm;
import com.example.demo.user.dto.UserRow;
import com.example.demo.user.entity.User;
import com.example.demo.user.enums.UserRole;
import com.example.demo.user.enums.UserStatus;
import org.springframework.data.domain.Page;

/**
 * Quản trị tài khoản người dùng.
 *
 * Quy tắc an toàn:
 * – không xoá tài khoản (đơn hàng/địa chỉ/đánh giá còn tham chiếu) ⇒ khoá hoặc chuyển INACTIVE;
 * – admin không thể tự hạ quyền hoặc tự khoá tài khoản đang đăng nhập;
 * – luôn phải còn ít nhất một ADMIN đang ACTIVE để không mất quyền quản trị hệ thống;
 * – mật khẩu luôn băm BCrypt, không lưu hoặc hiển thị mật khẩu thô.
 */
public interface AdminUserService {

    Page<UserRow> search(String keyword, UserRole role, UserStatus status, int pageNumber);

    User requireUser(Integer id);

    User create(AdminUserForm form);

    User update(Integer id, AdminUserForm form, Integer currentAdminId);

    User toggleLock(Integer id, Integer currentAdminId);
}
