package com.yuelin.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ScrollResult {
    private List<?> list = Collections.emptyList();
    private Long minTime;
    private Integer offset;

    public List<?> getList() {
        return new ArrayList<>(list);
    }

    public void setList(List<?> list) {
        this.list = list == null ? Collections.emptyList() : new ArrayList<>(list);
    }

    public Long getMinTime() {
        return minTime;
    }

    public void setMinTime(Long minTime) {
        this.minTime = minTime;
    }

    public Integer getOffset() {
        return offset;
    }

    public void setOffset(Integer offset) {
        this.offset = offset;
    }
}
