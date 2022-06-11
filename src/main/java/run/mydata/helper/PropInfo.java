package run.mydata.helper;


import run.mydata.annotation.ColumnRule;
import run.mydata.annotation.ColumnType;
import run.mydata.annotation.MyIndexFullText;
import run.mydata.annotation.MyIndex;

import javax.persistence.EnumType;
import javax.persistence.GenerationType;

/**
 * 字段映射信息
 *
 * @author Liu Tao
 */
public class PropInfo {
    // 属性名称
    private String fieldName;
    // 数据库字段名称
    private String columnName;
    // 是否主键
    private Boolean isPrimarykey = false;
    // 如果是切分字段，包含切分数据配置
    private ColumnRule columnRule;
    // 属性类型
    private Class<?> fieldTypeClass;
    // java.sql.Types,数据库字段类型
    private Integer sqlTypes;
    // 是否大字段
    private Boolean isLob = false;
    // 字段长度
    private Integer length = 255;
    // 是否不为空
    private Boolean isNotNull = false;
    // 是否唯一
    private Boolean isUnique = false;
    // 主键是否自动增长
    private GenerationType generatorValueAnnoStrategyVal;
    // 全局ID表初始值，小于10位的数字
    private String generatorValueAnnoGeneratorVal;
    // 创建索引信息
    private MyIndex index;
    // 创建全文索引信息
    private MyIndexFullText fullTextIndex;
    // 枚举映射数据库的类型
    private EnumType enumType;
    // 数据库字段备注
    private String comment;
    //是否是version
    private Boolean version=false;
    //双精度长度定义,例如 DECIMAL(8,2)
    private String moreLength;
    //特殊场景写主动指定表字段类型
    private ColumnType columnType;

    public String getFieldName() {
        return fieldName;
    }

    public String getGeneratorValueAnnoGeneratorVal() {
        return generatorValueAnnoGeneratorVal;
    }

    public void setGeneratorValueAnnoGeneratorVal(String generatorValueAnnoGeneratorVal) {
        this.generatorValueAnnoGeneratorVal = generatorValueAnnoGeneratorVal;
    }

    public MyIndex getIndex() {
        return index;
    }

    public void setIndex(MyIndex index) {
        this.index = index;
    }

    public PropInfo(String cname, Integer sqlTypes) {
        super();
        this.columnName = cname;
        this.sqlTypes = sqlTypes;
    }

    public GenerationType getGeneratorValueAnnoStrategyVal() {
        return generatorValueAnnoStrategyVal;
    }

    public void setGeneratorValueAnnoStrategyVal(GenerationType generatorValueAnnoStrategyVal) {
        this.generatorValueAnnoStrategyVal = generatorValueAnnoStrategyVal;
    }

    public EnumType getEnumType() {
        return enumType;
    }

    public void setEnumType(EnumType enumType) {
        this.enumType = enumType;
    }

    public Boolean getIsLob() {
        return isLob;
    }

    public Boolean getIsUnique() {
        return isUnique;
    }

    public void setIsUnique(Boolean isUnique) {
        this.isUnique = isUnique;
    }

    public Boolean getIsNotNull() {
        return isNotNull;
    }

    public void setIsNotNull(Boolean isNotNull) {
        this.isNotNull = isNotNull;
    }

    public void setIsLob(Boolean isLob) {
        this.isLob = isLob;
    }

    public Integer getLength() {
        return length;
    }

    public void setLength(Integer length) {
        this.length = length;
    }

    public PropInfo(String pname, Class<?> type) {
        super();
        this.fieldName = pname;
        this.fieldTypeClass = type;
    }

    public Class<?> getFieldTypeClass() {
        return fieldTypeClass;
    }

    public void setFieldTypeClass(Class<?> fieldTypeClass) {
        this.fieldTypeClass = fieldTypeClass;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public PropInfo() {
        super();
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public Boolean getIsPrimarykey() {
        return isPrimarykey;
    }

    public void setIsPrimarykey(Boolean isPrimarykey) {
        this.isPrimarykey = isPrimarykey;
    }

    public ColumnRule getColumnRule() {
        return columnRule;
    }

    public void setColumnRule(ColumnRule columnRule) {
        this.columnRule = columnRule;
    }

    public Integer getSqlTypes() {
        return sqlTypes;
    }

    public void setSqlTypes(Integer sqlTypes) {
        this.sqlTypes = sqlTypes;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Boolean getVersion() {
        return version;
    }

    public void setVersion(Boolean version) {
        this.version = version;
    }

    public String getMoreLength() {
        return moreLength;
    }

    public void setMoreLength(String moreLength) {
        this.moreLength = moreLength;
    }

    public MyIndexFullText getFullTextIndex() {
        return fullTextIndex;
    }

    public void setFullTextIndex(MyIndexFullText fullTextIndex) {
        this.fullTextIndex = fullTextIndex;
    }

    public ColumnType getColumnType() {
        return columnType;
    }

    public void setColumnType(ColumnType columnType) {
        this.columnType = columnType;
    }
}
