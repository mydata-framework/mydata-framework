package com.mydata.config;

import com.mydata.manager.ConnectionManager;
import com.mydata.manager.IConnectionManager;
import com.mydata.manager.TransManager;
import com.mydata.manager.TransManagerDefault;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import javax.sql.DataSource;
import java.util.Arrays;

public class MydataConfig {

    @Bean
    @ConfigurationProperties("mydata")
    public IConnectionManager connectionManager(DataSource dataSource){
        ConnectionManager connectionManager = new ConnectionManager();
        connectionManager.setDdl(true);
        connectionManager.setShowSql(false);
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
