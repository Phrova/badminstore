package com.example.demo.auth.service.impl;

import com.example.demo.auth.dto.RegisterRequest;
import com.example.demo.user.entity.User;
import com.example.demo.user.enums.UserRole;
import com.example.demo.user.enums.UserStatus;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.user.repository.UserRepository;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.auth.service.AuthService;

/** Nghiệp vụ tài khoản: đăng ký khách hàng mới. */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Đăng ký tài khoản khách hàng. Mọi kiểm tra đều làm ở server:
     * email chưa tồn tại, mật khẩu xác nhận khớp, mật khẩu được băm bằng BCrypt.
     * Tài khoản mới luôn là CUSTOMER/ACTIVE – khách không thể tự phong quyền ADMIN.
     */
    @Override
    @Transactional
    public User register(RegisterRequest request) {
        String email = AuthService.normalizeEmail(request.getEmail());

        if (userRepository.existsByEmail(email)) {
            throw new BusinessException("Email " + email + " đã được sử dụng. Vui lòng đăng nhập hoặc dùng email khác.");
        }
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException("Mật khẩu xác nhận không khớp. Vui lòng nhập lại.");
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setFullName(request.getFullName().trim());
        user.setPhone(hasText(request.getPhone()) ? request.getPhone().trim() : null);
        user.setRole(UserRole.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);

        return userRepository.save(user);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
