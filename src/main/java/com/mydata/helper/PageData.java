package com.mydata.helper;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 分页信息
 *
 * @param <T>
 * @author Liu Tao
 */
public final class PageData<T> implements Serializable {
    private static final long serialVersionUID = 909167901620112956L;
    // 当前页
    private int curPage = 1;
    // 每页显示多少条记录数
    private int pageSize = 20;
    // 总页数
    private long totalPage = 1;
    // 总记录数
    private long totalCount = 0;
    // 分页数据集合
    private List<T> dataList = new ArrayList<T>();
    // 分页页码信息
    private PgIdx pageIndex;
    // 是否有下一页
    private Boolean isNext = false;
    // 是否有上一页
    private Boolean isPrev = false;

    public void setIsNext(Boolean isNext) {
        this.isNext = isNext;
    }

    public void setIsPrev(Boolean isPrev) {
        this.isPrev = isPrev;
    }

    public PageData(int curPage, int pageSize, long totalCount, List<T> dataList) {
        this.curPage = curPage;
        this.pageSize = pageSize;
        this.dataList = dataList;
        this.totalCount = totalCount;
        this.totalPage = (totalCount+pageSize-1)/pageSize; //this.totalCount % this.pageSize == 0 ? this.totalCount / this.pageSize : this.totalCount / this.pageSize + 1;
        this.pageIndex = PgIdx.getPageIndex(10, curPage, this.totalPage);
        this.isNext = this.curPage < this.totalPage;
        this.isPrev = this.curPage > 1;
    }

    public Boolean getIsNext() {
        return isNext;
    }

    public PageData() {
        super();
    }

    public boolean getIsPrev() {
        return isPrev;
    }

    public int getCurPage() {
        return curPage;
    }

    public long getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(long totalCount) {
        this.totalCount = totalCount;
    }

    public PgIdx getPageIndex() {
        return pageIndex;
    }

    public void setPageIndex(PgIdx pageIndex) {
        this.pageIndex = pageIndex;
    }

    public void setCurPage(int curPage) {
        this.curPage = curPage;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public long getTotalPage() {
        return totalPage;
    }

    public void setTotalPage(long totalPage) {
        this.totalPage = totalPage;
    }

    public List<T> getDataList() {
        return dataList;
    }

    public void setDataList(List<T> dataList) {
        this.dataList = dataList;
    }

}
