package com.sony.sie.cicd.cd.utilities

class KmjServiceNow extends BaseServiceNow {

    KmjServiceNow(Map deployConfiguration = [infrastructure: "kamaji-cloud"]){
        super(deployConfiguration)
    }

    def getOverrideCrDefinition(def serviceName){
        return [:]
    }

    def getAloyApproverGroup() {
        String aloyGroup = 'PSN-CoreJenkinsProd-BlkOut-Kmj'
        String contactGroup = 'PSN-CoreJenkinsProd-BlkOut-Kmj'
        return [aloyGroup, contactGroup]
    }

    String getRestrictionPlatform() {
        return "kamaji"
    }

    def getTestEvidenceEnvs(String psenv) {
        String testEnv = ""
        String depEnv = ""
        switch (psenv) {   
            case 'p1-np':
            case 'p1-mgmt':
            case 'p1-pqa':
            case 'p1-spint':
                testEnv = 'E'
                depEnv = 'P'
                break
        }
        return [testEnv, depEnv]
    }

    def getTestEnv(String psenv) {
        String testEnv = ""
        switch (psenv) {   
            case 'p1-np':
            case 'p1-mgmt':
            case 'p1-pqa':
            case 'p1-spint':
                testEnv = 'e1-np'
                break
        }
        return testEnv
    }
}
