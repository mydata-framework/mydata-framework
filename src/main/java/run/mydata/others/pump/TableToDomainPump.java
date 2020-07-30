package run.mydata.others.pump;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import run.mydata.manager.IConnectionManager;

import java.io.File;
import java.io.FileOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * table to domain pump
 * 数据表转领域实体 泵
 *
 * @author tao.liu
 */
public class TableToDomainPump implements ApplicationContextAware {
    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    public static class PumpConfig {
        /**
         * default pump all table , if not , set false and set tableNames that you want
         * 是否全部抽取
         */
        private Boolean pumpAllTable = true;
        /**
         * if not pump all table , you need set this
         * 非抽取全部,可以在这里设置需要抽取的表
         */
        private List<String> tableNames = new ArrayList<>();
        /**
         * default hump for column to field , if you not want , you can set this
         * 是否转驼峰
         */
        private Boolean toHump = true;
        /**
         * domain target package , as com.mydata.domain
         * domain存放目标目录
         */
        private String packageTarget;
        /**
         * if use lombok , you can set true
         * domain是否使用lombok,省略了get set method
         */
        private Boolean useLombok = false;

        public Boolean getToHump() {
            return toHump;
        }

        public void setToHump(Boolean toHump) {
            this.toHump = toHump;
        }

        public Boolean getPumpAllTable() {
            return pumpAllTable;
        }

        public void setPumpAllTable(Boolean pumpAllTable) {
            this.pumpAllTable = pumpAllTable;
        }

        public List<String> getTableNames() {
            return tableNames;
        }

        public void setTableNames(List<String> tableNames) {
            this.tableNames = tableNames;
        }

        public String getPackageTarget() {
            return packageTarget;
        }

        public void setPackageTarget(String packageTarget) {
            this.packageTarget = packageTarget;
        }

        public Boolean getUseLombok() {
            return useLombok;
        }

        public void setUseLombok(Boolean useLombok) {
            this.useLombok = useLombok;
        }
    }


