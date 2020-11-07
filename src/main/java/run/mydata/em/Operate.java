package run.mydata.em;

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
            return " IN";
        }
    },
    /***
     * 不包含
     */
    NOT_IN {
        @Override
        public String getValue() {
            return "  NOT  IN";
        }

    },
    /**
     * 模糊
     */
    LIKE {
        @Override
        public String getValue() {
            return "  LIKE  ";
        }
    },
    /**
     * 左模糊
     */
    LIKE_LEFT {
        @Override
        public String getValue() {
            return "  LIKE  ";
        }
    },
    /**
     * 右模糊
     */
    LIKE_RIGHT {
        @Override
        public String getValue() {
            return "  LIKE  ";
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
            return "  BETWEEN  ";
        }
    },



    /**
     * Column 等于
     */
    C_EQ {
        @Override
        public String getValue() {
            return "=";
        }
    },
    /**
     * Column 大于
     */
    C_GT {
        @Override
        public String getValue() {
            return ">";
        }
    },
    /**
     * Column 小于
     */
    C_LT {
        @Override
        public String getValue() {
            return "<";
        }
    },
    /**
     * Column 大于等于
     */
    C_GE {
        @Override
        public String getValue() {
            return ">=";
        }
    },
    /**
     * Column 小于等于
     */
    C_LE {
        @Override
        public String getValue() {
            return "<=";
        }
    },
    /**
     * Column 不等于
     */
    C_NOT_EQ {
        @Override
        public String getValue() {
            return "<>";
        }
    },

    /**
     * FULLTEXT INDEX QUERY , 全文检索自然语言模型查询
     */
    AGAINST_IN_NATURAL_LANGUAGE_MODE{
        @Override
        public String getValue() {
            return "MATCH (%s) AGAINST (? IN NATURAL LANGUAGE MODE) ";
        }
    },
    /**
     * FULLTEXT INDEX QUERY , 全文检索BOOLEAN查询
     */
    AGAINST_IN_BOOLEAN_MODE{
        @Override
        public String getValue() {
            return "MATCH (%s) AGAINST (? IN BOOLEAN MODE) ";
        }
    },
    /**
     * FULLTEXT INDEX QUERY , 全文检索自然语言扩展查询
     */
    AGAINST_IN_NATURAL_LANGUAGE_MODE_WITH_QUERY_EXPANSION{
        @Override
        public String getValue() {
            return "MATCH (%s)  AGAINST (? IN NATURAL LANGUAGE MODE WITH QUERY EXPANSION) ";
        }
    },
    /**
     * FULLTEXT INDEX QUERY , 全文检索扩展查询
     */
    AGAINST_WITH_QUERY_EXPANSION{
        @Override
        public String getValue() {
            return "MATCH (%s)  AGAINST (? WITH QUERY EXPANSION) ";
        }
    },

    ;


    @Override
    public String toString() {

        return getValue();
    }

    public abstract String getValue();
}
