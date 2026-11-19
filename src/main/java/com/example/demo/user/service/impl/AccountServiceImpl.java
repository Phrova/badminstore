package com.example.demo.user.service.impl;

import com.example.demo.user.dto.ChangePasswordForm;
import com.example.demo.user.dto.ProfileForm;
import com.example.demo.user.entity.User;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.NotFoundException;
import com.example.demo.order.repository.OrderRepository;
import com.example.demo.user.repository.UserAddressRepository;
import com.example.demo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.auth.service.AuthService;
import com.example.demo.user.service.AccountService;

/**
 * Nghiệp vụ tài khoản phía khách: xem/cập nhật hồ sơ và đổi mật khẩu.
 *
 * Mọi kiểm tra đều làm ở server: email duy nhất, họ tên không rỗng, mật khẩu hiện tại
 * phải đúng trước khi đổi, mật khẩu mới được băm BCrypt và không được trùng mật khẩu cũ.
 */
@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final UserAddressRepository userAddressRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional(readOnly = true)
    public User requireUser(Integer userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy tài khoản của bạn."));
    }

    @Override
    @Transactional(readOnly = true)
    public long countOrders(Integer userId) {
        return orderRepository.countByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public long countAddresses(Integer userId) {
        return userAddressRepository.countByUserIdAndDeletedFalse(userId);
    }

    /** Cập nhật họ tên, email đăng nhập và số điện thoại; không đổi vai trò/trạng thái. */
    @Override
    @Transactional
    public User updateProfile(Integer userId, ProfileForm form) {
        User user = requireUser(userId);
        String email = AuthService.normalizeEmail(form.getEmail());

        if (userRepository.existsByEmailAndIdNot(email, userId)) {
            throw new BusinessException("Email " + email + " đã được tài khoản khác sử dụng. Vui lòng chọn email khác.");
        }

        user.setEmail(email);
        user.setFullName(normalizeFullName(form.getFullName()));
        user.setPhone(trimToNull(form.getPhone()));
        return userRepository.save(user);
    }

    /**
     * Đổi mật khẩu: xác thực mật khẩu hiện tại, kiểm tra xác nhận khớp và mật khẩu mới
     * khác mật khẩu cũ, sau đó băm BCrypt và lưu.
     */
    @Override
    @Transactional
    public void changePassword(Integer userId, ChangePasswordForm form) {
        User user = requireUser(userId);

        if (!passwordEncoder.matches(form.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessException("Mật khẩu hiện tại không đúng. Vui lòng nhập lại.");
        }
        if (!form.getNewPassword().equals(form.getConfirmPassword())) {
            throw new BusinessException("Mật khẩu xác nhận không khớp. Vui lòng nhập lại.");
        }
        if (passwordEncoder.matches(form.getNewPassword(), user.getPasswordHash())) {
            throw new BusinessException("Mật khẩu mới phải khác mật khẩu hiện tại.");
        }

        user.setPasswordHash(passwordEncoder.encode(form.getNewPassword()));
        userRepository.save(user);
    }

    private String normalizeFullName(String rawName) {
        String name = rawName == null ? "" : rawName.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new BusinessException("Vui lòng nhập họ tên.");
        }
        if (name.length() > 100) {
            throw new BusinessException("Họ tên tối đa 100 ký tự.");
        }
        return name;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
