package com.mydata.helper;

import java.io.Serializable;

/**
 * 页码信息
 *
 * @author Liu Tao
 */
public class PgIdx implements Serializable {

    private static final long serialVersionUID = -324298254087518023L;
    // 当前分页记录开始的页码
    private long startIndex = 1;
    // 当前分页记录结束的页码
    private long endIndex = 1;

    private PgIdx(long startIndex, long endIndex) {
        this.startIndex = startIndex;
        this.endIndex = endIndex;
    }

    public long getStartIndex() {
        return startIndex;
    }

    public void setStartIndex(long startIndex) {
        this.startIndex = startIndex;
    }

    public long getEndIndex() {
        return endIndex;
    }

    public PgIdx() {
        super();
    }

    public void setEndIndex(long endIndex) {
        this.endIndex = endIndex;
    }

    /**
     * 计算页码开始索引和结束索引
     *
     * @param indexCount 显示多少页
     * @param curPage    当前页
     * @param totalPage  总页数
     * @return
     */
    public static PgIdx getPageIndex(long indexCount, int curPage, long totalPage) {
        long startpage = curPage - (indexCount % 2 == 0 ? indexCount / 2 - 1 : indexCount / 2);

        long endpage = curPage + indexCount / 2;
        if (startpage < 1) {
            startpage = 1;
            if (totalPage >= indexCount)
                endpage = indexCount;
            else
                endpage = totalPage;
        }
        if (endpage > totalPage) {
            endpage = totalPage;
            if ((endpage - indexCount) > 0)
                startpage = endpage - indexCount + 1;
            else
                startpage = 1;
        }
        return new PgIdx(startpage, endpage);
    }
}
