package com.lagu.platform.common.dto;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.domain.Page;

import java.util.List;

@Data
@Builder
public class PageResult<T> {

    private List<T> content;
    private int page;
    private int size;
    private long total;
    private int totalPages;

    public static <T> PageResult<T> from(Page<T> page) {
        return PageResult.<T>builder()
                .content(page.getContent())
                .page(page.getNumber())
                .size(page.getSize())
                .total(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .build();
    }
}
