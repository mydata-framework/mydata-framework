package run.mydata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * index 索引, key 索引, 联合索引项
 *
 * @author Liu Tao
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Other {

    /**
     * 属性名称
     *
     * @return .
     */
    String name() default "";

    /**
     * 字符串类型索引长度
     *
     * @return .
     */
    int length() default 0;

}
