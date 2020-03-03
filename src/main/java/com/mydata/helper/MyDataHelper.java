package com.mydata.helper;

import com.mydata.annotation.TableComment;
import com.mydata.manager.IConnectionManager;

import javax.persistence.Table;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.sql.SQLException;

public class MyDataHelper<Pojo> {

    public static Class getDomainClassByDaoClass(Class<?> daoClass) {
        Type type = daoClass.getGenericSuperclass();
        String typeName = type.getTypeName();
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            Type[] ts = parameterizedType.getActualTypeArguments();
            Class cls = (Class) ts[0];
            return cls;
        } else {
            String errorMsg = String.format("[MyData find one error] , DAO 出现错误,缺少必要的泛型! 请检查目标类%s", typeName);
            throw new IllegalStateException(errorMsg);
        }
    }

    public static String getDataBaseTypeName(IConnectionManager connectionManager) throws SQLException {
        String databaseProductName = connectionManager.getConnection().getMetaData().getDatabaseProductName();
        return databaseProductName;
    }

    public static String getFirstTableName(Class<?> domainClazz) {
        String tableName = domainClazz.getSimpleName();
        if (domainClazz.isAnnotationPresent(Table.class)) {
            String tbn = domainClazz.getAnnotation(Table.class).name().trim();
            if (tbn.length() > 0) {
                tableName = tbn;
            }
        }
        return tableName;
    }

    public static String getTableColumn(Class<?> domainClazz) {
        TableComment tableComment = domainClazz.getAnnotation(TableComment.class);
        return tableComment==null?null:tableComment.value();
    }

}