    public void pump(PumpConfig pumpConfig) throws Exception {
        String domainPackageTarget = pumpConfig.getPackageTarget();

        if (domainPackageTarget == null || domainPackageTarget.trim().length() == 0) {
            throw new IllegalArgumentException(" PumpConfig domainPackageTarget is Empty; Domain存放目录不能未空; ");
        } else {
            String packages = domainPackageTarget.replaceAll("\\.", "/");
            domainPackageTarget = TableToDomainPump.class.getResource("/").getPath().replace("/target/classes/", "/src/main/java/" + packages);


        }

        //表名
        List<String> allTableNames = new ArrayList<>();
        List<String> NEED_PUMP_TABLE_NAMES = new ArrayList<>();
        IConnectionManager connectionManager = applicationContext.getBean(IConnectionManager.class);
        Connection conn = connectionManager.getConnection();
        PreparedStatement preparedStatement = conn.prepareStatement("SHOW TABLES");
        ResultSet rs = preparedStatement.executeQuery();
        while (rs.next()) {
            String table = rs.getString(1);
            allTableNames.add(table.toLowerCase());
        }
        if (pumpConfig.getPumpAllTable()) {
            NEED_PUMP_TABLE_NAMES = allTableNames;
        } else {
            List<String> settingPumpTableNames = pumpConfig.getTableNames();
            if (settingPumpTableNames == null || settingPumpTableNames.isEmpty()) {
                NEED_PUMP_TABLE_NAMES.clear();
            } else {
                for (String settingPumpTable : settingPumpTableNames) {
                    settingPumpTable = settingPumpTable.toLowerCase();
                    if (allTableNames.contains(settingPumpTable)) {
                        NEED_PUMP_TABLE_NAMES.add(settingPumpTable);
                    }
                }
            }
        }

        //表备注
        Map<String, String> TABLENAME_TABLECOMMENT_MAP = new HashMap<>();

        PreparedStatement prepareStatement = conn.prepareStatement("SELECT TABLE_NAME,TABLE_COMMENT FROM information_schema.TABLES WHERE table_schema=?");
        prepareStatement.setString(1, conn.getCatalog());
        ResultSet tcRs = prepareStatement.executeQuery();
        while (tcRs.next()) {
            String table_name = tcRs.getString("TABLE_NAME");
            String table_comment = tcRs.getString("TABLE_COMMENT");
            TABLENAME_TABLECOMMENT_MAP.put(table_name.toLowerCase(), table_comment);
        }


        //表字段信息
        Map<String, Map<String, DescInfo>> TABLENAME_FIELDNAME$DESCINFOMAP_MAP = new HashMap<>();

        for (String tableName : NEED_PUMP_TABLE_NAMES) {

            PreparedStatement cname_ccomment_psmt = conn.prepareStatement("SELECT COLUMN_NAME,COLUMN_COMMENT FROM information_schema.COLUMNS WHERE table_name = ?");
            cname_ccomment_psmt.setString(1, tableName);
            ResultSet cname_ccommentRs = cname_ccomment_psmt.executeQuery();
            Map<String, String> FIELDNAME$COMMENT_MAP = new HashMap<>();
            while (cname_ccommentRs.next()) {
                String column_name = cname_ccommentRs.getString("COLUMN_NAME");
                String column_comment = cname_ccommentRs.getString("COLUMN_COMMENT");
                if (column_comment == null || column_comment.trim().length() == 0) {
                    column_comment = null;
                }
                FIELDNAME$COMMENT_MAP.put(column_name, column_comment);
            }

            PreparedStatement tableDesc = conn.prepareStatement("DESC " + tableName);
            ResultSet tableDescRs = tableDesc.executeQuery();
            Map<String, DescInfo> FIELD_NAME$DESCINFO_MAP = new LinkedHashMap<>();
            TABLENAME_FIELDNAME$DESCINFOMAP_MAP.put(tableName, FIELD_NAME$DESCINFO_MAP);
            while (tableDescRs.next()) {
                String Field = tableDescRs.getString(1);
                String Type = tableDescRs.getString(2);
                String Null = tableDescRs.getString(3);
                String Key = tableDescRs.getString(4);
                String Default = tableDescRs.getString(5);
                String Extra = tableDescRs.getString(6);
                DescInfo descInfo = new DescInfo();
                descInfo.setField(Field);
                descInfo.setType(Type);
                descInfo.setNulll(Null);
                descInfo.setKey(Key);
                descInfo.setDefaultt(Default);
                descInfo.setExtra(Extra);
                descInfo.setComment(FIELDNAME$COMMENT_MAP.get(Field));
                FIELD_NAME$DESCINFO_MAP.put(Field, descInfo);
            }
        }


        for (Map.Entry<String, Map<String, DescInfo>> entry : TABLENAME_FIELDNAME$DESCINFOMAP_MAP.entrySet()) {
            StringBuilder sb = new StringBuilder();
            String table_name = entry.getKey();
            String table_Comment = TABLENAME_TABLECOMMENT_MAP.get(table_name);
            String JavaName = table_nameToJavaName(table_name);

            sb.append("package " + pumpConfig.getPackageTarget() + ";\n\n");

            sb.append("import javax.persistence.*; \n");
            sb.append("import java.sql.Time; \n");
            sb.append("import java.util.*; \n");
            if (pumpConfig.getUseLombok()) {
                sb.append("import lombok.Data; \n");
            }
            sb.append("import run.mydata.annotation.*; \n\n");

            //sb.append("import java.math.*;\n\n");

            sb.append("/***\n");
            sb.append(" * " + table_Comment + "\n");
            sb.append(" * @author Mydata \n");
            sb.append(" */\n");
            sb.append("@Table(name = \"" + table_name + "\")\n");
            if (table_Comment != null && table_Comment.trim().length() != 0) {
                sb.append("@TableComment(\"" + table_Comment + "\")\n");
            }
            if (pumpConfig.getUseLombok()) {
                sb.append("@TableComment(\"" + table_Comment + "\")\n");
            }
            if (pumpConfig.getUseLombok()) {
                sb.append("@Data \n");
            }
            sb.append("public class " + JavaName + " { \n");

            Map<String, DescInfo> FIELD_NAME$DESCINFO_MAP = entry.getValue();

            StringBuffer get$setSb = new StringBuffer();
            for (Map.Entry<String, DescInfo> field_name$descinfo_en : FIELD_NAME$DESCINFO_MAP.entrySet()) {
                //String field_name = field_name$descinfo_en.getKey();
                DescInfo desc_info = field_name$descinfo_en.getValue();
                String line = resolverDescInfoToLine(desc_info, pumpConfig.getToHump());
                get$setSb.append(resolverDescInfoToGetSetLine(pumpConfig.getToHump(), desc_info));
                sb.append(line);
            }

            if (!pumpConfig.getUseLombok()) {
                sb.append(get$setSb.toString());
            }

            sb.append("} ");

            FileOutputStream out = null;
            try {
                out = new FileOutputStream(new File(domainPackageTarget + "/" + JavaName + ".java"));
                out.write(sb.toString().getBytes());
                out.flush();
            } catch (Exception e) {
                e.printStackTrace();
                throw e;
            } finally {
                if (out != null) {
                    out.close();
                }
            }
        }


        //解析表字段
        //创建domain
    }

    private String resolverDescInfoToGetSetLine(Boolean toHump, DescInfo desc_info) {
        String type = desc_info.getType();
        String field = desc_info.getField();
        String reField = resolverField(toHump, field);
        String typeName = resolverType(type);
        String upReField = (reField.charAt(0) + "").toUpperCase() + reField.substring(1);
        String loReField = (reField.charAt(0) + "").toLowerCase() + reField.substring(1);

        StringBuilder sb = new StringBuilder();
        sb.append("    public " + typeName + " get" + upReField + "() {\n");
        sb.append("        return " + loReField + ";\n");
        sb.append("    }\n\n");

        sb.append("    public " + resolverType(type) + " set" + upReField + "(" + typeName + " " + loReField + ") {\n");
        sb.append("        this." + loReField + "=" + loReField + ";\n");
        sb.append("    }\n\n");

        return sb.toString();
    }

