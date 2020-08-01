package run.mydata.domain;


import run.mydata.annotation.ColumnComment;
import run.mydata.annotation.MyIndex;

import javax.persistence.*;
import java.math.BigDecimal;
import java.util.Date;

/***
 * 基础字段
 *
 * @author Liu Tao
 */
@Table
public class Domain extends MyDataOperationTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private Integer age;

    @Column(name = "is_delete")
    private Boolean isDelete;

    @Lob
    private String text;

    @ColumnComment("8,2")
    private BigDecimal amount;

    @Version
    private Long version;

    @MyIndex
    private String tag;

    @Temporal(TemporalType.TIMESTAMP)
    private Date createTime = new Date();

    @Temporal(TemporalType.TIMESTAMP)
    private Date updateTime;

    @Transient
    private String notColumn;
}
