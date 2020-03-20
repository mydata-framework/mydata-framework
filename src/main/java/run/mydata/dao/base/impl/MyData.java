package run.mydata.dao.base.impl;


import run.mydata.manager.IConnectionManager;

import javax.annotation.Resource;

/**
 * MyData
 * @author Liu Tao
 * @param <POJO>
 */
public class MyData<POJO> extends MyDataSupport<POJO> {

    private @Resource
    IConnectionManager connectionManager;

    public MyData() {
    }

    public MyData(IConnectionManager connectionManager) {
        this.connectionManager=connectionManager;
    }

    public void setConnectionManager(IConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    public IConnectionManager getConnectionManager() {
        return connectionManager;
    }
}
