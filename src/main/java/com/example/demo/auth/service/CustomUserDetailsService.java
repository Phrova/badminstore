package com.example.demo.auth.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Cầu nối giữa bảng users và Spring Security: đăng nhập bằng EMAIL.
 * Cột email trong DB có collation không phân biệt hoa/thường nên tìm thẳng không cần lower().
 */
public interface CustomUserDetailsService extends UserDetailsService {

    @Override
    UserDetails loadUserByUsername(String email) throws UsernameNotFoundException;
}
