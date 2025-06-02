package com.sony.sie.cicd.jenkins.configurations

class LacoConfigurations extends BaseConfigurations {

    LacoConfigurations(def pipelineDefinition) {
        super(pipelineDefinition)
    }

    def configDeploymentMap(def confMap, def deployInfo, String fileName, String cluster) {
        def clusterId = deployInfo.clusterId
        checkConfigParam "cluster", clusterId, fileName
        def psenv = deployInfo.sieEnv
        checkConfigParam "psenv", psenv, fileName
        String approver = confMap.approvers.devApprover + "," + confMap.approvers.coApprover
        def valuesConfigFiles = deployInfo.valuesConfigFiles ?: confMap.valuesConfigFiles ?: ["values-${cluster}.yaml"]
        String region = deployInfo.awsRegion
        if(region == "usw2") region = "us-west-2"

        return [approver: approver, psenv: psenv, clusterName: cluster, clusterId: clusterId,
                region: region, valuesConfigFiles: valuesConfigFiles,
                raaEnabled: confMap.raaEnabled ?: false, prodServiceNowManualCR: true, manualTestingHold: confMap.manualTestingHold]
    }
}
