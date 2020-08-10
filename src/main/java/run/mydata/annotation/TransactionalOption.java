package run.mydata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 选择性的事务管理
 *
 * @author Liu Tao
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TransactionalOption {

    /**
     * 连接管理器名称
     * @return .
     */
    String[] connectionManagerNames() default {};

}
