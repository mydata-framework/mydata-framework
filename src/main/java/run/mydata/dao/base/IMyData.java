package run.mydata.dao.base;


import run.mydata.em.StatisticsType;
import run.mydata.helper.OrderBy;
import run.mydata.helper.PageData;
import run.mydata.helper.Param;

import java.io.Serializable;
import java.util.*;

/**
 * IMyData
 * @author Liu Tao
 * @param <POJO>
 */
public interface IMyData<POJO> {

    /**
     * 保存
     * @param pojo
     * @return
     */
    Integer save(POJO pojo);

    /**
     * 批量保存
     * @param pojos
     * @return
     */
    Integer saveList(List<POJO> pojos);

    /**
     * 更新
     * @param po
     */
    void update(POJO po);

    /**
     * 更新
     * @param pms 条件 {"id":1}
     * @param mps {"username":"liutao","password":"root"}
     * @return
     */
    Integer update(Set<Param> pms, Map<String, Object> mps);

    /**
     * 根据条件删除
     * @param pms 条件
     * @return
     */
    Integer delete(Set<Param> pms);

    /**
     * 根据主键删除
     * @param id
     * @return
     */
    Integer deleteById(Serializable... id);

    /**
     * 查询总记录数
     * @param pms 条件
     * @param distincts   distincts=>COUNT(distincts),  NULL => COUNT(*)
     * @return
     */
    Long getCount(Set<Param> pms, String... distincts);

    /**
     * 根据条件查询所有记录
     * @param pms 条件
     * @param cls SELECT cls FROM pms ;  NULL=> SELECT * FROM pms
     * @return
     */
    List<POJO> getList(Set<Param> pms, String... cls);

    /**
     * 根据条件查询排序列表
     * @param orderbys  SELECT cls FROM TABLE WHERE pms ORDER BY orderbys
     * @param pms
     * @param cls  NULL=>SELECT * FROM TABLE WHERE pms ORDER BY orderbys
     * @return
     */
    List<POJO> getListOrderBy(Set<Param> pms, LinkedHashSet<OrderBy> orderbys, String... cls);

    /**
     * 去重
     * @param pms SELECT DISTINCT cls FROM TABLE WHERE pms
     * @param isDistinct
     * @param cls
     * @return
     */
    List<POJO> getList(Set<Param> pms, boolean isDistinct, String... cls);

    /**
     * 查询全部
     * @param cls
     * @return
     */
    List<POJO> getAll(String... cls);

    /**
     * 查询全部并排序
     * @param orderbys SELECT cls FROM TABLE ORDER BY orderbys
     * @param cls NULL=>SELECT * FROM TABLE ORDER BY orderbys
     * @return
     */
    List<POJO> getAllOrderBy(LinkedHashSet<OrderBy> orderbys, String... cls);

    /**
     * 根据主键列表查询
     * @param ids SELECT cls FROM TABLE WHERE ID IN (ids)
     * @param cls NULL=>SELECT * FROM TABLE WHERE ID IN (ids)
     * @return
     */
    List<POJO> getListByIdsIn(List<Serializable> ids, String... cls);

    /**
     * 根据字段值列表查询
     * @param propertyName SELECT cls FROM TABLE WHERE propertyName IN (vls)
     * @param vls
     * @param cls NULL=>SELECT * FROM TABLE WHERE propertyName IN (vls)
     * @return
     */
    List<POJO> getListByParamIn(String propertyName, List<Serializable> vls, String... cls);

    /**
     * 根据主键查询对象
     * @param id  SELECT cls FROM TABLE WHERE ID = id
     * @param cls NULL=>SELECT * FROM TABLE WHERE ID=id
     * @return
     */
    POJO getById(Serializable id, String... cls);

    /**
     * 根据唯一限定条件查询对象  如果有多条返回 null
     * @param propertyName SELECT cls FROM TABLE WHERE propertyName=value
     * @param value
     * @param cls NULL=>SELECT * FROM TABLE WHERE propertyName=value
     * @return
     */
    POJO getOne(String propertyName, Serializable value, String... cls);

    /**
     * 根据多个限定条件查询对象  如果有多条返回 null
     * @param pms SELECT cls FROM TABLE WHERE pms
     * @param cls NULL=>SELECT * FROM TABLE WHERE pms
     * @return
     */
    POJO getOne(Set<Param> pms, String... cls);

    /**
     * 不排序分页查询数据集合 性能好
     * @param curPage SELECT cls FROM TABLE WHERE pms LIMIT 1,10
     * @param pageSize
     * @param pms
     * @param cls NULL=>SELECT * FROM TABLE WHERE pms LIMIT 1,10
     * @return
     */
    List<POJO> getPageList(int curPage, int pageSize, Set<Param> pms,  String... cls);

    /**
     * 根据条件分页排序查询
     * @param curPage SELECT cls FROM TABLE WHERE pms ORDER BY orderbys LIMIT ... curPage 1,pageSize 10
     * @param pageSize
     * @param pms
     * @param orderbys
     * @param cls NULL=>SELECT * FROM TABLE WHERE pms ORDER BY orderbys LIMIT ... curPage 1,pageSize 10
     * @return
     */
    List<POJO> getPageList(int curPage, int pageSize, Set<Param> pms, LinkedHashSet<OrderBy> orderbys, String... cls);

    /**
     * 分组查询分页列表
     * @param curPage   SELECT  [funs...],[groupbys...] FROM TABLE  WHERE pms GROUP  BY  groupbys ORDER  BY orderbys  LIMIT ... curPage 1,pageSize 10
     * @param pageSize
     * @param pms
     * @param orderbys 排序字段名,必须包含在返回的数据之内,返回数据或是funs或是groupbys
     * @param funs    <函数名,属性名>
     * @param groupby
     * @return [ [funs...,groupbys...] , [...] ]
     */
    List<Object[]> getGroupPageList(int curPage, int pageSize, Set<Param> pms, LinkedHashSet<OrderBy> orderbys, LinkedHashMap<String, String> funs, String... groupby);

