package com.sony.sie.cicd.cd.utilities

class RoadsterServiceNow extends BaseServiceNow {

    RoadsterServiceNow(Map deployConfiguration = [infrastructure: "roadster-cloud"]){
        super(deployConfiguration)
    }

    def getOverrideCrDefinition(def serviceName){
        return [:]
    }

    def getAloyApproverGroup() {
        String aloyGroup = 'PSN-CoreJenkinsProd-BlkOut-Rds'
        String contactGroup = 'PSN-CoreJenkinsProd-BlkOut-Rds'
        return [aloyGroup, contactGroup]
    }

    String getRestrictionPlatform() {
        return "roadster"
    }
    
    def getTestEvidenceEnvs(String psenv) {
        String testEnv = ""
        String depEnv = ""
        switch (psenv) {  
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
            case ~/.*p1.*/:
                testEnv = 'e1-np'
                break
        }
        return testEnv
    }
}
