package run.mydata.others.pump;

import java.io.File;
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
        //that dao package name , as com.dao
        private String daoPackage;
        //if pump all set true , default true
        private Boolean pumpAllDomain = true;
        //if pump not all , add to this domains list , as [ Adomain,Bdomain ]
        private List<String> domains = new ArrayList<>();

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
    }

    public void pump(PumpConfig config) {
        String domainPackage = config.getDomainPackage();
        if (domainPackage == null || domainPackage.trim().length() == 0) {
            throw new IllegalArgumentException(" PumpConfig domainPackage can not be Empty; Domain目录不能为空; ");
        }
        List<String> domainClassNameList = new ArrayList<>();
        if (!config.getPumpAllDomain() && config.getDomains() != null && !config.getDomains().isEmpty()) {
            domainClassNameList = config.getDomains();
        }
        String packages = domainPackage.replaceAll("\\.", "/");
        domainPackage = DomainToDaoPump.class.getResource("/").getPath().replace("/target/classes/", "/src/main/java/" + packages);

        File file = new File(domainPackage);
        File[] files = file.listFiles();
        for (File file1 : files) {
            String file1Name = file1.getName();
            if (file1Name.endsWith(".java")) {
                System.out.println(file1Name);
            }
        }

    }


}
