package run.mydata.annotation;

import run.mydata.em.RuleType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 分表规则 每个表只支持一个字段
 *
 * @author Liu Tao
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ColumnRule {

    /**
     * 切分类型
     * @return .
     */
    RuleType ruleType();

    /**
     * 规则基础数据
     * @return
     * 类型为RANGE代表每个表存放数据的最大数量,如果为MOD表示最多切分多少个表
     * RANGE： 1202/value=0表示数据保存在第一个表以此类推
     * MOD:12%value=12表示数据保存在第12个表里面以此类推
     */
    long value();
}
