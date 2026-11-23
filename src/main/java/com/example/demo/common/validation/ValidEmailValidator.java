package com.example.demo.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Chặn email sai định dạng và tên miền gõ nhầm (gmail.col, yahoo.con, gmial.com…).
 * Trống/null được bỏ qua để @NotBlank xử lý riêng.
 */
public class ValidEmailValidator implements ConstraintValidator<ValidEmail, String> {

    private static final Pattern FORMAT = Pattern.compile(
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$");

    private static final Set<String> GMAIL_OK = Set.of("gmail.com", "googlemail.com");

    private static final Map<String, String> TLD_FIX = Map.ofEntries(
            Map.entry("col", "com"), Map.entry("con", "com"), Map.entry("cmo", "com"),
            Map.entry("comm", "com"), Map.entry("coom", "com"), Map.entry("vom", "com"),
            Map.entry("xom", "com"), Map.entry("om", "com"), Map.entry("cm", "com"),
            Map.entry("cim", "com"), Map.entry("cpm", "com"), Map.entry("clm", "com"),
            Map.entry("comv", "com"), Map.entry("comn", "com"), Map.entry("conm", "com"),
            Map.entry("ner", "net"), Map.entry("met", "net"), Map.entry("nrt", "net"),
            Map.entry("orgg", "org"), Map.entry("orh", "org"), Map.entry("vnn", "vn"));

    private static final Map<String, String> DOMAIN_FIX = Map.of(
            "gmial.com", "gmail.com", "gmai.com", "gmail.com", "gmal.com", "gmail.com",
            "gamil.com", "gmail.com", "gnail.com", "gmail.com", "gmail.co", "gmail.com",
            "yaho.com", "yahoo.com", "hotmial.com", "hotmail.com", "outlok.com", "outlook.com");

    /** Chuẩn hoá trước khi kiểm tra trùng / lưu: cắt khoảng trắng, tên miền về chữ thường. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        int at = v.lastIndexOf('@');
        return at > 0 ? v.substring(0, at) + '@' + v.substring(at + 1).toLowerCase() : v;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) return true;
        String v = normalize(value);
        String error = null;

        if (v.length() > 100) {
            error = "Email tối đa 100 ký tự";
        } else {
            int at = v.lastIndexOf('@');
            String local = at > 0 ? v.substring(0, at) : "";
            String domain = at > 0 ? v.substring(at + 1) : "";
            if (at <= 0 || domain.isEmpty() || local.length() > 64 || domain.length() > 255
                    || local.startsWith(".") || local.endsWith(".") || local.contains("..")
                    || !FORMAT.matcher(v).matches()) {
                error = "Email chưa đúng định dạng (ví dụ: ten@mien.com)";
            } else if (domain.startsWith("gmail.") && !GMAIL_OK.contains(domain)) {
                error = "Gmail chỉ dùng tên miền gmail.com (ý bạn là " + local + "@gmail.com?)";
            } else {
                int cut = domain.lastIndexOf('.');
                String tld = domain.substring(cut + 1);
                if (TLD_FIX.containsKey(tld)) {
                    error = "Tên miền \"." + tld + "\" không tồn tại (ý bạn là "
                            + local + '@' + domain.substring(0, cut + 1) + TLD_FIX.get(tld) + "?)";
                } else if (DOMAIN_FIX.containsKey(domain)) {
                    error = "Email có thể gõ nhầm tên miền (ý bạn là " + local + '@' + DOMAIN_FIX.get(domain) + "?)";
                }
            }
        }

        if (error == null) return true;
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(error).addConstraintViolation();
        return false;
    }
}