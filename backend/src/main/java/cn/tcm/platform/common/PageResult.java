package cn.tcm.platform.common;

import java.util.List;

public record PageResult<T>(int page,int pageSize,long total,List<T> items) {
    public static int offset(int page,int pageSize) {
        if(page<1 || page>100000 || pageSize<1 || pageSize>100) throw ApiException.invalid("分页参数超出范围");
        return (page-1)*pageSize;
    }
}
