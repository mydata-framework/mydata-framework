package run.mydata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * fulltext 全文索引
 *
 * @author Liu Tao
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MyIndexFullText {

    /**
     * 索引名称
     *
     * @return .
     */
    String name() default "";


    /**
     * 联合索引的第二个属性名称
     *
     * @return .
     */
    Other[] otherPropName() default {};

    /**
     * 解析器
     *
     * @return .
     */
    String parser() default "WITH PARSER ngram"; //you can set other parser , as  "WITH PARSER mecab" ;

    /**
     * 字符串类型索引长度
     *
     * @return .
     */
    int length() default 0;

}
