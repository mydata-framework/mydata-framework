package run.mydata.helper;


import run.mydata.em.Operate;
import run.mydata.em.PmType;

import java.util.*;

/**
 * 查询条件参数
 *
 * @author Liu Tao
 */
public class Param {
    // 属性名称
    private String pname;
    // 操作
    private Operate operators;
    // between 第一个值
    private Object firstValue;
    // 值
    private Object value;
    // in查询条件的值
    private List<?> inValue;
    // 或者条件
    private Param orParam;
    // 条件类型
    private PmType cdType = PmType.VL;
    // 函数复合条件
    private String funName;

    public Param() {
        super();
    }

    public Param(String pname, Operate operators, Object value, String funName, PmType cdType) {
        super();
        this.pname = pname;
        this.operators = operators;
        if (operators == Operate.IN) {
            this.inValue = (List<?>) value;
        } else {
            this.value = value;
        }
        this.funName = funName;
        this.cdType = cdType;
    }

    public String getPname() {
        return pname;
    }

    public void setPname(String pname) {
        this.pname = pname;
    }

    public Operate getOperators() {
        return operators;
    }

    public void setOperators(Operate operators) {
        this.operators = operators;
    }

    public Object getFirstValue() {
        return firstValue;
    }

    public void setFirstValue(Object firstValue) {
        this.firstValue = firstValue;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public List<?> getInValue() {
        return inValue;
    }

    public void setInValue(List<?> inValue) {
        this.inValue = inValue;
    }

    public Param getOrParam() {
        return orParam;
    }

    public void setOrParam(Param orParam) {
        this.orParam = orParam;
    }

    public PmType getCdType() {
        return cdType;
    }

    public void setCdType(PmType cdType) {
        this.cdType = cdType;
    }

    public String getFunName() {
        return funName;
    }

    public void setFunName(String funName) {
        this.funName = funName;
    }


    /**
     * 除了between外,最常用的条件参数初始化
     *
     * @param pname     .
     * @param operators .
     * @param value     .
     */
    public Param(String pname, Operate operators, Object value) {
        super();
        this.pname = pname;
        this.operators = operators;
        if (operators.equals(Operate.IN) || operators.equals(Operate.NOT_IN)) {
            this.inValue = (List<?>) value;
        } else {
            if (operators.equals(Operate.LIKE)) {
                this.value = "%" + value + "%";
            } else if (operators.equals(Operate.LIKE_LEFT)) {
                this.value = "%" + value;
            } else if (operators.equals(Operate.LIKE_RIGHT)) {
                this.value = value + "%";
            } else {
                this.value = value;
            }
        }
    }

    /**
     * between 查询
     *
     * @param pname      .
     * @param firstValue .
     * @param value      .
     */
    public Param(Object firstValue, String pname, Object value) {
        super();
        this.pname = pname;
        this.firstValue = firstValue;
        this.value = value;
        this.operators = Operate.BETWEEN;
    }

    /**
     * in 查询
     *
     * @param pname   .
     * @param inValue .
     */
    public Param(String pname, List<?> inValue) {
        super();
        this.pname = pname;
        this.inValue = inValue;
        this.operators = Operate.IN;
    }

    public Param OR(Param param) {
        this.setOrParam(param);
        return this;
    }

    /**
     * 用于使用 AND  END
     */
    private Set<Param> params;

    public Param AND(String pname, Operate operators, Object value) {
        Param param = new Param(pname, operators, value);
        if (this.params == null) {
            params = new HashSet<>();
            params.add(this);
        }
        params.add(param);
        return this;
    }

    public Param AND(Object firstValue, String pname, Object value) {
        Param param = new Param(firstValue, pname, value);
        if (this.params == null) {
            params = new HashSet<>();
            params.add(this);
        }
        params.add(param);
        return this;
    }

    public Param AND(String pname, List<?> inValue) {
        Param param = new Param(pname, inValue);
        if (this.params == null) {
            params = new HashSet<>();
            params.add(this);
        }
        params.add(param);
        return this;
    }

    public Param AND(Param param) {
        if (this.params == null) {
            params = new HashSet<>();
            params.add(this);
        }
        params.add(param);
        return this;
    }

    public Set<Param> END() {
        if (this.params == null) {
            params = new HashSet<>();
            params.add(this);
        }
        return this.params;
    }

    public static Set<Param> getParams(Param... params) {
        if (params == null) {
            //return new HashSet<>(0);
            return new LinkedHashSet<>();
        }

        List<Param> asList = Arrays.asList(params);
        if (asList.size() > 0) {
            //return asList.stream().filter(pm -> pm != null && pm.getPname() != null && pm.getPname().trim().length() > 0).collect(Collectors.toSet());
            Set<Param> set = new LinkedHashSet<>();
            for (Param param : params) {
                set.add(param);
            }
            return set;
        } else {
            //return new HashSet<>(0);
            return new LinkedHashSet<>();
        }
    }

    public static LinkedHashMap<String, Object> getMap(Object... ag) {
        LinkedHashMap<String, Object> mp = new LinkedHashMap<>();
        if (ag != null && ag.length > 0 && ag.length % 2 == 0) {
            int i = 0;
            for (@SuppressWarnings("unused")
                    Object o : ag) {
                mp.put(String.valueOf(ag[i]), ag[++i]);
                i++;
                if (i == ag.length) {
                    break;
                }

            }
        }
        return mp;
    }

    public static LinkedHashMap<String, String> getStringMap(String... ag) {
        LinkedHashMap<String, String> mp = new LinkedHashMap<>();
        if (ag != null && ag.length > 0 && ag.length % 2 == 0) {
            int i = 0;
            for (@SuppressWarnings("unused")
                    Object o : ag) {
                mp.put(String.valueOf(ag[i]), ag[++i]);
                i++;
                if (i == ag.length) {
                    break;
                }

            }
        }
        return mp;
    }

    public static String[] getStringArr(String... ag) {
        return ag;
    }

    //缩短代码长度,推荐静态导入 import static run.mydata.helper.Param.*;  import static run.mydata.em.Operate.*; import static run.mydata.helper.OrderBy.*;
    public static Set<Param> ps(Param... params) {
        return getParams(params);
    }

    public static Param p(String pname, Operate operators, Object value) {
        return new Param(pname, operators, value);
    }

    public static Param p(Object firstValue, String pname, Object value) {
        return new Param(firstValue, pname, value);
    }

    public static Param p(String pname, List<?> inValue) {
        return new Param(pname, inValue);
    }

    public static Param p(String pname, Operate operators, Object value, String funName, PmType cdType) {
        return new Param(pname, operators, value, funName, cdType);
    }

    public static LinkedHashMap<String, Object> m(Object... ag) {
        return getMap(ag);
    }

    public static LinkedHashMap<String, String> sm(String... ag) {
        return getStringMap(ag);
    }

    public static Object[] arr(Object... ag) {
        return ag;
    }

    public static String[] sarr(String... ag) {
        return getStringArr(ag);
    }

    public static String MATCH(String... ag) {
        String matchField = "";
        for (int i = 0; i < ag.length; i++) {
            if (i == 0) {
                matchField += ag[i];
            } else {
                matchField += ("," + ag[i]);
            }
        }
        return matchField;
    }
}
