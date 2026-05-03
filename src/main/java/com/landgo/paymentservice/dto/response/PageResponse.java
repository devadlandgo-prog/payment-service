package com.landgo.paymentservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PageResponse<T> {
    private List<T> data;
    private int page;
    private int pageSize;
    private long total;
    private int totalPages;
    private boolean first;
    private boolean last;
}
