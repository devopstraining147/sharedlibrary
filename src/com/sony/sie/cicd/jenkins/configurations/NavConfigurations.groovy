package com.sony.sie.cicd.jenkins.configurations

class NavConfigurations extends BaseConfigurations {
    
    NavConfigurations(def pipelineDefinition){
        super(pipelineDefinition)
    }

    def configDeploymentMap(def confMap, def deployInfo, String fileName, String clusterLineEnvName) {
        def clusterId = deployInfo.clusterId
        checkConfigParam "cluster", clusterId, fileName
        def psenv = deployInfo.sieEnv
        checkConfigParam "psenv", psenv, fileName
        String approver = confMap.approvers.devApprover + "," + confMap.approvers.coApprover
        def valuesConfigFiles = deployInfo.valuesConfigFiles ?: confMap.valuesConfigFiles ?: ["values-${clusterLineEnvName}.yaml"]
        String region = deployInfo.awsRegion
        if(region == "usw2") region = "us-west-2"
        def conf = [approver: approver, psenv: psenv, clusterName: clusterLineEnvName, clusterId: clusterId,
                region: region, valuesConfigFiles: valuesConfigFiles, prodServiceNowManualCR: true, manualTestingHold: confMap.manualTestingHold]

        return conf
    }
}

