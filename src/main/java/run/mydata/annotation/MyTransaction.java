
package run.mydata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 事务管理-开启事务
 *
 * @author Liu Tao
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MyTransaction {

    /**
     *
     * @return .
     */
    boolean readOnly() default false;

    /**
     *
     * @return .
     */
    String connectionManager() default "";
}
