package run.mydata.dao.beans;

import java.sql.Statement;

/***
 * 自定义控制sql日志的打印
 *
 * @author Liu Tao
 */
@FunctionalInterface
public interface IMyDataShowSqlBean {

    /**
     * custom sql for log
     * @param statement statement
     * @param sql sql
     * @return sql for log
     */
    public String showSqlForLog(Statement statement, String sql);

}
