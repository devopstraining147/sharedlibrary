package com.sony.sie.cicd.cd.utilities

import org.codehaus.groovy.GroovyException

class NavServiceNow extends BaseServiceNow {

    NavServiceNow(Map deployConfiguration = [infrastructure: "navigator-cloud"]){
        super(deployConfiguration)
    }

    def getOverrideCrDefinition(def serviceName){
        /* valuables in serviceNow CR
         * deploymentFramework: u_deployment_framework 
         */
        return [
            deploymentFramework : "5686db27dbffef006e52d1c2ca96191a",
       ]
    }
    
    def getAloyApproverGroup() {
        String aloyGroup = 'PSN-CoreJenkinsProd-BlkOut-Nav'
        String contactGroup = 'PSN-CoreJenkinsProd-BlkOut-Nav'
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
        String depEnv = ""
        (testEnv, depEnv) = getTestEvidenceEnvs(psenv)
        if(testEnv != "") {
            return psenv.replaceAll("${depEnv.toLowerCase()}1-", "${testEnv.toLowerCase()}1-")
        }
        return ""
    }
}
