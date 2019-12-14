package com.mydata.manager;

import java.sql.Connection;

/**
 * 数据库连接管理
 *
 * @author Liu Tao
 */
public interface IConnectionManager {

    /**
     * 1 获取连接(主库)
     * @return
     */
    Connection getConnection();

    /**
     * 2 获取连接
     *
     * @param readOnly 是否只读
     * @return
     */
    Connection getConnection(boolean readOnly);

    /**
     * 3 获取主库连接
     *
     * @return
     */
    Connection getWriteConnection();

    /**
     * 4 获取从库连接
     *
     * @return
     */
    Connection getReadConnection();

    /**
     * 5 关闭连接
     */
    void closeConnection();

    /**
     * 6 开启事务
     *
     * @return false 已经开启
     */
    Boolean beginTransaction(boolean readOnly);

    /**
     * 7 是否已经开启事务
     *
     * @return
     */
    boolean isTransactioning();

    /**
     * 8 是否只读事务
     *
     * @return
     */
    boolean isTransReadOnly();

    /**
     * 9 提交事务
     */
    void commitTransaction();

    /**
     * 10 回滚事务
     */
    void rollbackTransaction();

    /**
     * 11 是否自动创建表和索引
     *
     * @return
     */
    boolean isDdl();

    /**
     * 12 是否控制台打印SQL
     *
     * @return
     */
    boolean isShowSql();

}
