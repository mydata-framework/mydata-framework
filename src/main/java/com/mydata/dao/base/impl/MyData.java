package com.mydata.dao.base.impl;


import com.mydata.manager.IConnectionManager;
import javax.annotation.Resource;

public class MyData<POJO> extends MyDataSupport<POJO> {

    private @Resource IConnectionManager connectionManager;

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
