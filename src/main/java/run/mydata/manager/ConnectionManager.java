package run.mydata.manager;

import run.mydata.annotation.ColumnComment;
import run.mydata.annotation.ColumnMoreLength;
import run.mydata.annotation.ColumnRule;
import run.mydata.annotation.MyIndex;
import run.mydata.dao.beans.IMyDataShowSqlBean;
import run.mydata.dao.beans.MyDataShowSqlBeanDefault;
import run.mydata.helper.PropInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.*;
import javax.sql.DataSource;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Connection Manager
 *
 * @author Liu Tao
 */
public final class ConnectionManager implements IConnectionManager {
    private static Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    //enable show sql
    private boolean showSql = false;
    //enable ddl
    private boolean ddl = true;
    //init column string
    private String connectStr = "set  names  utf8";
    //db name
    private String db;//MySQL Oracle
    //primary db
    private DataSource dataSource;
    //read dbs
    private List<DataSource> readDataSources;
    //connection name
    private String connectionManagerName;
    //show sql ben
    private IMyDataShowSqlBean myDataShowSqlBean;

    //domain class mapping table info properties info
    //key: tableClass value : (key: tableName, value: properties)
    private volatile static ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, LinkedHashSet<PropInfo>>> ENTITY_CACHED = new ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, LinkedHashSet<PropInfo>>>();


    private static ThreadLocal<Map<ConnectionManager,TransactionLocal>> transactions = new ThreadLocal(){};
    private ThreadLocal<Map<ConnectionManager,TransactionLocal>> getTransactions(){
        Map<ConnectionManager, TransactionLocal> ctMap = transactions.get();
        if (ctMap == null) {
            ctMap = new HashMap<>();
            ctMap.put(this, new TransactionLocal(false,false,this));
            transactions.set(ctMap);
        }else {
            TransactionLocal tl = ctMap.get(this);
            if (tl == null) {
                ctMap.put(this, new TransactionLocal(false,false,this));
                transactions.set(ctMap);
            }
        }
        return transactions;
    }


    //primary db
    private static ThreadLocal<Map<ConnectionManager,Map<DataSource, Connection>>> connections = new ThreadLocal() {};
    private ThreadLocal<Map<ConnectionManager,Map<DataSource, Connection>>> getConnections(){
        Map<ConnectionManager, Map<DataSource, Connection>> cdcMap = connections.get();
        if (cdcMap == null) {
            cdcMap = new HashMap<>();
            Map<DataSource, Connection> dcMap = new HashMap<>();
            cdcMap.put(this,dcMap);
            connections.set(cdcMap);
        }else{
            Map<DataSource, Connection> dcMap = cdcMap.get(this);
            if (dcMap == null) {
                dcMap = new HashMap<>();
                cdcMap.put(this,dcMap);
                connections.set(cdcMap);
            }
        }
        return connections;
    }


    //read db
    private final static ThreadLocal<Map<DataSource, Connection>> readOnlyConnections = new ThreadLocal<Map<DataSource, Connection>>() {
        @Override
        protected Map<DataSource, Connection> initialValue() {
            return new HashMap<DataSource, Connection>(3);
        }
    };

    public ConnectionManager() {
    }

