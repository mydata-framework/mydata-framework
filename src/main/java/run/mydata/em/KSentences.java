package run.mydata.em;

/**
 * 所有支持的查询语句
 *
 * @author Liu Tao
 */
public enum KSentences {
    SPACING {
        @Override
        public String getValue() {
            return " ";
        }
    },
    AVG {
        @Override
        public String getValue() {
            return "  AVG";
        }
    },
    SUM {
        @Override
        public String getValue() {
            return "  SUM";
        }
    },

    UNION_ALL {
        @Override
        public String getValue() {
            return "  UNION  ALL  ";
        }
    },

    LIMIT {
        @Override
        public String getValue() {
            return "  LIMIT  ";
        }
    },

    /**
     * 删除语句前缀
     */
    DELETE_FROM {
        @Override
        public String getValue() {
            return " DELETE  FROM  ";
        }
    },

    /**
     * 查询条数语句
     */
    SELECT_COUNT {
        @Override
        public String getValue() {
            return " SELECT  COUNT(*)  FROM  ";
        }
    },

    INSERT {
        @Override
        public String getValue() {
            return " INSERT INTO  ";
        }
    },

    /**
     * 查询所有字段
     */
    SELECT_ALL {
        @Override
        public String getValue() {
            return "  *  ";
        }
    },

    /**
     * 位置占位符
     */
    POSITION_PLACEHOLDER {
        @Override
        public String getValue() {
            return "?";
        }
    },

    /**
     * 表名分隔符
     */
    SHARDING_SPLT {
        @Override
        public String getValue() {
            return "_";
        }

    },
    LIKE {
        @Override
        public String getValue() {
            return "  LIKE ";
        }
    },

    CREATE_SEQUENCE {
        @Override
        public String getValue() {
            return "CREATE SEQUENCE  ";
        }
    },

    CREATE_TABLE {
        @Override
        public String getValue() {
            return "CREATE TABLE ";
        }
    },
    COMMENT {
        @Override
        public String getValue() {
            return "COMMENT ";
        }
    },
    ENGINE {
        @Override
        public String getValue() {
            return "ENGINE";
        }
    },
    CHARSET {
        @Override
        public String getValue() {
            return "DEFAULT CHARSET";
        }
    },
    UPDATE {
        @Override
        public String getValue() {
            return " UPDATE  ";
        }
    },
    SELECT {
        @Override
        public String getValue() {
            return "  SELECT  ";
        }
    },

    COUNT {
        @Override
        public String getValue() {
            return " COUNT";
        }
    },
    UNIQUE {
        @Override
        public String getValue() {
            return "UNIQUE";
        }
    },
    FROM {
        @Override
        public String getValue() {
            return "  FROM  ";
        }
    },
    WHERE {
        @Override
        public String getValue() {
            return "  WHERE  ";
        }
    },
    /**
     * 分组
     */
    GROUPBY {
        @Override
        public String getValue() {
            return "  GROUP  BY  ";
        }
    },
    /**
     * 分组聚集查询条件
     */
    HAVING {
        @Override
        public String getValue() {
            return "  HAVING  ";
        }
    },
    /**
     * 并且
     */
    AND {
        @Override
        public String getValue() {
            return "  AND  ";
        }
    },
    OR {
        @Override
        public String getValue() {
            return "  OR  ";
        }
    },
    ON {
        @Override
        public String getValue() {

            return "  ON  ";
        }
    },
    /**
     * 降序
     */
    DESC {
        @Override
        public String getValue() {
            return "  DESC  ";
        }
    },
    RIGHT_BRACKETS {
        @Override
        public String getValue() {
            return ")";
        }
    },
    LEFT_BRACKETS {
        @Override
        public String getValue() {
            return "(";
        }
    },
    /**
     * 逗号
     */
    COMMA {
        @Override
        public String getValue() {
            return ",";
        }
    },
    /**
     * update SET VALUE =
     */
    EQ {
        @Override
        public String getValue() {
            return "=";
        }
    },
    /**
     * update SET
     */
    SET {
        @Override
        public String getValue() {
            return "   SET   ";
        }
    },
    /**
     * 去掉重复值
     */
    DISTINCT {
        @Override
        public String getValue() {
            return "  DISTINCT  ";
        }
    },
    IS_NULL {
        @Override
        public String getValue() {

            return "  IS  NULL  ";
        }
    },
    IS_NOT_NULL {
        @Override
        public String getValue() {
            return "  IS  NOT  NULL  ";
        }
    },
    ORDERBY {
        @Override
        public String getValue() {
            return "  ORDER  BY  ";
        }
    };

    @Override
    public String toString() {
        return getValue();
    }

    public abstract String getValue();
}
