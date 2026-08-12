package com.yingdao.aicommerce.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PageResponse<T> from(IPage<T> page) {
        return new PageResponse<>(
                page.getRecords(),
                Math.toIntExact(page.getCurrent() - 1),
                Math.toIntExact(page.getSize()),
                page.getTotal(),
                Math.toIntExact(page.getPages())
        );
    }

    public static <S, T> PageResponse<T> from(IPage<S> page, Function<S, T> mapper) {
        return new PageResponse<>(
                page.getRecords().stream().map(mapper).toList(),
                Math.toIntExact(page.getCurrent() - 1),
                Math.toIntExact(page.getSize()),
                page.getTotal(),
                Math.toIntExact(page.getPages())
        );
    }
}