    public ConnectionManager(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void setReadDataSources(List<DataSource> readDataSources) {
        this.readDataSources = readDataSources;
    }

    public String getConnectStr() {
        return connectStr;
    }

    public void setConnectStr(String connectStr) {
        this.connectStr = connectStr;
    }

    public void setShowSql(boolean showSql) {
        this.showSql = showSql;
    }

    public void setDdl(boolean ddl) {
        this.ddl = ddl;
    }

    private void setReadOnlyConnection(Connection conn) {
        try {
            log.debug("slave connection open  {}", Thread.currentThread().getName());
            readOnlyConnections.get().put(dataSource, conn);
            initConnect(readOnlyConnections.get().get(dataSource));
        } catch (SQLException e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        }
    }

    private void initConnect(Connection conn) throws SQLException {
        if (connectStr != null && connectStr.length() > 0) {
            conn.prepareStatement(connectStr).execute();
        }
    }

    private void closeReadconnection() {
        Connection connection = readOnlyConnections.get().get(dataSource);
        if (connection != null) {
            try {
                log.debug("slave connection close  {}", Thread.currentThread().getName());
                readOnlyConnections.remove();
                connection.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * get domains field column properties , key is @Table name, value is every filed properties
     * current class may be has more than 1 table for split , may be minimum is 1, then get the first table data info,this is domain column field properties, it's just how it is
     * @param domainClass .
     * @return .
     */
    public static Map<String, LinkedHashSet<PropInfo>> getTbinfo(Class<?> domainClass) {
        ConcurrentHashMap<String, LinkedHashSet<PropInfo>> tableNamePropsMap = ENTITY_CACHED.get(domainClass);
        if (tableNamePropsMap == null) {
            //table
            Table tableAnnotation = domainClass.getAnnotation(Table.class);
            LinkedHashSet<PropInfo> tableColumnInfos = new LinkedHashSet<PropInfo>();
            String tableName = null;
            if (tableAnnotation != null) {
                tableName = tableAnnotation.name().trim();
                if ("".equals(tableName)) {
                    tableName = domainClass.getSimpleName();
                }
            }
            //field column
            Field[] fields = domainClass.getDeclaredFields();
            int versionNum = 0;
            for (Field field : fields) {
                if (
                        !field.isAnnotationPresent(Transient.class)//非Transient
                            &&
                        !Modifier.isTransient(field.getModifiers())//非Transient
                            &&
                        !Modifier.isFinal(field.getModifiers())//非Final
                            &&
                        !Modifier.isStatic(field.getModifiers())//非Static
                ) {
                    PropInfo info = new PropInfo();
                    info.setPname(field.getName());
                    info.setType(field.getType());

                    if (field.isAnnotationPresent(ColumnComment.class)) {
                        ColumnComment fieldColumnCommentAnnotation = field.getAnnotation(ColumnComment.class);
                        if (fieldColumnCommentAnnotation.value() != null && !"".equals(fieldColumnCommentAnnotation.value())) {
                            info.setComment(fieldColumnCommentAnnotation.value());
                        }
                    }

                    if (field.isAnnotationPresent(Column.class)) {
                        Column fieldColumnAnnotation = field.getAnnotation(Column.class);
                        String columnName = fieldColumnAnnotation.name();
                        if (columnName != null && !columnName.equals("")) {
                            info.setCname(columnName);
                        } else {
                            info.setCname(field.getName());
                        }
                        if ( field.getType() == String.class || field.getType().isEnum() ) {
                            info.setLength(fieldColumnAnnotation.length());
                        }else {
                            if (fieldColumnAnnotation.length()!=255) {
                                info.setLength(fieldColumnAnnotation.length());
                            }
                        }
                        if (!fieldColumnAnnotation.nullable()) {
                            info.setIsNotNull(true);
                        }
                        if (fieldColumnAnnotation.unique()) {
                            info.setIsUnique(true);
                        }
                        if (info.getComment() == null && fieldColumnAnnotation.columnDefinition()!=null && !"".equals(fieldColumnAnnotation.columnDefinition())) {
                            info.setComment(fieldColumnAnnotation.columnDefinition());
                        }
                    } else {
                        info.setCname(field.getName());
                    }

                    if (field.isAnnotationPresent(Id.class)) {
                        info.setIsPrimarykey(true);
                        if (field.isAnnotationPresent(GeneratedValue.class)) {
                            GeneratedValue generatedValue = field.getAnnotation(GeneratedValue.class);
                            info.setGeneratorValueAnnoStrategyVal(generatedValue.strategy());
                            info.setGeneratorValueAnnoGeneratorVal(generatedValue.generator());
                        }
                    }
                    if (field.isAnnotationPresent(ColumnRule.class)) {
                        info.setColumnRule(field.getAnnotation(ColumnRule.class));
                    }
                    if (field.isAnnotationPresent(MyIndex.class) && !info.getIsUnique()) {
                        info.setIndex(field.getAnnotation(MyIndex.class));
                    }
                    if (field.isAnnotationPresent(Lob.class)) {
                        info.setIsLob(true);
                    }
                    if (field.getType().isEnum()) {
                        if (field.isAnnotationPresent(Enumerated.class)) {
                            info.setEnumType(field.getAnnotation(Enumerated.class).value());
                        } else {
                            info.setEnumType(EnumType.ORDINAL);
                        }
                    }
                    if (field.isAnnotationPresent(Version.class)) {
                        info.setVersion(true);
                        versionNum++;
                        if (!field.getType().equals(Long.class)){
                            String error = domainClass.getName()+" @Version Type Must Be Long ";
                            throw new IllegalArgumentException(error);
                        }
                    }
                    if (field.isAnnotationPresent(ColumnMoreLength.class)) {
                        ColumnMoreLength columnMoreLength = field.getAnnotation(ColumnMoreLength.class);
                        info.setMoreLength(columnMoreLength.length());
                    }
                    tableColumnInfos.add(info);
                }
            }
            if (versionNum > 1) {
                String error = domainClass.getName()+" @Version Type Has To Be Only 1 But Current Has "+versionNum;
                throw new IllegalArgumentException(error);
            }
            tableNamePropsMap = new ConcurrentHashMap<String, LinkedHashSet<PropInfo>>();
            tableNamePropsMap.put(tableName, tableColumnInfos);
            ENTITY_CACHED.put(domainClass, tableNamePropsMap);
        }
        return tableNamePropsMap;
    }


    /**
     * 1 get connection (primary db)
     * @return .
     */
    @Override
    public Connection getConnection() {
        return getWriteConnection();
    }

    /**
     * 2 get connection from is readOnly param
     * @param readOnly .
     * @return .
     */
    @Override
    public Connection getConnection(boolean readOnly) {
        if (readOnly) {
            return getReadConnection();
        }
        return getWriteConnection();
    }


    /**
     * 3 get primary db write connection
     * @return .
     */
    @Override
    public Connection getWriteConnection() {
        try {
            Map<DataSource, Connection> dcMap = getConnections().get().get(this);
            if (dcMap==null){
                log.debug("master connection open  {}", Thread.currentThread().getName());
                getConnections().get().get(this).put(dataSource, dataSource.getConnection());
                initConnect(getConnections().get().get(this).get(dataSource));
                return getConnections().get().get(this).get(dataSource);
            }else {
                Connection conn = dcMap.get(dataSource);
                if (conn == null) {
                    log.debug("master connection open  {}", Thread.currentThread().getName());
                    getConnections().get().get(this).put(dataSource, dataSource.getConnection());
                    initConnect(getConnections().get().get(this).get(dataSource));
                    return getConnections().get().get(this).get(dataSource);
                } else {
                    return conn;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        }
    }

    /**
     * 4 get read db connection
     * @return .
     */
    @Override
    public Connection getReadConnection() {
        try {
            Connection conn = readOnlyConnections.get().get(dataSource);
            if (conn == null) {
                if (readDataSources != null && readDataSources.size() > 0) {
                    setReadOnlyConnection(readDataSources.get(ThreadLocalRandom.current().nextInt(readDataSources.size())).getConnection());
                    return readOnlyConnections.get().get(dataSource);
                } else {
                    if (isTransReadOnly()) {
                        setReadOnlyConnection(dataSource.getConnection());
                        return readOnlyConnections.get().get(dataSource);
                    } else {
                        return getWriteConnection();
                    }
                }
            } else {
                return conn;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        }
    }

    /**
     * 5 close connection
     */
    @Override
    public void closeConnection() {
        if ( !isTransactioning() ) {
            Map<ConnectionManager, Map<DataSource, Connection>> mdcMap = connections.get();
            if (mdcMap != null && mdcMap.size() > 0) {
                Map<DataSource, Connection> dcMap = mdcMap.get(this);
                if (dcMap != null && dcMap.size() > 0) {
                    Connection connection = dcMap.get(dataSource);
                    if (connection != null) {
                        try {
                            log.debug("master connection close  {}", Thread.currentThread().getName());
                            connection.close();

                            dcMap.remove(dataSource);
                            mdcMap.remove(this);
                            if (mdcMap.size() == 0) {
                                connections.remove();
                            }
                        } catch (Exception e) {
                            connections.remove();
                            e.printStackTrace();
                        }
                    }else {
                        connections.remove();
                    }
                }else {
                    connections.remove();
                }
            } else {
                connections.remove();
            }
        }
        closeReadconnection();
    }

    /**
     * 6 begin transaction
     * @return false : is already begin befor .
     */
    @Override
    public Boolean beginTransaction(boolean readOnly) {
        if ( !isTransactioning() ) {
            try {
                getWriteConnection().setAutoCommit(false);
                TransactionLocal transactionLocal = getTransactions().get().get(this);
                transactionLocal.setBegin(true);
                transactionLocal.setReadOnly(readOnly);
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                throw new IllegalStateException(e);
            }
        } else {
            return false;
        }
    }

    /**
     * 7 check transaction is begined
     * @return .
     */
    @Override
    public boolean isTransactioning() {
        TransactionLocal transactionLocal = getTransactions().get().get(this);
        return transactionLocal != null && transactionLocal.getBegin() && this.equals(transactionLocal.getConnectionManager());
    }

    /**
     * 8 check transaction is only read transaction
     * @return .
     */
    @Override
    public boolean isTransReadOnly() {
        TransactionLocal transactionLocal = getTransactions().get().get(this);
        return transactionLocal != null && transactionLocal.getReadOnly();
    }

    /**
     * 9 commit transaction
     */
    @Override
    public void commitTransaction() {
        Map<ConnectionManager, Map<DataSource, Connection>> mdcMap = getConnections().get();
        Connection connection = mdcMap.get(this).get(dataSource);
        if (connection != null) {
            if ( isTransactioning() ) {
                try {
                    log.debug("master connection close  {}", Thread.currentThread().getName());
                    connection.commit();
                    connection.setAutoCommit(true);
                    connection.close();

                    mdcMap.remove(this);
                    if (mdcMap.size() == 0) {
                        connections.remove();
                    }
                    Map<ConnectionManager, TransactionLocal> ctMap = getTransactions().get();
                    ctMap.remove(this);
                    if (ctMap.size() == 0) {
                        transactions.remove();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    throw new IllegalStateException(e);
                }
            } else {
                closeConnection();
            }
        }
    }

    /**
     * 10 rollback transaction
     */
    @Override
    public void rollbackTransaction() {
        Map<ConnectionManager, Map<DataSource, Connection>> mdcMap = getConnections().get();
        Map<DataSource, Connection> dcMap = mdcMap.get(this);
        if (dcMap != null) {
            Connection connection = dcMap.get(dataSource);
            if (connection != null) {
                if ( isTransactioning() ) {
                    try {
                        log.debug("master connection close  {}", Thread.currentThread().getName());
                        connection.rollback();
                        connection.setAutoCommit(true);
                        connection.close();

                        mdcMap.remove(this);
                        if (mdcMap.size() == 0) {
                            connections.remove();
                        }
                        Map<ConnectionManager, TransactionLocal> ctMap = getTransactions().get();
                        ctMap.remove(this);
                        if (ctMap.size() == 0) {
                            transactions.remove();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        throw new IllegalStateException(e);
                    }
                } else {
                    closeConnection();
                }
            }
        }
    }

    /**
     * 11 check is open ddl
     * @return .
     */
    @Override
    public boolean isDdl() {
        return ddl;
    }

    /**
     * 12 check is show sql
     * @return .
     */
    @Override
    public boolean isShowSql() {
        return showSql;
    }

    @Override
    public String getDb() {
        return db;
    }

    public void setDb(String db){
        this.db=db;
    }

    @Override
    public String getConnectionManagerName() {
        return connectionManagerName;
    }

    public void setConnectionManagerName(String connectionManagerName) {
        this.connectionManagerName = connectionManagerName;
    }

    @Override
    public void SetMyDataShowSqlBean(IMyDataShowSqlBean myDataShowSqlBean) {
        this.myDataShowSqlBean = myDataShowSqlBean;
    }

    @Override
    public IMyDataShowSqlBean getMyDataShowSqlBean() {
        if (this.myDataShowSqlBean == null) {
            this.myDataShowSqlBean = new MyDataShowSqlBeanDefault();
        }
        return this.myDataShowSqlBean;
    }
}
