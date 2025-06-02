package com.sony.sie.cicd.cd.utilities

import com.sony.sie.cicd.helpers.annotations.*
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import com.sony.sie.cicd.cd.utilities.DeployUtils

def process(def objPipeline){
    def jenkinsUtils = new JenkinsUtils()
    final DeployUtils deployUtils = new DeployUtils()

    try{
        echo "starting CD pipeline steps"
        def pipelineMethods = getPipelineMethods(objPipeline)
        def stageLabels = getStageLabels(objPipeline, pipelineMethods)
        processPipelineStages(objPipeline, pipelineMethods, stageLabels)
    } catch (Exception err) {
        if(!jenkinsUtils.isBuildAborted()){
            String errorMsg =  err.getMessage()
            if(!errorMsg) errorMsg = 'Unknown error!'
            echo "deployment failed: " + errorMsg
            currentBuild.result = "FAILURE"
        }
        throw err
    } finally {
        def conf = objPipeline.deployConfiguration
        if (conf.crSysIdMulti) {
            crType = deployUtils.isEmergencyDeployment() == false ? 'normal' : 'emergency'
            jenkinsUtils.jenkinsNode(infrastructure: "${conf.infrastructure}", templateType: 'helm', clusterList: [conf.clusterId]) {
                container(conf.clusterId) {
                    if (crType == "emergency") {
                        stage("Add Notes to Emergency CR") {
                            String buildStatus = jenkinsUtils.getBuildStatus().toLowerCase()
                            for (int i = 0; i < conf.crSysIdMulti.size(); i++) {
                                crName = conf.serviceNowConfig[i].crName
                                crSysId = conf.crSysIdMulti[i]
                                echo "Updating CR: ${crName}"
                                new NavServiceNow(conf).cRTicketClosure(crSysId, buildStatus, conf)
                            }
                        }
                    } else {
                        def stageName = 'Close ServiceNow CR'
                        def closeMsg = 'Closing CR:' 
                        if (conf.orchMultiEnvCR) {
                            stageName = 'Updating Close Notes for CR'
                            closeMsg = 'Updating Close Notes for CR:'     
                        }   
                        stage(stageName) {
                            echo "${closeMsg}  ${conf.crSysIdMulti}"
                            String buildStatus = jenkinsUtils.getBuildStatus().toLowerCase()
                            for (int i = 0; i < conf.crSysIdMulti.size(); i++) {
                                crName = conf.serviceNowConfig[i].crName
                                crSysId = conf.crSysIdMulti[i]
                                echo "${closeMsg} ${crName}"
                                new NavServiceNow(conf).cRTicketClosure(crSysId, buildStatus, conf)
                            }
                        }
                    }
                }
            }
        }
    }
}

private processPipelineStages(def objPipeline, def pipelineMethods, def stageLabels){
    def jenkinsUtils = new JenkinsUtils()
    for(int i=0; i < pipelineMethods.size(); i++){
        if(!jenkinsUtils.isBuildStatusOK()) break
        def stageList = pipelineMethods[i]
        def buildStage = stageList[0]
        String lblStage = stageLabels[buildStage]
        if (stageList.size()>1) {
            lblStage = "Parallels: " + lblStage + "..."
        }

        if (stageList.size() == 1) {
            execBuildItem(buildStage, objPipeline, lblStage)
        } else {
            parallelBody = [:]
            for (int j = 0; j < stageList.size(); j++) {
                def stageName = stageList[j]
                def stageLabel = stageLabels[stageName]
                parallelBody[stageLabel] = { execBuildItem(stageName, objPipeline, stageLabel) }
            }
            stage(lblStage) {
                parallel(parallelBody)
            }
        }
    }
}

private def execBuildItem(String methodName, def objPipeline, String stageLabel='') {
    try{
        if(stageLabel == "") {
            objPipeline."$methodName"()
        } else {
            stage(stageLabel) {
                echo "starting ${methodName}"
                objPipeline."$methodName"()
            }
        }
    } catch (Exception err) {
        String msg =  err.getMessage()
        if(!msg) msg = 'Unknown error!'
        echo "Processing ${methodName} false: " + msg
        throw err
    }
}

private boolean isMethodExisted(def appMethods, def newMethod) {
    for(int i=0; i < appMethods.size(); i++) {
        if(newMethod == appMethods[i]) return true
    }
    return false
}

private def getPipelineMethods(def objPipeline){
    def appMethods = []
    def mapOrder = [:]
    int maxIndex = 0
    def scopeClass = CDScope.class
    def basePipelineClassList = objPipeline.basePipelineClassList
    basePipelineClassList.add(objPipeline.class)
    for(int i=0; i < basePipelineClassList.size(); i++) {
        basePipelineClassList[i].declaredMethods.each { m ->
            def scope = m.getAnnotation(scopeClass)
            if (scope && !isMethodExisted(appMethods, m.name)) {
                appMethods.add(m.name)
            }
            def stageOrder = m.getAnnotation(StageOrder)
            if (stageOrder) {
                int index = stageOrder.id()
                mapOrder["${m.name}"] = index
            }
        }
    }

    def methodOrder = [:]
    appMethods.each { name ->
        int index = mapOrder["${name}"]
        if(maxIndex<index) maxIndex=index
        String strIndex = "${index}"
        if(methodOrder[strIndex] == null) methodOrder[strIndex] = []
        methodOrder[strIndex].add(name)
    }
    def rtnMethods = []
    for(int index = 0; index<=maxIndex; index++ ){
        String strIndex = "${index}"
        if(methodOrder[strIndex]){
            rtnMethods.add(methodOrder[strIndex])
        }
    }
    return rtnMethods
}

private def getStageLabels(def objPipeline, def pipelineMethods){
    def mapLabels = [:]
    //use pipelineMethod name as default stage labels
    for(int i=0; i < pipelineMethods.size(); i++) {
        def stageList = pipelineMethods[i]
        for(int j=0; j < stageList.size(); j++) {
            String buildStage = stageList[j]
            // String lbl = buildStage.replaceAll('[A-Z]'){ c -> " ${c}" }
            mapLabels[buildStage] =  "" //lbl.capitalize()
        }
    }
    def basePipelineClassList = objPipeline.basePipelineClassList
    basePipelineClassList.add(objPipeline.class)
    for(int i=0; i < basePipelineClassList.size(); i++) {
        basePipelineClassList[i].declaredMethods.each { m ->
            def scope = m.getAnnotation(StageLabel)
            if (scope) {
                mapLabels["${m.name}"] =  scope.value()
            }
        }
    }
    return mapLabels
}

return this
