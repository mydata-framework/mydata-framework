package run.mydata.domain;

import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import java.util.Date;

/***
 * 基础时间字段
 *
 * @author Liu Tao
 */
public class MyDataOperationTimeEntity {

    @Temporal(TemporalType.TIMESTAMP)
    private Date createTime = new Date();

    @Temporal(TemporalType.TIMESTAMP)
    private Date updateTime;

}
