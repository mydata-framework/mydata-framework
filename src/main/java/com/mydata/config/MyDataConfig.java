package com.mydata.config;

import com.mydata.manager.ConnectionManager;
import com.mydata.manager.IConnectionManager;
import com.mydata.manager.TransManager;
import com.mydata.manager.TransManagerDefault;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import javax.sql.DataSource;
import java.util.Arrays;

public class MyDataConfig {

    @Bean @ConfigurationProperties(prefix = "mydata", ignoreUnknownFields = true)
    public MyDataProperties myDataProperties(){
        MyDataProperties myDataProperties = new MyDataProperties();
        return myDataProperties;
    }

    @Bean
    public IConnectionManager connectionManager(DataSource dataSource,MyDataProperties properties){
        ConnectionManager connectionManager = new ConnectionManager();
        connectionManager.setDdl(properties.getDdl());
        connectionManager.setShowSql(properties.getShowSql());
        connectionManager.setConnectStr("set  names  utf8");

        connectionManager.setDataSource(dataSource);
        connectionManager.setReadDataSources(Arrays.asList(dataSource));
        return connectionManager;
    }

    @Bean
    public TransManagerDefault transManagerDefault(IConnectionManager connectionManager) {
        TransManagerDefault trans = new TransManagerDefault();
        trans.setConnectionManager(connectionManager);
        return trans;
    }

    @Bean
    public TransManager transManager(IConnectionManager connectionManager) {
        TransManager trans = new TransManager();
        trans.setConnectionManager(connectionManager);
        return trans;
    }

}
