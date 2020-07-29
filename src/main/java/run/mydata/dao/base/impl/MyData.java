package run.mydata.dao.base.impl;


import run.mydata.manager.IConnectionManager;

import javax.annotation.Resource;

/**
 * MyData
 *
 * @param <POJO> .
 * @author Liu Tao
 */
public class MyData<POJO> extends MyDataSupport<POJO> {

    @Resource
    private IConnectionManager connectionManager;

    public MyData() {
    }

    public MyData(IConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    public void setConnectionManager(IConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    public IConnectionManager getConnectionManager() {
        return connectionManager;
    }
}
