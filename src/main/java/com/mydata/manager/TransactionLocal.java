package com.mydata.manager;

public class TransactionLocal {
    /**
     * 是否开启了事务
     */
    private Boolean begin = false;
    /**
     * 是否只读事务
     */
    private Boolean readOnly = false;

    public Boolean getBegin() {
        return begin;
    }

    public void setBegin(Boolean begin) {
        this.begin = begin;
    }

    public Boolean getReadOnly() {
        return readOnly;
    }

    public TransactionLocal(Boolean begin, Boolean readOnly) {
        super();
        this.begin = begin;
        this.readOnly = readOnly;
    }

    public void setReadOnly(Boolean readOnly) {
        this.readOnly = readOnly;
    }

    public TransactionLocal() {
        super();
    }

}
