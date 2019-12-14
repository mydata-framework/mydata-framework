package com.mydata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 索引信息
 *
 * @author Liu Tao
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MyIndex {

    /**
     * 索引名称
     */
    String name() default "";

    /**
     * 是否创建唯一索引
     *
     * @return
     */
    boolean unique() default false;

    /**
     * 联合索引的第二个属性名称
     *
     * @return
     */
    String otherPropName() default "";

    /**
     * 字符串类型索引长度
     *
     * @return
     */
    int length() default 20;
}
