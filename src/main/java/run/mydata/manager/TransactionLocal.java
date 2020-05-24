package run.mydata.manager;

/**
 * TransactionLocal
 *
 * @author Liu Tao
 */
public class TransactionLocal {
    /**
     * 是否开启了事务
     */
    private Boolean begin = false;
    /**
     * 是否只读事务
     */
    private Boolean readOnly = false;

    private ConnectionManager connectionManager;

    public Boolean getBegin() {
        return begin;
    }

    public void setBegin(Boolean begin) {
        this.begin = begin;
    }

    public Boolean getReadOnly() {
        return readOnly;
    }

    public TransactionLocal(Boolean begin, Boolean readOnly,ConnectionManager connectionManager) {
        super();
        this.begin = begin;
        this.readOnly = readOnly;
        this.connectionManager = connectionManager;
    }

    public void setReadOnly(Boolean readOnly) {
        this.readOnly = readOnly;
    }

    public TransactionLocal() {
        super();
    }

    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    public void setConnectionManager(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }
}
