package com.example.demo.catalog.service;

import com.example.demo.catalog.dto.CompareItem;
import jakarta.servlet.http.HttpSession;
import java.util.List;

/**
 * So sánh sản phẩm (tính năng mức Khá) – danh sách chọn nằm trong SESSION, không ghi CSDL:
 * khách vãng lai vẫn so sánh được, tối đa {@value #MAX_ITEMS} sản phẩm.
 */
public interface CompareService {

    public static final String SESSION_KEY = "compareProductIds";

    public static final int MAX_ITEMS = 4;

    public record AddResult(boolean added, String message) {
    }

    List<Integer> currentIds(HttpSession session);

    int count(HttpSession session);

    AddResult add(HttpSession session, Integer productId);

    void remove(HttpSession session, Integer productId);

    void clear(HttpSession session);

    List<CompareItem> compareItems(HttpSession session);
}
