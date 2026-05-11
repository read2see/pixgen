package com.ga.pixgen.dto;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

public record PageResponse<T>(
        List<T> content,
        PageableResponse pageable,
        long totalElements,
        int totalPages,
        boolean last,
        boolean first,
        int size,
        int number,
        SortResponse sort,
        int numberOfElements,
        boolean empty
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                PageableResponse.from(page.getPageable()),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast(),
                page.isFirst(),
                page.getSize(),
                page.getNumber(),
                SortResponse.from(page.getSort()),
                page.getNumberOfElements(),
                page.isEmpty());
    }

    public record PageableResponse(
            int pageNumber,
            int pageSize,
            SortResponse sort,
            long offset,
            boolean paged,
            boolean unpaged
    ) {
        static PageableResponse from(Pageable pageable) {
            if (pageable == null || pageable.isUnpaged()) {
                return new PageableResponse(0, 0, SortResponse.from(Sort.unsorted()), 0, false, true);
            }
            return new PageableResponse(
                    pageable.getPageNumber(),
                    pageable.getPageSize(),
                    SortResponse.from(pageable.getSort()),
                    pageable.getOffset(),
                    pageable.isPaged(),
                    pageable.isUnpaged());
        }
    }

    public record SortResponse(
            boolean empty,
            boolean sorted,
            boolean unsorted
    ) {
        static SortResponse from(Sort sort) {
            Sort value = sort == null ? Sort.unsorted() : sort;
            return new SortResponse(value.isEmpty(), value.isSorted(), value.isUnsorted());
        }
    }
}
