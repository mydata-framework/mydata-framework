package run.mydata.manager;

import java.sql.Connection;

/**
 * Connection Manager Intergace
 *
 * @author Liu Tao
 */
public interface IConnectionManager {

    /**
     * 1 1 get connection (primary db)
     * @return .
     */
    Connection getConnection();

    /**
     * 2 get connection from is readOnly param
     * @param readOnly .
     * @return .
     */
    Connection getConnection(boolean readOnly);

    /**
     * 3 get primary db write connection
     * @return .
     */
    Connection getWriteConnection();

    /**
     * 4 get read db connection
     * @return .
     */
    Connection getReadConnection();

    /**
     * 5 close connection
     */
    void closeConnection();

    /**
     * 6 begin transaction
     * @param readOnly .
     * @return false : is already begin befor
     */
    Boolean beginTransaction(boolean readOnly);

    /**
     * 7 check transaction is begined
     * @return .
     */
    boolean isTransactioning();

    /**
     * 8 check transaction is only read transaction
     * @return .
     */
    boolean isTransReadOnly();

    /**
     * 9 commit transaction
     */
    void commitTransaction();

    /**
     * 10 rollback transaction
     */
    void rollbackTransaction();

    /**
     * 11 check is open ddl
     * @return .
     */
    boolean isDdl();

    /**
     * 12 check is show sql
     * @return .
     */
    boolean isShowSql();

}
