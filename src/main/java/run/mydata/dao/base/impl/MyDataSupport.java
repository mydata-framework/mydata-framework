package run.mydata.dao.base.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import run.mydata.annotation.ColumnRule;
import run.mydata.annotation.ColumnType;
import run.mydata.annotation.MyIndexFullText;
import run.mydata.annotation.Other;
import run.mydata.dao.base.IMyData;
import run.mydata.em.*;
import run.mydata.exception.ObjectOptimisticLockingFailureException;
import run.mydata.helper.OrderBy;
import run.mydata.helper.*;
import run.mydata.manager.ConnectionManager;
import run.mydata.manager.IConnectionManager;

import javax.annotation.PostConstruct;
import javax.persistence.*;
import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * MyDataSupport
 *
 * @param <POJO> .
 * @author Liu Tao
 */
public abstract class MyDataSupport<POJO> implements IMyData<POJO> {
    private static Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    //current operational domain class
    private Class<POJO> domainClazz;
    //current domain class mapper table
    private String firstTableName;
    //current connection database type name , ex MySQL Oracle
    private String dataBaseTypeName;
    //is showSql
    private boolean isShowSql;
    //is ddl
    private boolean isGenerateDdl;

    //is has table comment
    private boolean hasTableComment;
    //if has table comment , this is table comment content
    private String tableComment;

    //is has table engine
    private boolean hasTableEngine;
    //if has table engine , this is table engine content
    private String tableEngine;

    //is has table charset
    private boolean hasTableCharset;
    //if has table charset , this is table charset content
    private String tableCharset;

    //is use global table id
    private boolean isUseGlobalTableId=false;

    //connection Manager
    public abstract IConnectionManager getConnectionManager();

    //one table split max count
    private volatile int maxTableCount = 1024;
    private static final String ALTER_TABLE_MODIFY_COLUMN = "ALTER TABLE %s MODIFY %s %s";
    private static final String INDEX_SUBFIX = "_idx";
    private static final String FULLTEXT_INDEX_SUBFIX="_fulltext_idx";
    private static final String ALTER_TABLE_S_ADD_S = "ALTER TABLE %s ADD (%s)";
    private static final String SEQUENCE_QUERY = "SELECT sequence_name FROM user_sequences WHERE sequence_name=?";
    private static final int MAX_IDLE_TABLE_COUNT = 8;
    private static final ForkJoinPool NEW_FIXED_THREAD_POOL = new ForkJoinPool(Integer.min(Runtime.getRuntime().availableProcessors() * 4, 32));
    //domain class key , already split table names value, map
    private volatile static ConcurrentHashMap<Class<?>, ConcurrentSkipListSet<String>> DOMAINCLASS_TABLES_MAP = new ConcurrentHashMap<Class<?>, ConcurrentSkipListSet<String>>();


    @PostConstruct
    public void init() {
        try {
            //初始化过程
            this.domainClazz = MyDataHelper.getDomainClassByDaoClass(this.getClass());//获取domain的class类型
            this.firstTableName = MyDataHelper.getFirstTableName(this.domainClazz); //获取第一种表的名称, 因为默认mydata是用来自动分表的,如果单表则就是表名
            this.dataBaseTypeName = MyDataHelper.getDataBaseTypeName(this.getConnectionManager());//数据库类型
            this.tableComment = MyDataHelper.getTableColumn(this.domainClazz);//表备注
            this.hasTableComment = this.tableComment == null ? false : true;//是否设置了表备注
            this.tableEngine = MyDataHelper.getTableEngine(this.domainClazz);//表引擎
            this.hasTableEngine = this.tableEngine == null ? false : true;//是否设置了表引擎
            this.tableCharset = MyDataHelper.getTableCharset(this.domainClazz);//表字符集
            this.hasTableCharset = this.tableCharset == null ? false : true;//是否设置了表字符集
            this.isShowSql = this.getConnectionManager().isShowSql();//是否显示sql
            this.isGenerateDdl = this.getConnectionManager().isDdl();//是否ddl
            final Set<PropInfo> pps = getPropInfos();//表字段对象集
            if (this.isGenerateDdl) {//如果设置ddl为true,则开始做建表操作
                this.createFirstTable(pps);//建表
            }
            this.setSqlType(pps);//设置实体字段与表字段sqlTypes的对应关系
        } catch (Exception e) {
            e.printStackTrace();
            log.info("[ MyData init error ]");
        }
    }

    //get domain column field properties
    protected Set<PropInfo> getPropInfos() {
        //current class may be has more than 1 table for split , may be minimum is 1, then get the first table data info,this is domain column field properties, it's just how it is
        Map<String, LinkedHashSet<PropInfo>> tbinfo = ConnectionManager.getTbinfo(this.domainClazz);
        LinkedHashSet<PropInfo> propInfos = tbinfo.entrySet().iterator().next().getValue();
        return propInfos;
    }

