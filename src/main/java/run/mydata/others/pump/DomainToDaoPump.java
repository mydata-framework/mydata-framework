package run.mydata.others.pump;

import java.util.ArrayList;
import java.util.List;

/**
 * domain to dao pump
 * 领域实体转dao 泵
 *
 * @author tao.liu
 * @date 2020/7/31
 */
public class DomainToDaoPump {


    public static class PumpConfig {
        private String domainPackage;
        private String daoPackage;
        private Boolean pumpAllDomain = true;
        private List<String> domains = new ArrayList<>();

    }

}
