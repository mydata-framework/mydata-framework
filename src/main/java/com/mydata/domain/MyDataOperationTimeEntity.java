package com.mydata.domain;

import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import java.util.Date;

public class MyDataOperationTimeEntity {

    @Temporal(TemporalType.TIMESTAMP)
    private Date createTime = new Date();

    @Temporal(TemporalType.TIMESTAMP)
    private Date updateTime;

}
