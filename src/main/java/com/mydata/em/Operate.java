package com.mydata.em;

/**
 * 查询条件规则操作符
 *
 * @author Liu Tao
 */
public enum Operate {
    /**
     * 等于
     */
    EQ {
        @Override
        public String getValue() {
            return "=";
        }
    },
    /**
     * 大于
     */
    GT {
        @Override
        public String getValue() {
            return ">";
        }
    },
    /**
     * 小于
     */
    LT {
        @Override
        public String getValue() {
            return "<";
        }
    },
    /**
     * 大于等于
     */
    GE {
        @Override
        public String getValue() {
            return ">=";
        }
    },
    /**
     * 小于等于
     */
    LE {
        @Override
        public String getValue() {
            return "<=";
        }
    },
    /**
     * 包含
     */
    IN {
        @Override
        public String getValue() {
            return " in";
        }
    },
    /***
     * 不包含
     */
    NOT_IN {
        @Override
        public String getValue() {
            return "  not  in";
        }

    },
    /**
     * 模糊
     */
    LIKE {
        @Override
        public String getValue() {
            return "  like  ";
        }
    },
    /**
     * 左模糊
     */
    LIKE_LEFT {
        @Override
        public String getValue() {
            return "  like  ";
        }
    },
    /**
     * 右模糊
     */
    LIKE_RIGHT {
        @Override
        public String getValue() {
            return "  like  ";
        }
    },
    /**
     * 不等于
     */
    NOT_EQ {
        @Override
        public String getValue() {
            return "<>";
        }
    }
    /**
     * 范围查询
     */
    ,
    BETWEEN {
        @Override
        public String getValue() {
            return "  between  ";
        }
    };

    @Override
    public String toString() {

        return getValue();
    }

    public abstract String getValue();
}
