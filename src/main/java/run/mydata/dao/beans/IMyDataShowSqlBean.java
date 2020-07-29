package run.mydata.dao.beans;

import java.sql.Statement;

/***
 * 自定义控制sql日志的打印
 *
 * @author Liu Tao
 */
public interface IMyDataShowSqlBean {

    public String showSqlForLog(Statement statement, String sql);

}
