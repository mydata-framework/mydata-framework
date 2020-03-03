package com.mydata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
