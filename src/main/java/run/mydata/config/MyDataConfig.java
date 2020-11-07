package run.mydata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import run.mydata.manager.ConnectionManager;
import run.mydata.manager.IConnectionManager;
import run.mydata.manager.TransManager;
import run.mydata.manager.TransManagerDefault;

import javax.sql.DataSource;
import java.util.Arrays;

/**
 * 自动配置
 *
 * @author Liu Tao
 */
public class MyDataConfig {

    @Bean
    @ConfigurationProperties(prefix = "mydata", ignoreUnknownFields = true)
    public MyDataProperties myDataProperties() {
        MyDataProperties myDataProperties = new MyDataProperties();
        return myDataProperties;
    }

    @Bean
    public IConnectionManager connectionManager(DataSource dataSource, MyDataProperties properties) {
        ConnectionManager connectionManager = new ConnectionManager();
        connectionManager.setDdl(properties.getDdl());
        connectionManager.setShowSql(properties.getShowSql());
        connectionManager.setConnectStr(properties.getConnectCharset() == null || properties.getConnectCharset().trim().length() == 0 ? "set names utf8mb4" : "set names "+properties.getConnectCharset());
        connectionManager.setDb(properties.getDb());

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
