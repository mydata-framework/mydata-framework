package com.mydata.dao.base.impl;

import com.mydata.annotation.ColumnRule;
import com.mydata.dao.base.IMyData;
import com.mydata.em.*;
import com.mydata.exception.ObjectOptimisticLockingFailureException;
import com.mydata.helper.*;
import com.mydata.helper.OrderBy;
import com.mydata.manager.ConnectionManager;
import com.mydata.manager.IConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class MyDataSupport<POJO> implements IMyData<POJO> {
    private static Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    //当前操作的实体对象类型
    private Class<POJO> domainClazz ;
    //当前操作的实体对应的表(准确来说是第一张表)
    private String firstTableName ;
    //当前数据库类型 MySQL Oracle
    private String dataBaseTypeName;
    //showSql
    private boolean isShowSql;
    //ddl
    private boolean isGenerateDdl;
    //连接管理器
    public abstract IConnectionManager getConnectionManager();
    //单表表拆分最大数量
    private volatile int maxTableCount = 1024;
    private static final String ALTER_TABLE_MODIFY_COLUMN = "ALTER TABLE  %s   MODIFY     %s   %s";
    private static final String INDEX_SUBFIX = "_idx";
    private static final String ALTER_TABLE_S_ADD_S = " ALTER  table  %s  add  (%s)";
    private static final String CREATE_INDEX_S = "CREATE      %s   INDEX      %s   ON   %s(%s)";
    private static final String SEQUENCE_QUERY = "select  sequence_name  from  user_sequences  where  sequence_name=?";
    private static final int MAX_IDLE_TABLE_COUNT = 8;
    private static final ForkJoinPool NEW_FIXED_THREAD_POOL = new ForkJoinPool(Integer.min(Runtime.getRuntime().availableProcessors() * 4, 32));
    //实体类对应的当前已经分表的表名集合
    private volatile static ConcurrentHashMap<Class<?>, ConcurrentSkipListSet<String>> DOMAINCLASS_TABLES_MAP = new ConcurrentHashMap<Class<?>, ConcurrentSkipListSet<String>>();


    @PostConstruct
    public void init() {
        try {
            //基本数据
            this.domainClazz = MyDataHelper.getDomainClassByDaoClass(this.getClass());
            this.firstTableName = MyDataHelper.getFirstTableName(this.domainClazz);
            this.dataBaseTypeName = MyDataHelper.getDataBaseTypeName(getConnectionManager());
            this.isShowSql = getConnectionManager().isShowSql();
            this.isGenerateDdl = getConnectionManager().isDdl();
            //实体信息表
            final Set<PropInfo> pps = getPropInfos();
            //如果开启ddl,则自动创建表
            if (this.isGenerateDdl) {
                createFirstTable(pps);
            }
            //设置实体信息的 数据库字段类型
            setSqlType(pps);
        } catch (Exception e) {
            e.printStackTrace();
            log.info("[ MyData init error]");
        }
    }

    //得到字段信息
    protected Set<PropInfo> getPropInfos() {
        //当前类对于可能多个表, 至少一个, 如果有分表可能是多个, 然后取出第一个表名队员的数据信息,即为实体的描述
        Map<String, LinkedHashSet<PropInfo>> tbinfo = ConnectionManager.getTbinfo(this.domainClazz);
        LinkedHashSet<PropInfo> propInfos = tbinfo.entrySet().iterator().next().getValue();
        return propInfos;
    }

    //创建表
    private void createFirstTable(Set<PropInfo> propInfos) {
        try {
            String tableName = this.firstTableName;
            Connection connection = this.getConnectionManager().getConnection();
            if ("Oracle".equalsIgnoreCase(this.dataBaseTypeName)) {
                if (propInfos.stream().anyMatch(p -> autoNextVal(p))) {
                    String seqName = getSequenceName(tableName);
                    boolean isSeqExists = sequenceExists(connection, seqName);
                    // 如果不存在就自动创建一个
                    if (!isSeqExists) {
                        String createseqsql = String.format("%s %s", KSentences.CREATE_SEQUENCE, seqName);
                        connection.prepareStatement(createseqsql).executeUpdate();
                        if (this.getConnectionManager().isShowSql()) {
                            log.info(createseqsql);
                        }
                    }

                }
            } else if ("MySQL".equalsIgnoreCase(this.dataBaseTypeName)) {
                // 判断是否采用全局ID表生成主键( 如果有字段上被Table标记,那么就是要加全局id,意味着要分表 )
                boolean isUseGlobalTableId = propInfos.stream().anyMatch(p -> GenerationType.TABLE.equals(p.getGeneratorValueAnnoStrategyVal()));
                if (isUseGlobalTableId) {
                    //TUSER_SEQ_ID
                    String idTableName = getIdTableName(tableName);
                    //如果全局id表不存在则执行创建操作
                    if (!isTableExists(connection, idTableName)) {
                        //CREATE TABLE TUSER_SEQ_ID(SID BIGINT PRIMARY  KEY AUTO_INCREMENT) 创建一个自增表,作为分表的统一主键
                        String createIdTableSql = String.format("%s %s(SID BIGINT PRIMARY  KEY AUTO_INCREMENT)", KSentences.CREATE_TABLE, idTableName);
                        connection.prepareStatement(createIdTableSql).executeUpdate();
                        if (this.isShowSql) { log.info(createIdTableSql); }
                        // 如果全局ID表有初始值就使用初始值初始化,初始值必须小于10位的数值
                        Optional<PropInfo> opst =
                                propInfos.stream().filter(
                                        id ->
                                                id.getGeneratorValueAnnoGeneratorVal() != null
                                                    &&
                                                id.getGeneratorValueAnnoGeneratorVal().length() < 10
                                                    &&
                                                Pattern.matches("\\d+", id.getGeneratorValueAnnoGeneratorVal().trim())
                                ).findFirst();
                        //如果存在分表字段,就创建全局id表
                        if (opst.isPresent()) {
                            //INSERT INTO TUSER_SEQ_ID SID VALUES( 10 )
                            String insertIdTableSql = genInsertIdTableSql(idTableName, opst.get().getGeneratorValueAnnoGeneratorVal().trim());
                            connection.prepareStatement(insertIdTableSql).executeUpdate();
                            if (this.isShowSql){ log.info(insertIdTableSql); }
                        }
                    } else {
                        // 清理旧的过期已被使用的ID
                        // DELETE FROM TUSER_SEQ_ID
                        String cleanIdSql = String.format("%s %s", KSentences.DELETE_FROM, idTableName);
                        connection.prepareStatement(cleanIdSql).executeUpdate();
                        if (this.isShowSql) { log.info(cleanIdSql); }
                    }
                }
            }
            //如果表不存在,就创建表
            if (!isTableExists(connection, tableName)) {
                //创建表
                createTableBySql(tableName);
                //获取表的切分规则
                ColumnRule columnRule = getColumnRule();
                //如果存在需要切分,执行切分
                if (columnRule != null && columnRule.ruleType().equals(RuleType.MOD)) {
                    int maxIdleTablecount = getMaxIdleTablecount(columnRule);
                    for (int i = 1; i < maxIdleTablecount ; i++) {
                        String ctbname = getTableName(Long.valueOf(i), tableName);
                        executeCreate(tableName, ctbname);
                    }
                }
            } else {
                //如果表已存在
                List<PropInfo> cnames = getDbProps(tableName, connection);
                if (cnames.size() < 1) {
                    cnames = getDbProps(tableName.toUpperCase(), connection);
                }
                List<PropInfo> ncns = new ArrayList<>();
                a:
                for (PropInfo pi : propInfos) {
                    for (PropInfo cn : cnames) {
                        if (cn.getCname().equalsIgnoreCase(pi.getCname())) {
                            if (cn.getSqlTypes() == Types.VARCHAR && cn.getLength().intValue() < pi.getLength()) {
                                changeToString(pi);
                            } else if ((cn.getSqlTypes() == Types.INTEGER || cn.getSqlTypes() == Types.BIGINT)
                                    && (pi.getType() == Double.class || pi.getType() == Float.class)) {
                                for (String t : getCurrentTables()) {
                                    String altertablesql = String.format(ALTER_TABLE_MODIFY_COLUMN, t, cn.getCname(),
                                            getPrecisionDatatype(pi.getType().getSimpleName()));
                                    if (this.getConnectionManager().isShowSql()) {
                                        log.info(altertablesql);
                                    }
                                    this.getConnectionManager().getConnection().prepareStatement(altertablesql)
                                            .executeUpdate();
                                }
                            } else if ((cn.getSqlTypes() == Types.INTEGER || cn.getSqlTypes() == Types.BIGINT)
                                    && pi.getType() == String.class && !pi.getIsLob()) {
                                changeToString(pi);
                            } else if (cn.getSqlTypes() == Types.DATE && pi.getType() == Date.class) {
                                Field fd = domainClazz.getDeclaredField(pi.getPname());
                                Temporal tp = fd.getAnnotation(Temporal.class);
                                if (tp != null && tp.value().equals(TemporalType.TIMESTAMP)) {
                                    for (String t : getCurrentTables()) {
                                        String altertablesql = String.format(ALTER_TABLE_MODIFY_COLUMN, t,
                                                cn.getCname(), getTimestampType());
                                        if (this.getConnectionManager().isShowSql()) {
                                            log.info(altertablesql);
                                        }
                                        this.getConnectionManager().getConnection().prepareStatement(altertablesql)
                                                .executeUpdate();
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
                        for (String t : getCurrentTables()) {
                            try {
                                String sql = String.format(ALTER_TABLE_S_ADD_S, t, avl);
                                if (this.getConnectionManager().isShowSql()) {
                                    log.info(sql);
                                }
                                this.getConnectionManager().getConnection().prepareStatement(sql).executeUpdate();

                            } catch (Throwable e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
            createIndex(tableName);
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } finally {
            this.getConnectionManager().closeConnection();
        }
    }

    //表是否存在
    private boolean isTableExists(Connection connection, String tableName) throws SQLException {
        ResultSet rs = getTableMeta(connection);
        //循环所有的表,看当前表是否存在
        while (rs.next()) {
            String rzn = rs.getString("TABLE_NAME");
            if (rzn.equalsIgnoreCase(tableName)) {
                return true;
            }
        }
        return false;
    }

    //设置当前实体每个字段对于的数据库字段类型 ( 数据库字段类型 )
    private void setSqlType(Set<PropInfo> pps) {
        try {
            Connection connection = this.getConnectionManager().getConnection();
            ResultSet crs = connection.getMetaData().getColumns(connection.getCatalog(), null, this.firstTableName,
                    null);
            while (crs.next()) {
                for (PropInfo o : pps) {
                    if (crs.getString("COLUMN_NAME").equals(o.getCname())) {
                        o.setSqlTypes(crs.getInt("DATA_TYPE"));
                        break;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } finally {
            this.getConnectionManager().closeConnection();
        }
    }

    private String getIdTableName(String tableName) {
        // TUSER_SEQ_ID
        return String.format("%s_%s_%s", tableName, "SEQ", "ID").toUpperCase();
    }

    private String genInsertIdTableSql(String idTableName, String valueOfInsert) {
        // INSERT INTO ID_TABLE VALUES(%s)
        String insertIdtable = String.format("%s %s %s", KSentences.INSERT, idTableName,String.format("  VALUES(%s)", valueOfInsert));
        return insertIdtable;
    }

    private boolean createTableBySql(String tableName) throws SQLException {
        String csql = createTable(tableName);
        if (csql != null && csql.trim().length() > 0) {
            // 执行建表语句
            this.getConnectionManager().getConnection().prepareStatement(csql).executeUpdate();
            if (isShowSql){ log.info(csql);}
            // 创建索引
            createIndex(tableName);
            return true;
        } else {
            return false;
        }
    }

    //根据实体自动创建表，默认支持MYSQL，如果需要支持其他数据库，请在子类重写这个方法
    protected String createTable(String tableName) {
        Set<PropInfo> props = getPropInfos();
        if (props.size() > 0) {
            //CREATE TABLE USER (
            StringBuilder ctbsb = new StringBuilder(KSentences.CREATE_TABLE.getValue());
            ctbsb.append(tableName).append("(");
            Iterator<PropInfo> psItrat = props.iterator();
            while (psItrat.hasNext()) {
                PropInfo p = psItrat.next();
                // ID BIGINT(20) PRIMARY KEY AUTO_INCREMTN COMMENT 'primaryKeyId',
                ctbsb.append(getColumnLine(p));
                if (psItrat.hasNext()) {
                    // ,
                    ctbsb.append(KSentences.COMMA.getValue());
                }
            }
            // )
            ctbsb.append(")");
            return ctbsb.toString();
        }
        return "";
    }
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
        ctsb.append(p.getCname()).append(" ");
        if (p.getType() == Integer.class) {
            ctsb.append("INT");
        } else if (p.getType() == Float.class) {
            ctsb.append("FLOAT");
        } else if (p.getType() == Long.class) {
            ctsb.append("BIGINT");
        } else if (p.getType() == Double.class) {
            ctsb.append("Double");
        } else if (p.getType() == Boolean.class) {
            ctsb.append("BIT");
        } else if (p.getType() == Date.class) {
            try {
                Field fd = domainClazz.getDeclaredField(p.getPname());
                Temporal tp = fd.getAnnotation(Temporal.class);
                if (tp != null && tp.value().equals(TemporalType.TIMESTAMP)) {
                    ctsb.append(getTimestampType());
                } else if (tp != null && tp.value().equals(TemporalType.TIME)) {
                    ctsb.append("TIME");
                } else {
                    ctsb.append("DATE");
                }
            } catch (NoSuchFieldException | SecurityException e) {
                e.printStackTrace();
                throw new IllegalStateException(e);
            }
        } else if (p.getType() == Time.class) {
            ctsb.append("TIME");
        } else if (p.getType() == Timestamp.class) {
            ctsb.append(getTimestampType());
        } else if (p.getType() == String.class) {
            if (p.getIsLob()) {
                ctsb.append("LONGTEXT");
            } else {
                ctsb.append("VARCHAR(").append(p.getLength()).append(")");
            }
        } else if (p.getType() == byte[].class) {
            ctsb.append("LONGBLOB");
        } else if (p.getType().isEnum()) {
            try {
                Field fd = domainClazz.getDeclaredField(p.getPname());
                Enumerated enm = fd.getAnnotation(Enumerated.class);
                if (enm != null && enm.value() == EnumType.STRING) {
                    ctsb.append("VARCHAR(").append(p.getLength()).append(")");
                } else {
                    ctsb.append("INT");
                }
            } catch (NoSuchFieldException | SecurityException e) {
                e.printStackTrace();
                throw new IllegalStateException(e);
            }
        } else {
            String ermsg = "POJO属性类型并不支持" + p.getType();
            log.error(ermsg);
            throw new IllegalStateException(ermsg);
        }

        if (p.getIsPrimarykey()) {
            ctsb.append("  PRIMARY KEY  ");
            String autoincrement = "  AUTO_INCREMENT  ";
            if (GenerationType.IDENTITY.equals(p.getGeneratorValueAnnoStrategyVal())) {
                ctsb.append(autoincrement);
            } else {
                if (GenerationType.AUTO.equals(p.getGeneratorValueAnnoStrategyVal())) {
                    ctsb.append(autoincrement);
                }
            }
        } else {
            if (p.getIsNotNull()) {
                ctsb.append("  NOT NULL  ");
            }
            if (p.getIsUnique()) {
                ctsb.append("  UNIQUE  ");
            }
        }
        if (p.getComment()!=null&&!"".equals(p.getComment())) {
            ctsb.append(" ").append(KSentences.COMMENT.getValue()).append(" '").append(p.getComment()).append("' ");
        }
        return ctsb.toString();
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
        sb.append(p.getCname()).append("   ");
        if (p.getType() == Integer.class) {
            sb.append("number(10,0)");
        } else if (p.getType() == Float.class) {
            sb.append("float");
        } else if (p.getType() == Long.class) {
            sb.append("number(19,0)");
        } else if (p.getType() == Double.class) {
            sb.append("float");
        } else if (p.getType() == Boolean.class) {
            sb.append("number(1,0)");
        } else if (p.getType() == Date.class) {
            try {
                Field fd = domainClazz.getDeclaredField(p.getPname());
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
        } else if (p.getType() == Time.class) {
            sb.append("date");
        } else if (p.getType() == Timestamp.class) {
            sb.append("timestamp");
        } else if (p.getType() == String.class) {
            if (p.getIsLob()) {
                sb.append("clob");
            } else {// 存储字符长度跟字节无关
                sb.append("varchar2(").append(p.getLength()).append(" char)");
            }
        } else if (p.getType() == byte[].class) {
            sb.append("blob");
        } else if (p.getType().isEnum()) {
            try {
                Field fd = domainClazz.getDeclaredField(p.getPname());
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
            String ermsg = "POJO属性类型并不支持" + p.getType();
            log.error(ermsg);
            throw new IllegalStateException(ermsg);
        }
        if (p.getIsPrimarykey()) {
            sb.append("  PRIMARY KEY  ");
        } else {

            if (p.getIsNotNull()) {
                sb.append("  NOT NULL  ");
            }
            if (p.getIsUnique()) {
                sb.append("  UNIQUE  ");
            }

        }
        return sb.toString();
    }
    //获取最大切分表数量
    private int getMaxIdleTablecount(ColumnRule crn) {
        if (crn.ruleType().equals(RuleType.MOD)) {
            //如果按照取余,最大分表数位1024,以设置最低为准
            return Long.valueOf(Math.min(maxTableCount, crn.value())).intValue();
        } else {
            return MAX_IDLE_TABLE_COUNT;
        }
    }
    //创建索引
    private void createIndex(String tableName) throws SQLException {
        //遍历props
        for (PropInfo prop : getPropInfos()) {
            //当前字段存在索引
            if (indexIsExist(tableName, prop)) {
                //循环当前实体对于的所有表(可能存在分表就会存在多个表)
                for (String tableNameOfTables : getCurrentTables()) {
                    //查看当前表是否存在这个索引
                    if (indexIsExist(tableNameOfTables, prop)) {
                        //如果不存在,就创建索引
                        try {
                            // 当前索引的名称
                            // CREATE $UNIQUE$ INDEX $idcard_inx$ ON $User$($idcard(20),age$)"
                            String sql = String.format(
                                                        CREATE_INDEX_S,
                                                        (prop.getIndex().unique() ? KSentences.UNIQUE : ""),
                                                        getIndexName(prop),
                                                        tableNameOfTables,
                                                        getIndexColumns(prop)
                                                    );
                            //执行索引插入
                            this.getConnectionManager().getConnection().prepareStatement(sql).executeUpdate();
                            if (this.isShowSql) { log.info(sql); }
                        } catch (Throwable e) {
                            e.printStackTrace();
                            log.error("创建索引报错", e);
                        }
                    }
                }
            }
        }
    }
    protected Set<String> getCurrentTables() {
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
    private boolean indexIsExist(String tableName, PropInfo prop) throws SQLException {
        return indexIsExistByTableName(tableName, prop) && indexIsExistByTableName(tableName.toUpperCase(), prop);
    }
    private boolean indexIsExistByTableName(String tbn, PropInfo prop) throws SQLException {
        // 是否创建索引
        if (prop.getIndex() != null) {
            // 当前索引的名称
            String idxName = getIndexName(prop);
            /**
             * 统计目前数据索引数据
             */
            Map<String, String> grps = new HashMap<>(5);
            ResultSet saa = this.getConnectionManager().getConnection().getMetaData().getIndexInfo(null, null, tbn,
                    prop.getIndex().unique(), false);

            while (saa.next()) {
                String idn = saa.getString("INDEX_NAME");
                if (idn != null) {
                    if (idxName.equalsIgnoreCase(idn)) {
                        return false;
                    }
                    String cn = saa.getString("COLUMN_NAME");
                    if (grps.get(idn) != null) {
                        grps.put(idn, grps.get(idn) + cn);
                    } else {
                        grps.put(idn, cn);
                    }
                    if (this.dataBaseTypeName.equalsIgnoreCase("Oracle") && idn.startsWith("SYS_")
                            && cn.equalsIgnoreCase(prop.getCname())) {
                        return false;
                    }
                }
            }
            PropInfo propInfo = getPropInfoByPName(prop.getIndex().otherPropName());
            if (!grps.containsKey(idxName)
                    && !grps.containsValue(prop.getCname() + (propInfo == null ? "" : propInfo.getCname()))) {
                return true;
            }
        }
        return false;
    }
    private String getIndexName(PropInfo p) {
        String idxName =
                p.getIndex().name().equals("")
                    ?
                String.format
                            ("%s%s",
                                p.getCname() +
                                (
                                    p.getIndex().otherPropName() != null && p.getIndex().otherPropName().length() > 0
                                        ?
                                    "_" + p.getIndex().otherPropName().replace(",","_")
                                        :
                                    ""
                                ),
                                this.INDEX_SUBFIX
                            )
                    :
                p.getIndex().name();
        return idxName;
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
            this.getConnectionManager().closeConnection();
        }
        return DOMAINCLASS_TABLES_MAP.get(domainClazz);
    }
    //TODO ===
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
        if (getCurrentTables().size() < 1) {
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
                            if (p.getPname().equals(en.getKey())) {
                                buf.append(p.getCname()).append(KSentences.EQ.getValue())
                                        .append(KSentences.POSITION_PLACEHOLDER.getValue());
                                if (ite.hasNext()) {
                                    buf.append(KSentences.COMMA.getValue());
                                }
                            }
                        }
                    }
                    buf.append(getWhereSqlByParam(pms));
                    String sql = buf.toString();
                    PreparedStatement statement = getStatementBySql(false, sql);
                    if (this.getConnectionManager().isShowSql()) {
                        log.info(sql);
                    }
                    setWhereSqlParamValue(pms, statement, setUpdateNewValues(newValues, statement));
                    ttc += statement.executeUpdate();
                }
                return ttc;
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } finally {
            this.getConnectionManager().closeConnection();

        }
        return 0;
    }


    @Override
    public Integer delete(Set<Param> pms) {
        if (getCurrentTables().size() < 1) {
            return 0;
        }
        return deleteByCondition(pms);
    }

    // 判断是否为日期时间类型
    private boolean isDate(String property) {
        for (PropInfo p : getPropInfos()) {
            if (p.getPname().equals(property)) {
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
        return getDateFuncValue(pms, property, StatisticsType.MIN);
    }

    @Override
    public Date getMaxDate(Set<Param> pms, String property) {
        return getDateFuncValue(pms, property, StatisticsType.MAX);
    }

    @Override
    public <T> T nativeQuery(String sql, Object[] pms, Class<T> resultClass) {
        try {
            T t = getT(resultClass);
            PreparedStatement st = getPreparedStatement(sql, pms);
            ResultSet rs = st.executeQuery();
            if (t instanceof String || t instanceof Number || t instanceof Boolean || t instanceof Date) {
                if (rs.next()) {
                    t = getRT(resultClass, t, rs);
                }else {
                    t = null;
                }
                return t;
            }
            else {
                Field[] declaredFields = resultClass.getDeclaredFields();
                if (rs.next()) {
                    return getRTObj(declaredFields,resultClass, t, rs);
                }else {
                    return null;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> List<T> nativeQueryList(String sql, Object[] pms, Class<T> resultClass){
        try {
            T t = getT(resultClass);
            PreparedStatement st = getPreparedStatement(sql, pms);
            ResultSet rs = st.executeQuery();
            List<T> tList = new ArrayList<>();
            if (t instanceof String || t instanceof Number || t instanceof Boolean || t instanceof Date) {
                while (rs.next()){
                    tList.add(getRT(resultClass, getT(resultClass), rs));
                }
                return tList;
            }
            else {
                Field[] declaredFields = resultClass.getDeclaredFields();
                while (rs.next()) {
                    tList.add(getRTObj(declaredFields,resultClass, getT(resultClass), rs));
                }
                return tList;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> PageData<T> nativeQueryPage(int curPage, int pageSize, String sql, Object[] pms, Class<T> result){
        String countSql = "SELECT COUNT(1) FROM ("+sql+") t";//KSentences.SELECT + KSentences.COMMA
        Long totalCount = this.nativeQuery(countSql, pms, Long.class);
        if (totalCount == 0) {
            return new PageData<>(curPage, pageSize, totalCount, new ArrayList<>(0));
        }
        int startIndex = (curPage - 1) * pageSize;
        String limitSql = sql+KSentences.LIMIT + startIndex + KSentences.COMMA + pageSize ;
        List<T> dataList = nativeQueryList(limitSql , pms , result);
        return new PageData<T>(curPage, pageSize, totalCount, dataList);
    }

    private <T> T getT(Class<T> resultClass) throws InstantiationException, IllegalAccessException {
        T t = null;
        Integer izreo = 0;
        String zreo = "0";
        if ( resultClass.equals(Byte.class)){ t = (T)new Byte(zreo); }
        else if (resultClass.equals(Short.class)){ t = (T)new Short(zreo); }
        else if (resultClass.equals(Integer.class)) { t = (T)new Integer(zreo); }
        else if (resultClass.equals(Long.class)) { t = (T)new Long(zreo); }
        else if (resultClass.equals(Float.class)) { t = (T)new Float(zreo); }
        else if (resultClass.equals(Double.class)) { t = (T)new Double(zreo); }
        else if (resultClass.equals(BigDecimal.class)) { t = (T)new BigDecimal(zreo); }
        else if (resultClass.equals(Boolean.class)){t = (T)new Boolean(false); }
        else if (resultClass.equals(java.sql.Date.class)){t = (T)new java.sql.Date(izreo);}
        else if (resultClass.equals(Timestamp.class)){ t = (T)new Timestamp(izreo);}
        else if (resultClass.equals(Time.class)){ t = (T)new Time(izreo);}
        else {
            t = resultClass.newInstance();
        }
        if (t instanceof Collection || t instanceof Map) {
            String error = "NOT SUPPORT resultClass  OF " + resultClass;
            log.error(error);
            throw new IllegalStateException(error);
        }
        return t;
    }

    private PreparedStatement getPreparedStatement(String sql, Object[] pms) throws SQLException {
        if (this.isShowSql) {
            log.info(sql);
            if (pms!=null){
                for (int i = 0; i < pms.length; i++) {
                    log.info("param"+(i+1)+"="+pms[i].toString());
                }
            }
        }
        PreparedStatement st = this.getConnectionManager().getConnection().prepareStatement(sql);
        if (pms != null) {
            for (int i = 1; i < pms.length + 1; i++) {
                Object o = pms[i - 1];
                if (o instanceof String) { st.setString(i, (String) o); }
                else if (o instanceof Long) { st.setLong(i, (Long) o); }
                else if (o instanceof Integer) { st.setInt(i, (Integer) o); }
                else if (o instanceof Boolean) { st.setBoolean(i, (Boolean) o); }
                else if (o instanceof Double) { st.setDouble(i, (Double) o); }
                else if (o instanceof Date) { st.setDate(i, (java.sql.Date) o); }
                else if (o instanceof BigDecimal) { st.setBigDecimal(i, (BigDecimal) o); }
                else if (o instanceof Float) { st.setFloat(i, (Float) o); }
                else if (o instanceof Time) { st.setTime(i, (Time)o); }
                else if (o instanceof Timestamp) { st.setTimestamp(i, (Timestamp) o); }
                else if (o instanceof Blob) { st.setBlob(i, (Blob)o); }
                else if (o instanceof Byte) { st.setByte(i, (Byte)o); }
                else if (o instanceof Short) { st.setShort(i, (Short) o); }
                else{
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

    private <T> T getRTObj(Field[] declaredFields,Class<T> resultClass, T t, ResultSet rs) throws SQLException {
        for (int i = 0; i < declaredFields.length; i++) {
            Field field = declaredFields[i];
            if (field.isAnnotationPresent(Transient.class)) {
                continue;
            }
            String name = field.getName();
            if (field.isAnnotationPresent(Column.class)) {
                Column column = field.getAnnotation(Column.class);
                if (column.name()!=null&&!column.name().trim().equals("")){
                    name = column.name();
                }
            }
            int columnIndex = rs.findColumn(name);
            Class<?> type = field.getType();
            Object value = null;
            if (type.equals(Byte.class)) { value = rs.getByte(columnIndex); }
            else if (type.equals(Short.class)) { value = rs.getShort(columnIndex); }
            else if (type.equals(Integer.class)) { value = rs.getInt(columnIndex); }
            else if (type.equals(Long.class)) { value = rs.getLong(columnIndex); }
            else if (type.equals(String.class)) { value = rs.getString(columnIndex); }
            else if (type.equals(Boolean.class)) { value = rs.getBoolean(columnIndex); }
            else if (type.equals(BigDecimal.class)) { value = rs.getBigDecimal(columnIndex); }
            else if (type.equals(Double.class)) { value = rs.getDouble(columnIndex); }
            else if (type.equals(Float.class)) { value = rs.getFloat(columnIndex); }
            else if (type.equals(Date.class)) { value = rs.getDate(columnIndex); }
            else if (type.equals(Timestamp.class)) { value = rs.getTimestamp(columnIndex); }
            else if (type.equals(Time.class)) { value = rs.getTime(columnIndex); }
            else{ continue;}
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
            if (this.isShowSql) {
                log.info(sql);
                for (int i = 0; i < pms.length; i++) {
                    log.info("param"+(i+1)+"="+pms[i].toString());
                }
            }
            PreparedStatement st = this.getConnectionManager().getConnection().prepareStatement(sql);
            for (int i = 1; i < pms.length + 1; i++) {
                Object o = pms[i - 1];
                     if (o instanceof String) { st.setString(i, (String) o); }
                else if (o instanceof Long) { st.setLong(i, (Long) o); }
                else if (o instanceof Integer) { st.setInt(i, (Integer) o); }
                else if (o instanceof Boolean) { st.setBoolean(i, (Boolean) o); }
                else if (o instanceof Double) { st.setDouble(i, (Double) o); }
                else if (o instanceof BigDecimal) { st.setBigDecimal(i, (BigDecimal) o); }
                else if (o instanceof Float) { st.setFloat(i, (Float) o); }
                else if (o instanceof Date) { st.setDate(i, (java.sql.Date) o); }
                else if (o instanceof Timestamp) { st.setTimestamp(i, (Timestamp) o); }
                else if (o instanceof Time) { st.setTime(i, (Time)o); }
                else if (o instanceof Blob) { st.setBlob(i, (Blob)o); }
                else if (o instanceof Byte) { st.setByte(i, (Byte)o); }
                else if (o instanceof Short) { st.setShort(i, (Short) o); }
                else{
                    String error = "NOT SUPPORT TYPE OF " + o.getClass();
                    log.error(error);
                    throw new IllegalStateException(error);
                }
            }
            return st.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Date getDateFuncValue(Set<Param> pms, String property, StatisticsType st) {
        try {
            if (isDate(property)) {
                List<Future<QueryVo<ResultSet>>> rzts = getFunctionValues(pms, property, st);
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
                            throw new IllegalArgumentException(String.format("Date类型不支持%s！", st));
                        }
                    }
                } else {
                    return null;
                }
            } else {
                throw new IllegalArgumentException("字段必须是Date类型！");
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            this.getConnectionManager().closeConnection();
        }
    }

    @Override
    public Double getStatisticsValue(StatisticsType functionName ,String property , Set<Param> pms) {
        if (property != null && functionName != null) {
            if (getCurrentTables().size() < 1) {
                return 0d;
            }
            try {
                List<Future<QueryVo<ResultSet>>> rzts = getFunctionValues(pms, property, functionName);
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
                this.getConnectionManager().closeConnection();
            }
        }
        return 0D;
    }

    private List<Future<QueryVo<ResultSet>>> getFunctionValues(Set<Param> pms, String property,
                                                               StatisticsType functionName) throws SQLException {
        StringBuffer sb = new StringBuffer(KSentences.SELECT.getValue());
        sb.append(functionName);
        for (PropInfo p : getPropInfos()) {
            if (p.getPname().equals(property.trim())) {
                sb.append(KSentences.LEFT_BRACKETS.getValue()).append(p.getCname())
                        .append(KSentences.RIGHT_BRACKETS.getValue()).append(KSentences.FROM);
                break;
            }
        }

        Set<String> tbs = getTableNamesByParams(pms);
        List<Future<QueryVo<ResultSet>>> rzts = invokeall(true, pms, sb.toString(), tbs);
        return rzts;
    }

    @Override
    public Long getCount(Set<Param> pms, String... distincts) {
        return getCountPerTable(true, pms, distincts);
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

    private List<Future<QueryVo<ResultSet>>> invokeall(boolean isRead, Set<Param> pms, String sqlselect,
                                                       Set<String> tbs) throws SQLException {
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
        return getRztPos(false, true, pms, cls);
    }

    @Override
    public List<POJO> getAll(String... cls) {
        return getRztPos(false, true, null, cls);
    }

    @Override
    public List<POJO> getListFromMater(Set<Param> pms, String... cls) {
        return getRztPos(false, false, pms, cls);
    }

    @Override
    public List<POJO> getList( Set<Param> pms, boolean isDistinct,String... cls) {
        return getRztPos(isDistinct, true, pms, cls);
    }

    @Override
    public List<POJO> getListFromMater(Set<Param> pms, boolean isDistinct,String... cls) {
        return getRztPos(isDistinct, false, pms, cls);
    }

    @Override
    public List<POJO> getListOrderBy(Set<Param> pms, LinkedHashSet<OrderBy> orderbys, String... cls) {
        if (getCurrentTables().size() < 1) {
            return new ArrayList<>(0);
        }
        return getRztPos(true, 1, Integer.MAX_VALUE / getCurrentTables().size(), orderbys, pms, cls);
    }

    @Override
    public List<POJO> getPageList(int curPage, int pageSize, Set<Param> pms, LinkedHashSet<OrderBy> orderbys, String... cls) {
        return getRztPos(true, curPage, pageSize, orderbys, pms, cls);
    }

    @Override
    public List<POJO> getPageListFromMaster(int curPage, int pageSize,Set<Param> pms, LinkedHashSet<OrderBy> orderbys, String... cls) {
        return getRztPos(false, curPage, pageSize, orderbys, pms, cls);
    }

    @Override
    public PageData<POJO> getPageInfoFromMaster(Set<Param> pms, int curPage, int pageSize, String... cls) {
        return getListFromNotSorted(false, curPage, pageSize, pms, cls);
    }

    @Override
    public PageData<POJO> getPageInfo(int curPage, int pageSize,Set<Param> pms, String... cls) {
        return getListFromNotSorted(true, curPage, pageSize, pms, cls);
    }

    @Override
    public List<POJO> getPageListFromMaster(int curPage, int pageSize, Set<Param> pms, String... cls) {
        return getListFromNotSorted(false, curPage, pageSize, pms, cls).getDataList();
    }

    @Override
    public List<POJO> getPageList(int curPage, int pageSize, Set<Param> pms,  String... cls) {
        return getListFromNotSorted(true, curPage, pageSize, pms, cls).getDataList();
    }

    @Override
    public PageData<Object[]> getGroupPageInfo(int curPage, int pageSize, Set<Param> pms,LinkedHashSet<OrderBy> orderbys, LinkedHashMap<String, String> funs, String... groupby) {
        if (pms == null) {
            pms = new HashSet<>();
        }
        Long groupbyCount = getGroupbyCount(new HashSet<>(pms), groupby);
        if (groupbyCount > 0) {
            return new PageData<>(curPage, pageSize, groupbyCount, getGroupPageList(curPage, pageSize, pms ,orderbys, funs, groupby));
        } else {
            return new PageData<>(curPage, pageSize, groupbyCount, new ArrayList<>(0));
        }
    }

    @Override
    public Long getGroupbyCount(Set<Param> pms, String... groupby) {
        return groupcount(true, pms, groupby);

    }

    @Override
    public Long getGroupbyCountFromMaster(Set<Param> pms, String... groupby) {
        return groupcount(false, pms, groupby);

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
        if (groupby == null || groupby.length == 0 || getCurrentTables().size() < 1) {
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
                if (prop.getPname().equals(groupby[0].trim())) {
                    sqlsb.append(prop.getCname());
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
            if (this.getConnectionManager().isShowSql()) {
                log.info(sql);
            }
            int ix = 1;
            for (String tn : tbns) {
                ix = setWhereSqlParamValue(pms, statement, ix);
            }
            setWhereSqlParamValue(hvcs, statement, ix);
            ResultSet rs = statement.executeQuery();
            if (rs.next()) {
                return rs.getObject(1, Long.class);
            }

            return 0L;
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } finally {
            this.getConnectionManager().closeConnection();
        }
    }

    private String groupbysql(String[] groupby) {

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < groupby.length; i++) {
            String g = groupby[i];
            for (PropInfo p : getPropInfos()) {
                if (p.getPname().equals(g)) {
                    sb.append(p.getCname());
                }
            }
            if (i < groupby.length - 1) {
                sb.append(KSentences.COMMA.getValue());
            }
        }
        return sb.toString();
    }

    @Override
    public List<Object[]> getGroupPageList(
            int curPage, int pageSize,
            Set<Param> pms,
            LinkedHashSet<OrderBy> orderbys,
            LinkedHashMap<String, String> funs, String... groupby) {
        return grouplist(true, curPage, pageSize, orderbys, pms, funs, groupby);
    }

    @Override
    public List<Object[]> getGroupPageListFromMaster(int curPage, int pageSize,Set<Param> pms,LinkedHashSet<OrderBy> orderbys, LinkedHashMap<String, String> funs, String... groupby) {
        return grouplist(false, curPage, pageSize, orderbys, pms, funs, groupby);
    }

    private List<Object[]> grouplist(boolean readOnly, int curPage, int pageSize, LinkedHashSet<OrderBy> orderbys, Set<Param> pms, LinkedHashMap<String, String> funs, String... groupby) {
        try {
            if (curPage < 1 || pageSize < 1 || groupby == null || groupby.length == 0 || getCurrentTables().size() < 1) {
                return new ArrayList<>(0);
            }
            if (pms != null) {
                pms = new HashSet<>(pms);
            }
            /**
             * 分组函数条件
             */
            Set<Param> hvcs = gethvconditions(pms);
            /**
             * where 条件
             */
            String whereSqlByParam = getWhereSqlByParam(pms);

            StringBuilder grpsql = new StringBuilder(KSentences.SELECT.getValue());
            Set<PropInfo> propInfos = getPropInfos();
            /**
             * 拼接查询函数
             */
            if (funs != null && funs.size() > 0) {
                Iterator<Entry<String, String>> enite = funs.entrySet().iterator();
                while (enite.hasNext()) {
                    Entry<String, String> funen = enite.next();
                    for (PropInfo p : propInfos) {
                        if (p.getPname().equals(funen.getValue())) {
                            grpsql.append(funen.getKey().trim().toUpperCase()).append("(").append(p.getCname().trim()).append(")").append(KSentences.COMMA.getValue());
                            break;
                        }
                    }
                }
            }
            /**
             * 拼接查询字段
             */
            for (int i = 0; i < groupby.length; i++) {
                for (PropInfo p : propInfos) {
                    if (groupby[i].equals(p.getPname())) {
                        grpsql.append(p.getCname());
                        break;
                    }
                }

                if (i < groupby.length - 1) {
                    grpsql.append(KSentences.COMMA.getValue());
                }
            }

            grpsql.append(KSentences.FROM.getValue()).append("(");
            /**
             * 汇总所有表数据
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
                                Optional<PropInfo> propInfoOptional = propInfos.stream().filter(p -> p.getPname().equals(en.getValue().trim())).findFirst();
                                if (propInfoOptional.isPresent()) {
                                    grpsql.append(en.getKey().trim().toUpperCase()).append("(").append(propInfoOptional.get().getCname()).append(")");
                                } else {
                                    throw new IllegalArgumentException(String.format("In %s ,Can not find field %s",this.domainClazz.getSimpleName(),en.getValue()));
                                }
                            }
                        }
                    } else {
                        a:
                        for (PropInfo p : getPropInfos()) {
                            if (p.getPname().equals(ob.getPropertyName())) {
                                for (String g : groupby) {
                                    if (g.trim().equals(p.getPname())) {
                                        grpsql.append(p.getCname().trim());
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
            if (this.getConnectionManager().isShowSql()) {
                log.info(selectPagingSql);
            }
            int ix = 1;
            for (String tn : tbns) {
                ix = setWhereSqlParamValue(pms, statement, ix);
            }
            setWhereSqlParamValue(hvcs, statement, ix);
            return getObjectList(statement.executeQuery());
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } finally {
            this.getConnectionManager().closeConnection();
        }
    }

    private PreparedStatement getStatementBySql(boolean readOnly, String selectPagingSql) throws SQLException {
        PreparedStatement statement = this.getConnectionManager().getConnection(readOnly).prepareStatement(selectPagingSql);
        // 300秒超时
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
                        if (p.getPname().equals(pm.getPname())) {
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
        if (getCurrentTables().size() < 1) {
            return new ArrayList<>(0);
        }
        return getRztPos(true, 1, Integer.MAX_VALUE / getCurrentTables().size(), orderbys, null, cls);
    }

    @Override
    public List<POJO> getListByIdsIn(List<Serializable> ids, String... strings) {
        if (ids != null && ids.size() > 0) {
            Set<PropInfo> pis = getPropInfos();
            for (PropInfo fd : pis) {
                if (fd.getIsPrimarykey()) {
                    return getRztPos(false, true, Param.getParams(new Param(fd.getPname(), ids)), strings);
                }
            }

        }
        return new ArrayList<>(0);
    }

    @Override
    public List<POJO> getListByParamIn(String propertyName, List<Serializable> vls, String... cls) {
        if (vls != null && vls.size() > 0) {
            Set<PropInfo> pis = getPropInfos();
            for (PropInfo fd : pis) {
                if (fd.getPname().equals(propertyName)) {
                    return getRztPos(false, true, Param.getParams(new Param(fd.getPname(), vls)), cls);
                }
            }
        }

        return new ArrayList<>(0);
    }

    // 获取主键名称
    private String getPrimaryKeyPname() {
        for (PropInfo fd : getPropInfos()) {
            if (fd.getIsPrimarykey()) {
                return fd.getPname();
            }
        }
        throw new IllegalStateException(
                String.format("%s没有定义主键！！", ConnectionManager.getTbinfo(domainClazz).entrySet().iterator().next().getKey()));
    }

    @Override
    public POJO getById(Serializable id, String... strings) {
        return getObjByid(true, id, strings);

    }

    @Override
    public POJO getByIdFromMaster(Serializable id, String... strings) {
        return getObjByid(false, id, strings);

    }

    protected POJO getObjByid(Boolean isRead, Serializable id, String... strings) {
        if (id != null) {
            try {
                Entry<String, LinkedHashSet<PropInfo>> tbimp = ConnectionManager.getTbinfo(domainClazz).entrySet().iterator()
                        .next();
                for (PropInfo fd : tbimp.getValue()) {
                    if (fd.getIsPrimarykey()) {
                        ColumnRule cr = fd.getColumnRule();
                        Set<Param> pms = Param.getParams(new Param(fd.getPname(), Operate.EQ, id));
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
                this.getConnectionManager().closeConnection();
            }
        }
        return null;
    }

    private List<POJO> getSingleObj(Boolean isRead, Serializable id, Entry<String, LinkedHashSet<PropInfo>> tbimp,
                                    PropInfo fd, ColumnRule cr, Set<Param> pms, String... strings) throws SQLException {
        String tableName = getTableName(getTableMaxIdx(id, fd.getType(), cr), tbimp.getKey());
        if (!isContainsTable(tableName)) {
            return new ArrayList<>(0);
        }
        StringBuilder sb = getSelectSql(tableName, strings);
        sb.append(getWhereSqlByParam(pms));
        String sql = sb.toString();
        PreparedStatement prepare = getStatementBySql(isRead, sql);
        if (this.getConnectionManager().isShowSql()) {
            log.info(sql);
        }
        setWhereSqlParamValue(pms, prepare);
        ResultSet rs = prepare.executeQuery();
        return getRztObject(rs, strings);
    }

    @Override
    public POJO getOne(String propertyName, Serializable value, String... cls) {
        return getObj(true, propertyName, value, cls);
    }

    @Override
    public POJO getOneByMaster(String propertyName, Serializable value, String... cls) {
        return getObj(false, propertyName, value, cls);
    }

    private POJO getObj(Boolean isRead, String propertyName, Serializable value, String... cls) {
        try {
            Entry<String, LinkedHashSet<PropInfo>> tbimp = ConnectionManager.getTbinfo(domainClazz).entrySet().iterator()
                    .next();
            for (PropInfo fd : tbimp.getValue()) {
                if (fd.getPname().equals(propertyName)) {
                    Set<Param> pms = Param.getParams(new Param(fd.getPname(), Operate.EQ, value));
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
            this.getConnectionManager().closeConnection();
        }

        return null;
    }

    @Override
    public POJO getOne(Set<Param> pms, String... cls) {
        List<POJO> rzlist = getRztPos(false, true, pms, cls);
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
                this.getConnectionManager().closeConnection();
            }
        }
        return i;
    }

    @Override
    public Integer save(POJO pojo) {
        int rzc = 0;
        if (pojo != null) {
            try {
                //持久化
                rzc = persist(pojo);
            } catch (Exception e) {
                e.printStackTrace();
                throw new IllegalArgumentException(e);
            } finally {
                this.getConnectionManager().closeConnection();
            }
        }
        return rzc;
    }
    //持久化
    protected int persist(POJO pojo) throws IllegalAccessException, SQLException {
        Field[] fields = domainClazz.getDeclaredFields();
        Entry<String, LinkedHashSet<PropInfo>> tbe = ConnectionManager.getTbinfo(domainClazz).entrySet().iterator().next();
        Field idkey = checkPrimarykey(fields, tbe);

        StringBuilder sb = new StringBuilder(KSentences.INSERT.getValue());
        String tableSharding = tableSharding(pojo, fields, tbe.getKey());
        sb.append(tableSharding);
        sb.append("(");
        Iterator<PropInfo> clite = tbe.getValue().iterator();
        while (clite.hasNext()) {
            sb.append(clite.next().getCname());
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
        if (this.getConnectionManager().isShowSql()) {
            log.info(insertSql);
        }
        boolean autoincrement = isAutoIncrement();
        Connection connection = this.getConnectionManager().getConnection();
        PreparedStatement statement = autoincrement?connection.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS):connection.prepareStatement(insertSql);
        setParamVal(pojo, fields, tbe.getValue(), statement, this.getConnectionManager().getConnection());
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
    //获取切分规则
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
    //表切分
    private String tableSharding(POJO pojo, Field[] fds, String name) throws IllegalAccessException, SQLException {
        //遍历所有字段
        for (Field field : fds) {
            //获取字段上的表切分朱姐
            ColumnRule columnRule = field.getAnnotation(ColumnRule.class);
            //如果当前字段存在切分
            if (columnRule != null) {
                //且当前字段内容不为空
                field.setAccessible(true);
                if (field.get(pojo) == null) {
                    //循环所有字段的属性内容
                    for (PropInfo propInfo : getPropInfos()) {
                        //如果字段与字段属性内容匹配上
                        if (paired(propInfo, field)) {
                            //那么字段是否是主键
                            if (propInfo.getIsPrimarykey()) {
                                //是否被@GenerationType修饰,并且值是TABLE,并且是Mysq数据库
                                if (
                                        GenerationType.TABLE.equals(propInfo.getGeneratorValueAnnoStrategyVal())
                                            &&
                                        "MySQL".equalsIgnoreCase(this.dataBaseTypeName)
                                ) {
                                    //获取下一个全局id
                                    //设置主键id为全局id
                                    Long nextId = getNextIdFromIdTable(this.getConnectionManager().getConnection());
                                    field.set(pojo, nextId);
                                } else if (
                                        autoNextVal(propInfo)
                                            &&
                                        "Oracle".equalsIgnoreCase(this.dataBaseTypeName)
                                ) {
                                    //设置Oracle全局id到当前主键
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
                    //如果全部循环匹配没有主键,抛出错误
                    if (field.get(pojo) == null) {
                        throw new IllegalArgumentException(String.format("%s切分字段数据不能为空！！", field.getName()));
                    }
                }
                //当前字段带有切分规则,通过当前字段,当前字段类型,切分规则,获取表的下标
                long max = getTableMaxIdx(field.get(pojo), field.getType(), columnRule);
                Set<String> currentTables = getCurrentTables();
                if (currentTables.size()>= maxTableCount) {
                    throw new IllegalStateException(String.format("超出了表拆分最大数量，最多只能拆分%s个表", maxTableCount));
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
    //获取表的下标
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
            throw new IllegalStateException(String.format("%s类型不能用来对数据进行切分，请使用int、long、string、date类型的字段", fieldType));
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
                if (this.isShowSql) { log.info(sql); }
                getCurrentTables().add(ctbname);
            } else if ("Oracle".equalsIgnoreCase(this.dataBaseTypeName)) {
                boolean create = createTableBySql(ctbname);
                if (create) {
                    getCurrentTables().add(ctbname);
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
        throw new IllegalStateException(String.format("%s没有定义主键！！", tbe.getKey()));
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
        if (getCurrentTables().size() < 1) {
            return 0;
        }
        try {
            Set<String> tbns = getTableNamesByParams(pms);
            String whereSqlByParam = getWhereSqlByParam(pms);
            int ttc = 0;
            for (String tn : tbns) {
                String sql = KSentences.DELETE_FROM.getValue() + tn + whereSqlByParam;
                PreparedStatement statement = getStatementBySql(false, sql);
                if (this.getConnectionManager().isShowSql()) {
                    log.info(sql);
                }
                setWhereSqlParamValue(pms, statement);
                ttc += statement.executeUpdate();

            }
            return ttc;
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } finally {
            this.getConnectionManager().closeConnection();
        }
    }

    //配对
    private static boolean paired(PropInfo propInfo, Field field) {
        Column clm = field.getAnnotation(Column.class);
        if (clm == null || clm.name().trim().length() < 1) {
            if (propInfo.getCname().equalsIgnoreCase(field.getName())) {
                return true;
            }
        } else if (clm.name().equalsIgnoreCase(propInfo.getCname())) {
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
                if (paired(zd, fd)) {
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
                if (field.isAnnotationPresent(Enumerated.class)
                        && field.getAnnotation(Enumerated.class).value() == EnumType.STRING) {
                    statement.setObject(index, vl.toString());
                } else {
                    statement.setObject(index, Enum.valueOf(cls, vl.toString()).ordinal());
                }
            }
        } else {
            if (vl == null && propInfo.getIsPrimarykey()) {
                if (GenerationType.TABLE.equals(propInfo.getGeneratorValueAnnoStrategyVal())
                        && "MySQL".equalsIgnoreCase(this.dataBaseTypeName)) {
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
                if (vl != null && "Oracle".equalsIgnoreCase(this.dataBaseTypeName) && (propInfo.getType() == Date.class || propInfo.getType().getSuperclass() == Date.class)) {
                    Date dt = (Date) vl;
                    statement.setTimestamp(index, new Timestamp(dt.getTime()));
                } else {
                    if (propInfo.getVersion()&&vl==null) {
                        vl = 1L;
                        try {
                            field.setAccessible(true);
                            field.set(pojo,vl);
                        } catch (Exception e) {
                            String error = "Set New Value To @Version Type Error!";
                            log.error(error);
                            throw new IllegalArgumentException(error);
                        }
                    }
                    statement.setObject(index, vl);
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
        ResultSet rs = conn.prepareStatement(String.format("select  %s.%s   from  dual", seqName, "nextval")).executeQuery();
        if (rs.next()) {
            return rs.getLong(1);
        }
        return null;
    }

    //获取全局id表的下一个id
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
        return getRztPos(property, params, true, false);
    }

    @Override
    public List<Object> getVListFromMaster(String property, Set<Param> params) {
        return getRztPos(property, params, false, false);
    }

    @Override
    public List<Object> getVList(String property, Set<Param> params, boolean isDistinct) {
        return getRztPos(property, params, true, isDistinct);
    }

    @Override
    public List<Object> getVListFromMaster(String property, Set<Param> params, boolean isDistinct) {
        return getRztPos(property, params, false, isDistinct);
    }

    // 单个字段值列表
    private List<Object> getRztPos(String property, Set<Param> params, boolean isRead, boolean isDistinct) {

        if (getCurrentTables().size() < 1) {
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
                return querylist(pss);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } finally {
            this.getConnectionManager().closeConnection();
        }

    }

    /// 实体对象列表
    private List<POJO> getRztPos(boolean isDistinct, boolean isRead, Set<Param> params, String... strings) {
        if (getCurrentTables().size() < 1) {
            return new ArrayList<>(0);
        }
        try {
            String selectpre = getPreSelectSql(isDistinct, strings);
            String whereSqlByParam = getWhereSqlByParam(params);
            Set<String> tbns = getTableNamesByParams(params);
            if (tbns.size() == 1) {
                return getSingleObject(isRead, params, selectpre + tbns.iterator().next() + whereSqlByParam, strings);
            } else {
                List<QueryVo<PreparedStatement>> pss = getqvs(isRead, params, selectpre, whereSqlByParam, tbns);
                return querylist(pss, strings);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        } finally {
            this.getConnectionManager().closeConnection();
        }
    }

    private List<QueryVo<PreparedStatement>> getqvs(boolean isRead, Set<Param> params, String selectpre,
                                                    String whereSqlByParam, Set<String> tbns) throws SQLException {
        List<QueryVo<PreparedStatement>> pss = new ArrayList<>();
        for (String tn : tbns) {
            String sql = selectpre + tn + whereSqlByParam;
            PreparedStatement statement = getStatementBySql(isRead, sql);
            if (this.getConnectionManager().isShowSql()) {
                log.info(sql);
            }
            setWhereSqlParamValue(params, statement);
            pss.add(new QueryVo<PreparedStatement>(tn, statement));
        }
        return pss;
    }

    @Override
    public PageData<POJO> getPageInfo(int curPage, int pageSize, Set<Param> params, LinkedHashSet<OrderBy> orderbys,String... strings) {
        Long count = getCount(params);
        if (count > 0) {
            return new PageData<>(curPage, pageSize, count,
                    getRztPos(true, curPage, pageSize, orderbys, params, strings));
        } else {
            return new PageData<>(curPage, pageSize, count, new ArrayList<>(0));
        }
    }

    @Override
    public PageData<POJO> getPageInfoFromMaster(int curPage, int pageSize,Set<Param> params, LinkedHashSet<OrderBy> orderbys,String... strings) {
        Long count = getCountFromMaster(params);
        if (count > 0) {
            return new PageData<>(curPage, pageSize, count,
                    getRztPos(false, curPage, pageSize, orderbys, params, strings));
        } else {
            return new PageData<>(curPage, pageSize, count, new ArrayList<>(0));
        }
    }

    /***
     * 多表分页
     *
     * @param sql
     * @param curPage
     * @param pageSize
     * @return
     */
    private String getSelectPagingSql(String sql, int curPage, int pageSize) {
        try {
            String dpname = this.getConnectionManager().getConnection(true).getMetaData().getDatabaseProductName();
            if (dpname.equalsIgnoreCase("MySQL")) {
                return sql + getPagingSql(curPage, pageSize);
            } else if (dpname.equalsIgnoreCase("Oracle")) {
                StringBuilder sb = new StringBuilder("select  row_.*,   rownum  rownum_      from (");
                sb.append(sql);
                sb.append(")  row_  where    rownum <=");
                sb.append(curPage * pageSize);
                return sb.toString();
            }
            throw new IllegalStateException(String.format("当前查询分页路由不支持%s数据库系统", dpname));
        } catch (SQLException e) {
            e.printStackTrace();
            throw new IllegalStateException("无法获取数据库名称");
        }

    }

    /**
     * 单表分页
     *
     * @param sql
     * @param curPage
     * @param pageSize
     * @return
     */
    private String getSingleTableSelectPagingSql(String sql, int curPage, int pageSize) {
        try {
            String dpname = this.getConnectionManager().getConnection(true).getMetaData().getDatabaseProductName();
            if (dpname.equalsIgnoreCase("MySQL")) {
                return sql + getSingleTablePagingSql(curPage, pageSize);
            } else if (dpname.equalsIgnoreCase("Oracle")) {
                return oraclepageselect(sql, curPage, pageSize);
            }
            throw new IllegalStateException(String.format("当前查询分页路由不支持：%s数据库系统", dpname));
        } catch (SQLException e) {
            e.printStackTrace();
            throw new IllegalStateException("无法获取数据库名称");
        }

    }

    private String getSingleTableSelectPagingSqlByStartIndex(int start, String sql, int pageSize) {

        try {
            String dpname = this.getConnectionManager().getConnection(true).getMetaData().getDatabaseProductName();
            if (dpname.equalsIgnoreCase("MySQL")) {
                return sql + getSinglePagingSql(start, pageSize);
            } else if (dpname.equalsIgnoreCase("Oracle")) {
                return getoracleSinglepagingSelectsql(start, sql, pageSize);
            }
            throw new IllegalStateException(String.format("当前查询分页路由不支持：%s数据库系统", dpname));
        } catch (SQLException e) {
            e.printStackTrace();
            throw new IllegalStateException("无法获取数据库名称");
        }

    }

    /**
     * Oracle
     *
     * @param sql
     * @param curPage  当前页
     * @param pageSize 每页显示多少条记录
     * @return
     */
    private String oraclepageselect(String sql, int curPage, int pageSize) {
        StringBuilder sb = new StringBuilder(
                "select   *      from        ( select  row_.*,   rownum  rownum_      from (");
        sb.append(sql);
        sb.append(")  row_  where    rownum <=");
        sb.append(curPage * pageSize);
        sb.append(" )   where  rownum_ > ").append((curPage - 1) * pageSize);
        return sb.toString();
    }

    /**
     * Oracle
     *
     * @param start    开始位置
     * @param sql
     * @param pageSize 获取多少条记录
     * @return
     */
    private String getoracleSinglepagingSelectsql(int start, String sql, int pageSize) {
        if (start < 0 || pageSize < 1) {
            throw new IllegalArgumentException("当开始位置不能小于0或者页大小不能小于1");
        }
        StringBuilder sb = new StringBuilder(
                "select   *      from        ( select  row_.*,   rownum  rownum_      from (");
        sb.append(sql);
        sb.append(")  row_  where    rownum <=");
        sb.append(start + pageSize);
        sb.append(" )   where  rownum_ > ").append(start);
        return sb.toString();

    }

    private List<POJO> getRztPos(Boolean isRead, int curPage, int pageSize, LinkedHashSet<OrderBy> orderbys,
                                 Set<Param> params, String... strings) {
        if (curPage < 1 || pageSize < 1 || getCurrentTables().size() < 1) {
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
                    String sql = getSingleTableSelectPagingSql(
                            selectpre + tbns.iterator().next() + whereSqlByParam + orderBySql, curPage, pageSize);
                    return getSingleObject(isRead, params, sql, strings);
                } else {
                    List<QueryVo<PreparedStatement>> pss = new ArrayList<>();
                    for (String tn : tbns) {
                        String sql = getSelectPagingSql(selectpre + tn + whereSqlByParam + orderBySql, curPage,
                                pageSize);
                        PreparedStatement statement = getStatementBySql(isRead, sql);
                        if (this.getConnectionManager().isShowSql()) {
                            log.info(sql);
                        }
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
                this.getConnectionManager().closeConnection();
            }
        }

    }

    private List<Object> getSingleObject(Boolean isRead, String sql, Set<Param> params) throws SQLException {
        PreparedStatement statement = getStatementBySql(isRead, sql);
        if (this.getConnectionManager().isShowSql()) {
            log.info(sql);
        }
        setWhereSqlParamValue(params, statement);
        return getRztObject(statement.executeQuery());
    }

    private List<POJO> getSingleObject(Boolean isRead, Set<Param> params, String sql, String... strings)
            throws SQLException {
        PreparedStatement statement = getStatementBySql(isRead, sql);
        if (this.getConnectionManager().isShowSql()) {
            log.info(sql);
        }
        setWhereSqlParamValue(params, statement);
        return getRztObject(statement.executeQuery(), strings);
    }

    /**
     * 不排序分页列表
     *
     * @param isRead
     * @param curPage
     * @param pageSize
     * @param params
     * @param strings
     * @return
     * @throws SQLException
     * @throws InterruptedException
     * @throws ExecutionException
     */
    private PageData<POJO> getListFromNotSorted(Boolean isRead, int curPage, int pageSize, Set<Param> params,
                                                String... strings) {
        if (this.getConnectionManager().isShowSql()) {
            log.info("begin........................................");
        }
        try {
            String selectpre = getPreSelectSql(false, strings);
            String whereSqlByParam = getWhereSqlByParam(params);
            List<QueryVo<PreparedStatement>> pss = new ArrayList<>();
            Set<String> tbs = getTableNamesByParams(params);
            List<QueryVo<Long>> qvs = getMultiTableCount(isRead, params, tbs);
            long totalCount = qvs.stream().mapToLong(QueryVo::getOv).sum();
            if (totalCount < 1) {
                return new PageData<>(curPage, pageSize, totalCount, new ArrayList<>(0));
            }
            // 开始位置
            int start = getPageStartIndex(curPage, pageSize);
            // 当前所有查到的最大位置
            int csum = 0;
            // 当前已经可以查到的数据量
            int rdsum = 0;
            for (QueryVo<Long> q : qvs) {
                csum += q.getOv();
                if (rdsum < pageSize) {
                    if (csum > start) {
                        // 当前 表开始位置
                        int startindex = 0;
                        // 还剩多少数据需要查询
                        int left = pageSize - rdsum;
                        int initSize = q.getOv().intValue() > left ? left : q.getOv().intValue();
                        if (start > 0) {
                            // 当前表查询剩余多少记录
                            int step = csum - start;
                            if (step < q.getOv().intValue()) {
                                startindex = q.getOv().intValue() - step;
                                if (step < pageSize) {
                                    initSize = step;
                                }
                            }
                        }
                        rdsum += initSize;
                        String sql = getSingleTableSelectPagingSqlByStartIndex(startindex,
                                selectpre + q.getTbn() + whereSqlByParam, initSize);
                        PreparedStatement statement = getStatementBySql(isRead, sql);
                        if (this.getConnectionManager().isShowSql()) {
                            log.info(sql);
                        }
                        setWhereSqlParamValue(params, statement);
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
            this.getConnectionManager().closeConnection();
            if (this.getConnectionManager().isShowSql()) {
                log.info("........................................end");
            }
        }

    }

    /**
     * 开始位置
     *
     * @param curPage
     * @param pageSize
     * @return
     */
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

    /// 获取总记录数
    private Long getCountPerTable(Boolean isRead, Set<Param> params, String... distincts) {
        try {
            Set<String> tbs = getTableNamesByParams(params);
            if (tbs.size() > 1) {
                if (isArrayEffective(distincts)) {
                    return groupcount(isRead, params, distincts);
                } else {
                    return getQvcSum(getMultiTableCount(isRead, params, tbs));
                }
            } else {
                StringBuilder sb = new StringBuilder(KSentences.SELECT.getValue());
                sb.append(KSentences.COUNT.getValue());
                sb.append(KSentences.LEFT_BRACKETS.getValue());
                if (isArrayEffective(distincts)) {
                    sb.append(KSentences.DISTINCT.getValue());
                    for (int i = 0; i < distincts.length; i++) {
                        String ps = distincts[i];
                        for (PropInfo p : getPropInfos()) {
                            if (p.getPname().equals(ps.trim())) {
                                sb.append(p.getCname());
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
                sb.append(KSentences.RIGHT_BRACKETS.getValue()).append(KSentences.FROM.getValue())
                        .append(tbs.iterator().next());
                sb.append(getWhereSqlByParam(params));
                String sql = sb.toString();
                if (this.getConnectionManager().isShowSql()) {
                    log.info(sql);
                }
                PreparedStatement statement = getPreParedStatement(isRead, params, sql);
                ResultSet rs = statement.executeQuery();
                if (rs.next()) {
                    return rs.getLong(1);
                } else {
                    return 0L;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            this.getConnectionManager().closeConnection();
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
                if (paired(propInfo, field)) {
                    Object propValue = getPropValue(po, field);
                    if (propInfo.getPname().equals(primaryKeyName)) {
                        if (propValue != null) {
                            id = propValue;
                            pms.add(new Param(propInfo.getPname(), Operate.EQ, id));
                        } else {
                            throw new IllegalArgumentException("主键的值不能为空！");
                        }
                    }
                    else if (propInfo.getVersion()) {
                        version = true;
                        versionPname = propInfo.getPname();
                        pms.add(new Param(versionPname, Operate.EQ, propValue));
                        oldVersion = Long.parseLong(propValue.toString());
                        if (oldVersion == null) {
                            oldVersion = 0L;
                        }
                        Long newVersion = oldVersion + 1;
                        newValues.put(versionPname, newVersion);
                        try {
                            field.setAccessible(true);
                            field.set(po,newVersion);
                        } catch (Exception e) {
                            String error = "Set New Value To @Version Type Error!";
                            log.error(error);
                            throw new IllegalArgumentException(error);
                        }
                    }
                    else {
                        newValues.put(propInfo.getPname(), propValue);
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
                            if (p.getPname().equals(entry.getKey())) {
                                buf.append(p.getCname()).append(KSentences.EQ.getValue()).append(KSentences.POSITION_PLACEHOLDER.getValue());
                                if (it.hasNext()) {
                                    buf.append(KSentences.COMMA.getValue());
                                }
                            }
                        }
                    }
                    buf.append(getWhereSqlByParam(pms));

                    String sql = buf.toString();
                    PreparedStatement statement = getStatementBySql(false, sql);
                    if (this.getConnectionManager().isShowSql()&&!isShowSqled) {
                        isShowSqled = true;
                        log.info(sql);
                        Set<Entry<String, Object>> entrySet = newValues.entrySet();
                        for (Entry<String, Object> entry : entrySet) {
                            if (entry.getValue() != null) {
                                log.info("param("+entry.getKey()+")"+"="+entry.getValue().toString());
                            }
                        }
                    }
                    int i = setUpdateNewValues(newValues, statement);
                    setWhereSqlParamValue(pms, statement, i);
                    ttc += statement.executeUpdate();
                }
                if (version && ttc == 0) {
                    POJO pojo = this.getById((Serializable) id, primaryKeyName, versionPname);
                    if (pojo != null) {
                        Field nowVersionField = pojo.getClass().getDeclaredField(versionPname);
                        nowVersionField.setAccessible(true);
                        Long nowVersion = Long.parseLong(nowVersionField.get(pojo).toString());
                        if (!oldVersion.equals(nowVersion)) {
                            String errorMsg = "Current Version Is "+oldVersion+",But The New Version Is "+nowVersion+",So Changes Cannot Be Performed In Different Versions.";
                            throw new ObjectOptimisticLockingFailureException(errorMsg);
                        }
                    }
                }
            }
        }catch (ObjectOptimisticLockingFailureException e) {
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
            this.getConnectionManager().closeConnection();
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
                    if (p.getPname().equals(ob.getPropertyName().trim())) {
                        if (ob.getFunName() != null && ob.getFunName().trim().length() > 0) {
                            sb.append(ob.getFunName());
                            sb.append("(");
                            sb.append(p.getCname());
                            sb.append(")");
                        } else {
                            sb.append(p.getCname());
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

    /**
     * 多表分页
     *
     * @param curPage
     * @param pageSize
     * @return
     */
    private String getPagingSql(int curPage, int pageSize) {
        if (curPage < 1 || pageSize < 1) {
            throw new IllegalArgumentException("当前页和页大小不能小于0");
        }
        StringBuilder sb = new StringBuilder(KSentences.LIMIT.getValue());
        sb.append(curPage * pageSize);
        return sb.toString();
    }

    /**
     * 单表分页
     *
     * @param curPage
     * @param pageSize
     * @return
     */
    private String getSingleTablePagingSql(int curPage, int pageSize) {
        if (curPage < 1 || pageSize < 1) {
            throw new IllegalArgumentException("当前页和页大小不能小于0");
        }
        StringBuilder sb = new StringBuilder(KSentences.LIMIT.getValue());
        sb.append((curPage - 1) * pageSize);
        sb.append(KSentences.COMMA.getValue()).append(pageSize);
        return sb.toString();
    }

    /**
     * 不排序分页查询
     *
     * @param start    开始位置
     * @param pageSize 获取多少条数据
     * @return
     */
    private String getSinglePagingSql(int start, int pageSize) {
        if (start < 0 || pageSize < 1) {
            throw new IllegalArgumentException("当开始位置不能小于0或者页大小不能小于1");
        }
        StringBuilder sb = new StringBuilder(KSentences.LIMIT.getValue());
        sb.append(start);
        sb.append(KSentences.COMMA.getValue()).append(pageSize);
        return sb.toString();
    }

    private List<Object> querylist(List<QueryVo<PreparedStatement>> pss)
            throws InterruptedException, ExecutionException {
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

    private List<POJO> querylist(List<QueryVo<PreparedStatement>> pss, String... strings)
            throws InterruptedException, ExecutionException {
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
            qcs.add(new QueryCallable(ps.getOv(), ps.getTbn()));
            if (this.getConnectionManager().isShowSql()) {
                log.error(ps.getOv().toString());
            }
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
                            if (pi.getPname().equals(strings[i])) {
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
                            if (pi.getCname().equalsIgnoreCase(rs.getMetaData().getColumnName(i + 1))) {
                                Field fd = domainClazz.getDeclaredField(pi.getPname());
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

    /**
     * 设置查询条件
     *
     * @param pms
     */
    protected String getWhereSqlByParam(Set<Param> pms) {
        StringBuilder sb = new StringBuilder();
        if (pms != null && pms.size() > 0) {
            sb.append(KSentences.WHERE.getValue());
            geneConditionSql(pms, sb);
        }
        return sb.toString();
    }

    //TODO
    private void geneConditionSql(Set<Param> pms, StringBuilder sb) {
        Iterator<Param> pmsite = pms.iterator();
        while (pmsite.hasNext()) {
            Param pm = pmsite.next();
            if (pm.getPname() != null && pm.getPname().trim().length() > 0) {
                boolean isor = pm.getOrParam() != null;
                if (isor) {
                    sb.append("(");
                }
                do {
                    for (PropInfo p : getPropInfos()) {
                        if (p.getPname().equals(pm.getPname())) {
                            if (pm.getCdType().equals(PmType.OG)) {
                                setogcds(sb, pm, p);
                            } else {
                                setvlcds(sb, pm, p);
                            }
                        }
                    }
                    pm = pm.getOrParam();
                    if (pm != null) {
                        sb.append(KSentences.OR.getValue());
                    }
                } while (pm != null);
                if (isor) {
                    sb.append(")");
                }
                if (pmsite.hasNext()) {
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
                throw new IllegalArgumentException("非法的条件查询：CdType.OG类型的条件值不能为空");
            }
        }
    }

    private void setvlcds(StringBuilder sb, Param pm, PropInfo p) {
        if (pm.getOperators().equals(Operate.BETWEEN)) {
            if (pm.getFirstValue() == null || pm.getValue() == null) {
                throw new IllegalArgumentException(
                        String.format("%s BETWEEN param   value   is not  null  ! ", pm.getPname()));
            }
            setcName(sb, pm, p);
            sb.append(pm.getOperators().getValue());
            sb.append(KSentences.POSITION_PLACEHOLDER);
            sb.append(KSentences.AND);
            sb.append(KSentences.POSITION_PLACEHOLDER);

        } else if (pm.getOperators().equals(Operate.IN) || pm.getOperators().equals(Operate.NOT_IN)) {
            if (pm.getInValue() == null || pm.getInValue().size() < 1) {
                throw new IllegalArgumentException(
                        String.format("%s IN param list value size is not zero or null! ", pm.getPname()));
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
                sb.append(pm.getOperators().getValue()).append(KSentences.POSITION_PLACEHOLDER.getValue());
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
                throw new IllegalArgumentException(
                        String.format("%s %s  param  value  is not null ! ", domainClazz.getSimpleName(), pm.getPname()));
            }
        }
    }

    private Class<?> getPmsType(Param pm) {
        for (PropInfo p : getPropInfos()) {
            if (p.getPname().equals(pm.getPname())) {
                return p.getType();
            }
        }
        throw new IllegalArgumentException(String.format("%s字段没有定义...", pm.getPname()));
    }

    private void setcName(StringBuilder sb, Param pm, PropInfo p) {
        if (pm.getCdType().equals(PmType.FUN)) {
            sb.append(pm.getFunName()).append("(");
        }
        sb.append(p.getCname());
        if (pm.getCdType().equals(PmType.FUN)) {
            sb.append(")");

        }
    }

    protected int setWhereSqlParamValue(Set<Param> pms, PreparedStatement statement, int ix) {
        if (pms != null && pms.size() > 0) {
            for (Param pm : pms) {
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
            if (o.getClass().isEnum() && pp.getType().isEnum()) {
                EnumType et = pp.getEnumType();
                if (et.equals(EnumType.STRING)) {
                    return o.toString();
                } else {
                    Class<Enum> cls = (Class<Enum>) pp.getType();
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
                if (pp.getPname().equals(pname)) {
                    return pp;
                }
            }
        }
        return null;
    }

    /**
     * 给查询条件赋值
     *
     * @param pms
     * @param statement
     */
    protected void setWhereSqlParamValue(Set<Param> pms, PreparedStatement statement) {
        setWhereSqlParamValue(pms, statement, 1);

    }

    /**
     * 根据条件得到数据所在的表
     *
     * @param pms
     * @return
     */
    protected Set<String> getTableNamesByParams(Set<Param> pms) {
        if (pms != null && pms.size() > 0) {
            Entry<String, LinkedHashSet<PropInfo>> tbimp = ConnectionManager.getTbinfo(domainClazz).entrySet().iterator()
                    .next();
            for (Param pm : pms) {
                if (pm.getPname() != null && pm.getPname().trim().length() > 0) {
                    for (PropInfo p : tbimp.getValue()) {
                        if (p.getColumnRule() != null) {
                            if (pm.getPname().equals(p.getPname()) && pm.getOrParam() == null) {
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
                                                    getTableMaxIdx(sid, p.getType(), p.getColumnRule()),
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
                                    long st = getTableMaxIdx(pm.getFirstValue(), p.getType(), p.getColumnRule());
                                    long ed = getTableMaxIdx(pm.getValue(), p.getType(), p.getColumnRule());
                                    Set<String> nms = gettbs(tbimp, st, ed);
                                    if (nms.size() > 0) {
                                        return nms;
                                    }
                                } else if (p.getColumnRule().ruleType().equals(RuleType.RANGE)
                                        && pm.getOperators().equals(Operate.GE) && pm.getValue() != null) {

                                    long st = getTableMaxIdx(pm.getValue(), p.getType(), p.getColumnRule());
                                    if (st > 0) {
                                        int len = getTableName(st, tbimp.getKey())
                                                .split(KSentences.SHARDING_SPLT.getValue()).length;

                                        long ed = getCurrentTables().stream().mapToLong(n -> {
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

        return getCurrentTables();
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
        return getTableName(getTableMaxIdx(pm.getValue(), p.getType(), p.getColumnRule()), tbimp.getKey());

    }

    private boolean isContainsTable(String tbname) {
        Iterator<String> ite = getCurrentTables().iterator();
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
                    if (strings[i].equals(pi.getPname())) {
                        sb.append(pi.getCname());
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

    /**
     * 判断表是否已经被创建
     *
     * @param tblname
     * @return
     */
    private boolean isExistTable(String tblname) {
        Set<String> tbns = getCurrentTables();
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
        // 序列名称一定要大写
        String seqName = String.format("%s_%s", tableName, "SEQ").toUpperCase();
        return seqName;
    }

    private void changeToString(PropInfo pi) throws SQLException {
        for (String t : getCurrentTables()) {
            String altertablesql = String.format(ALTER_TABLE_MODIFY_COLUMN, t, pi.getCname(), getVarchar(pi));
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
        sbd.append(p.getCname());
        if (p.getType() == String.class && p.getLength() > p.getIndex().length()) {
            if ("MySQL".equalsIgnoreCase(this.dataBaseTypeName)) {
                sbd.append("(").append(p.getIndex().length()).append(")");
            }
        }
        if (p.getIndex().otherPropName() != null && !"".equals(p.getIndex().otherPropName().trim())) {
            String[] pNames = p.getIndex().otherPropName().split(",");
            for (String pName : pNames) {
                PropInfo propInfo = getPropInfoByPName(pName);
                sbd.append(KSentences.COMMA.getValue()).append(propInfo.getCname());
                if (propInfo != null) {
                    if (propInfo.getType() == String.class) {
                        if ("MySQL".equalsIgnoreCase(this.dataBaseTypeName)) {
                            //String 类型的字段最好指定索引长度,例如18位身份证号可以指定20位长度 , 超过255不适合创建索引, 255内适合建立索引,
                            sbd.append("(").append(p.getIndex().length()).append(")");
                        }
                    }
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


}
