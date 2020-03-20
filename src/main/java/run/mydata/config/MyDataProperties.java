package run.mydata.config;

/**
 * 配置properties
 *
 * @author Liu Tao
 */
public class MyDataProperties {

    //enable show sql
    private Boolean showSql = false;

    //enable ddl
    private Boolean ddl = true;

    public Boolean getShowSql() {
        return showSql;
    }

    public void setShowSql(Boolean showSql) {
        this.showSql = showSql;
    }

    public Boolean getDdl() {
        return ddl;
    }

    public void setDdl(Boolean ddl) {
        this.ddl = ddl;
    }
}
