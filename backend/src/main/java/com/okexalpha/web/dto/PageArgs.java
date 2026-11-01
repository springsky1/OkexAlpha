package com.okexalpha.web.dto;

import com.okexalpha.error.BadRequestException;

/** limit/offset 归一化。 */
public record PageArgs(int limit, int offset) {

    public static PageArgs of(Integer limit, Integer offset, int defaultLimit, int maxLimit) {
        int resolvedLimit = limit == null ? defaultLimit : limit;
        int resolvedOffset = offset == null ? 0 : offset;
        if (resolvedLimit < 1) {
            throw new BadRequestException("limit must be >= 1");
        }
        if (resolvedOffset < 0) {
            throw new BadRequestException("offset must be >= 0");
        }
        return new PageArgs(Math.min(resolvedLimit, maxLimit), resolvedOffset);
    }
}
