package com.sony.sie.cicd.jenkins.configurations

class KmjConfigurations extends BaseConfigurations {

    KmjConfigurations(def pipelineDefinition) {
        super(pipelineDefinition)
    }

    def configDeploymentMap(def confMap, def deployInfo, String fileName, String cluster) {
        def clusterId = deployInfo.clusterId 
        checkConfigParam "cluster", clusterId, fileName
        def psenv = deployInfo.sieEnv
        checkConfigParam "psenv", psenv, fileName
        String approver = ""
        switch (psenv) {
            case 'p1-np':
            case 'p1-mgmt':
            case 'p1-pqa':
            case 'p1-spint':
                approver = confMap.approvers.coApprover
                break
            default:
                approver = confMap.approvers.devApprover + "," + confMap.approvers.coApprover
                break
        }

        def valuesConfigFiles = deployInfo.valuesConfigFiles ?: confMap.valuesConfigFiles ?: ["values-${cluster}.yaml"]
        int deployApprovalTimeOut = psenv == "p1-np" ? 5 : 1
        return [approver: approver, psenv: psenv, clusterName: cluster, clusterId: clusterId, region: deployInfo.awsRegion,
                valuesConfigFiles: valuesConfigFiles, deployApprovalTimeOut: deployApprovalTimeOut,
                prodServiceNowManualCR: confMap.prodServiceNowManualCR, manualTestingHold: confMap.manualTestingHold]
    }
}