    private String table_nameToJavaName(String table_name) {
        String JavaName = lineToHump(table_name);
        return (JavaName.charAt(0) + "").toUpperCase() + JavaName.substring(1);
    }

    private String resolverDescInfoToLine(DescInfo descInfo, Boolean toHump) {
        //map.put("Field", Field);
        //map.put("Type", Type);
        //map.put("Null", Null);
        //map.put("Key", Key);
        //map.put("Default", Default);
        //map.put("Extra", Extra);
        String $Column = resolverFieldColumnAnnos(descInfo);
        String private_String_name_$ = resolverFieldType(descInfo.getField(), descInfo.getType(), toHump);
        return $Column + private_String_name_$;
    }

    private String resolverFieldType(String Field, String Type, Boolean toHump) {
        String line = "    private " + resolverType(Type) + " " + resolverField(toHump, Field) + ";\n\n";
        return line;
    }

    private String resolverField(Boolean toHump, String Field) {
        if (toHump) {
            return this.humpToLine2(Field);
        } else {
            return Field;
        }
    }

    private String resolverType(String Type) {
        //private $String$ name;
        String typeName = Type.split("\\(")[0].toLowerCase();
        return TypeEnum.getJavaTypeNameByType(typeName); //return TypeEnum.getJavaTypeNameByType(Type);
    }

    private String resolverFieldColumnAnnos(DescInfo descInfo) {
        StringBuilder sb = new StringBuilder();

        if (descInfo.getKey() != null && descInfo.getKey().trim().length() != 0 && descInfo.getKey().toUpperCase().startsWith("PRI")) {
            sb.append("    @Id \n");
            if (descInfo.getExtra().toUpperCase().startsWith("AUTO_INCREMENT")) {
                sb.append("    @GeneratedValue(strategy = GenerationType.IDENTITY) \n");
            }
        } else {
            if (descInfo.getKey() != null && descInfo.getKey().trim().length() != 0 && descInfo.getKey().toUpperCase().startsWith("MUL")) {
                sb.append("    @MyIndex \n");
            }
            Boolean unique = descInfo.getKey() != null && descInfo.getKey().trim().length() != 0 && descInfo.getKey().toUpperCase().startsWith("UNI");
            sb.append("    @Column(name = \"" + descInfo.getField() + "\"");
            if (unique) {
                sb.append(", unique = true");
            }
            if (descInfo.getType().contains("(")) {
                String type = descInfo.getType();
                String length = descInfo.getType().substring(type.indexOf("(") + 1, type.indexOf(")"));
                sb.append(", length = " + length);
            }
            if (descInfo.getNulll().toUpperCase().startsWith("NO")) {
                sb.append(", nullable = false");
            }
            sb.append(")\n");

            if (descInfo.getComment() != null && descInfo.getComment().trim().length() != 0) {
                sb.append("    @ColumnComment(\"" + descInfo.getComment() + "\") \n");
            }
        }
        return sb.toString();
    }

    public static enum TypeEnum {
        Byte("tinyint", "Byte"),
        Short("smallint", "Short"),
        Integer("int", "Integer"),
        Long("bigint", "Long"),

        StringChar("char", "String"),
        StringVarchar("varchar", "String"),

        Boolean("bit", "Boolean"),

        Date("date", "Date"),
        DateTimestamp("timestamp", "Date"),
        DateDatetime("datetime", "Date"),
        Time("time", "Time"),
        ;

        private String type;
        private String javaTypeName;

        TypeEnum(String type, String javaTypeName) {
            this.type = type;
            this.javaTypeName = javaTypeName;
        }

        public String getType() {
            return type;
        }

        public String getJavaTypeName() {
            return javaTypeName;
        }

        public static String getJavaTypeNameByType(String Type) {
            for (TypeEnum enumConstant : TypeEnum.class.getEnumConstants()) {
                if (enumConstant.getType().equals(Type)) {
                    return enumConstant.getJavaTypeName();
                }
            }
            return "null";
        }
    }

    private static class DescInfo {
        private String field;
        private String type;
        private String nulll;
        private String key;
        private String defaultt;
        private String extra;
        private String comment;

        public String getField() {
            return field;
        }

        public void setField(String field) {
            this.field = field;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getNulll() {
            return nulll;
        }

        public void setNulll(String nulll) {
            this.nulll = nulll;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getDefaultt() {
            return defaultt;
        }

        public void setDefaultt(String defaultt) {
            this.defaultt = defaultt;
        }

        public String getExtra() {
            return extra;
        }

        public void setExtra(String extra) {
            this.extra = extra;
        }

        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }
    }


    private static Pattern linePattern = Pattern.compile("_(\\w)");

    public static String lineToHump(String str) {
        str = str.toLowerCase();
        Matcher matcher = linePattern.matcher(str);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, matcher.group(1).toUpperCase());
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public static String humpToLine(String str) {
        return str.replaceAll("[A-Z]", "_$0").toLowerCase();
    }

    private static Pattern humpPattern = Pattern.compile("[A-Z]");

    public static String humpToLine2(String str) {
        Matcher matcher = humpPattern.matcher(str);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, "_" + matcher.group(0).toLowerCase());
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

}
