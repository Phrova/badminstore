package com.example.demo.catalog.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** Form admin thêm NHIỀU biến thể cùng lúc – mỗi dòng là một VariantForm, kiểm tra từng dòng ở service. */
@Getter
@Setter
public class VariantBulkForm {

    private List<VariantForm> rows = new ArrayList<>();
}