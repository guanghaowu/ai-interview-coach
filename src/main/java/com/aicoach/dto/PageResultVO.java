package com.aicoach.dto;

import lombok.Data;

import java.util.List;

/**
 * 通用分页返回
 *
 * @param <T> 列表元素类型
 */
@Data
public class PageResultVO<T> {

    /** 总记录数 */
    private long total;

    /** 当前页码（从 1 开始） */
    private long page;

    /** 每页条数 */
    private long size;

    /** 当前页数据 */
    private List<T> records;

    public static <T> PageResultVO<T> of(long total, long page, long size, List<T> records) {
        PageResultVO<T> vo = new PageResultVO<>();
        vo.setTotal(total);
        vo.setPage(page);
        vo.setSize(size);
        vo.setRecords(records);
        return vo;
    }
}
