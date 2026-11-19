package com.example.demo.auth.service;

import com.example.demo.auth.dto.RegisterRequest;
import com.example.demo.user.entity.User;
import java.util.Locale;

/** Nghiệp vụ tài khoản: đăng ký khách hàng mới. */
public interface AuthService {

    User register(RegisterRequest request);

    public static String normalizeEmail(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }
}
