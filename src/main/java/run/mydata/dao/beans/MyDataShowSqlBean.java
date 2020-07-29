package run.mydata.dao.beans;

import java.sql.Statement;

public class MyDataShowSqlBean implements IMyDataShowSqlBean {

    @Override
    public String showSqlForLog(Statement statement, String inanitionSql) {
        String hopeSql = statement.toString();
        if (hopeSql.startsWith("HikariProxyPreparedStatement")) {
            return hopeSql;
        }
        return inanitionSql;
    }
}
