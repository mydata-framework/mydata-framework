package run.mydata.others.pump;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * domain to dao pump
 * 领域实体转dao 泵
 *
 * @author tao.liu
 */
public class DomainToDaoPump {

    public static class PumpConfig {
        //that domain package name , as com.domain
        private String domainPackage;
        //that dao package name , as com.dao.impl
        private String daoPackage;
        //if pump all set true , default true
        private Boolean pumpAllDomain = true;
        //if pump not all , add to this domains list , as [ Adomain,Bdomain ]
        private List<String> domains = new ArrayList<>();
        //if dao use interface
        private Boolean useDaoInterface = false;
        //that dao interface package name , as com.dao
        private String daoInterfacePackage;

        public String getDomainPackage() {
            return domainPackage;
        }

        public void setDomainPackage(String domainPackage) {
            this.domainPackage = domainPackage;
        }

        public String getDaoPackage() {
            return daoPackage;
        }

        public void setDaoPackage(String daoPackage) {
            this.daoPackage = daoPackage;
        }

        public Boolean getPumpAllDomain() {
            return pumpAllDomain;
        }

        public void setPumpAllDomain(Boolean pumpAllDomain) {
            this.pumpAllDomain = pumpAllDomain;
        }

        public List<String> getDomains() {
            return domains;
        }

        public void setDomains(List<String> domains) {
            this.domains = domains;
        }

        public Boolean getUseDaoInterface() {
            return useDaoInterface;
        }

        public void setUseDaoInterface(Boolean useDaoInterface) {
            this.useDaoInterface = useDaoInterface;
        }

        public String getDaoInterfacePackage() {
            return daoInterfacePackage;
        }

        public void setDaoInterfacePackage(String daoInterfacePackage) {
            this.daoInterfacePackage = daoInterfacePackage;
        }
    }

    public void pump(PumpConfig config) throws Exception {
        String domainPackage = config.getDomainPackage();
        if (domainPackage == null || domainPackage.trim().length() == 0) {
            throw new IllegalArgumentException(" PumpConfig domainPackage can not be Empty; Domain目录不能为空; ");
        }
        String daoPackage = config.getDaoPackage();
        if (daoPackage == null || daoPackage.trim().length() == 0) {
            throw new IllegalArgumentException(" PumpConfig daoPackage can not be Empty; Dao目录不能为空; ");
        }
        Boolean useDaoInterface = config.getUseDaoInterface();
        String daoInterfacePackage = config.getDaoInterfacePackage();
        if (useDaoInterface && (daoInterfacePackage == null || daoInterfacePackage.trim().length() == 0)) {
            throw new IllegalArgumentException(" PumpConfig useDaoInterface is true, that daoInterfacePackage can not be Empty; 如使用Dao接口,那么Dao接口目录不能为空; ");
        }

        String domainPackageRealFilePath = DomainToDaoPump.class.getResource("/").getPath().replace("/target/classes/", "/src/main/java/" + domainPackage.replaceAll("\\.", "/"));
        String daoInterfacePackageFileRealPath = null;
        if (useDaoInterface) {
            daoInterfacePackageFileRealPath = DomainToDaoPump.class.getResource("/").getPath().replace("/target/classes/", "/src/main/java/" + daoInterfacePackage.replaceAll("\\.", "/"));
        }
        String daoPackageFileRealPath = DomainToDaoPump.class.getResource("/").getPath().replace("/target/classes/", "/src/main/java/" + daoPackage.replaceAll("\\.", "/"));

        List<String> queryFileAllDomainClassNameList = new ArrayList<>();
        File file = new File(domainPackageRealFilePath);
        File[] files = file.listFiles();
        for (File file1 : files) {
            String file1Name = file1.getName();
            if (file1Name.endsWith(".java")) {
                queryFileAllDomainClassNameList.add(file1Name);
            }
        }
        if (queryFileAllDomainClassNameList.isEmpty()) {
            return;
        }
        List<String> domainClassNameList = new ArrayList<>();
        if (config.getPumpAllDomain()) {
            domainClassNameList = queryFileAllDomainClassNameList;
        } else {
            if (config.getDomains() != null && !config.getDomains().isEmpty()) {
                for (String domainClassName : config.getDomains()) {
                    if (queryFileAllDomainClassNameList.contains(domainClassName+".java")) {
                        domainClassNameList.add(domainClassName);
                    }
                }
            } else {
                return;
            }
        }


        for (String domainClassName : domainClassNameList) {
            String domainName = domainClassName.replace(".java", "");
            //转化为dao
            if (useDaoInterface) {
                generateDaoInterface(domainPackage, domainName, daoInterfacePackage, daoInterfacePackageFileRealPath);
            }
            generateDao(domainPackage, domainName, daoPackage, useDaoInterface, daoInterfacePackage, daoPackageFileRealPath);
        }
    }


    //
    // package com.main.dao;
    // import com.main.domain.Student;
    // import run.mydata.dao.base.IMyData;
    // public interface IStudentDao extends IMyData<Student> {
    // }
    //
    private void generateDaoInterface(String domainPackage, String domainName, String daoInterfacePackage, String daoInterfacePackageFileRealPath) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(daoInterfacePackage).append(";\n\n");
        sb.append("import ").append(domainPackage).append(".").append(domainName).append(";\n");
        sb.append("import run.mydata.dao.base.IMyData;\n\n");
        sb.append("public interface ").append("I").append(domainName).append("Dao extends IMyData<").append(domainName).append("> {\n")
                .append("}");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(daoInterfacePackageFileRealPath + "/" + ("I" + domainName + "Dao") + ".java"));
            out.write(sb.toString().getBytes());
            out.flush();
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    //
    //package com.main.dao.impl;
    //import com.main.dao.IStudentDao;
    //import com.main.domain.Student;
    //import org.springframework.stereotype.Repository;
    //import run.mydata.dao.base.impl.MyData;
    //@Repository
    //public class StudentDao extends MyData<Student> implements IStudentDao {
    //}
    //
    private void generateDao(String domainPackage, String domainName, String daoPackage, Boolean useDaoInterface, String daoInterfacePackage, String daoPackageFileRealPath) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("package " + daoPackage + ";\n\n");

        if (useDaoInterface) {
            sb.append("import ").append(daoInterfacePackage).append(".I").append(domainName).append("Dao;\n");
        }
        sb.append("import ").append(domainPackage).append(".").append(domainName).append(";\n");
        sb.append("import org.springframework.stereotype.Repository;\n");
        sb.append("import run.mydata.dao.base.impl.MyData;\n\n");

        sb.append("@Repository\n");
        sb.append("public class ").append(domainName).append("Dao extends MyData<").append(domainName).append(">");
        if (useDaoInterface) {
            sb.append(" implements I").append(domainName).append("Dao");
        }
        sb.append(" {\n")
                .append("}");

        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(daoPackageFileRealPath + "/" + (domainName + "Dao") + ".java"));
            out.write(sb.toString().getBytes());
            out.flush();
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

}