    /**
     * 建表
     * @param propInfos 字段信息集
     */
    private void createFirstTable(Set<PropInfo> propInfos) {
        try {
            //表名称
            String tableName = this.firstTableName;
            //获取连接
            Connection connection = this.getConnectionManager().getConnection();
            //多数据库支持, 建议使用Mysql, 因为Oracle测试不足, 不建议使用
            if ("Oracle".equalsIgnoreCase(this.dataBaseTypeName)) {
                if (propInfos.stream().anyMatch(p -> autoNextVal(p))) {
                    String seqName = getSequenceName(tableName);
                    boolean isSeqExists = sequenceExists(connection, seqName);
                    //if not exist , create
                    if (!isSeqExists) {
                        String createseqsql = String.format("%s %s", KSentences.CREATE_SEQUENCE, seqName);
                        PreparedStatement preparedStatement = connection.prepareStatement(createseqsql);
                        if (this.isShowSql) {
                            log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(preparedStatement, createseqsql));
                        }
                        preparedStatement.executeUpdate();
                    }

                }
            } else if ("MySQL".equalsIgnoreCase(this.dataBaseTypeName)) {
                //如果主键策略使用Table的处理,一般使用Table的目的是为了分表
                boolean isUseGlobalTableId = propInfos.stream().anyMatch(p -> GenerationType.TABLE.equals(p.getGeneratorValueAnnoStrategyVal()));
                if (isUseGlobalTableId) {
                    this.isUseGlobalTableId=isUseGlobalTableId;
                    //TUSER_SEQ_ID
                    String idTableName = getIdTableName(tableName);
                    //if global table id TABLE not exist , create that
                    if (!isTableExists(connection, idTableName)) {
                        //CREATE TABLE TUSER_SEQ_ID(SID BIGINT PRIMARY  KEY AUTO_INCREMENT)
                        //CREATE auto increment table for split tables id, the id is global
                        String createIdTableSql = String.format("%s %s(SID BIGINT PRIMARY  KEY AUTO_INCREMENT)", KSentences.CREATE_TABLE, idTableName);
                        PreparedStatement preparedStatement = connection.prepareStatement(createIdTableSql);
                        if (this.isShowSql) {
                            log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(preparedStatement, createIdTableSql));/*log.info(createIdTableSql);*/
                        }
                        preparedStatement.executeUpdate();
                        //if global id table has init value , use this value of init, this value length < 10
                        Optional<PropInfo> opst =
                                propInfos.stream().filter(
                                        id ->
                                                id.getGeneratorValueAnnoGeneratorVal() != null
                                                        &&
                                                        id.getGeneratorValueAnnoGeneratorVal().length() < 10
                                                        &&
                                                        Pattern.matches("\\d+", id.getGeneratorValueAnnoGeneratorVal().trim())
                                ).findFirst();
                        //if has split column field, to create global id table
                        if (opst.isPresent()) {
                            //INSERT INTO TUSER_SEQ_ID SID VALUES( 10 )
                            String insertIdTableSql = genInsertIdTableSql(idTableName, opst.get().getGeneratorValueAnnoGeneratorVal().trim());
                            PreparedStatement preparedStatement1 = connection.prepareStatement(insertIdTableSql);
                            if (this.isShowSql) {
                                log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(preparedStatement1, insertIdTableSql)); /*log.info(insertIdTableSql);*/
                            }
                            preparedStatement1.executeUpdate();
                        }
                    } else {
                        //clean old global table id
                        //DELETE FROM TUSER_SEQ_ID

                        //String cleanIdSql = String.format("%s %s", KSentences.DELETE_FROM, idTableName);
                        //PreparedStatement preparedStatement = connection.prepareStatement(cleanIdSql);
                        //if (this.isShowSql) {
                        //    log.info(preparedStatement.toString());/*log.info(cleanIdSql);*/
                        //}
                        //preparedStatement.executeUpdate();

                        //ResultSet resultSet = connection.prepareStatement(String.format("SELECT SID FROM %s ORDER BY SID DESC LIMIT 1", idTableName)).executeQuery();
                        //if (resultSet.next()) {
                        //    Long lastId = resultSet.getLong(1);
                        //    if (lastId != null) {
                        //        connection.prepareStatement(String.format("DELETE FROM %s WHERE SID < %d", idTableName, lastId)).executeUpdate();
                        //    }
                        //}
                    }
                }
            }
            //表不存在,创建表
            if (!isTableExists(connection, tableName)) {
                //创建表
                createTable(tableName);
                //get table split rule
                ColumnRule columnRule = getColumnRule();
                //if need split , to split
                if (columnRule != null && columnRule.ruleType().equals(RuleType.MOD)) {
                    int maxIdleTablecount = getMaxIdleTablecount(columnRule);
                    for (int i = 1; i < maxIdleTablecount; i++) {
                        String ctbname = getTableName(Long.valueOf(i), tableName);
                        executeCreate(tableName, ctbname);
                    }
                }
            } else {
                //表存在
                List<PropInfo> cnames = getDbProps(tableName, connection);
                if (cnames.size() < 1) {
                    cnames = getDbProps(tableName.toUpperCase(), connection);
                }
                List<PropInfo> ncns = new ArrayList<>();
                a:
                for (PropInfo pi : propInfos) {
                    for (PropInfo cn : cnames) {
                        if (cn.getColumnName().equalsIgnoreCase(pi.getColumnName())) {
                            if (cn.getSqlTypes() == Types.VARCHAR && cn.getLength().intValue() < pi.getLength()) {
                                changeToString(pi);
                            } else if ((cn.getSqlTypes() == Types.INTEGER || cn.getSqlTypes() == Types.BIGINT)
                                    && (pi.getFieldTypeClass() == Double.class || pi.getFieldTypeClass() == Float.class)) {
                                for (String t : getCurrentTableNames()) {
                                    String altertablesql = String.format(ALTER_TABLE_MODIFY_COLUMN, t, cn.getColumnName(), getPrecisionDatatype(pi.getFieldTypeClass().getSimpleName()));
                                    PreparedStatement preparedStatement = connection.prepareStatement(altertablesql);
                                    if (this.isShowSql) {
                                        log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(preparedStatement, altertablesql));/*log.info(altertablesql);*/
                                    }
                                    preparedStatement.executeUpdate();
                                }
                            } else if ((cn.getSqlTypes() == Types.INTEGER || cn.getSqlTypes() == Types.BIGINT)
                                    && pi.getFieldTypeClass() == String.class && !pi.getIsLob()) {
                                changeToString(pi);
                            } else if (cn.getSqlTypes() == Types.DATE && pi.getFieldTypeClass() == Date.class) {
                                Field fd = domainClazz.getDeclaredField(pi.getFieldName());
                                Temporal tp = fd.getAnnotation(Temporal.class);
                                if (tp != null && tp.value().equals(TemporalType.TIMESTAMP)) {
                                    for (String t : getCurrentTableNames()) {
                                        String altertablesql = String.format(ALTER_TABLE_MODIFY_COLUMN, t, cn.getColumnName(), getTimestampType());
                                        PreparedStatement preparedStatement = connection.prepareStatement(altertablesql);
                                        if (this.isShowSql) {
                                            log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(preparedStatement, altertablesql));/*log.info(altertablesql);*/
                                        }
                                        preparedStatement.executeUpdate();
                                    }
                                }
                            }
                            continue a;
                        }
                    }
                    ncns.add(pi);
                }

                if (ncns.size() > 0) {
                    StringBuilder sb = new StringBuilder();
                    Iterator<PropInfo> ite = ncns.iterator();
                    while (ite.hasNext()) {
                        PropInfo nextcn = ite.next();
                        sb.append(getColumnLine(nextcn));
                        if (ite.hasNext()) {
                            sb.append(KSentences.COMMA.getValue());
                        }
                    }
                    if (sb.length() > 0) {
                        String avl = sb.toString();
                        Set<String> currentTables = getCurrentTableNames();
                        connection = this.getConnectionManager().getConnection();
                        for (String t : currentTables) {
                            try {
                                String sql = String.format(ALTER_TABLE_S_ADD_S, t, avl);
                                PreparedStatement preparedStatement = connection.prepareStatement(sql);
                                if (this.isShowSql) {
                                    log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(preparedStatement, sql));/*log.info(sql);*/
                                }
                                preparedStatement.executeUpdate();
                            } catch (Throwable e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
            //crate index
            createIndex(tableName);
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } finally {
            closeConnection();
        }
    }

    private void closeConnection() {
        this.getConnectionManager().closeConnection();
    }

    //table is exist?
    private boolean isTableExists(Connection connection, String tableName) throws SQLException {
        ResultSet rs = getTableMeta(connection);
        //foreach all table names, watch the table name is exist
        while (rs.next()) {
            String rzn = rs.getString("TABLE_NAME");
            if (rzn.equalsIgnoreCase(tableName)) {
                return true;
            }
        }
        return false;
    }

    //setting domain field mapping column type
    private void setSqlType(Set<PropInfo> pps) {
        try {
            //获取连接
            Connection connection = this.getConnectionManager().getConnection();
            //获取表所有字段信息
            ResultSet crs = connection.getMetaData().getColumns(connection.getCatalog(), null, this.firstTableName, null);
            //遍历表字段
            while (crs.next()) {
                //匹配表字段
                for (PropInfo o : pps) {
                    if (crs.getString("COLUMN_NAME").equals(o.getColumnName())) {
                        o.setSqlTypes(crs.getInt("DATA_TYPE"));
                        break;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } finally {
            closeConnection();
        }
    }

    private String getIdTableName(String tableName) {
        //TUSER_SEQ_ID
        return String.format("%s_%s_%s", tableName, "SEQ", "ID").toUpperCase();
    }

    private String genInsertIdTableSql(String idTableName, String valueOfInsert) {
        //INSERT INTO ID_TABLE VALUES(%s)
        String insertIdtable = String.format("%s %s %s", KSentences.INSERT, idTableName, String.format(" VALUES(%s)", valueOfInsert));
        return insertIdtable;
    }

    /**
     * 创建表
     * @param tableName
     * @return
     * @throws SQLException
     */
    private boolean createTable(String tableName) throws SQLException {
        String csql = getCreateTableSql(tableName);
        if (csql != null && csql.trim().length() > 0) {
            //execute create table sql
            PreparedStatement preparedStatement = this.getConnectionManager().getConnection().prepareStatement(csql);
            if (this.isShowSql) {
                log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(preparedStatement, csql));/*log.info(csql);*/
            }
            preparedStatement.executeUpdate();
            //create table index
            createIndex(tableName);
            return true;
        } else {
            return false;
        }
    }

    //auto create table from domain field column properties info, default support MYSQL , if use other db , please override this method
    protected String getCreateTableSql(String tableName) {
        Set<PropInfo> props = getPropInfos();
        if (props.size() > 0) {
            //CREATE TABLE USER (
            StringBuilder ctbsb = new StringBuilder(KSentences.CREATE_TABLE.getValue());
            ctbsb.append(tableName).append("(");
            Iterator<PropInfo> psItrat = props.iterator();
            while (psItrat.hasNext()) {
                PropInfo p = psItrat.next();
                //ID BIGINT(20) PRIMARY KEY AUTO_INCREMTN COMMENT 'primaryKeyId',
                ctbsb.append(getColumnLine(p));
                if (psItrat.hasNext()) {
                    // ,
                    ctbsb.append(KSentences.COMMA.getValue());
                }
            }
            // )
            ctbsb.append(") ");
            if (this.hasTableEngine) {
                ctbsb.append(KSentences.ENGINE.getValue()).append(KSentences.EQ.getValue()).append(this.tableEngine).append(KSentences.SPACING.getValue());
            }
            if (this.hasTableCharset) {
                ctbsb.append(KSentences.CHARSET.getValue()).append(KSentences.EQ.getValue()).append(this.tableCharset).append(KSentences.SPACING.getValue());
            }
            if (this.hasTableComment) {
                ctbsb.append(KSentences.COMMENT.getValue()).append(" '").append(this.tableComment).append("' ");
            }
            return ctbsb.toString();
        }
        return "";
    }

    //`name` varchanr(255) null default null comment '',
    protected String getColumnLine(PropInfo p) {
        if ("MySQL".equalsIgnoreCase(this.dataBaseTypeName)) {
            return getMysqlColumnLine(p);
        } else if ("Oracle".equalsIgnoreCase(this.dataBaseTypeName)) {
            return getOracleColumnLine(p);
        } else {
            throw new IllegalArgumentException("not support database");
        }
    }

    private String getMysqlColumnLine(PropInfo p) {
        StringBuilder ctsb = new StringBuilder();
        //表名,例如`username`
        String columnName = p.getColumnName();
        ctsb.append("`").append(columnName).append("` ");
        //字段类型例如, varchar
        ColumnType columnType = p.getColumnType();
        String customColumnType = null;
        if (columnType != null) {
            customColumnType = columnType.value();
        }
        if (p.getFieldTypeClass() == Boolean.class) {
            ctsb.append(getColumnTypeSelect("BIT", customColumnType));
            if (p.getLength() != null && p.getLength() != 255) {
                ctsb.append("(").append(p.getLength()).append(")");
            }
        }
        else if (p.getFieldTypeClass() == Byte.class) {
            ctsb.append(getColumnTypeSelect("TINYINT", customColumnType));
            if (p.getLength() != null && p.getLength() != 255) {
                ctsb.append("(").append(p.getLength()).append(")");
            }
        }
        else if (p.getFieldTypeClass() == Short.class) {
            ctsb.append(getColumnTypeSelect("SMALLINT",customColumnType));
            if (p.getLength() != null && p.getLength() != 255) {
                ctsb.append("(").append(p.getLength()).append(")");
            }
        }
        else if (p.getFieldTypeClass() == Integer.class) {
            ctsb.append(getColumnTypeSelect("INT",customColumnType));
            if (p.getLength() != null && p.getLength() != 255) {
                ctsb.append("(").append(p.getLength()).append(")");
            }
        }
        else if (p.getFieldTypeClass() == Long.class) {
            ctsb.append(getColumnTypeSelect("BIGINT",customColumnType));
            if (p.getLength() != null && p.getLength() != 255) {
                ctsb.append("(").append(p.getLength()).append(")");
            }
        }
        else if (p.getFieldTypeClass() == Float.class) {
            ctsb.append(getColumnTypeSelect("FLOAT",customColumnType));
            if (p.getLength() != null && p.getLength() != 255) {
                ctsb.append("(").append(p.getLength()).append(")");
            }
        }
        else if (p.getFieldTypeClass() == Double.class) {
            ctsb.append(getColumnTypeSelect("DOUBLE",customColumnType));
            if (p.getLength() != null && p.getLength() != 255) {
                ctsb.append("(").append(p.getLength()).append(")");
            }
        }
        else if (p.getFieldTypeClass() == BigDecimal.class) {
            ctsb.append(getColumnTypeSelect("DECIMAL",customColumnType));
            if (p.getMoreLength() == null || p.getMoreLength().trim().length() == 0) {
                if (p.getLength() != null && p.getLength() != 255) {
                    ctsb.append("("+p.getLength()+",2)");
                } else {
                    ctsb.append("(10,2)");
                }
            } else {
                ctsb.append("(").append(p.getMoreLength()).append(")");
            }
        }
        else if (p.getFieldTypeClass() == Character.class){
            ctsb.append(getColumnTypeSelect("CHAR",customColumnType));
            if (p.getLength() != null && p.getLength() != 255) {
                ctsb.append("(").append(p.getLength()).append(")");
            }
        }
        else if (p.getFieldTypeClass() == byte[].class) {
            ctsb.append(getColumnTypeSelect("LONGBLOB",customColumnType));
            if (p.getLength() != null && p.getLength() != 255) {
                ctsb.append("(").append(p.getLength()).append(")");
            }
        }
        else if (p.getFieldTypeClass() == String.class) {
            if (p.getIsLob()) {
                ctsb.append("LONGTEXT");
                if (p.getLength() != null && p.getLength() != 255) {
                    ctsb.append("(").append(p.getLength()).append(")");
                }
            } else {
                ctsb.append(getColumnTypeSelect("VARCHAR",customColumnType));
                if (p.getLength() != null && p.getLength() != 255) {
                    ctsb.append("(").append(p.getLength()).append(")");
                } else {
                    ctsb.append("(255)");
                }
            }
        }
        else if (p.getFieldTypeClass() == Date.class) {
            try {
                Field fd = domainClazz.getDeclaredField(p.getFieldName());
                Temporal tp = fd.getAnnotation(Temporal.class);
                if (tp != null && tp.value().equals(TemporalType.TIMESTAMP)) {
                    ctsb.append(getTimestampType());
                } else if (tp != null && tp.value().equals(TemporalType.TIME)) {
                    ctsb.append("TIME");
                } else {
                    ctsb.append(getColumnTypeSelect("DATE",customColumnType));
                }
            } catch (NoSuchFieldException | SecurityException e) {
                e.printStackTrace();
                throw new IllegalStateException(e);
            }
        }
        else if (p.getFieldTypeClass() == Time.class) {
            ctsb.append("TIME");
        }
        else if (p.getFieldTypeClass() == Timestamp.class) {
            ctsb.append(getTimestampType());
        }
        else if (p.getFieldTypeClass().isEnum()) {
            try {
                Field fd = domainClazz.getDeclaredField(p.getFieldName());
                Enumerated enm = fd.getAnnotation(Enumerated.class);
                if (enm != null && enm.value() == EnumType.STRING) {
                    ctsb.append("VARCHAR");
                    if (p.getLength() != null) {
                        ctsb.append("(").append(p.getLength()).append(")");
                    }
                } else {
                    ctsb.append("INT");
                    if (p.getLength() != null && p.getLength() != 255) {
                        ctsb.append("(").append(p.getLength()).append(")");
                    }
                }
            } catch (NoSuchFieldException | SecurityException e) {
                e.printStackTrace();
                throw new IllegalStateException(e);
            }
        }
        //不支持的字段类型
        else {
            String type = p.getFieldTypeClass().toString();
            String domainClazzName = this.domainClazz.getName();
            String err = String.format("POJO field type not support mapping to column %s,about class %s; POJO字段属性类型并不支持 %s,关注类 %s;", type, domainClazzName, type, domainClazzName);
            log.error(err);
            throw new IllegalStateException(err);
        }

        //设置column长度
        //if (!(p.getFieldTypeClass() == String.class || p.getFieldTypeClass().isEnum()) && p.getLength() != null && p.getLength() != 255 && p.getFieldTypeClass() != BigDecimal.class) {
        //    ctsb.append("(").append(p.getLength()).append(")");
        //}

        //if (p.getFieldTypeClass() == BigDecimal.class) {
        //    if (p.getMoreLength() == null || p.getMoreLength().trim().length() == 0) {
        //        ctsb.append("(10,4)");
        //    } else {
        //        ctsb.append("(").append(p.getMoreLength()).append(")");
        //    }
        //}

        if (p.getIsPrimarykey()) {
            ctsb.append(" PRIMARY KEY ");
            if (GenerationType.IDENTITY.equals(p.getGeneratorValueAnnoStrategyVal())) {
                ctsb.append(" AUTO_INCREMENT ");
            } else {
                if (GenerationType.AUTO.equals(p.getGeneratorValueAnnoStrategyVal())) {
                    ctsb.append(" AUTO_INCREMENT ");
                }
            }
        } else {
            if (p.getIsNotNull()) {
                ctsb.append(" NOT NULL ");
            }
            if (p.getIsUnique()) {
                ctsb.append(" UNIQUE ");
            }
        }
        if (p.getComment() != null && !"".equals(p.getComment())) {
            ctsb.append(" ").append(KSentences.COMMENT.getValue()).append(" '").append(p.getComment()).append("' ");
        }
        String columnLineSql = ctsb.toString();
        return columnLineSql;
    }

    private String getTimestampType() {
        if ("MySQL".equalsIgnoreCase(this.dataBaseTypeName)) {
            return "DATETIME";
        } else if ("Oracle".equalsIgnoreCase(this.dataBaseTypeName)) {
            return "timestamp";
        } else {
            throw new IllegalArgumentException("not support database");
        }
    }

    protected String getOracleColumnLine(PropInfo p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.getColumnName()).append("   ");
        if (p.getFieldTypeClass() == Integer.class) {
            sb.append("number(10,0)");
        } else if (p.getFieldTypeClass() == Float.class) {
            sb.append("float");
        } else if (p.getFieldTypeClass() == Long.class) {
            sb.append("number(19,0)");
        } else if (p.getFieldTypeClass() == Double.class) {
            sb.append("float");
        } else if (p.getFieldTypeClass() == Boolean.class) {
            sb.append("number(1,0)");
        } else if (p.getFieldTypeClass() == Date.class) {
            try {
                Field fd = domainClazz.getDeclaredField(p.getFieldName());
                Temporal tp = fd.getAnnotation(Temporal.class);
                if (tp != null && tp.value().equals(TemporalType.TIMESTAMP)) {
                    sb.append("timestamp");
                } else {
                    sb.append("DATE");
                }
            } catch (NoSuchFieldException | SecurityException e) {
                e.printStackTrace();
                throw new IllegalStateException(e);
            }
        } else if (p.getFieldTypeClass() == Time.class) {
            sb.append("date");
        } else if (p.getFieldTypeClass() == Timestamp.class) {
            sb.append("timestamp");
        } else if (p.getFieldTypeClass() == String.class) {
            if (p.getIsLob()) {
                sb.append("clob");
            } else {
                sb.append("varchar2(").append(p.getLength()).append(" char)");
            }
        } else if (p.getFieldTypeClass() == byte[].class) {
            sb.append("blob");
        } else if (p.getFieldTypeClass().isEnum()) {
            try {
                Field fd = domainClazz.getDeclaredField(p.getFieldName());
                Enumerated enm = fd.getAnnotation(Enumerated.class);
                if (enm != null && enm.value() == EnumType.STRING) {
                    sb.append("varchar2(").append(p.getLength()).append(" char)");
                } else {
                    sb.append("number(10,0)");
                }
            } catch (NoSuchFieldException | SecurityException e) {
                e.printStackTrace();
                throw new IllegalStateException(e);
            }
        } else {
            String type = p.getFieldTypeClass().toString();
            String err = String.format("POJO field type not support mapping to column , %s ; POJO字段属性类型并不支持, %s ;", type, type);
            log.error(err);
            throw new IllegalStateException(err);
        }
        if (p.getIsPrimarykey()) {
            sb.append(" PRIMARY KEY ");
        } else {

            if (p.getIsNotNull()) {
                sb.append(" NOT NULL ");
            }
            if (p.getIsUnique()) {
                sb.append(" UNIQUE ");
            }

        }
        return sb.toString();
    }

    //get table max split num
    private int getMaxIdleTablecount(ColumnRule crn) {
        if (crn.ruleType().equals(RuleType.MOD)) {
            //if use mod for split , the max table num is 1024, setting min is right
            return Long.valueOf(Math.min(maxTableCount, crn.value())).intValue();
        } else {
            return MAX_IDLE_TABLE_COUNT;
        }
    }

    //create index
    private void createIndex(String tableName) throws SQLException {
        //foreach properties
        for (PropInfo prop : getPropInfos()) {

            //if current column has index
            if (indexIsNeedCreate(tableName, prop)) {
                //foreach current domains all tables , may be split table ,  has more than 1
                for (String tableNameOfTables : getCurrentTableNames()) {
                    //check current table item is has this index
                    if (indexIsNeedCreate(tableNameOfTables, prop)) {
                        //if not has , then create this index
                        try {
                            //current index name
                            //CREATE $UNIQUE$ INDEX $idcard_inx$ ON $User$($idcard(20),age$)"
                            String sql = String.format(
                                    "CREATE %s INDEX %s ON %s(%s) ",
                                    (prop.getIndex().unique() ? KSentences.UNIQUE : ""),
                                    getIndexName(prop),
                                    tableNameOfTables,
                                    getIndexColumns(prop)
                            );
                            //execute index inset
                            PreparedStatement preparedStatement = this.getConnectionManager().getConnection().prepareStatement(sql);
                            if (this.isShowSql) {
                                log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(preparedStatement, sql)); /*log.info(sql);*/
                            }
                            preparedStatement.executeUpdate();
                        } catch (Throwable e) {
                            e.printStackTrace();
                            log.error("create index error ", e);
                        }
                    }
                }
            }

            if (fullTextIndexIsNeedCreate(tableName, prop)) {
                //foreach current domains all tables , may be split table ,  has more than 1
                for (String tableNameOfTables : getCurrentTableNames()) {
                    //check current table item is has this index
                    if (fullTextIndexIsNeedCreate(tableNameOfTables, prop)) {
                        //if not has , then create this index
                        try {
                            //current index name
                            //CREATE FULLTEXT INDEX `xxx_fulltext_idx` ON `table_name` (`column_name`) WITH PARSER ngram
                            String sql = String.format(
                                    "CREATE FULLTEXT INDEX %s ON %s(%s) ",
                                    getFullTextIndexName(prop),
                                    tableNameOfTables,
                                    getFullTextIndexColumns(prop)
                            );
                            if (prop.getFullTextIndex().parser() != null) {
                                sql += prop.getFullTextIndex().parser();
                            }
                            //execute index inset
                            PreparedStatement preparedStatement = this.getConnectionManager().getConnection().prepareStatement(sql);
                            if (this.isShowSql) {
                                log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(preparedStatement, sql)); /*log.info(sql);*/
                            }
                            preparedStatement.executeUpdate();
                        } catch (Throwable e) {
                            e.printStackTrace();
                            log.error("create fulltext index error ", e);
                        }
                    }
                }
            }

        }
    }

    protected Set<String> getCurrentTableNames() {
        ConcurrentSkipListSet<String> tbns = DOMAINCLASS_TABLES_MAP.get(domainClazz);
        if (tbns == null) {
            synchronized (DOMAINCLASS_TABLES_MAP) {
                tbns = DOMAINCLASS_TABLES_MAP.get(domainClazz);
                if (tbns == null) {
                    tbns = reFreshTables();
                }
            }
        }
        return tbns;
    }

    protected TreeMap<Integer, String> getCurrentTableNamesTreeMap(){
        Set<String> currentTables = this.getCurrentTableNames();
        TreeMap<Integer, String> treeMap = new TreeMap<>();
        for (String tableName : currentTables) {
            if (tableName.equalsIgnoreCase(this.firstTableName)) {
                treeMap.put(0, tableName);
            } else {
                treeMap.put(Integer.valueOf(tableName.substring(tableName.lastIndexOf("_") + 1)), tableName);
            }
        }
        return treeMap;
    }

    protected List<String> getCurrentTableNamesOrderAsc(){
        TreeMap<Integer, String> treeMap = this.getCurrentTableNamesTreeMap();
        List<String> tableNameList = new ArrayList<>();
        for (Entry<Integer, String> en : treeMap.entrySet()) {
            String tableName = en.getValue();
            tableNameList.add(tableName);
        }
        return tableNameList;
    }

    protected List<String> getCurrentTableNamesOrderDesc(){
        List<String> currentTablesOrderAscList = this.getCurrentTableNamesOrderAsc();
        List<String> tableNameList = new ArrayList<>();
        for (int i = currentTablesOrderAscList.size()-1; i >=0 ; i--) {
            tableNameList.add(currentTablesOrderAscList.get(i));
        }
        return tableNameList;
    }

    private boolean indexIsNeedCreate(String tableName, PropInfo prop) throws SQLException {
        return indexIsNeedCreateByTableName(tableName, prop) && indexIsNeedCreateByTableName(tableName.toUpperCase(), prop);
    }

    private boolean fullTextIndexIsNeedCreate(String tableName, PropInfo prop) throws SQLException {
        return fullTextIndexIsNeedCreateByTableName(tableName, prop) && fullTextIndexIsNeedCreateByTableName(tableName.toUpperCase(), prop);
    }

    private boolean indexIsNeedCreateByTableName(String tbn, PropInfo prop) throws SQLException {
        //is has index flag
        if (prop.getIndex() != null) {
            //current index name
            String idxName = getIndexName(prop);

            //statistics current index data
            Map<String, String> idxNameKeyColumnNameValueMap = new HashMap<>(5);
            ResultSet saa = this.getConnectionManager()
                    .getConnection()
                    .getMetaData()
                    .getIndexInfo(null, null, tbn, prop.getIndex().unique(), false);

            while (saa.next()) {
                String dbIndexName = saa.getString("INDEX_NAME");
                if (dbIndexName != null) {
                    if (idxName.equalsIgnoreCase(dbIndexName)) {
                        return false;
                    }
                    String dbColumnName = saa.getString("COLUMN_NAME");
                    if (idxNameKeyColumnNameValueMap.get(dbIndexName) != null) {
                        idxNameKeyColumnNameValueMap.put(dbIndexName, idxNameKeyColumnNameValueMap.get(dbIndexName) + dbColumnName);
                    } else {
                        idxNameKeyColumnNameValueMap.put(dbIndexName, dbColumnName);
                    }
                    if (this.dataBaseTypeName.equalsIgnoreCase("Oracle") && dbIndexName.startsWith("SYS_") && dbColumnName.equalsIgnoreCase(prop.getColumnName())) {
                        return false;
                    }
                }
            }

            if ( !idxNameKeyColumnNameValueMap.containsKey(idxName) ) {
                return true;
            }
        }
        return false;
    }

    private boolean fullTextIndexIsNeedCreateByTableName(String tbn, PropInfo prop) throws SQLException {
        //is has index flag
        if (prop.getFullTextIndex() != null) {
            //current index name
            String fullTextIdxName = getFullTextIndexName(prop);

            //statistics current index data
            Map<String, String> idxNameKeyColumnNameValueMap = new HashMap<>(5);
            ResultSet saa = this.getConnectionManager()
                    .getConnection()
                    .getMetaData()
                    .getIndexInfo( null, null, tbn, false, false);

            while (saa.next()) {
                String dbIndexName = saa.getString("INDEX_NAME");
                if (dbIndexName != null) {
                    if (fullTextIdxName.equalsIgnoreCase(dbIndexName)) {
                        return false;
                    }
                    String dbColumnName = saa.getString("COLUMN_NAME");
                    if (idxNameKeyColumnNameValueMap.get(dbIndexName) != null) {
                        idxNameKeyColumnNameValueMap.put(dbIndexName, idxNameKeyColumnNameValueMap.get(dbIndexName) + dbColumnName);
                    } else {
                        idxNameKeyColumnNameValueMap.put(dbIndexName, dbColumnName);
                    }
                    if (this.dataBaseTypeName.equalsIgnoreCase("Oracle") && dbIndexName.startsWith("SYS_") && dbColumnName.equalsIgnoreCase(prop.getColumnName())) {
                        return false;
                    }
                }
            }
            if (!idxNameKeyColumnNameValueMap.containsKey(fullTextIdxName)) {
                return true;
            }
        }
        return false;
    }

    private String getIndexName(PropInfo p) {
        return this.getIndexNameOrigin(p) + INDEX_SUBFIX;
    }
    private String getIndexNameOrigin(PropInfo p) {
        if (p.getIndex().name().equals("")) {
            if (p.getIndex().otherPropName() != null && p.getIndex().otherPropName().length != 0) {
                String indexName = p.getColumnName();
                Other[] otherArr = p.getIndex().otherPropName();
                for (int i = 0; i < otherArr.length; i++) {
                    String pName = otherArr[i].name();
                    PropInfo pNameProp = getPropInfoByPName(pName);
                    String cname = pNameProp.getColumnName();
                    indexName = ( indexName+"_"+cname );
                }
                return indexName;
            } else {
                return p.getColumnName();
            }
        } else {
            return p.getIndex().name();
        }
    }

    private String getFullTextIndexName(PropInfo p) {
        return this.getFullTextIndexNameOrigin(p) + FULLTEXT_INDEX_SUBFIX;
    }
    private String getFullTextIndexNameOrigin(PropInfo p) {
        if (p.getFullTextIndex().name().equals("")) {
            if (p.getFullTextIndex().otherPropName() != null && p.getFullTextIndex().otherPropName().length != 0) {
                String fullTextIndexName = p.getColumnName();
                Other[] otherArr = p.getFullTextIndex().otherPropName();
                for (int i = 0; i < otherArr.length; i++) {
                    String pName = otherArr[i].name();
                    PropInfo pNameProp = getPropInfoByPName(pName);
                    String cname = pNameProp.getColumnName();
                    fullTextIndexName = ( fullTextIndexName+"_"+cname );
                }
                return fullTextIndexName;
            } else {
                return p.getColumnName();
            }
        } else {
            return p.getFullTextIndex().name();
        }
    }

    private static String getTableName(Long max, String name) {
        if (max < 1) {
            return name;
        }
        return name + KSentences.SHARDING_SPLT.getValue() + max;
    }

    private ConcurrentSkipListSet<String> reFreshTables() {
        try {
            ResultSet rs = getTableMeta(this.getConnectionManager().getConnection());
            ConcurrentSkipListSet<String> tbns = new ConcurrentSkipListSet<String>();
            String srctb = ConnectionManager.getTbinfo(domainClazz).entrySet().iterator().next().getKey();
            while (rs.next()) {
                String dbtbn = rs.getString("TABLE_NAME");
                String schem = rs.getString("TABLE_SCHEM");
                String[] tbsps = dbtbn.toUpperCase().split("^" + srctb.toUpperCase());
                char z = 'n';
                if (tbsps.length == 2) {
                    String ts = tbsps[1].replaceAll("_", "");
                    if (ts.length() == 0) {
                        z = 0;
                    } else {
                        z = ts.charAt(0);
                    }
                }
                if (tbsps.length == 0 || (z >= '0' && z <= '9') || (z >= 0 && z <= 9)) {
                    if (dbtbn.toLowerCase().startsWith(srctb.toLowerCase())) {
                        if (schem != null && schem.length() > 0) {
                            dbtbn = schem + "." + dbtbn;
                        }
                        tbns.add(dbtbn);
                    }
                }
            }
            DOMAINCLASS_TABLES_MAP.put(domainClazz, tbns);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            closeConnection();
        }
        return DOMAINCLASS_TABLES_MAP.get(domainClazz);
    }


    @Override
    public Integer deleteById(Serializable... id) {
        if (id != null && id.length > 0) {
            Set<Param> pms = Param.getParams(new Param(getPrimaryKeyPname(), Arrays.asList(id)));
            return deleteByCondition(pms);
        }
        return 0;
    }

    @Override
    public Integer update(Set<Param> pms, Map<String, Object> newValues) {
        if (getCurrentTableNames().size() < 1) {
            return 0;
        }
        try {
            Set<String> tbns = getTableNamesByParams(pms);
            if (newValues != null && newValues.size() > 0) {
                Set<PropInfo> pps = getPropInfos();
                int ttc = 0;
                for (String tn : tbns) {
                    StringBuilder buf = new StringBuilder(KSentences.UPDATE.getValue());
                    buf.append(tn).append(KSentences.SET.getValue());
                    Iterator<Entry<String, Object>> ite = newValues.entrySet().iterator();
                    while (ite.hasNext()) {
                        Entry<String, Object> en = ite.next();
                        for (PropInfo p : pps) {
                            if (p.getFieldName().equals(en.getKey())) {
                                buf.append("`").append(p.getColumnName()).append("`").append(KSentences.EQ.getValue()).append(KSentences.POSITION_PLACEHOLDER.getValue());
                                if (ite.hasNext()) {
                                    buf.append(KSentences.COMMA.getValue());
                                }
                            }
                        }
                    }
                    buf.append(getWhereSqlByParam(pms));
                    String sql = buf.toString();
                    PreparedStatement statement = getStatementBySql(false, sql);
                    setWhereSqlParamValue(pms, statement, setUpdateNewValues(newValues, statement));
                    if (this.isShowSql) {
                        log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(statement, sql));/*log.info(sql);*/
                    }
                    ttc += statement.executeUpdate();
                }
                return ttc;
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } finally {
            closeConnection();

        }
        return 0;
    }


    @Override
    public Integer delete(Set<Param> pms) {
        if (getCurrentTableNames().size() < 1) {
            return 0;
        }
        return deleteByCondition(pms);
    }

    //check field name is Date type
    private boolean isDate(String property) {
        for (PropInfo p : getPropInfos()) {
            if (p.getFieldName().equals(property)) {
                if (p.getSqlTypes() == Types.DATE || p.getSqlTypes() == Types.TIME || p.getSqlTypes() == Types.TIMESTAMP) {
                    return true;
                } else {
                    return false;
                }
            }
        }
        return false;
    }

    @Override
    public Date getMinDate(Set<Param> pms, String property) {
        return getDateFuncValue(getQueryIsRead(), pms, property, StatisticsType.MIN);
    }

    @Override
    public Date getMinDateFromMaster(Set<Param> pms, String property) {
        return getDateFuncValue(false, pms, property, StatisticsType.MIN);
    }

    @Override
    public Date getMaxDate(Set<Param> pms, String property) {
        return getDateFuncValue(getQueryIsRead(), pms, property, StatisticsType.MAX);
    }

    @Override
    public Date getMaxDateFromMaster(Set<Param> pms, String property) {
        return getDateFuncValue(false, pms, property, StatisticsType.MAX);
    }

    @Override
    public <T> T nativeQuery(String sql, Object[] pms, Class<T> resultClass) {
        return nativeQuery(getQueryIsRead(), sql, pms, resultClass);
    }

    @Override
    public <T> T nativeQueryFromMaster(String sql, Object[] pms, Class<T> resultClass) {
        return nativeQuery(false, sql, pms, resultClass);
    }

    private <T> T nativeQuery(Boolean isRead, String sql, Object[] pms, Class<T> resultClass) {
        try {
            T t = getT(resultClass);
            PreparedStatement st = getPreparedStatement(isRead, sql, pms);
            if (this.isShowSql) {
                log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(st, sql));
            }
            ResultSet rs = st.executeQuery();
            if (t instanceof String || t instanceof Number || t instanceof Boolean || t instanceof Date) {
                if (rs.next()) {
                    t = getRT(resultClass, t, rs);
                } else {
                    t = null;
                }
                return t;
            } else {
                Field[] declaredFields = resultClass.getDeclaredFields();
                if (rs.next()) {
                    return getRTObj(declaredFields, resultClass, t, rs);
                } else {
                    return null;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            closeConnection();
        }
    }

    @Override
    public <T> List<T> nativeQueryList(String sql, Object[] pms, Class<T> resultClass) {
        return nativeQueryList(getQueryIsRead(), sql, pms, resultClass);
    }

    @Override
    public <T> List<T> nativeQueryListFromMaster(String sql, Object[] pms, Class<T> resultClass) {
        return nativeQueryList(false, sql, pms, resultClass);
    }

    private <T> List<T> nativeQueryList(Boolean isRead, String sql, Object[] pms, Class<T> resultClass) {
        try {
            T t = getT(resultClass);
            PreparedStatement st = getPreparedStatement(isRead, sql, pms);
            if (this.isShowSql) {
                log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(st, sql));
            }
            ResultSet rs = st.executeQuery();
            List<T> tList = new ArrayList<>();
            if (t instanceof String || t instanceof Number || t instanceof Boolean || t instanceof Date) {
                while (rs.next()) {
                    tList.add(getRT(resultClass, getT(resultClass), rs));
                }
                return tList;
            } else {
                Field[] declaredFields = resultClass.getDeclaredFields();
                while (rs.next()) {
                    tList.add(getRTObj(declaredFields, resultClass, getT(resultClass), rs));
                }
                return tList;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            closeConnection();
        }
    }

    @Override
    public <T> PageData<T> nativeQueryPage(int curPage, int pageSize, String sql, Object[] pms, Class<T> result) {
        return nativeQueryPage(getQueryIsRead(), curPage, pageSize, sql, pms, result);
    }

    @Override
    public <T> PageData<T> nativeQueryPageFromMaster(int curPage, int pageSize, String sql, Object[] pms, Class<T> result) {
        return nativeQueryPage(false, curPage, pageSize, sql, pms, result);
    }

    private <T> PageData<T> nativeQueryPage(Boolean isRead, int curPage, int pageSize, String sql, Object[] pms, Class<T> result) {
        String countSql = "SELECT COUNT(1) FROM (" + sql + ") t";//KSentences.SELECT + KSentences.COMMA
        Long totalCount = this.nativeQuery(isRead, countSql, pms, Long.class);
        if (totalCount == 0) {
            return new PageData<>(curPage, pageSize, totalCount, new ArrayList<>(0));
        }
        int startIndex = (curPage - 1) * pageSize;
        String limitSql = sql + KSentences.LIMIT + startIndex + KSentences.COMMA + pageSize;
        List<T> dataList = nativeQueryList(isRead, limitSql, pms, result);
        return new PageData<T>(curPage, pageSize, totalCount, dataList);
    }

    private <T> T getT(Class<T> resultClass) throws InstantiationException, IllegalAccessException {
        T t = null;
        Integer izreo = 0;
        String zreo = "0";
        if (resultClass.equals(Byte.class)) {
            t = (T) new Byte(zreo);
        } else if (resultClass.equals(Short.class)) {
            t = (T) new Short(zreo);
        } else if (resultClass.equals(Integer.class)) {
            t = (T) new Integer(zreo);
        } else if (resultClass.equals(Long.class)) {
            t = (T) new Long(zreo);
        } else if (resultClass.equals(Float.class)) {
            t = (T) new Float(zreo);
        } else if (resultClass.equals(Double.class)) {
            t = (T) new Double(zreo);
        } else if (resultClass.equals(BigDecimal.class)) {
            t = (T) new BigDecimal(zreo);
        } else if (resultClass.equals(Boolean.class)) {
            t = (T) new Boolean(false);
        } else if (resultClass.equals(java.sql.Date.class)) {
            t = (T) new java.sql.Date(izreo);
        } else if (resultClass.equals(Timestamp.class)) {
            t = (T) new Timestamp(izreo);
        } else if (resultClass.equals(Time.class)) {
            t = (T) new Time(izreo);
        } else {
            t = resultClass.newInstance();
        }
        if (t instanceof Collection || t instanceof Map) {
            String error = "NOT SUPPORT resultClass  OF " + resultClass;
            log.error(error);
            throw new IllegalStateException(error);
        }
        return t;
    }

    private PreparedStatement getPreparedStatement(Boolean isRead, String sql, Object[] pms) throws SQLException {
        if (pms == null) {
            pms = new String[0];
        }
        PreparedStatement st = this.getConnectionManager().getConnection(isRead).prepareStatement(sql);
        if (pms != null) {
            for (int i = 1; i < pms.length + 1; i++) {
                Object o = pms[i - 1];
                if (o instanceof String) {
                    st.setString(i, (String) o);
                } else if (o instanceof Long) {
                    st.setLong(i, (Long) o);
                } else if (o instanceof Integer) {
                    st.setInt(i, (Integer) o);
                } else if (o instanceof Boolean) {
                    st.setBoolean(i, (Boolean) o);
                } else if (o instanceof Double) {
                    st.setDouble(i, (Double) o);
                } else if (o instanceof Date) {
                    st.setDate(i, (java.sql.Date) o);
                } else if (o instanceof BigDecimal) {
                    st.setBigDecimal(i, (BigDecimal) o);
                } else if (o instanceof Float) {
                    st.setFloat(i, (Float) o);
                } else if (o instanceof Time) {
                    st.setTime(i, (Time) o);
                } else if (o instanceof Timestamp) {
                    st.setTimestamp(i, (Timestamp) o);
                } else if (o instanceof Blob) {
                    st.setBlob(i, (Blob) o);
                } else if (o instanceof Byte) {
                    st.setByte(i, (Byte) o);
                } else if (o instanceof Short) {
                    st.setShort(i, (Short) o);
                } else {
                    String error = "NOT SUPPORT TYPE IN pms OF " + o.getClass();
                    log.error(error);
                    throw new IllegalStateException(error);
                }
            }
        }
        return st;
    }

    private <T> T getRT(Class<T> resultClass, T t, ResultSet rs) throws SQLException {
        if (resultClass.equals(String.class)) {
            t = (T) rs.getString(1);
        } else if (resultClass.equals(Long.class)) {
            t = (T) new Long(rs.getString(1));
        } else if (resultClass.equals(Double.class)) {
            t = (T) new Double(rs.getString(1));
        } else if (resultClass.equals(Integer.class)) {
            t = (T) new Integer(rs.getString(1));
        } else if (resultClass.equals(Short.class)) {
            t = (T) new Short(rs.getString(1));
        } else if (resultClass.equals(Byte.class)) {
            t = (T) new Byte(rs.getString(1));
        } else if (resultClass.equals(Float.class)) {
            t = (T) new Float(rs.getString(1));
        } else if (resultClass.equals(Boolean.class)) {
            String str = rs.getString(1);
            if (str.equals("1") || str.equalsIgnoreCase("true")) {
                t = (T) new Boolean(true);
            } else {
                t = (T) new Boolean(false);
            }
        } else if (resultClass.equals(BigDecimal.class)) {
            t = (T) rs.getBigDecimal(1);
        } else if (resultClass.equals(Date.class) || resultClass.equals(java.sql.Date.class)) {
            t = (T) rs.getDate(1);
        } else if (resultClass.equals(Timestamp.class)) {
            t = (T) rs.getTimestamp(1);
        } else if (resultClass.equals(Time.class)) {
            t = (T) rs.getTime(1);
        }
        return t;
    }

    private <T> T getRTObj(Field[] declaredFields, Class<T> resultClass, T t, ResultSet rs) throws SQLException {
        for (int i = 0; i < declaredFields.length; i++) {
            Field field = declaredFields[i];
            if (field.isAnnotationPresent(Transient.class)) {
                continue;
            }
            String name = field.getName();
            if (field.isAnnotationPresent(Column.class)) {
                Column column = field.getAnnotation(Column.class);
                if (column.name() != null && !column.name().trim().equals("")) {
                    name = column.name();
                }
            }
            int columnIndex = rs.findColumn(name);
            Class<?> type = field.getType();
            Object value = null;
            if (type.equals(Byte.class)) {
                value = rs.getByte(columnIndex);
            } else if (type.equals(Short.class)) {
                value = rs.getShort(columnIndex);
            } else if (type.equals(Integer.class)) {
                value = rs.getInt(columnIndex);
            } else if (type.equals(Long.class)) {
                value = rs.getLong(columnIndex);
            } else if (type.equals(String.class)) {
                value = rs.getString(columnIndex);
            } else if (type.equals(Boolean.class)) {
                value = rs.getBoolean(columnIndex);
            } else if (type.equals(BigDecimal.class)) {
                value = rs.getBigDecimal(columnIndex);
            } else if (type.equals(Double.class)) {
                value = rs.getDouble(columnIndex);
            } else if (type.equals(Float.class)) {
                value = rs.getFloat(columnIndex);
            } else if (type.equals(Date.class)) {
                value = rs.getDate(columnIndex);
            } else if (type.equals(Timestamp.class)) {
                value = rs.getTimestamp(columnIndex);
            } else if (type.equals(Time.class)) {
                value = rs.getTime(columnIndex);
            } else {
                continue;
            }
            field.setAccessible(true);
            try {
                field.set(t, value);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        return t;
    }

    @Override
    public int nativeExecute(String sql, Object[] pms) {
        try {
//            if (this.isShowSql) {
//                log.info(sql);
//                for (int i = 0; i < pms.length; i++) {
//                    log.info("param"+(i+1)+"="+pms[i].toString());
//                }
//            }
            if (pms == null) {
                pms = new String[0];
            }
            PreparedStatement st = this.getConnectionManager().getConnection().prepareStatement(sql);
            for (int i = 1; i < pms.length + 1; i++) {
                Object o = pms[i - 1];
                if (o instanceof String) {
                    st.setString(i, (String) o);
                } else if (o instanceof Long) {
                    st.setLong(i, (Long) o);
                } else if (o instanceof Integer) {
                    st.setInt(i, (Integer) o);
                } else if (o instanceof Boolean) {
                    st.setBoolean(i, (Boolean) o);
                } else if (o instanceof Double) {
                    st.setDouble(i, (Double) o);
                } else if (o instanceof BigDecimal) {
                    st.setBigDecimal(i, (BigDecimal) o);
                } else if (o instanceof Float) {
                    st.setFloat(i, (Float) o);
                } else if (o instanceof Date) {
                    st.setDate(i, (java.sql.Date) o);
                } else if (o instanceof Timestamp) {
                    st.setTimestamp(i, (Timestamp) o);
                } else if (o instanceof Time) {
                    st.setTime(i, (Time) o);
                } else if (o instanceof Blob) {
                    st.setBlob(i, (Blob) o);
                } else if (o instanceof Byte) {
                    st.setByte(i, (Byte) o);
                } else if (o instanceof Short) {
                    st.setShort(i, (Short) o);
                } else {
                    String error = "NOT SUPPORT TYPE OF " + o.getClass();
                    log.error(error);
                    throw new IllegalStateException(error);
                }
            }
            if (this.isShowSql) {
                log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(st, sql));
            }
            return st.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            closeConnection();
        }
    }

    private Date getDateFuncValue(Boolean isRead, Set<Param> pms, String property, StatisticsType st) {
        try {
            if (isDate(property)) {
                List<Future<QueryVo<ResultSet>>> rzts = getFunctionValues(isRead, pms, property, st);
                List<Timestamp> rzslist = new ArrayList<>();
                for (Future<QueryVo<ResultSet>> f : rzts) {
                    ResultSet rs = f.get().getOv();
                    while (rs.next()) {
                        Timestamp o = rs.getTimestamp(1);
                        if (o != null) {
                            rzslist.add(new Timestamp(o.getTime()));
                        }
                    }
                }
                if (rzslist.size() > 0) {
                    if (rzslist.size() == 1) {
                        return rzslist.get(0);
                    } else {
                        if (StatisticsType.MIN.equals(st)) {
                            return rzslist.parallelStream().min(Comparator.comparing(d -> d)).get();
                        } else if (StatisticsType.MAX.equals(st)) {
                            return rzslist.parallelStream().max(Comparator.comparing(d -> d)).get();
                        } else {
                            throw new IllegalArgumentException(String.format("Date type not supprot %s ; Date类型不支持 %s ;", st, st));
                        }
                    }
                } else {
                    return null;
                }
            } else {
                throw new IllegalArgumentException("column must Data type ; 字段必须是Date类型 ;");
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            closeConnection();
        }
    }

    @Override
    public Double getStatisticsValue(StatisticsType functionName, String property, Set<Param> pms) {
        return getStatisticsValue(getQueryIsRead(), functionName, property, pms);
    }

    @Override
    public Double getStatisticsValueFromMaster(StatisticsType functionName, String property, Set<Param> pms) {
        return getStatisticsValue(false, functionName, property, pms);
    }

    private Double getStatisticsValue(Boolean isRead, StatisticsType functionName, String property, Set<Param> pms) {
        if (property != null && functionName != null) {
            if (getCurrentTableNames().size() < 1) {
                return 0d;
            }
            try {
                List<Future<QueryVo<ResultSet>>> rzts = getFunctionValues(isRead, pms, property, functionName);
                List<Double> rzslist = new ArrayList<>();
                for (Future<QueryVo<ResultSet>> f : rzts) {
                    ResultSet rs = f.get().getOv();
                    while (rs.next()) {
                        Double o = rs.getDouble(1);
                        if (o != null) {
                            rzslist.add(o);
                        }
                    }
                }
                if (rzslist.size() > 0) {
                    if (rzslist.size() == 1) {
                        return rzslist.get(0);
                    } else {
                        if (StatisticsType.MAX.equals(functionName)) {
                            return rzslist.parallelStream().max(Comparator.comparing(d -> d)).get();
                        } else if (StatisticsType.MIN.equals(functionName)) {
                            return rzslist.parallelStream().min(Comparator.comparing(d -> d)).get();

                        } else if (StatisticsType.SUM.equals(functionName)) {
                            return rzslist.parallelStream().mapToDouble(i -> i).sum();
                        }
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                closeConnection();
            }
        }
        return 0D;
    }


    private List<Future<QueryVo<ResultSet>>> getFunctionValues(Boolean isRead, Set<Param> pms, String property, StatisticsType functionName) throws SQLException {
        StringBuffer sb = new StringBuffer(KSentences.SELECT.getValue());
        sb.append(functionName);
        for (PropInfo p : getPropInfos()) {
            if (p.getFieldName().equals(property.trim())) {
                sb.append(KSentences.LEFT_BRACKETS.getValue()).append(p.getColumnName()).append(KSentences.RIGHT_BRACKETS.getValue()).append(KSentences.FROM);
                break;
            }
        }
        Set<String> tbs = getTableNamesByParams(pms);
        List<Future<QueryVo<ResultSet>>> rzts = invokeall(isRead, pms, sb.toString(), tbs);
        return rzts;
    }


    @Override
    public Long getCount(Set<Param> pms, String... distincts) {
        return getCountPerTable(getQueryIsRead(), pms, distincts);
    }

    @Override
    public Long getCountFromMaster(Set<Param> pms, String... distincts) {
        return getCountPerTable(false, pms, distincts);
    }

    private Long getQvcSum(List<QueryVo<Long>> qvs) {
        if (qvs != null && qvs.size() > 0) {
            if (qvs.size() > 1) {
                return qvs.stream().filter(o -> o.getOv() != null).mapToLong(QueryVo::getOv).sum();
            } else {
                return qvs.get(0).getOv();
            }
        } else {
            return 0L;
        }
    }

    private List<Future<QueryVo<ResultSet>>> invokeall(boolean isRead, Set<Param> pms, String sqlselect, Set<String> tbs) throws SQLException {
        Iterator<String> tbnsite = tbs.iterator();
        List<QueryVo<PreparedStatement>> pss = new ArrayList<>();
        String whereSqlByParam = getWhereSqlByParam(pms);
        while (tbnsite.hasNext()) {
            String tn = tbnsite.next();
            String sql = sqlselect + tn + whereSqlByParam;
            PreparedStatement statement = getPreParedStatement(isRead, pms, sql);
            pss.add(new QueryVo<PreparedStatement>(tn, statement));
        }
        return invokeQueryAll(pss);
    }

    private PreparedStatement getPreParedStatement(boolean isRead, Set<Param> pms, String sql) throws SQLException {
        PreparedStatement statement = getStatementBySql(isRead, sql);
        setWhereSqlParamValue(pms, statement);
        return statement;
    }

    @Override
    public List<POJO> getList(Set<Param> pms, String... cls) {
        return getRztPos(getQueryIsRead(), getQueryIsRead(), pms, cls);
    }

    @Override
    public List<POJO> getAll(String... cls) {
        return getAll(getQueryIsRead(), cls);
    }

    @Override
    public List<POJO> getAllFromMaster(String... cls) {
        return getAll(false, cls);
    }

    private List<POJO> getAll(Boolean isRead, String... cls) {
        return getRztPos(false, isRead, null, cls);
    }

    @Override
    public List<POJO> getListFromMaster(Set<Param> pms, String... cls) {
        return getRztPos(false, false, pms, cls);
    }

    @Override
    public List<POJO> getList(Set<Param> pms, boolean isDistinct, String... cls) {
        return getRztPos(isDistinct, getQueryIsRead(), pms, cls);
    }

    @Override
    public List<POJO> getListFromMaster(Set<Param> pms, boolean isDistinct, String... cls) {
        return getRztPos(isDistinct, false, pms, cls);
    }

    @Override
    public List<POJO> getListOrderBy(Set<Param> pms, LinkedHashSet<OrderBy> orderbys, String... cls) {
        return getListOrderBy(getQueryIsRead(), pms, orderbys, cls);
    }

    @Override
    public List<POJO> getListOrderByFromMaster(Set<Param> pms, LinkedHashSet<OrderBy> orderbys, String... cls) {
        return getListOrderBy(false, pms, orderbys, cls);
    }

    private List<POJO> getListOrderBy(Boolean isRead, Set<Param> pms, LinkedHashSet<OrderBy> orderbys, String... cls) {
        return getRztPos(isRead, 1, Integer.MAX_VALUE / getCurrentTableNames().size(), orderbys, pms, cls);
    }

    @Override
    public List<POJO> getPageList(int curPage, int pageSize, Set<Param> pms, LinkedHashSet<OrderBy> orderbys, String... cls) {
        return getRztPos(getQueryIsRead(), curPage, pageSize, orderbys, pms, cls);
    }

    @Override
    public List<POJO> getPageListFromMaster(int curPage, int pageSize, Set<Param> pms, LinkedHashSet<OrderBy> orderbys, String... cls) {
        return getRztPos(false, curPage, pageSize, orderbys, pms, cls);
    }

    @Override
    public PageData<POJO> getPageInfoFromMaster(Set<Param> pms, int curPage, int pageSize, String... cls) {
        return getListFromNotSorted(false, curPage, pageSize, pms, cls);
    }

    @Override
    public PageData<POJO> getPageInfo(int curPage, int pageSize, Set<Param> pms, String... cls) {
        return getListFromNotSorted(getQueryIsRead(), curPage, pageSize, pms, cls);
    }

    @Override
    public List<POJO> getPageListFromMaster(int curPage, int pageSize, Set<Param> pms, String... cls) {
        return getListFromNotSorted(false, curPage, pageSize, pms, cls).getDataList();
    }

    @Override
    public List<POJO> getPageList(int curPage, int pageSize, Set<Param> pms, String... cls) {
        return getListFromNotSorted(getQueryIsRead(), curPage, pageSize, pms, cls).getDataList();
    }

    @Override
    public PageData<Object[]> getGroupPageInfo(int curPage, int pageSize, Set<Param> pms, LinkedHashSet<OrderBy> orderbys, LinkedHashMap<String, String> funs, String... groupby) {
        return getGroupPageInfo(getQueryIsRead(), curPage, pageSize, pms, orderbys, funs, groupby);
    }

    @Override
    public PageData<Object[]> getGroupPageInfoFromMaster(int curPage, int pageSize, Set<Param> pms, LinkedHashSet<OrderBy> orderbys, LinkedHashMap<String, String> funs, String... groupby) {
        return getGroupPageInfo(false, curPage, pageSize, pms, orderbys, funs, groupby);
    }

    private PageData<Object[]> getGroupPageInfo(Boolean isRead, int curPage, int pageSize, Set<Param> pms, LinkedHashSet<OrderBy> orderbys, LinkedHashMap<String, String> funs, String... groupby) {
        if (pms == null) {
            pms = new HashSet<>();
        }
        Long groupbyCount = getGroupbyCount(isRead, new HashSet<>(pms), groupby);
        if (groupbyCount > 0) {
            return new PageData<>(curPage, pageSize, groupbyCount, getGroupPageList(isRead, curPage, pageSize, pms, orderbys, funs, groupby));
        } else {
            return new PageData<>(curPage, pageSize, groupbyCount, new ArrayList<>(0));
        }
    }

    @Override
    public Long getGroupbyCount(Set<Param> pms, String... groupby) {
        return getGroupbyCount(getQueryIsRead(), pms, groupby);
    }

    @Override
    public Long getGroupbyCountFromMaster(Set<Param> pms, String... groupby) {
        return getGroupbyCount(false, pms, groupby);
    }

    private Long getGroupbyCount(Boolean isRead, Set<Param> pms, String... groupby) {
        return groupcount(isRead, pms, groupby);
    }

    private Set<String> getobfp(Set<Param> pms) {
        if (pms != null) {
            Set<String> pps = new HashSet<>(pms.size());
            for (Param p : pms) {
                if (p.getFunName() != null && PmType.FUN.equals(p.getCdType())) {
                    pps.add(p.getPname());
                    while (p.getOrParam() != null) {
                        p = p.getOrParam();
                        if (p.getFunName() != null && PmType.FUN.equals(p.getCdType())) {
                            pps.add(p.getPname());
                        }
                    }
                }
            }
        }
        return Collections.emptySet();

    }

    private Long groupcount(boolean isRead, Set<Param> pms, String... groupby) {
        if (groupby == null || groupby.length == 0) {
            return 0L;
        }

        if (this.isNotOneResult(pms)) {
            return 0L;
        }

        try {
            if (pms != null) {
                pms = new HashSet<>(pms);
            }
            Set<String> getobfp = getobfp(pms);
            Set<String> tbns = getTableNamesByParams(pms);
            Set<Param> hvcs = gethvconditions(pms);
            String whereSqlByParam = getWhereSqlByParam(pms);

            StringBuilder sqlsb = new StringBuilder("SELECT COUNT(1) FROM  (SELECT count(");
            for (PropInfo prop : getPropInfos()) {
                if (prop.getFieldName().equals(groupby[0].trim())) {
                    sqlsb.append(prop.getColumnName());
                    break;
                }
            }
            sqlsb.append(")  FROM (");
            Iterator<String> tnite = tbns.iterator();
            while (tnite.hasNext()) {
                String tn = tnite.next();
                sqlsb.append(getPreSelectSql(false, getGSelect(groupby, getobfp))).append(tn).append(whereSqlByParam);
                if (tnite.hasNext()) {
                    sqlsb.append(KSentences.UNION_ALL.getValue());
                }
            }
            String havingSql = getHavingSql(hvcs);
            sqlsb.append(")  gdtc  ").append(KSentences.GROUPBY.getValue()).append(groupbysql(groupby))
                    .append(havingSql).append(")  ccfd ");
            String sql = sqlsb.toString();
            PreparedStatement statement = getStatementBySql(isRead, sql);
            int ix = 1;
            for (String tn : tbns) {
                ix = setWhereSqlParamValue(pms, statement, ix);
            }
            setWhereSqlParamValue(hvcs, statement, ix);

            if (this.isShowSql) {
                log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(statement, sql));/*log.info(sql);*/
            }
            ResultSet rs = statement.executeQuery();
            if (rs.next()) {
                return rs.getObject(1, Long.class);
            }

            return 0L;
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } finally {
            closeConnection();
        }
    }

    private String groupbysql(String[] groupby) {

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < groupby.length; i++) {
            String g = groupby[i];
            for (PropInfo p : getPropInfos()) {
                if (p.getFieldName().equals(g)) {
                    sb.append(p.getColumnName());
                }
            }
            if (i < groupby.length - 1) {
                sb.append(KSentences.COMMA.getValue());
            }
        }
        return sb.toString();
    }

    @Override
    public List<Object[]> getGroupPageList(int curPage, int pageSize, Set<Param> pms, LinkedHashSet<OrderBy> orderbys, LinkedHashMap<String, String> funs, String... groupby) {
        return getGroupPageList(getQueryIsRead(), curPage, pageSize, pms, orderbys, funs, groupby);
    }

    @Override
    public List<Object[]> getGroupPageListFromMaster(int curPage, int pageSize, Set<Param> pms, LinkedHashSet<OrderBy> orderbys, LinkedHashMap<String, String> funs, String... groupby) {
        return getGroupPageList(false, curPage, pageSize, pms, orderbys, funs, groupby);
    }

    private List<Object[]> getGroupPageList(Boolean isRead, int curPage, int pageSize, Set<Param> pms, LinkedHashSet<OrderBy> orderbys, LinkedHashMap<String, String> funs, String... groupby) {
        return grouplist(isRead, curPage, pageSize, orderbys, pms, funs, groupby);
    }

    private List<Object[]> grouplist(boolean readOnly, int curPage, int pageSize, LinkedHashSet<OrderBy> orderbys, Set<Param> pms, LinkedHashMap<String, String> funs, String... groupby) {
        try {
            if (curPage < 1 || pageSize < 1 || groupby == null || groupby.length == 0) {
                return new ArrayList<>(0);
            }

            if (this.isNotOneResult(pms)) {
                return new ArrayList<>(0);
            }

            if (pms != null) {
                pms = new HashSet<>(pms);
            }
            /**
             * group func condition
             */
            Set<Param> hvcs = gethvconditions(pms);
            /**
             * where condition
             */
            String whereSqlByParam = getWhereSqlByParam(pms);

            StringBuilder grpsql = new StringBuilder(KSentences.SELECT.getValue());
            Set<PropInfo> propInfos = getPropInfos();
            /**
             * append query func
             */
            if (funs != null && funs.size() > 0) {
                Iterator<Entry<String, String>> enite = funs.entrySet().iterator();
                while (enite.hasNext()) {
                    Entry<String, String> funen = enite.next();
                    for (PropInfo p : propInfos) {
                        if (p.getFieldName().equals(funen.getValue())) {
                            grpsql.append(funen.getKey().trim().toUpperCase()).append("(").append(p.getColumnName().trim()).append(")").append(KSentences.COMMA.getValue());
                            break;
                        }
                    }
                }
            }
            /**
             * append query column
             */
            for (int i = 0; i < groupby.length; i++) {
                for (PropInfo p : propInfos) {
                    if (groupby[i].equals(p.getFieldName())) {
                        grpsql.append(p.getColumnName());
                        break;
                    }
                }

                if (i < groupby.length - 1) {
                    grpsql.append(KSentences.COMMA.getValue());
                }
            }

            grpsql.append(KSentences.FROM.getValue()).append("(");
            /**
             * collect all table data
             */
            Set<String> tbns = getTableNamesByParams(pms);
            /**
             * select groupby from
             */
            String selectpre = getPreSelectSql(false, getGSelect(groupby, funs != null ? funs.values() : null));
            Iterator<String> tnite = tbns.iterator();
            while (tnite.hasNext()) {
                String tn = tnite.next();
                grpsql.append(selectpre).append(tn).append(whereSqlByParam);
                if (tnite.hasNext()) {
                    grpsql.append(KSentences.UNION_ALL.getValue());
                }
            }
            grpsql.append(")  gdt ").append(KSentences.GROUPBY.getValue()).append(groupbysql(groupby)).append(getHavingSql(hvcs));


            if (orderbys != null && orderbys.size() > 0) {
                grpsql.append(KSentences.ORDERBY.getValue());
                Iterator<OrderBy> obite = orderbys.iterator();
                c:
                while (obite.hasNext()) {
                    OrderBy ob = obite.next();
                    if (ob.getFunName() != null) {
                        Set<Entry<String, String>> ens = funs.entrySet();
                        for (Entry<String, String> en : ens) {
                            if (en.getValue().equals(ob.getPropertyName())) {
                                Optional<PropInfo> propInfoOptional = propInfos.stream().filter(p -> p.getFieldName().equals(en.getValue().trim())).findFirst();
                                if (propInfoOptional.isPresent()) {
                                    grpsql.append(en.getKey().trim().toUpperCase()).append("(").append(propInfoOptional.get().getColumnName()).append(")");
                                } else {
                                    throw new IllegalArgumentException(String.format("In %s ,Can not find field %s", this.domainClazz.getSimpleName(), en.getValue()));
                                }
                            }
                        }
                    } else {
                        a:
                        for (PropInfo p : getPropInfos()) {
                            if (p.getFieldName().equals(ob.getPropertyName())) {
                                for (String g : groupby) {
                                    if (g.trim().equals(p.getFieldName())) {
                                        grpsql.append(p.getColumnName().trim());
                                        break a;
                                    }
                                }
                                continue c;
                            }
                        }

                    }
                    if (ob.getIsDesc()) {
                        grpsql.append(KSentences.DESC.getValue());
                    }
                    if (obite.hasNext()) {
                        grpsql.append(KSentences.COMMA.getValue());
                    }

                }
                if (grpsql.lastIndexOf(KSentences.COMMA.getValue()) == grpsql.length() - 1) {
                    grpsql.deleteCharAt(grpsql.length() - 1);
                }

            }
            String selectPagingSql = getSingleTableSelectPagingSql(grpsql.toString(), curPage, pageSize);
            PreparedStatement statement = getStatementBySql(readOnly, selectPagingSql);
            int ix = 1;
            for (String tn : tbns) {
                ix = setWhereSqlParamValue(pms, statement, ix);
            }
            setWhereSqlParamValue(hvcs, statement, ix);
            if (this.isShowSql) {
                log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(statement, selectPagingSql));/*log.info(selectPagingSql);*/
            }
            ResultSet resultSet = statement.executeQuery();
            return getObjectList(resultSet);
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } finally {
            closeConnection();
        }
    }

    private PreparedStatement getStatementBySql(boolean readOnly, String selectPagingSql) throws SQLException {
        PreparedStatement statement = this.getConnectionManager().getConnection(readOnly).prepareStatement(selectPagingSql);
        // 300 s timeout
        statement.setQueryTimeout(360);
        return statement;
    }

    private String getHavingSql(Set<Param> hvcs) {
        if (hvcs.size() > 0) {
            StringBuilder sb = new StringBuilder(KSentences.HAVING.getValue());
            geneConditionSql(hvcs, sb);
            return sb.toString();
        }
        return "";
    }

    private Set<Param> gethvconditions(Set<Param> pms) {
        Set<Param> hvcs = new HashSet<>();
        if (pms != null && pms.size() > 0) {
            Iterator<Param> pmite = pms.iterator();
            while (pmite.hasNext()) {
                Param pm = pmite.next();
                if (pm.getFunName() != null && pm.getFunName().length() > 0) {
                    for (PropInfo p : getPropInfos()) {
                        if (p.getFieldName().equals(pm.getPname())) {
                            hvcs.add(pm);
                            pmite.remove();
                        }
                    }
                }
            }
        }
        return hvcs;
    }

    @Override
    public List<POJO> getAllOrderBy(LinkedHashSet<OrderBy> orderbys, String... cls) {
        return getAllOrderBy(getQueryIsRead(), orderbys, cls);
    }

    @Override
    public List<POJO> getAllOrderByFromMaster(LinkedHashSet<OrderBy> orderbys, String... cls) {
        return getAllOrderBy(false, orderbys, cls);
    }

    private List<POJO> getAllOrderBy(Boolean isRead, LinkedHashSet<OrderBy> orderbys, String... cls) {
        if (getCurrentTableNames().size() < 1) {
            return new ArrayList<>(0);
        }
        return getRztPos(isRead, 1, Integer.MAX_VALUE / getCurrentTableNames().size(), orderbys, null, cls);
    }

    @Override
    public List<POJO> getListByIdsIn(List<Serializable> ids, String... cls) {
        return this.getListByIdsIn(getQueryIsRead(), ids, cls);
    }

    @Override
    public List<POJO> getListByIdsInFromMaster(List<Serializable> ids, String... cls) {
        return this.getListByIdsIn(false, ids, cls);
    }

    private List<POJO> getListByIdsIn(Boolean isRead, List<Serializable> ids, String... cls) {
        if (ids != null && ids.size() > 0) {
            Set<PropInfo> pis = getPropInfos();
            for (PropInfo fd : pis) {
                if (fd.getIsPrimarykey()) {
                    return getRztPos(false, isRead, Param.getParams(new Param(fd.getFieldName(), ids)), cls);
                }
            }
        }
        return new ArrayList<>(0);
    }

    @Override
    public List<POJO> getListByParamIn(String propertyName, List<Serializable> vls, String... cls) {
        return this.getListByParamIn(getQueryIsRead(), propertyName, vls, cls);
    }

    @Override
    public List<POJO> getListByParamInFromMaster(String propertyName, List<Serializable> vls, String... cls) {
        return this.getListByParamIn(false, propertyName, vls, cls);
    }

    private List<POJO> getListByParamIn(Boolean isRead, String propertyName, List<Serializable> vls, String... cls) {
        if (vls != null && vls.size() > 0) {
            Set<PropInfo> pis = getPropInfos();
            for (PropInfo fd : pis) {
                if (fd.getFieldName().equals(propertyName)) {
                    return getRztPos(false, isRead, Param.getParams(new Param(fd.getFieldName(), vls)), cls);
                }
            }
        }
        return new ArrayList<>(0);
    }

    //get primary key name
    private String getPrimaryKeyPname() {
        for (PropInfo fd : getPropInfos()) {
            if (fd.getIsPrimarykey()) {
                return fd.getFieldName();
            }
        }
        String tableName = ConnectionManager.getTbinfo(domainClazz).entrySet().iterator().next().getKey();
        String err = String.format("%s not has primary key ; %s 没有定义主键 ;", tableName, tableName);
        throw new IllegalStateException(err);
    }

    @Override
    public POJO getById(Serializable id, String... cls) {
        return getById(getQueryIsRead(), id, cls);
    }

    @Override
    public POJO getByIdFromMaster(Serializable id, String... cls) {
        return getById(false, id, cls);
    }

    private POJO getById(Boolean isRead, Serializable id, String... strings) {
        return getObjByid(isRead, id, strings);
    }

    protected POJO getObjByid(Boolean isRead, Serializable id, String... strings) {
        if (id != null) {
            try {
                Entry<String, LinkedHashSet<PropInfo>> tbimp = ConnectionManager.getTbinfo(domainClazz).entrySet().iterator().next();
                for (PropInfo fd : tbimp.getValue()) {
                    if (fd.getIsPrimarykey()) {
                        ColumnRule cr = fd.getColumnRule();
                        Set<Param> pms = Param.getParams(new Param(fd.getFieldName(), Operate.EQ, id));
                        if (cr != null) {
                            List<POJO> rzlist = getSingleObj(isRead, id, tbimp, fd, cr, pms, strings);
                            if (rzlist.size() == 1) {
                                return rzlist.get(0);
                            }
                        } else {
                            List<POJO> rzlist = getRztPos(false, isRead, pms, strings);
                            if (rzlist.size() == 1) {
                                return rzlist.get(0);
                            }
                        }
                        break;
                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
                throw new IllegalStateException(e);
            } finally {
                closeConnection();
            }
        }
        return null;
    }

    private List<POJO> getSingleObj(Boolean isRead, Serializable id, Entry<String, LinkedHashSet<PropInfo>> tbimp,
                                    PropInfo fd, ColumnRule cr, Set<Param> pms, String... strings) throws SQLException {
        String tableName = getTableName(getTableMaxIdx(id, fd.getFieldTypeClass(), cr), tbimp.getKey());
        if (!isContainsTable(tableName)) {
            return new ArrayList<>(0);
        }
        StringBuilder sb = getSelectSql(tableName, strings);
        sb.append(getWhereSqlByParam(pms));
        String sql = sb.toString();
        PreparedStatement prepare = getStatementBySql(isRead, sql);
        setWhereSqlParamValue(pms, prepare);
        if (this.isShowSql) {
            log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(prepare, sql));/*log.info(sql); */
        }
        ResultSet rs = prepare.executeQuery();
        return getRztObject(rs, strings);
    }

    @Override
    public POJO getOne(String propertyName, Serializable value, String... cls) {
        return getObj(getQueryIsRead(), propertyName, value, cls);
    }

    @Override
    public POJO getOneFromMater(String propertyName, Serializable value, String... cls) {
        return getObj(false, propertyName, value, cls);
    }

    @Override
    public POJO getOneFirst(String propertyName, Serializable value, String... cls) {
        List<POJO> list = this.getList(Param.getParams(new Param(propertyName, Operate.EQ, value)), cls);
        if (list.isEmpty()) {
            return null;
        } else {
            return list.get(0);
        }
    }

    @Override
    public POJO getOneFirstFromMater(String propertyName, Serializable value, String... cls) {
        List<POJO> list = this.getListFromMaster(Param.getParams(new Param(propertyName, Operate.EQ, value)), cls);
        if (list.isEmpty()) {
            return null;
        } else {
            return list.get(0);
        }
    }


    private POJO getObj(Boolean isRead, String propertyName, Serializable value, String... cls) {
        try {
            Entry<String, LinkedHashSet<PropInfo>> tbimp = ConnectionManager.getTbinfo(domainClazz).entrySet().iterator().next();
            for (PropInfo fd : tbimp.getValue()) {
                if (fd.getFieldName().equals(propertyName)) {
                    Set<Param> pms = Param.getParams(new Param(fd.getFieldName(), Operate.EQ, value));
                    if (value != null && fd.getColumnRule() != null) {

                        List<POJO> rzlist = getSingleObj(isRead, value, tbimp, fd, fd.getColumnRule(), pms, cls);
                        if (rzlist.size() == 1) {
                            return rzlist.get(0);
                        }
                    } else {
                        List<POJO> rzlist = getRztPos(false, isRead, pms, cls);
                        if (rzlist.size() == 1) {
                            return rzlist.get(0);
                        }
                    }
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } finally {
            closeConnection();
        }

        return null;
    }

    @Override
    public POJO getOne(Set<Param> pms, String... cls) {
        return this.getOne(getQueryIsRead(), pms, cls);
    }

    @Override
    public POJO getOneFromMater(Set<Param> pms, String... cls) {
        return this.getOne(false, pms, cls);
    }

    @Override
    public POJO getOneFirst(Set<Param> pms, String... cls) {
        List<POJO> list = this.getList(pms, cls);
        if (list.isEmpty()) {
            return null;
        } else {
            return list.get(0);
        }
    }

    @Override
    public POJO getOneFirstFromMater(Set<Param> pms, String... cls) {
        List<POJO> list = this.getListFromMaster(pms, cls);
        if (list.isEmpty()) {
            return null;
        } else {
            return list.get(0);
        }
    }

    private POJO getOne(Boolean isRead, Set<Param> pms, String... cls) {
        List<POJO> rzlist = getRztPos(false, isRead, pms, cls);
        if (rzlist.size() == 1) {
            return rzlist.get(0);
        }
        return null;
    }

    @Override
    public Integer saveList(List<POJO> pojos) {
        int i = 0;
        if (pojos != null) {
            boolean istransaction = this.getConnectionManager().isTransactioning();
            try {
                if (!istransaction) {
                    this.getConnectionManager().beginTransaction(this.getConnectionManager().isTransReadOnly());
                }
                for (POJO po : pojos) {
                    i += persist(po);
                }
                if (!istransaction) {
                    this.getConnectionManager().commitTransaction();
                }
            } catch (Throwable e) {
                if (!istransaction) {
                    this.getConnectionManager().rollbackTransaction();
                }
                e.printStackTrace();
                throw new IllegalArgumentException(e);
            } finally {
                closeConnection();
            }
        }
        return i;
    }

    @Override
    public Integer save(POJO pojo) {
        int rzc = 0;
        if (pojo != null) {
            try {
                //persistence
                rzc = persist(pojo);
            } catch (Exception e) {
                e.printStackTrace();
                throw new IllegalArgumentException(e);
            } finally {
                closeConnection();
            }
        }
        return rzc;
    }

    //persistence
    protected int persist(POJO pojo) throws IllegalAccessException, SQLException {
        Field[] fields = domainClazz.getDeclaredFields();
        Entry<String, LinkedHashSet<PropInfo>> tbe = ConnectionManager.getTbinfo(domainClazz).entrySet().iterator().next();
        Field idkey = checkPrimarykey(fields, tbe);

        StringBuilder sb = new StringBuilder(KSentences.INSERT.getValue());
        String tableSharding = tableSharding(pojo, fields, tbe.getKey());//pojo , declaredFields , this.firstTableName
        sb.append(tableSharding);
        sb.append("(");
        Iterator<PropInfo> clite = tbe.getValue().iterator();
        while (clite.hasNext()) {
            sb.append("`").append(clite.next().getColumnName()).append("`");
            if (clite.hasNext()) {
                sb.append(KSentences.COMMA.getValue());
            }
        }
        sb.append(")  VALUES(");
        for (int i = 0; i < tbe.getValue().size(); i++) {
            sb.append(KSentences.POSITION_PLACEHOLDER.getValue());
            if (i < tbe.getValue().size() - 1) {
                sb.append(KSentences.COMMA.getValue());
            }
        }
        sb.append(")");
        String insertSql = sb.toString();
        boolean autoincrement = isAutoIncrement();
        Connection connection = this.getConnectionManager().getConnection();
        PreparedStatement statement = autoincrement ? connection.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS) : connection.prepareStatement(insertSql);
        setParamVal(pojo, fields, tbe.getValue(), statement, this.getConnectionManager().getConnection());
        if (this.isShowSql) {
            log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(statement, insertSql)); /*log.info(insertSql);*/
        }
        int cc = statement.executeUpdate();
        if (autoincrement) {
            ResultSet rs = statement.getGeneratedKeys();
            if (rs.next()) {
                idkey.setAccessible(true);
                idkey.set(pojo, rs.getLong(1));
            }
        }
        return cc;
    }

    private boolean isAutoIncrement() {
        if ("MySQL".equalsIgnoreCase(this.dataBaseTypeName)) {
            return getPropInfos().stream().anyMatch(p -> GenerationType.IDENTITY.equals(p.getGeneratorValueAnnoStrategyVal())
                    || GenerationType.AUTO.equals(p.getGeneratorValueAnnoStrategyVal()));
        } else {
            return false;
        }
    }

    //get table split rule
    private ColumnRule getColumnRule() {
        Field[] fds = domainClazz.getDeclaredFields();
        for (Field fd : fds) {
            ColumnRule crn = fd.getAnnotation(ColumnRule.class);
            if (crn != null) {
                return crn;
            }
        }
        return null;
    }

    //table split
    private String tableSharding(POJO pojo, Field[] fds, String name) throws IllegalAccessException, SQLException {
        //has split rule flag field , and primary key use TABLE flag

        //foreach all field
        for (Field field : fds) {
            ColumnRule columnRule = field.getAnnotation(ColumnRule.class);
            //is has split rule flag field
            if (columnRule != null) {
                field.setAccessible(true);
                if (field.get(pojo) == null) {
                    for (PropInfo propInfo : this.getPropInfos()) {
                        //if this field with properties paired is true
                        if (this.fieldPropertiesPaired(propInfo, field)) {
                            //if is primary key
                            if (propInfo.getIsPrimarykey()) {
                                //if @GenerationType use TABLE and database is mysql
                                if (
                                        GenerationType.TABLE.equals(propInfo.getGeneratorValueAnnoStrategyVal())
                                                &&
                                                "MySQL".equalsIgnoreCase(this.dataBaseTypeName)
                                ) {
                                    //then , get the next global id , this id is for domain
                                    Long nextId = getNextIdFromIdTable(this.getConnectionManager().getConnection());
                                    field.set(pojo, nextId);
                                } else if (
                                    //if @GenerationType use AUTO or SEQUENCE,  and database is oracle
                                        this.autoNextVal(propInfo)
                                                &&
                                                "Oracle".equalsIgnoreCase(this.dataBaseTypeName)
                                ) {
                                    //then , get the next global id , this id is for domain
                                    Long nextId = getNextVal(this.getConnectionManager().getConnection());
                                    field.set(pojo, nextId);
                                } else {
                                    break;
                                }
                            } else {
                                break;
                            }
                        }
                    }
                    //if want split , but not has primary key , throw err info
                    if (field.get(pojo) == null) {
                        String err = String.format("%s split flag field not be null ; %s 切分字段不能为空 ;", field.getName(), field.getName());
                        throw new IllegalArgumentException(err);
                    }
                }
                //current field has split flag , get table index   by current field type and split rule
                long max = getTableMaxIdx(field.get(pojo), field.getType(), columnRule);
                Set<String> currentTables = getCurrentTableNames();
                if (currentTables.size() >= maxTableCount) {
                    String err = String.format("out of range for split table max num %s ; 超出了表拆分最大数量 , 最多只能拆分%s个表", maxTableCount, maxTableCount);
                    throw new IllegalStateException(err);
                }
                String ctbname = getTableName(max, name);
                if (!isExistTable(ctbname)) {
                    synchronized (FIRST_TABLECREATE) {
                        reFreshTables();
                        if (!isExistTable(ctbname)) {
                            executeCreate(name, ctbname);
                            for (int i = 1; i < getMaxIdleTablecount(columnRule); i++) {
                                executeCreate(name, getTableName(max + i, name));
                            }
                        }
                    }
                }
                return ctbname;
            }
        }
        return name;
    }

    //get table index   by current field type and split rule
    private long getTableMaxIdx(Object fieldObject, Class<?> fieldType, ColumnRule cr) {
        long max = 0;
        if (fieldType == Long.class) {
            max = getTbIdx(Long.valueOf(fieldObject.toString()), cr);
        } else if (fieldType == Integer.class) {
            if (cr.ruleType().equals(RuleType.RANGE)) {
                max = Integer.valueOf(fieldObject.toString()) / cr.value();
            } else {
                max = Integer.valueOf(fieldObject.toString()) % cr.value();
            }
        } else if (fieldType == String.class) {

            if (cr.ruleType().equals(RuleType.RANGE)) {
                max = Math.abs(fieldObject.toString().hashCode()) / cr.value();
            } else {
                max = Math.abs(fieldObject.toString().hashCode()) % cr.value();
            }

        } else if (fieldType == Date.class) {
            Date date = (Date) fieldObject;
            if (fieldObject.getClass() != fieldType) {
                date = new Date(date.getTime());
            }
            max = getTbIdx(date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay(), cr);

        } else if (fieldType == Timestamp.class) {

            Timestamp dt = (Timestamp) fieldObject;
            max = getTbIdx(dt.toLocalDateTime().toLocalDate().toEpochDay(), cr);

        } else if (fieldType == LocalDate.class) {

            LocalDate dt = (LocalDate) fieldObject;
            max = getTbIdx(dt.toEpochDay(), cr);

        } else if (fieldType == LocalDateTime.class) {

            LocalDateTime dt = (LocalDateTime) fieldObject;
            max = getTbIdx(dt.toLocalDate().toEpochDay(), cr);

        } else {
            String err = String.format("%s not support for split , must be int long string or date type ; %s类型不能用来对数据进行切分，请使用int、long、string、date类型的字段", fieldType, fieldType);
            throw new IllegalStateException(err);
        }
        return max;
    }

    private static long getTbIdx(long tv, ColumnRule crn) {
        if (crn.ruleType().equals(RuleType.RANGE)) {
            return tv / crn.value();
        } else {
            return tv % crn.value();
        }
    }

    private void executeCreate(String name, String ctbname) throws SQLException {
        reFreshTables();
        if (!isExistTable(ctbname)) {
            if ("MySQL".equalsIgnoreCase(this.dataBaseTypeName)) {
                //CREATE TABLE t_user_11  LIKE t_user
                String sql = KSentences.CREATE_TABLE.getValue() + ctbname + KSentences.LIKE + name;
                this.getConnectionManager().getConnection().prepareStatement(sql).executeUpdate();
                if (this.isShowSql) {
                    log.info(sql);
                }
                getCurrentTableNames().add(ctbname);
            } else if ("Oracle".equalsIgnoreCase(this.dataBaseTypeName)) {
                boolean create = createTable(ctbname);
                if (create) {
                    getCurrentTableNames().add(ctbname);
                }
            }
        }
    }

    private Field checkPrimarykey(Field[] fields, Entry<String, LinkedHashSet<PropInfo>> tbe) {
        for (Field field : fields) {
            if (field.isAnnotationPresent(Id.class)) {
                return field;
            }
        }
        String table = tbe.getKey();
        String err = String.format("%s not has primary key field ; %s 没有定义主键 ;", table, table);
        throw new IllegalStateException(err);
    }

    private final static Object FIRST_TABLECREATE = new Object();

    private int setUpdateNewValues(Map<String, Object> newValues, PreparedStatement statement) throws SQLException {
        Iterator<Entry<String, Object>> ite = newValues.entrySet().iterator();
        int i = 1;
        while (ite.hasNext()) {
            Entry<String, Object> next = ite.next();
            statement.setObject(i++, getParamSqlValue(next.getValue(), next.getKey()));
        }
        return i;
    }

    private int deleteByCondition(Set<Param> pms) {
        if (getCurrentTableNames().size() < 1) {
            return 0;
        }
        try {
            Set<String> tbns = getTableNamesByParams(pms);
            String whereSqlByParam = getWhereSqlByParam(pms);
            int ttc = 0;
            for (String tn : tbns) {
                String sql = KSentences.DELETE_FROM.getValue() + tn + whereSqlByParam;
                PreparedStatement statement = getStatementBySql(false, sql);
                setWhereSqlParamValue(pms, statement);
                if (this.isShowSql) {
                    log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(statement, sql));/*log.info(sql);*/
                }
                ttc += statement.executeUpdate();
            }
            return ttc;
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } finally {
            closeConnection();
        }
    }

    //field with properties paired
    private static boolean fieldPropertiesPaired(PropInfo propInfo, Field field) {
        Column clm = field.getAnnotation(Column.class);
        if (clm == null || clm.name().trim().length() < 1) {
            if (propInfo.getColumnName().equalsIgnoreCase(field.getName())) {
                return true;
            }
        } else if (clm.name().equalsIgnoreCase(propInfo.getColumnName())) {
            return true;
        }
        return false;
    }

    private void setParamVal(POJO pojo, Field[] fds, Set<PropInfo> clset, PreparedStatement statement, Connection conn)
            throws SQLException, IllegalAccessException {
        int idx = 0;
        for (PropInfo zd : clset) {
            idx = idx + 1;
            for (Field fd : fds) {
                if (fieldPropertiesPaired(zd, fd)) {
                    setParameter(pojo, statement, conn, idx, zd, fd);
                    break;
                }
            }

        }
    }

    private void setParameter(POJO pojo, PreparedStatement statement, Connection conn, int index, PropInfo propInfo, Field field)
            throws IllegalAccessException, SQLException {
        Object vl = getPropValue(pojo, field);
        if (field.getType().isEnum()) {
            if (vl == null) {
                statement.setObject(index, null);
            } else {
                Class<Enum> cls = (Class<Enum>) field.getType();
                if (field.isAnnotationPresent(Enumerated.class) && field.getAnnotation(Enumerated.class).value() == EnumType.STRING) {
                    statement.setObject(index, vl.toString());
                } else {
                    statement.setObject(index, Enum.valueOf(cls, vl.toString()).ordinal());
                }
            }
        } else {
            if (vl == null && propInfo.getIsPrimarykey()) {
                if (GenerationType.TABLE.equals(propInfo.getGeneratorValueAnnoStrategyVal()) && "MySQL".equalsIgnoreCase(this.dataBaseTypeName)) {
                    Long nextId = getNextIdFromIdTable(conn);
                    statement.setObject(index, nextId);
                    field.set(pojo, nextId);
                } else if (autoNextVal(propInfo) && "Oracle".equalsIgnoreCase(this.dataBaseTypeName)) {
                    Long nextId = getNextVal(conn);
                    statement.setObject(index, nextId);
                    field.set(pojo, nextId);
                } else {
                    statement.setObject(index, vl);
                }
            } else {
                if (vl != null && "Oracle".equalsIgnoreCase(this.dataBaseTypeName) && (propInfo.getFieldTypeClass() == Date.class || propInfo.getFieldTypeClass().getSuperclass() == Date.class)) {
                    Date dt = (Date) vl;
                    statement.setTimestamp(index, new Timestamp(dt.getTime()));
                } else {
                    if (propInfo.getVersion() && vl == null) {
                        vl = 1L;
                        try {
                            field.setAccessible(true);
                            field.set(pojo, vl);
                        } catch (Exception e) {
                            String error = "set new value to @Version type error";
                            throw new IllegalArgumentException(error);
                        }
                    }
                    if (vl instanceof java.lang.Character) {
                        statement.setObject(index, vl.toString());
                    } else {
                        statement.setObject(index, vl);
                    }
                }
            }
        }

    }

    //get field obj
    private Object getPropValue(POJO pojo, Field fd) {
        try {
            fd.setAccessible(true);
            Object vl = fd.get(pojo);
            return vl;
        } catch (SecurityException | IllegalArgumentException | IllegalAccessException e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        }
    }

    private Long getNextVal(Connection conn) throws SQLException {
        String seqName = getSequenceName(this.firstTableName);
        ResultSet rs = conn.prepareStatement(String.format("SELECT  %s.%s   FROM  dual", seqName, "nextval")).executeQuery();
        if (rs.next()) {
            return rs.getLong(1);
        }
        return null;
    }

    //get next global table id
    private Long getNextIdFromIdTable(Connection conn) throws SQLException {
        String idTableName = getIdTableName(this.firstTableName);
        String insertIdtable = genInsertIdTableSql(idTableName, "NULL");
        PreparedStatement idstatement = conn.prepareStatement(insertIdtable, Statement.RETURN_GENERATED_KEYS);
        idstatement.executeUpdate();
        ResultSet rs = idstatement.getGeneratedKeys();
        if (rs.next()) {
            return rs.getLong(1);
        }
        return null;
    }

    @Override
    public List<Object> getVList(String property, Set<Param> params) {
        return getRztPos(property, params, getQueryIsRead(), false);
    }

    @Override
    public List<Object> getVListFromMaster(String property, Set<Param> params) {
        return getRztPos(property, params, false, false);
    }

    @Override
    public List<Object> getVList(String property, Set<Param> params, boolean isDistinct) {
        return getRztPos(property, params, getQueryIsRead(), isDistinct);
    }

    @Override
    public List<Object> getVListFromMaster(String property, Set<Param> params, boolean isDistinct) {
        return getRztPos(property, params, false, isDistinct);
    }

    //query get single value list
    private List<Object> getRztPos(String property, Set<Param> params, boolean isRead, boolean isDistinct) {
        if (this.isNotOneResult(params)) {
            return new ArrayList<>(0);
        }

        try {
            String selectpre = getPreSelectSql(isDistinct, property);
            String whereSqlByParam = getWhereSqlByParam(params);
            Set<String> tbns = getTableNamesByParams(params);
            if (tbns.size() == 1) {
                return getSingleObject(isRead, selectpre + tbns.iterator().next() + whereSqlByParam, params);
            } else {
                List<QueryVo<PreparedStatement>> pss = getqvs(isRead, params, selectpre, whereSqlByParam, tbns);
                List<Object> querylist = this.querylist(pss);
                return querylist;
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } finally {
            closeConnection();
        }

    }

    private Boolean paramIsInEmpty(Param param) {
        if (param.getOperators().equals(Operate.IN)) {
            if (param.getInValue() == null || param.getInValue().isEmpty()) {
                Param orParam = param.getOrParam();
                if (orParam == null) {
                    return true;
                } else {
                    return this.paramIsInEmpty(orParam);
                }
            }
        }
        return false;
    }

    private Boolean paramsIsInEmpty(Set<Param> params) {
        if (params != null) {
            for (Param param : params) {
                return this.paramIsInEmpty(param);
            }
        }
        return false;
    }

    private Boolean isNotOneResult(Set<Param> params) {
        // zero table
        if (getCurrentTableNames().size() < 1) {
            return true;
        }
        // in empty
        if (this.paramsIsInEmpty(params)) {
            return true;
        }
        return false;
    }


    //query get pojo list
    private List<POJO> getRztPos(boolean isDistinct, boolean isRead, Set<Param> params, String... strings) {
        //no one result
        if (this.isNotOneResult(params)) {
            return new ArrayList<>(0);
        }

        try {
            String selectpre = getPreSelectSql(isDistinct, strings);
            String whereSqlByParam = getWhereSqlByParam(params);
            Set<String> tbns = getTableNamesByParams(params);
            if (tbns.size() == 1) {
                return getSingleObject(isRead, params, selectpre + tbns.iterator().next() + whereSqlByParam, strings);
            } else {
                List<QueryVo<PreparedStatement>> pss = this.getqvs(isRead, params, selectpre, whereSqlByParam, tbns);
                List<POJO> querylist = this.querylist(pss, strings);
                return querylist;
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } finally {
            closeConnection();
        }
    }

    private List<QueryVo<PreparedStatement>> getqvs(boolean isRead, Set<Param> params, String selectpre, String whereSqlByParam, Set<String> tbns) throws SQLException {
        List<QueryVo<PreparedStatement>> pss = new ArrayList<>();
        for (String tn : tbns) {
            String sql = selectpre + tn + whereSqlByParam;
            PreparedStatement statement = getStatementBySql(isRead, sql);
            setWhereSqlParamValue(params, statement);
            //if (this.isShowSql){//if (this.isShowSql) { log.info(sql); }
            //    log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(statement,sql));
            //}
            pss.add(new QueryVo<PreparedStatement>(tn, statement));
        }
        return pss;
    }

    @Override
    public PageData<POJO> getPageInfo(int curPage, int pageSize, Set<Param> params, LinkedHashSet<OrderBy> orderbys, String... strings) {
        Long count = this.getCount(params);
        if (count > 0) {
            return new PageData<>(curPage, pageSize, count, getRztPos(getQueryIsRead(), curPage, pageSize, orderbys, params, strings));
        } else {
            return new PageData<>(curPage, pageSize, count, new ArrayList<>(0));
        }
    }

    @Override
    public PageData<POJO> getPageInfoFromMaster(int curPage, int pageSize, Set<Param> params, LinkedHashSet<OrderBy> orderbys, String... strings) {
        Long count = getCountFromMaster(params);
        if (count > 0) {
            return new PageData<>(curPage, pageSize, count, getRztPos(false, curPage, pageSize, orderbys, params, strings));
        } else {
            return new PageData<>(curPage, pageSize, count, new ArrayList<>(0));
        }
    }

    //more table page query
    private String getSelectPagingSql(String sql, int curPage, int pageSize) {
        if (this.dataBaseTypeName.equalsIgnoreCase("MySQL")) {
            return sql + getPagingSql(curPage, pageSize);
        } else if (this.dataBaseTypeName.equalsIgnoreCase("Oracle")) {
            StringBuilder sb = new StringBuilder("select  row_.*,   rownum  rownum_      from (");
            sb.append(sql);
            sb.append(")  row_  where    rownum <=");
            sb.append(curPage * pageSize);
            return sb.toString();
        } else {
            String err = String.format("current page router not support %s database ; 当前查询分页路由不支持%s数据库系统 ;", this.dataBaseTypeName, this.dataBaseTypeName);
            throw new IllegalStateException(err);
        }
    }

    //single table page query
    private String getSingleTableSelectPagingSql(String sql, int curPage, int pageSize) {
        if (this.dataBaseTypeName.equalsIgnoreCase("MySQL")) {
            return sql + getSingleTablePagingSql(curPage, pageSize);
        } else if (this.dataBaseTypeName.equalsIgnoreCase("Oracle")) {
            return oraclepageselect(sql, curPage, pageSize);
        }
        String err = String.format("current page router not support %s database ; 当前查询分页路由不支持%s数据库系统 ;", this.dataBaseTypeName, this.dataBaseTypeName);
        throw new IllegalStateException(err);
    }

    private String getSingleTableSelectPagingSqlByStartIndex(int start, String sql, int pageSize) {
        if (this.dataBaseTypeName.equalsIgnoreCase("MySQL")) {
            return sql + getSinglePagingSql(start, pageSize);
        } else if (this.dataBaseTypeName.equalsIgnoreCase("Oracle")) {
            return getoracleSinglepagingSelectsql(start, sql, pageSize);
        }
        String err = String.format("current page router not support %s database ; 当前查询分页路由不支持%s数据库系统 ;", this.dataBaseTypeName, this.dataBaseTypeName);
        throw new IllegalStateException(err);
    }

    //oracle page query
    private String oraclepageselect(String sql, int curPage, int pageSize) {
        StringBuilder sb = new StringBuilder(
                "SELECT * FROM ( select row_.*, rownum rownum_ from (");
        sb.append(sql);
        sb.append(") row_ where rownum <=");
        sb.append(curPage * pageSize);
        sb.append(" ) WHERE rownum_ > ").append((curPage - 1) * pageSize);
        return sb.toString();
    }

    //oracle single page query
    private String getoracleSinglepagingSelectsql(int start, String sql, int pageSize) {
        if (start < 0 || pageSize < 1) {
            throw new IllegalArgumentException("start can not lt 0 , page size can not lt 1 ; 开始位置不能小于0,页大小不能小于1 ;");
        }
        StringBuilder sb = new StringBuilder(
                "SELECT * FROM ( select  row_.*, rownum rownum_ from (");
        sb.append(sql);
        sb.append(") row_ where rownum <=");
        sb.append(start + pageSize);
        sb.append(" ) WHERE rownum_ > ").append(start);
        return sb.toString();

    }

    private List<POJO> getRztPos(Boolean isRead, int curPage, int pageSize, LinkedHashSet<OrderBy> orderbys, Set<Param> params, String... strings) {
        if (curPage < 1 || pageSize < 1) {
            return new ArrayList<>(0);
        }

        if (this.isNotOneResult(params)) {
            return new ArrayList<>(0);
        }

        Set<String> tbns = getTableNamesByParams(params);
        if (tbns.size() > 1 && (orderbys == null || orderbys.isEmpty())) {
            return getListFromNotSorted(isRead, curPage, pageSize, params, strings).getDataList();
        } else {
            try {
                String selectpre = getPreSelectSql(false, strings);
                String whereSqlByParam = getWhereSqlByParam(params);
                String orderBySql = getOrderBySql(orderbys);
                if (tbns.size() == 1) {
                    String sql = getSingleTableSelectPagingSql(selectpre + tbns.iterator().next() + whereSqlByParam + orderBySql, curPage, pageSize);
                    return getSingleObject(isRead, params, sql, strings);
                } else {
                    List<QueryVo<PreparedStatement>> pss = new ArrayList<>();
                    for (String tn : tbns) {
                        String sql = getSelectPagingSql(selectpre + tn + whereSqlByParam + orderBySql, curPage, pageSize);
                        PreparedStatement statement = getStatementBySql(isRead, sql);
                        setWhereSqlParamValue(params, statement);
                        pss.add(new QueryVo<PreparedStatement>(tn, statement));
                    }
                    List<POJO> querylist = querylist(pss, strings);
                    if (querylist.size() > 1) {
                        return getOrderbyPagelist(curPage, pageSize, querylist, addsortinfo(orderbys, strings));
                    } else {
                        return querylist;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw new IllegalStateException(e);
            } finally {
                closeConnection();
            }
        }

    }

    private List<Object> getSingleObject(Boolean isRead, String sql, Set<Param> params) throws SQLException {
        PreparedStatement statement = getStatementBySql(isRead, sql);
        setWhereSqlParamValue(params, statement);
        if (this.isShowSql) {
            log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(statement, sql));/*log.info(sql);*/
        }
        return getRztObject(statement.executeQuery());
    }

    private List<POJO> getSingleObject(Boolean isRead, Set<Param> params, String sql, String... strings) throws SQLException {
        PreparedStatement statement = getStatementBySql(isRead, sql);
        setWhereSqlParamValue(params, statement);
        if (this.isShowSql) {
            log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(statement, sql));/*log.info(sql);*/
        }
        return getRztObject(statement.executeQuery(), strings);
    }

    //not sort page query
    private PageData<POJO> getListFromNotSorted(Boolean isRead, int curPage, int pageSize, Set<Param> params, String... strings) {
        //if (this.getConnectionManager().isShowSql()) {
        //    log.info("begin........................................");
        //}
        try {
            //not one result
            if (this.isNotOneResult(params)) {
                return new PageData<>(curPage, pageSize, 0, new ArrayList<>(0));
            }

            String selectpre = getPreSelectSql(false, strings);
            String whereSqlByParam = getWhereSqlByParam(params);
            List<QueryVo<PreparedStatement>> pss = new ArrayList<>();
            Set<String> tbs = getTableNamesByParams(params);
            List<QueryVo<Long>> qvs = getMultiTableCount(isRead, params, tbs);
            long totalCount = qvs.stream().mapToLong(QueryVo::getOv).sum();
            if (totalCount < 1) {
                return new PageData<>(curPage, pageSize, totalCount, new ArrayList<>(0));
            }
            //start pos
            int start = getPageStartIndex(curPage, pageSize);
            //current query max pos
            int csum = 0;
            //current query count
            int rdsum = 0;
            for (QueryVo<Long> q : qvs) {
                csum += q.getOv();
                if (rdsum < pageSize) {
                    if (csum > start) {
                        //current table begin pos
                        int startindex = 0;
                        //sur how mach data nedd query
                        int left = pageSize - rdsum;
                        int initSize = q.getOv().intValue() > left ? left : q.getOv().intValue();
                        if (start > 0) {
                            //current table sur how math data
                            int step = csum - start;
                            if (step < q.getOv().intValue()) {
                                startindex = q.getOv().intValue() - step;
                                if (step < pageSize) {
                                    initSize = step;
                                }
                            }
                        }
                        rdsum += initSize;
                        String sql = getSingleTableSelectPagingSqlByStartIndex(startindex, selectpre + q.getTbn() + whereSqlByParam, initSize);
                        PreparedStatement statement = getStatementBySql(isRead, sql);
                        setWhereSqlParamValue(params, statement);
                        //if (this.isShowSql) {
                        // log.info(statement.toString()); /*log.info(sql); */}
                        pss.add(new QueryVo<PreparedStatement>(q.getTbn(), statement));
                    }
                } else {
                    break;
                }
            }
            return new PageData<>(curPage, pageSize, totalCount, querylist(pss, strings));
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } finally {
            closeConnection();
            //if (this.getConnectionManager().isShowSql()) {
            //    log.info("........................................end");
            //}
        }

    }

    //get start pos index
    private int getPageStartIndex(int curPage, int pageSize) {
        int start = (curPage - 1) * pageSize;
        return start;
    }

    private LinkedHashSet<SortInfo> addsortinfo(LinkedHashSet<OrderBy> orderbys, String... strings) {
        LinkedHashSet<SortInfo> sts = new LinkedHashSet<>();
        if (orderbys != null && orderbys.size() > 0) {
            List<String> asList = Arrays.asList(strings);
            for (OrderBy ob : orderbys) {
                if ((strings != null && strings.length > 0) && asList.contains(ob.getPropertyName())) {
                    sts.add(new SortInfo(ob.getPropertyName(), ob.getIsDesc()));
                } else {
                    sts.add(new SortInfo(ob.getPropertyName(), ob.getIsDesc()));
                }
            }

        }
        return sts;
    }

    private static boolean isArrayEffective(String... distincts) {
        if (distincts != null && distincts.length > 0 && distincts[0].trim().length() > 0) {
            return true;
        } else {
            return false;
        }
    }

    //get total count
    private Long getCountPerTable(Boolean isRead, Set<Param> params, String... distincts) {
        try {
            if (this.isNotOneResult(params)) {
                return 0L;
            }
            Set<String> tbs = getTableNamesByParams(params);
            if (tbs.size() > 1) {
                if (isArrayEffective(distincts)) {
                    return groupcount(isRead, params, distincts);
                } else {
                    return getQvcSum(getMultiTableCount(isRead, params, tbs));
                }
            } else {
                String signleTableName = tbs.iterator().next();
                return this.getSingleTableCount(isRead,signleTableName,params,distincts);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            closeConnection();
        }
    }

    private Long getSingleTableCount(Boolean isRead, String tableName,Set<Param> params,String... distincts) throws SQLException {
        StringBuilder sb = new StringBuilder(KSentences.SELECT.getValue());
        sb.append(KSentences.COUNT.getValue());
        sb.append(KSentences.LEFT_BRACKETS.getValue());
        if (isArrayEffective(distincts)) {
            sb.append(KSentences.DISTINCT.getValue());
            for (int i = 0; i < distincts.length; i++) {
                String ps = distincts[i];
                for (PropInfo p : getPropInfos()) {
                    if (p.getFieldName().equals(ps.trim())) {
                        sb.append(p.getColumnName());
                        break;
                    }
                }
                if (i < distincts.length - 1) {
                    sb.append(KSentences.COMMA.getValue());
                }
            }
        } else {
            sb.append("*");
        }
        sb.append(KSentences.RIGHT_BRACKETS.getValue()).append(KSentences.FROM.getValue()).append(tableName);
        sb.append(getWhereSqlByParam(params));
        String sql = sb.toString();
        PreparedStatement statement = getPreParedStatement(isRead, params, sql);
        if (this.isShowSql) {
            log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(statement, sql));/*log.info(sql);*/
        }
        ResultSet rs = statement.executeQuery();
        if (rs.next()) {
            return rs.getLong(1);
        } else {
            return 0L;
        }
    }

    @Override
    public void update(POJO po) {
        String primaryKeyName = getPrimaryKeyPname();
        Set<PropInfo> propInfos = getPropInfos();
        Set<Param> pms = new HashSet<>(1);
        Map<String, Object> newValues = new HashMap<>(propInfos.size());
        Field[] fds = domainClazz.getDeclaredFields();
        Object id = null;
        boolean version = false;
        String versionPname = null;
        Long oldVersion = null;
        for (PropInfo propInfo : propInfos) {
            for (Field field : fds) {
                if (fieldPropertiesPaired(propInfo, field)) {
                    Object propValue = getPropValue(po, field);
                    if (propInfo.getFieldName().equals(primaryKeyName)) {
                        if (propValue != null) {
                            id = propValue;
                            pms.add(new Param(propInfo.getFieldName(), Operate.EQ, id));
                        } else {
                            throw new IllegalArgumentException("primary key not null ; 主键的值不能为空 ;");
                        }
                    } else if (propInfo.getVersion()) {
                        version = true;
                        versionPname = propInfo.getFieldName();
                        pms.add(new Param(versionPname, Operate.EQ, propValue));
                        oldVersion = Long.parseLong(propValue.toString());
                        if (oldVersion == null) {
                            oldVersion = 0L;
                        }
                        Long newVersion = oldVersion + 1;
                        newValues.put(versionPname, newVersion);
                        try {
                            field.setAccessible(true);
                            field.set(po, newVersion);
                        } catch (Exception e) {
                            String error = "set new value to @Version type error";
                            throw new IllegalArgumentException(error);
                        }
                    } else {
                        newValues.put(propInfo.getFieldName(), propValue);
                    }
                }
            }
        }
        try {
            if (newValues != null && newValues.size() > 0) {
                Set<String> tableNames = getTableNamesByParams(pms);
                Set<PropInfo> pps = getPropInfos();
                int ttc = 0;
                boolean isShowSqled = false;
                for (String tableName : tableNames) {
                    StringBuilder buf = new StringBuilder(KSentences.UPDATE.getValue());
                    buf.append(tableName).append(KSentences.SET.getValue());
                    Iterator<Entry<String, Object>> it = newValues.entrySet().iterator();
                    while (it.hasNext()) {
                        Entry<String, Object> entry = it.next();
                        for (PropInfo p : pps) {
                            if (p.getFieldName().equals(entry.getKey())) {
                                buf.append("`").append(p.getColumnName()).append("`").append(KSentences.EQ.getValue()).append(KSentences.POSITION_PLACEHOLDER.getValue());
                                if (it.hasNext()) {
                                    buf.append(KSentences.COMMA.getValue());
                                }
                            }
                        }
                    }
                    buf.append(getWhereSqlByParam(pms));

                    String sql = buf.toString();
                    PreparedStatement statement = getStatementBySql(false, sql);
//                    if (this.getConnectionManager().isShowSql()&&!isShowSqled) {
//                        isShowSqled = true;
//                        log.info(sql);
//                        Set<Entry<String, Object>> entrySet = newValues.entrySet();
//                        for (Entry<String, Object> entry : entrySet) {
//                            if (entry.getValue() != null) {
//                                log.info("param("+entry.getKey()+")"+"="+entry.getValue().toString());
//                            }
//                        }
//                    }
                    int i = setUpdateNewValues(newValues, statement);
                    setWhereSqlParamValue(pms, statement, i);
                    if (this.isShowSql) {
                        log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(statement, sql));
                    }
                    ttc += statement.executeUpdate();
                }
                if (version && ttc == 0) {
                    POJO pojo = this.getById((Serializable) id, primaryKeyName, versionPname);
                    if (pojo != null) {
                        Field nowVersionField = pojo.getClass().getDeclaredField(versionPname);
                        nowVersionField.setAccessible(true);
                        Long nowVersion = Long.parseLong(nowVersionField.get(pojo).toString());
                        if (!oldVersion.equals(nowVersion)) {
                            String errorMsg = "Current Version Is " + oldVersion + ",But The New Version Is " + nowVersion + ",So Changes Cannot Be Performed In Different Versions.";
                            throw new ObjectOptimisticLockingFailureException(errorMsg);
                        }
                    }
                }
            }
        } catch (ObjectOptimisticLockingFailureException e) {
            throw e;
        } catch (IllegalAccessException e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } catch (SQLException e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } finally {
            closeConnection();
        }
    }

    private List<QueryVo<Long>> getMultiTableCount(Boolean isRead, Set<Param> params, Set<String> tbs)
            throws SQLException, InterruptedException, ExecutionException {
        List<QueryVo<Long>> qvs = new ArrayList<>();
        List<Future<QueryVo<ResultSet>>> rzts = invokeall(isRead, params, KSentences.SELECT_COUNT.getValue(), tbs);
        for (Future<QueryVo<ResultSet>> f : rzts) {
            ResultSet rs = f.get().getOv();
            if (rs.next()) {
                long cc = rs.getLong(1);
                if (cc > 0) {
                    qvs.add(new QueryVo<Long>(f.get().getTbn(), cc));
                }
            }
        }
        if (qvs.size() > 1) {
            qvs.sort(new Comparator<QueryVo<Long>>() {
                @Override
                public int compare(QueryVo<Long> o1, QueryVo<Long> o2) {
                    return o2.getTbn().compareTo(o1.getTbn());
                }
            });
        }
        return qvs;
    }

    private <T> List<T> getOrderbyPagelist(int curPage, int pageSize, List<T> querylist, LinkedHashSet<SortInfo> sts) {
        if (sts != null && sts.size() > 0) {
            querylist.sort(new SortComparator<>(sts));
        }
        int fromIndex = getPageStartIndex(curPage, pageSize);
        int toIndex = fromIndex + pageSize;
        if (toIndex > querylist.size()) {
            toIndex = querylist.size();
        }
        if (fromIndex >= toIndex) {
            return new ArrayList<>(0);
        }
        return querylist.subList(fromIndex, toIndex);
    }

    private String getOrderBySql(LinkedHashSet<OrderBy> orderbys) {
        StringBuilder sb = new StringBuilder();
        if (orderbys != null && orderbys.size() > 0) {
            sb.append(KSentences.ORDERBY.getValue());
            Iterator<OrderBy> ite = orderbys.iterator();
            while (ite.hasNext()) {
                OrderBy ob = ite.next();
                for (PropInfo p : getPropInfos()) {
                    if (p.getFieldName().equals(ob.getPropertyName().trim())) {
                        if (ob.getFunName() != null && ob.getFunName().trim().length() > 0) {
                            sb.append(ob.getFunName());
                            sb.append("(");
                            sb.append(p.getColumnName());
                            sb.append(")");
                        } else {
                            sb.append("`").append(p.getColumnName()).append("`");
                        }
                        if (ob.getIsDesc()) {
                            sb.append(KSentences.DESC.getValue());
                        }
                        if (ite.hasNext()) {
                            sb.append(KSentences.COMMA.getValue());
                        }
                    }
                }
            }
        }

        return sb.toString();
    }

    //more table page query
    private String getPagingSql(int curPage, int pageSize) {
        if (curPage < 1 || pageSize < 1) {
            throw new IllegalArgumentException("current page num and view num not lt 0 ; 当前页和页大小不能小于0 ;");
        }
        StringBuilder sb = new StringBuilder(KSentences.LIMIT.getValue());
        sb.append(curPage * pageSize);
        return sb.toString();
    }

    //single table page query
    private String getSingleTablePagingSql(int curPage, int pageSize) {
        if (curPage < 1 || pageSize < 1) {
            throw new IllegalArgumentException("current page num and view num not lt 0 ; 当前页和页大小不能小于0 ;");
        }
        StringBuilder sb = new StringBuilder(KSentences.LIMIT.getValue());
        sb.append((curPage - 1) * pageSize);
        sb.append(KSentences.COMMA.getValue()).append(pageSize);
        return sb.toString();
    }

    //not sort page query
    private String getSinglePagingSql(int start, int pageSize) {
        if (start < 0 || pageSize < 1) {
            throw new IllegalArgumentException("start can not lt 0 , page size can not lt 1 ; 开始位置不能小于0,页大小不能小于1 ;");
        }
        StringBuilder sb = new StringBuilder(KSentences.LIMIT.getValue());
        sb.append(start);
        sb.append(KSentences.COMMA.getValue()).append(pageSize);
        return sb.toString();
    }

    private List<Object> querylist(List<QueryVo<PreparedStatement>> pss) throws InterruptedException, ExecutionException {
        if (pss != null && pss.size() > 0) {
            List<Future<QueryVo<ResultSet>>> rzs = invokeQueryAll(pss);
            List<Object> pos = new ArrayList<>();
            for (Future<QueryVo<ResultSet>> f : rzs) {
                pos.addAll(getRztObject(f.get().getOv()));
            }
            return pos;
        } else {
            return new ArrayList<>(0);
        }
    }

    private List<POJO> querylist(List<QueryVo<PreparedStatement>> pss, String... strings) throws InterruptedException, ExecutionException {
        if (pss != null && pss.size() > 0) {
            List<Future<QueryVo<ResultSet>>> rzs = invokeQueryAll(pss);
            List<POJO> pos = new ArrayList<>();
            for (Future<QueryVo<ResultSet>> f : rzs) {
                try {
                    pos.addAll(getRztObject(f.get().getOv(), strings));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return pos;

        }
        return new ArrayList<>(0);
    }

    private List<Object[]> getObjectList(ResultSet resultSet) throws SQLException {
        List<Object[]> objs = new ArrayList<>();
        while (resultSet.next()) {
            int columnCount = resultSet.getMetaData().getColumnCount();
            Object[] os = new Object[columnCount];
            for (int i = 1; i <= columnCount; i++) {
                os[i - 1] = resultSet.getObject(i);
            }
            objs.add(os);
        }
        return objs;
    }

    private List<Future<QueryVo<ResultSet>>> invokeQueryAll(List<QueryVo<PreparedStatement>> pss) {
        List<QueryCallable> qcs = new ArrayList<>();
        for (QueryVo<PreparedStatement> ps : pss) {
            PreparedStatement preparedStatement = ps.getOv();
            if (this.isShowSql) {
                log.info(this.getConnectionManager().getMyDataShowSqlBean().showSqlForLog(preparedStatement, preparedStatement.toString()));
            }
            qcs.add(new QueryCallable(preparedStatement, ps.getTbn()));
        }
        try {
            return NEW_FIXED_THREAD_POOL.invokeAll(qcs);
        } catch (Throwable e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        }
    }

    private List<Object> getRztObject(ResultSet rs) {
        try {
            List<Object> ts = new ArrayList<>();
            while (rs.next()) {
                ts.add(rs.getObject(1));
            }
            return ts;
        } catch (SQLException e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        }
    }

    protected List<POJO> getRztObject(ResultSet rs, String... strings) {
        List<POJO> pos = new ArrayList<>();
        try {
            Set<PropInfo> pis = getPropInfos();
            while (rs.next()) {
                POJO po = domainClazz.newInstance();
                if (strings != null && strings.length > 0) {
                    a:
                    for (int i = 0; i < strings.length; i++) {
                        for (PropInfo pi : pis) {
                            if (pi.getFieldName().equals(strings[i])) {
                                Field fd = domainClazz.getDeclaredField(strings[i]);
                                setPoValue(rs, po, i, fd);
                                continue a;
                            }
                        }
                    }
                } else {
                    a:
                    for (int i = 0; i < rs.getMetaData().getColumnCount(); i++) {
                        for (PropInfo pi : pis) {
                            if (pi.getColumnName().equalsIgnoreCase(rs.getMetaData().getColumnName(i + 1))) {
                                Field fd = domainClazz.getDeclaredField(pi.getFieldName());
                                setPoValue(rs, po, i, fd);
                                continue a;
                            }
                        }
                    }
                }
                pos.add(po);
            }
            return pos;
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        }

    }

    private void setPoValue(ResultSet rs, POJO po, int i, Field fd) throws SQLException, IllegalAccessException {
        Object objectv = getRzVl(rs, i, fd);
        MyObjectUtils.setObjectValue(fd, objectv, po);
    }

    private Object getRzVl(ResultSet rs, int i, Field fd) throws SQLException {
        Object objectv;
        if ((fd.getType() == Date.class || fd.getType().getSuperclass() == Date.class)) {
            objectv = rs.getTimestamp(i + 1);
        } else {
            objectv = rs.getObject(i + 1);
        }
        return objectv;
    }

    //seeting query param
    protected String getWhereSqlByParam(Set<Param> pms) {
        StringBuilder sb = new StringBuilder();
        if (pms != null && pms.size() > 0) {
            sb.append(KSentences.WHERE.getValue());
            geneConditionSql(pms, sb);
        }
        return sb.toString();
    }

    private void geneConditionSql(Set<Param> pms, StringBuilder sb) {
        Iterator<Param> pmsIterator = pms.iterator();
        while (pmsIterator.hasNext()) {
            Param pm = pmsIterator.next();

            if (pm.getPname() != null && pm.getPname().trim().length() > 0) {
                boolean hasOr = pm.getOrParam() != null;
                if (hasOr) {
                    sb.append("(");
                }

                do {
                    if (pm.getOperators().getValue().startsWith("MATCH")) {
                        String[] pNames = pm.getPname().split(",");
                        String matchNames = "";
                        for (int i = 0; i < pNames.length; i++) {
                            String pName = pNames[i];
                            PropInfo pNameProp = getPropInfoByPName(pName);
                            String cname = pNameProp.getColumnName();
                            if (i == 0) {
                                matchNames += cname;
                            } else {
                                matchNames += (","+cname);
                            }
                        }
                        sb.append(String.format(pm.getOperators().getValue(), matchNames));

                    } else {
                        for (PropInfo p : getPropInfos()) {
                            if (p.getFieldName().equals(pm.getPname())) {
                                if (pm.getCdType().equals(PmType.OG)) {//原生类型
                                    setogcds(sb, pm, p);

                                } else { //值类型 或 函数
                                    setvlcds(sb, pm, p);
                                }
                            }
                        }
                    }

                    pm = pm.getOrParam();
                    if (pm != null) {
                        sb.append(KSentences.OR.getValue());
                    }
                } while (pm != null);

                if (hasOr) {
                    sb.append(")");
                }
                if (pmsIterator.hasNext()) {
                    sb.append(KSentences.AND.getValue());
                }
            }
        }
    }

    private void setogcds(StringBuilder sb, Param pm, PropInfo p) {
        setcName(sb, pm, p);
        if (pm.getOperators().equals(Operate.BETWEEN)) {
            sb.append(pm.getOperators().getValue());
            sb.append(pm.getFirstValue());
            sb.append(KSentences.AND);
            sb.append(pm.getValue());
        } else if (pm.getOperators().equals(Operate.IN)
                || pm.getOperators().equals(Operate.NOT_IN) && pm.getInValue() != null) {
            sb.append(pm.getOperators().getValue());
            sb.append("(");
            sb.append(pm.getValue());
            sb.append(")");
        } else {
            if (pm.getValue() != null && !pm.getValue().toString().trim().equals("")) {
                sb.append(pm.getOperators().getValue()).append(pm.getValue());
            } else {
                throw new IllegalArgumentException("CdType.OG type param can not bank ; 非法的条件查询,CdType.OG类型的条件值不能为空 ;");
            }
        }
    }

    private void setvlcds(StringBuilder sb, Param pm, PropInfo p) {
        if (pm.getOperators().equals(Operate.BETWEEN)) {
            if (pm.getFirstValue() == null || pm.getValue() == null) {
                throw new IllegalArgumentException(String.format("%s BETWEEN param value is not null  ! ", pm.getPname()));
            }
            setcName(sb, pm, p);
            sb.append(pm.getOperators().getValue());
            sb.append(KSentences.POSITION_PLACEHOLDER);
            sb.append(KSentences.AND);
            sb.append(KSentences.POSITION_PLACEHOLDER);

        } else if (pm.getOperators().equals(Operate.IN) || pm.getOperators().equals(Operate.NOT_IN)) {
            if (pm.getInValue() == null || pm.getInValue().size() < 1) {
                throw new IllegalArgumentException(String.format("%s IN param list value size is not zero or null;  %s字段,IN查询条件的List不能为空;", pm.getPname(), pm.getPname()));
            }
            setcName(sb, pm, p);
            sb.append(pm.getOperators().getValue());
            sb.append("(");
            for (int i = 0; i < pm.getInValue().size(); i++) {
                sb.append(KSentences.POSITION_PLACEHOLDER);
                if (i < pm.getInValue().size() - 1) {
                    sb.append(KSentences.COMMA.getValue());
                }
            }
            sb.append(")");

        } else {
            if (pm.getValue() != null && !pm.getValue().toString().trim().equals("")) {
                setcName(sb, pm, p);
                if (pm.getOperators().name().startsWith("C_")) {
                    sb.append(pm.getOperators().getValue()).append("`").append(pm.getValue()).append("`");
                } else {
                    sb.append(pm.getOperators().getValue()).append(KSentences.POSITION_PLACEHOLDER.getValue());
                }

            } else if (pm.getOperators().equals(Operate.EQ) || pm.getOperators().equals(Operate.NOT_EQ)) {
                if (getPmsType(pm) == String.class) {
                    sb.append("(");
                    setcName(sb, pm, p);
                    sb.append(pm.getOperators().getValue()).append("''");
                    if (pm.getOperators().equals(Operate.EQ)) {
                        sb.append(KSentences.OR.getValue());
                    } else {
                        sb.append(KSentences.AND.getValue());
                    }
                }
                if (pm.getOperators().equals(Operate.EQ)) {
                    setcName(sb, pm, p);
                    sb.append(KSentences.IS_NULL.getValue());
                } else {
                    setcName(sb, pm, p);
                    sb.append(KSentences.IS_NOT_NULL.getValue());
                }
                if (getPmsType(pm) == String.class) {
                    sb.append(")");
                }
            } else {
                throw new IllegalArgumentException(String.format("%s %s  param  value  is not null ! ", domainClazz.getSimpleName(), pm.getPname()));
            }
        }
    }

    private Class<?> getPmsType(Param pm) {
        for (PropInfo p : getPropInfos()) {
            if (p.getFieldName().equals(pm.getPname())) {
                return p.getFieldTypeClass();
            }
        }
        throw new IllegalArgumentException(String.format("% field not definition ; %s字段没有定义 ;", pm.getPname(), pm.getPname()));
    }

    private void setcName(StringBuilder sb, Param pm, PropInfo p) {
        if (!pm.getCdType().equals(PmType.FUN)) {
            sb.append("`").append(p.getColumnName()).append("`");
        } else {
            sb.append(pm.getFunName()).append("(");
            sb.append(p.getColumnName());
            sb.append(")");
        }
    }

    protected int setWhereSqlParamValue(Set<Param> pms, PreparedStatement statement, int ix) {
        if (pms != null && pms.size() > 0) {
            for (Param pm : pms) {
                if (pm.getOperators().name().startsWith("C_")) {
                    continue;
                }
                if (pm.getPname() != null && pm.getPname().trim().length() > 0) {
                    do {
                        try {
                            if (!pm.getCdType().equals(PmType.OG)) {
                                if (pm.getOperators().equals(Operate.BETWEEN)) {
                                    statement.setObject(ix++, getParamSqlValue(pm.getFirstValue(), pm.getPname()));
                                    statement.setObject(ix++, getParamSqlValue(pm.getValue(), pm.getPname()));
                                } else if (pm.getOperators().equals(Operate.IN)
                                        || pm.getOperators().equals(Operate.NOT_IN) && pm.getInValue() != null) {
                                    for (Object se : pm.getInValue()) {
                                        statement.setObject(ix++, getParamSqlValue(se, pm.getPname()));
                                    }
                                } else {
                                    if (pm.getValue() != null && !pm.getValue().toString().trim().equals("")) {
                                        statement.setObject(ix++, getParamSqlValue(pm.getValue(), pm.getPname()));
                                    }
                                }
                            }
                        } catch (SQLException e) {
                            e.printStackTrace();
                            throw new IllegalArgumentException(e);
                        }
                        pm = pm.getOrParam();
                    } while (pm != null);

                }
            }

        }
        return ix;

    }

    private Object getParamSqlValue(Object o, String pname) {
        if (o != null && !(o instanceof String) && !(o instanceof Number)) {
            PropInfo pp = getPropInfoByPName(pname);
            if (o.getClass().isEnum() && pp.getFieldTypeClass().isEnum()) {
                EnumType et = pp.getEnumType();
                if (et.equals(EnumType.STRING)) {
                    return o.toString();
                } else {
                    Class<Enum> cls = (Class<Enum>) pp.getFieldTypeClass();
                    return Enum.valueOf(cls, o.toString()).ordinal();
                }
            } else if (pp.getSqlTypes() != null) {
                if (pp.getSqlTypes().equals(Types.DATE)) {
                    Date dt = (Date) o;
                    Date ddd = Date.from(dt.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
                            .atStartOfDay(ZoneId.systemDefault()).toInstant());
                    return ddd;
                }
            }
        }
        return o;
    }

    protected PropInfo getPropInfoByPName(String pname) {
        if (pname != null && pname.trim().length() > 0) {
            Set<PropInfo> pps = getPropInfos();
            for (PropInfo pp : pps) {
                if (pp.getFieldName().equals(pname)) {
                    return pp;
                }
            }
        }
        return null;
    }

    //setting where for statement
    protected void setWhereSqlParamValue(Set<Param> pms, PreparedStatement statement) {
        setWhereSqlParamValue(pms, statement, 1);

    }

    //get table from param ; 根据条件得到数据所在的表
    protected Set<String> getTableNamesByParams(Set<Param> pms) {
        if (pms != null && pms.size() > 0) {
            Entry<String, LinkedHashSet<PropInfo>> tbimp = ConnectionManager.getTbinfo(domainClazz).entrySet().iterator()
                    .next();
            for (Param pm : pms) {
                if (pm.getPname() != null && pm.getPname().trim().length() > 0) {
                    for (PropInfo p : tbimp.getValue()) {
                        if (p.getColumnRule() != null) {
                            if (pm.getPname().equals(p.getFieldName()) && pm.getOrParam() == null) {
                                if (pm.getOperators().equals(Operate.EQ) && pm.getValue() != null) {
                                    String tableName = gettbName(tbimp, pm, p);
                                    if (isContainsTable(tableName)) {
                                        return new HashSet<>(Arrays.asList(tableName));
                                    }
                                } else if (pm.getOperators().equals(Operate.IN) && pm.getInValue() != null
                                        && pm.getInValue().size() > 0) {
                                    Set<String> tbns = new HashSet<>();
                                    for (Object sid : pm.getInValue()) {
                                        if (sid != null) {
                                            String tableName = getTableName(
                                                    getTableMaxIdx(sid, p.getFieldTypeClass(), p.getColumnRule()),
                                                    tbimp.getKey());
                                            if (isContainsTable(tableName)) {
                                                tbns.add(tableName);
                                            }
                                        }
                                    }
                                    if (tbns.size() > 0) {
                                        return tbns;
                                    }
                                } else if (p.getColumnRule().ruleType().equals(RuleType.RANGE)
                                        && pm.getOperators().equals(Operate.BETWEEN) && pm.getValue() != null
                                        && pm.getFirstValue() != null) {
                                    long st = getTableMaxIdx(pm.getFirstValue(), p.getFieldTypeClass(), p.getColumnRule());
                                    long ed = getTableMaxIdx(pm.getValue(), p.getFieldTypeClass(), p.getColumnRule());
                                    Set<String> nms = gettbs(tbimp, st, ed);
                                    if (nms.size() > 0) {
                                        return nms;
                                    }
                                } else if (p.getColumnRule().ruleType().equals(RuleType.RANGE)
                                        && pm.getOperators().equals(Operate.GE) && pm.getValue() != null) {

                                    long st = getTableMaxIdx(pm.getValue(), p.getFieldTypeClass(), p.getColumnRule());
                                    if (st > 0) {
                                        int len = getTableName(st, tbimp.getKey())
                                                .split(KSentences.SHARDING_SPLT.getValue()).length;

                                        long ed = getCurrentTableNames().stream().mapToLong(n -> {
                                            String[] arr = n.split(KSentences.SHARDING_SPLT.getValue());
                                            if (arr.length == len) {
                                                return Long.valueOf(arr[arr.length - 1]);
                                            }
                                            return 0L;
                                        }).max().getAsLong();

                                        Set<String> nms = gettbs(tbimp, st, ed);
                                        if (nms.size() > 0) {
                                            return nms;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return getCurrentTableNames();
    }

    private Set<String> gettbs(Entry<String, LinkedHashSet<PropInfo>> tbimp, long st, long ed) {
        Set<String> nms = new HashSet<>();
        for (long i = st; i <= ed; i++) {
            String tableName = getTableName(i, tbimp.getKey());
            if (isContainsTable(tableName)) {
                nms.add(tableName);
            }
        }
        return nms;
    }

    private String gettbName(Entry<String, LinkedHashSet<PropInfo>> tbimp, Param pm, PropInfo p) {
        return getTableName(getTableMaxIdx(pm.getValue(), p.getFieldTypeClass(), p.getColumnRule()), tbimp.getKey());

    }

    private boolean isContainsTable(String tbname) {
        Iterator<String> ite = getCurrentTableNames().iterator();
        while (ite.hasNext()) {
            String tn = ite.next();
            if (tn.trim().equalsIgnoreCase(tbname.trim())) {
                return true;
            }
        }
        return false;
    }

    private StringBuilder getSelectSql(String tableName, String... strings) {
        StringBuilder sb = new StringBuilder(getPreSelectSql(false, strings));
        sb.append(tableName);
        return sb;
    }

    private String getPreSelectSql(boolean isDistinct, String... strings) {
        StringBuilder sb = new StringBuilder(KSentences.SELECT.getValue());
        if (strings != null && strings.length > 0) {
            if (isDistinct) {
                sb.append(KSentences.DISTINCT.getValue());
            }
            for (int i = 0; i < strings.length; i++) {
                for (PropInfo pi : getPropInfos()) {
                    if (strings[i].equals(pi.getFieldName())) {
                        sb.append(pi.getColumnName());
                        break;
                    }
                }
                if (i < strings.length - 1) {
                    sb.append(KSentences.COMMA.getValue());
                }
            }
        } else {
            sb.append(KSentences.SELECT_ALL);
        }
        sb.append(KSentences.FROM.getValue());
        return sb.toString();
    }

    private String[] getGSelect(String[] gbs, Collection<String> vvs) {
        LinkedHashSet<String> rz = new LinkedHashSet<>();
        for (String g : gbs) {
            rz.add(g.trim());
        }
        if (vvs != null) {
            for (String v : vvs) {
                rz.add(v.trim());
            }
        }
        return rz.toArray(new String[0]);
    }

    //check table is created , is exist
    private boolean isExistTable(String tblname) {
        Set<String> tbns = getCurrentTableNames();
        for (String tn : tbns) {
            if (tn.trim().equalsIgnoreCase(tblname.trim())) {
                return true;
            } else if (tn.trim().contains(".")) {
                if (tn.trim().split("[.]")[1].equalsIgnoreCase(tblname.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void refreshCurrentTables() {
        reFreshTables();
    }

    private boolean autoNextVal(PropInfo p) {
        return GenerationType.AUTO.equals(p.getGeneratorValueAnnoStrategyVal()) || GenerationType.SEQUENCE.equals(p.getGeneratorValueAnnoStrategyVal());
    }

    private List<PropInfo> getDbProps(String tableName, Connection connection) throws SQLException {
        ResultSet crs = connection.getMetaData().getColumns(connection.getCatalog(), null, tableName, null);
        List<PropInfo> cnames = new ArrayList<>();
        while (crs.next()) {
            PropInfo p = new PropInfo(crs.getString("COLUMN_NAME"), crs.getInt("DATA_TYPE"));
            p.setLength(crs.getInt("COLUMN_SIZE"));
            cnames.add(p);
        }
        return cnames;
    }

    private String getPrecisionDatatype(String className) {
        if ("MySQL".equalsIgnoreCase(this.dataBaseTypeName)) {
            return className;
        } else if ("Oracle".equalsIgnoreCase(this.dataBaseTypeName)) {
            if ("double".equalsIgnoreCase(className)) {
                return "float";
            } else {
                return className;
            }
        } else {
            throw new IllegalArgumentException("not support database");
        }

    }

    private boolean sequenceExists(Connection connection, String seqName) throws SQLException {
        PreparedStatement prepare = connection.prepareStatement(SEQUENCE_QUERY);
        prepare.setString(1, seqName);
        boolean isSeqExists = prepare.executeQuery().next();
        return isSeqExists;
    }

    private String getSequenceName(String tableName) {
        //序列名称一定要大写
        String seqName = String.format("%s_%s", tableName, "SEQ").toUpperCase();
        return seqName;
    }

    private void changeToString(PropInfo pi) throws SQLException {
        for (String t : getCurrentTableNames()) {
            String altertablesql = String.format(ALTER_TABLE_MODIFY_COLUMN, t, pi.getColumnName(), getVarchar(pi));
            if (this.getConnectionManager().isShowSql()) {
                log.info(altertablesql);
            }
            this.getConnectionManager().getConnection().prepareStatement(altertablesql).executeUpdate();
        }
    }

    private String getVarchar(PropInfo pi) {
        if ("MySQL".equalsIgnoreCase(this.dataBaseTypeName)) {
            return "VARCHAR(" + pi.getLength() + ")";
        } else if ("Oracle".equalsIgnoreCase(this.dataBaseTypeName)) {
            return "VARCHAR2(" + pi.getLength() + " char)";
        } else {
            throw new IllegalArgumentException("not support database");
        }
    }

    private String getIndexColumns(PropInfo p) {
        StringBuilder sbd = new StringBuilder();
        sbd.append(p.getColumnName());
        if (p.getFieldTypeClass() == String.class && p.getLength() > p.getIndex().length()) {
            if ("MySQL".equalsIgnoreCase(this.dataBaseTypeName)) {
                if (p.getIndex().length() > 0) {
                    sbd.append("(").append(p.getIndex().length()).append(")");
                }
            }
        }
        if (p.getIndex().otherPropName()!=null && p.getIndex().otherPropName().length!=0) {
            for (Other other : p.getIndex().otherPropName()) {
                PropInfo propInfo = getPropInfoByPName(other.name());
                sbd.append(KSentences.COMMA.getValue()).append(propInfo.getColumnName());
                if (other.length() > 0) {
                    sbd.append("(").append(other.length()).append(")");
                }
            }
        }
        return sbd.toString();
    }

    private String getFullTextIndexColumns(PropInfo p) {
        StringBuilder sbd = new StringBuilder();
        MyIndexFullText fullTextIndex = p.getFullTextIndex();
        sbd.append(p.getColumnName());
        if (p.getFieldTypeClass() == String.class && p.getLength() > fullTextIndex.length()) {
            if ("MySQL".equalsIgnoreCase(this.dataBaseTypeName)) {
                if (fullTextIndex.length() > 0) {
                    sbd.append("(").append(fullTextIndex.length()).append(")");
                }
            }
        }
        if (fullTextIndex.otherPropName()!=null && fullTextIndex.otherPropName().length!=0) {
            for (Other other : fullTextIndex.otherPropName()) {
                PropInfo propInfo = getPropInfoByPName(other.name());
                sbd.append(KSentences.COMMA.getValue()).append(propInfo.getColumnName());
                if (other.length() > 0) {
                    sbd.append("(").append(other.length()).append(")");
                }
            }
        }
        return sbd.toString();
    }

    private static ResultSet getTableMeta(Connection conn) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        ResultSet rs = metaData.getTables(conn.getCatalog(), null, null, new String[]{"TABLE"});
        return rs;
    }

    protected void setMaxTableCount(int maxTableCount) {
        this.maxTableCount = maxTableCount;
    }

    public boolean isShowSql() {
        return isShowSql;
    }

    public void setShowSql(boolean showSql) {
        isShowSql = showSql;
    }

    private Boolean getQueryIsRead(){
        boolean transactioning = getConnectionManager().isTransactioning();
        Boolean isRead = true;
        if (transactioning) {
            isRead = false;
        }
        return isRead;
    }

    private String getColumnTypeSelect(String expectColumnType, String customColumnType) {
        if (customColumnType != null) {
            return customColumnType;
        } else {
            return expectColumnType;
        }
    }

//    @Override
//    public List<POJO> getListOrderByTableNameDesc(int curPage, int pageSize,Set<Param> pms,LinkedHashSet<OrderBy> orderbys,String... cls){
//        try {
//            if (curPage < 1 || pageSize < 1) {
//                return new ArrayList<>(0);
//            }
//            if (this.isNotOneResult(pms)) {
//                return new ArrayList<>(0);
//            }
//            //single table
//            if (!isUseGlobalTableId) {
//                return this.getPageList(curPage,pageSize,pms,orderbys,cls);
//            }else{//more table
//                List<POJO> resultList = new ArrayList<>();
//                //对表进行排序, 通过表倒序依次查询获得结果
//                List<String> currentTablesOrderDesc = this.getCurrentTableNamesOrderDesc();
//                for (String tableName : currentTablesOrderDesc) {
//                    Long count = this.getSingleTableCount(true, tableName, pms, null);
//                    if (count > 0) {
//
//                        String whereSqlByParam = getWhereSqlByParam(pms);
//                        String selectpre = getPreSelectSql(false, cls);
//                        String orderBySql = getOrderBySql(orderbys);
//                        String sql = getSingleTableSelectPagingSql(selectpre + tableName + whereSqlByParam + orderBySql, curPage, pageSize);
//                        List<POJO> list = getSingleObject(true, pms, sql, cls);
//                    }else{
//
//                    }
//                    PreparedStatement statement = getStatementBySql(true, sql);
//                    setWhereSqlParamValue(pms, statement);
//                    resultList.addAll(list);
//
//                }
//                return null;
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//            throw new IllegalArgumentException(e);
//        }
//    }


}
