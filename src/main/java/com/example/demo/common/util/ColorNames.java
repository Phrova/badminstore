package com.example.demo.common.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Chuẩn hoá tên màu của biến thể.
 *
 * CSDL dùng collation Vietnamese_100_CI_AS (không phân biệt hoa/thường), nhưng Java
 * so sánh chuỗi mặc định lại phân biệt. Nếu admin nhập "Xanh", "xanh" hoặc "XANH"
 * thì DB coi là một màu còn giao diện lại tách thành nhiều nhóm.
 *
 * Hai hàm dưới đây tạo quy ước chung cho toàn ứng dụng:
 * – {@link #key(String)}: khoá so sánh (trim + gộp khoảng trắng + NFC + lowercase),
 *   dùng để gom nhóm màu, tìm ảnh đại diện và cộng tồn kho;
 * – {@link #normalize(String)}: tên hiển thị chuẩn (viết hoa chữ đầu, phần còn lại lowercase),
 *   dùng khi lưu biến thể để không sinh thêm cách viết mới.
 */
public final class ColorNames {

    /** Khoảng trắng thường và NBSP (hay gặp khi copy tên màu từ Excel/Word). */
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0]+");

    private ColorNames() {
    }

    /** Khoá so sánh màu, không phân biệt hoa/thường và khoảng trắng thừa. */
    public static String key(String color) {
        if (color == null) {
            return "";
        }
        String value = WHITESPACE.matcher(color).replaceAll(" ").trim();
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    /** Tên màu chuẩn để lưu/hiển thị: "  xanh   ĐEN " → "Xanh đen". */
    public static String normalize(String color) {
        if (color == null) {
            return null;
        }
        String value = Normalizer.normalize(
                WHITESPACE.matcher(color).replaceAll(" ").trim(), Normalizer.Form.NFC);
        if (value.isEmpty()) {
            return value;
        }
        int first = value.codePointAt(0);
        int firstLength = Character.charCount(first);
        return new StringBuilder(value.length())
                .appendCodePoint(Character.toUpperCase(first))
                .append(value.substring(firstLength).toLowerCase(Locale.ROOT))
                .toString();
    }
}
