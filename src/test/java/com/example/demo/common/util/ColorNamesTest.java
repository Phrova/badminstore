package com.example.demo.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Kiểm thử quy ước tên màu dùng chung giữa trang khách, trang admin và JavaScript.
 */
class ColorNamesTest {

    @Test
    @DisplayName("key(): bỏ khoảng trắng, gộp khoảng trắng giữa chữ và không phân biệt hoa/thường")
    void key_normalizesCaseAndWhitespace() {
        assertThat(ColorNames.key("  Xanh   đen ")).isEqualTo("xanh đen");
        assertThat(ColorNames.key("XANH ĐEN")).isEqualTo(ColorNames.key("xanh đen"));
        assertThat(ColorNames.key("Xanh\u00A0đen")).isEqualTo("xanh đen");
        assertThat(ColorNames.key("\u00A0Xanh đen\u00A0")).isEqualTo("xanh đen");
        assertThat(ColorNames.key(null)).isEmpty();
    }

    @Test
    @DisplayName("normalize(): viết hoa chữ đầu, lowercase phần còn lại và gộp khoảng trắng")
    void normalize_returnsCanonicalDisplayName() {
        assertThat(ColorNames.normalize("  xanh   ĐEN ")).isEqualTo("Xanh đen");
        assertThat(ColorNames.normalize("ĐỎ ĐÔ")).isEqualTo("Đỏ đô");
        assertThat(ColorNames.normalize("   ")).isEmpty();
        assertThat(ColorNames.normalize(null)).isNull();
    }

    @Test
    @DisplayName("NFC: chữ có dấu dạng tổ hợp và dạng dựng sẵn được coi là cùng một màu")
    void key_treatsComposedAndDecomposedUnicodeAsSameColor() {
        String composed = "Đỏ";
        String decomposed = "Đo\u0309";
        assertThat(ColorNames.key(decomposed)).isEqualTo(ColorNames.key(composed));
    }
}