    /**
     * 分组查询总记录数
     * @param pms  SELECT COUNT(*) FROM TABLE WHERE pms GROUP BY groupby
     * @param groupby
     * @return
     */
    Long getGroupbyCount(Set<Param> pms, String... groupby);

    /**
     * 分页不排序 性能高，速度快
     * @param pms SELECT cls FROM TABLE WHERE pms LIMT 1,10
     * @param curPage
     * @param pageSize
     * @param cls NULL=> SELECT * FROM TABLE WHERE pms LIMT 1,10
     * @return
     */
    PageData<POJO> getPageInfo(int curPage, int pageSize, Set<Param> pms, String... cls);

    /**
     * 获取分页列表
     * @param curPage SELECT cls FROM TABLE WHERE pms ORDER BY orderbys LIMIT 1,10
     * @param pageSize
     * @param orderbys
     * @param pms
     * @param cls
     * @return
     */
    PageData<POJO> getPageInfo(int curPage, int pageSize, Set<Param> pms, LinkedHashSet<OrderBy> orderbys, String... cls);

    /***
     * 分组分页
     * @param curPage  SELECT  [funs...],[groupbys...] FROM TABLE  WHERE pms GROUP  BY  groupbys ORDER  BY orderbys  LIMIT  0,10;
     * @param pageSize
     * @param orderbys 排序字段名,必须包含在返回的数据之内,返回数据或是funs或是groupbys
     * @param pms      查询条件
     * @param funs     统计函数
     * @param groupby  分组字段不能为空
     * @return [ [funs...,groupbys...] , [...] ]
     */
    PageData<Object[]> getGroupPageInfo(int curPage, int pageSize, Set<Param> pms,LinkedHashSet<OrderBy> orderbys, LinkedHashMap<String, String> funs, String... groupby);

    /**
     * 使用统计函数
     * @param pms          SELECT functionName(property) FROM TABLE WHERE pms
     * @param property     属性名称
     * @param functionName 函数名 StatisticsType.SUM, MIN, MAX
     * @return
     */
    Double getStatisticsValue(StatisticsType functionName , String property , Set<Param> pms);

    /**
     * 获取属性值列表
     * @param property SELECT property FROM TABLE WHERE
     * @param pms
     * @return
     */
    List<Object> getVList(String property, Set<Param> pms);

    /**
     * 去重 获取属性值列表
     * @param property SELECT DISTINCT property FROM TABLE WHERE pms
     * @param pms
     * @param isDistinct
     * @return
     */
    List<Object> getVList(String property, Set<Param> pms, boolean isDistinct);

    /**
     * 获取最小日期时间值
     * @param pms      条件
     * @param dataTypePropertyName 需要统计的日期时间属性名称
     * @return
     */
    Date getMinDate(Set<Param> pms, String dataTypePropertyName);

    /**
     * 获取最大日期时间值
     * @param pms      条件
     * @param dataTypePropertyName 需要统计的日期时间属性名称
     * @return
     */
    Date getMaxDate(Set<Param> pms, String dataTypePropertyName);

    /**
     * 原生查询 单实例查询
     * @param sql     SELECT * FROM TABLE WHERE id=? AND NAME=?
     * @param pms
     * @param resultClass
     * @param <T>
     * @return
     */
    <T> T nativeQuery(String sql, Object[] pms, Class<T> resultClass);

    /**
     * 原生查询 List查询
     * @param sql
     * @param pms
     * @param resultClass
     * @param <T>
     * @return
     */
    <T> List<T> nativeQueryList(String sql, Object[] pms, Class<T> resultClass);

    /**
     * 原生查询 分页查询
     * @param curPage
     * @param pageSize
     * @param sql
     * @param pms
     * @param result
     * @param <T>
     * @return
     */
    <T> PageData<T> nativeQueryPage(int curPage, int pageSize, String sql, Object[] pms, Class<T> result);

    /**
     * 原生执行操作
     * @param sql
     * @param pms
     * @return
     */
    int nativeExecute(String sql,Object[] pms);

    void refreshCurrentTables();
    List<POJO> getListFromMater(Set<Param> pms, String... cls);
    Long getCountFromMaster(Set<Param> pms, String... distincts);
    POJO getByIdFromMaster(Serializable id, String... strings);
    POJO getOneByMaster(String propertyName, Serializable value, String... cls);
    PageData<POJO> getPageInfoFromMaster(int curPage, int pageSize, Set<Param> params,LinkedHashSet<OrderBy> orderbys, String... strings);
    List<Object[]> getGroupPageListFromMaster(int curPage, int pageSize, Set<Param> pms,LinkedHashSet<OrderBy> orderbys, LinkedHashMap<String, String> funs, String... groupby);
    List<POJO> getPageListFromMaster(int curPage, int pageSize, Set<Param> pms, LinkedHashSet<OrderBy> orderbys, String... cls);
    List<POJO> getPageListFromMaster(int curPage, int pageSize,Set<Param> pms,String... cls);//从主库获取数据 不排序分页查询集合数据 性能最好
    PageData<POJO> getPageInfoFromMaster(Set<Param> pms, int curPage, int pageSize, String... cls);//分页不排序 性能高，速度快
    List<Object> getVListFromMaster(String property, Set<Param> pms);
    List<Object> getVListFromMaster(String property, Set<Param> pms, boolean isDistinct);
    List<POJO> getListFromMater(Set<Param> pms, boolean isDistinct,  String... cls);
    Long getGroupbyCountFromMaster(Set<Param> pms, String... groupby);
}
