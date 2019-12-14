package com.mydata.em;

/**
 * 条件类型
 *
 * @author Liu Tao
 */
public enum PmType {
    /**
     * 原生类型
     * <p>
     * createdate=moddate
     */
    OG,
    /**
     * 值类型
     * <p>
     * where amount !<100
     */
    VL,
    /**
     * 函数
     * <p>
     * having count(amount)>20
     */
    FUN
}
