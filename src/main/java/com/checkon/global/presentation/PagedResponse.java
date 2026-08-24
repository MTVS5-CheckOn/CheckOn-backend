package com.checkon.global.presentation;

import java.util.List;

public record PagedResponse<T>(
	PaginationMetadata metadata,
	List<T> items
) {
	public PagedResponse {
		items = List.copyOf(items);
	}

	public static <T> PagedResponse<T> of(
		List<T> items,
		int pageNumber,
		int pageSize,
		long totalItemCount
	) {
		long totalPageCount = totalItemCount == 0
			? 0
			: ((totalItemCount - 1) / pageSize) + 1;
		var metadata = new PaginationMetadata(
			pageNumber,
			pageSize,
			items.size(),
			totalItemCount,
			totalPageCount,
			pageNumber == 0,
			totalPageCount == 0 || pageNumber >= totalPageCount - 1
		);
		return new PagedResponse<>(metadata, items);
	}

	public record PaginationMetadata(
		int pageNumber,
		int pageSize,
		int itemCount,
		long totalItemCount,
		long totalPageCount,
		boolean isFirst,
		boolean isLast
	) { }
}
