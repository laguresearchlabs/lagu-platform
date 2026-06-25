package com.lagu.platform.search.dto;

import lombok.Data;

@Data
public class SortCriteria {
    private String field;
    private String order = "asc";  // asc | desc
}
