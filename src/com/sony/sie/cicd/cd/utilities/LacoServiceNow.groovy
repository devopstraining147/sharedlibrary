package com.sony.sie.cicd.cd.utilities

class LacoServiceNow extends BaseServiceNow {

    LacoServiceNow(Map deployConfiguration = [infrastructure: "laco-cloud"]){
        super(deployConfiguration)
    }

    def getOverrideCrDefinition(def serviceName){
        return [:]
    }

    def getAloyApproverGroup() {
        String aloyGroup = 'PSN-CoreJenkinsProd-BlkOut-LACO'
        String contactGroup = 'PSN-CoreJenkinsProd-BlkOut-LACO'
        return [aloyGroup, contactGroup]
    }

    String getRestrictionPlatform() {
        return "navigator"
    }

    def getTestEvidenceEnvs(String psenv) {
        String testEnv = ""
        String depEnv = ""
        switch (psenv) { 
            case ~/.*e1.*/:            
                testEnv = 'Q'
                depEnv = 'E'
                break   
            case ~/.*p1.*/:
                testEnv = 'E'
                depEnv = 'P'
                break
        }
        return [testEnv, depEnv]
    }

    def getTestEnv(String psenv) {
        String testEnv = ""
        switch (psenv) { 
            case ~/.*e1.*/:            
                testEnv = 'q1-np'
                break   
            case ~/.*p1.*/:
                testEnv = 'e1-np'
                break
        }
        return testEnv
    }
}
