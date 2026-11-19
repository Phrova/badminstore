package com.example.demo.auth.config;

import com.example.demo.auth.service.CustomUserDetails;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.FlashMapManager;

/**
 * Thêm flash "Đăng nhập thành công. Xin chào …" để trang kế tiếp hiển thị toast sau khi đăng nhập.
 * Kèm tên và vai trò để main.js dựng toast chào mừng có avatar, thay cho câu thông báo dài.
 * Không ghim {@code targetRequestPath} để thông báo vẫn hiện khi khách được đưa về đúng trang
 * họ định mở trước khi bị yêu cầu đăng nhập (ví dụ trang sản phẩm/giỏ hàng), không chỉ trang chủ.
 *
 * LƯU Ý Spring Framework 7: {@code RequestContextUtils.getOutputFlashMap()} / {@code getFlashMapManager()}
 * chỉ ĐỌC request attribute do DispatcherServlet đặt. Ở tầng filter (đăng nhập) các attribute này chưa có,
 * nên phải tự tạo FlashMap rồi gọi thẳng bean {@link FlashMapManager} (sessionFlashMapManager của Spring MVC).
 */
@Component
@RequiredArgsConstructor
public class FlashMessageAuthenticationSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private final MessageSource messageSource;
    private final FlashMapManager flashMapManager;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                       Authentication authentication) throws IOException, ServletException {
        if (authentication.getPrincipal() instanceof CustomUserDetails principal) {
            FlashMap flashMap = new FlashMap();
            flashMap.put("successMessage",
                    messageSource.getMessage("msg.login.success", new Object[]{displayName(principal)}, null));
            // Dữ liệu có cấu trúc cho toast chào mừng (xem static/js/main.js).
            flashMap.put("loginSuccessName", displayName(principal));
            flashMap.put("loginSuccessAdmin", principal.isAdmin());
            // Đánh dấu để flash.html bỏ alert trùng: main.js đã hiện toast "Đăng nhập thành công".
            flashMap.put("loginSuccess", Boolean.TRUE);
            flashMapManager.saveOutputFlashMap(flashMap, request, response);
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }

    /** Tên hiển thị trên toast: ưu tiên họ tên, tài khoản thiếu tên thì dùng email. */
    private String displayName(CustomUserDetails principal) {
        String fullName = principal.getFullName();
        return fullName == null || fullName.isBlank() ? principal.getEmail() : fullName.trim();
    }
}
