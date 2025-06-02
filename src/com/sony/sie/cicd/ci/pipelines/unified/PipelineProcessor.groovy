package com.sony.sie.cicd.ci.pipelines.unified

import com.sony.sie.cicd.helpers.annotations.*
import com.sony.sie.cicd.helpers.enums.BuildAction
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import com.sony.sie.cicd.helpers.enums.TriggerStatus

def process(def objPipeline){
    String errorMsg = ""
    try{
        echo "starting CI pipeline steps"
        jenkinsUtils = new JenkinsUtils()
        def pipelineMethods = getCiPipelineMethods(objPipeline)
        def stageLabels = getStageLabels(objPipeline, pipelineMethods)
        processPipelineStages(objPipeline, pipelineMethods, stageLabels)
    } catch (Exception err) {
        if(!jenkinsUtils.isBuildAborted()){
            errorMsg =  err.getMessage()
            if(!errorMsg) errorMsg = 'Unknown error!'
            echo "ci release failed: " + errorMsg
            currentBuild.result = "FAILURE"
            currentBuild.description = "CI release failed"
        }
        throw err
    } finally {
        if(!jenkinsUtils.isBuildAborted()) {
            // if (!objPipeline.pipelineDefinition.ciTestApplicable) {
            //     objPipeline.NotifyRelayServer()
            // }
            String buildStatus = jenkinsUtils.getBuildStatus()
            String failedStage = buildStatus=="FAILURE" ? objPipeline.pipelineDefinition.failedStage : ""
            String msgSlack = objPipeline.getSlackMessage(buildStatus, failedStage, errorMsg)
            objPipeline.NotifyTeamChannel(msgSlack, buildStatus)
        }
    }
}

def startCiTest(def objPipeline){
    echo "starting ci test pipeline steps"
    String errorMsg = ""
    try {
        jenkinsUtils = new JenkinsUtils()
        def pipelineMethods = getPipelineMethods(objPipeline, CiTestScope.class)
        def stageLabels = getStageLabels(objPipeline, pipelineMethods)
        processPipelineStages(objPipeline, pipelineMethods, stageLabels)
    } catch (Exception err) {
        if(!jenkinsUtils.isBuildAborted()){
            errorMsg =  err.getMessage()
            if(!errorMsg) errorMsg = 'Unknown error!'
            echo "ci test failed: " + errorMsg
            currentBuild.result = "FAILURE"
        }
        throw err
    } finally {
        if(!jenkinsUtils.isBuildAborted()){
            // objPipeline.NotifyRelayServer()
            String buildStatus = jenkinsUtils.getBuildStatus()
            String failedStage = buildStatus=="FAILURE" ? objPipeline.pipelineDefinition.failedStage : ""
            String msgSlack = objPipeline.getSlackMessage(buildStatus, failedStage, errorMsg)
            objPipeline.NotifyTeamChannel(msgSlack, buildStatus)
        }
        if(objPipeline.pipelineDefinition.pipelineType == 'docker-compose') {
            jenkinsUtils.cleanupJenkinsWorkspace()
        }
    }
}

boolean isCiTestParalleSupported(def objPipeline) {
    if(env.CHANGE_ID) return false
    switch (objPipeline.pipelineDefinition.buildAction){
        case BuildAction.CI_ONLY:
            def pipelineMethods = getPipelineMethods(objPipeline, CiTestScope.class)
            return pipelineMethods != []
        default:
            return false
    }
}

def startSecurityAnalysis(def securityPipeline, def workDir = env.SECURITY_WORKDIR){
    echo "starting security scan upload pipeline steps"
    try{
        dir(workDir) {
            timeout(securityPipeline.scanTimeOut) {
                def pipelineMethods = getPipelineMethods(securityPipeline, SecurityScope.class)
                def stageLabels = getStageLabels(securityPipeline, pipelineMethods)
                processPipelineStages(securityPipeline, pipelineMethods, stageLabels)
            }
        }
    } catch (Exception err) {
        echo "Security Scan Upload failed: " + err.getMessage()
        currentBuild.result = "UNSTABLE"
    }
}

// private boolean isProjectExcludedForSecurityAnalysis(String projectName) {
//     return projectName.contains('kamaji-archetype')
// }

boolean ifSecurityAnalysisSupported(def securityPipeline) {
    if(securityPipeline) 
        return new JenkinsUtils().isMainBranch(env.BRANCH_NAME) || params.ENABLE_SECURITY_SCAN
    else return false
}

private processPipelineStages(def objPipeline, def pipelineMethods, def stageLabels){
    jenkinsUtils = new JenkinsUtils()
    for(int i=0; i < pipelineMethods.size(); i++){
        if(!jenkinsUtils.isBuildStatusOK()) break
        def stageList = pipelineMethods[i]
        def buildStage = stageList[0]
        String lblStage = stageLabels[buildStage]
        if (stageList.size()>1) {
            if(lblStage == "Source Clear" || lblStage == "Fortify")
                lblStage = "Security Scan Upload"
            else
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

private def execBuildItem(String buildStage, def objPipeline, String stageLabel='') {
    try{
        if(stageLabel == "") {
            objPipeline.process(buildStage, stageLabel)
        } else {
            stage(stageLabel) {
                echo "starting ${buildStage}"
                objPipeline.process(buildStage, stageLabel)
            }
        }
    } catch (Exception err) {
        String msg =  err.getMessage()
        if(!msg) msg = 'Unknown error!'
        echo "Processing ${buildStage} false: " + msg
        objPipeline.pipelineDefinition.failedStage = stageLabel=='' ? buildStage : stageLabel
        throw err
    }
}

private def getCiPipelineMethods(def objPipeline) {
    def pipelineDefinition = objPipeline.pipelineDefinition
    def scopeClass = BranchScope.class
    if (env.CHANGE_ID)
        scopeClass = PRScope.class
    else if (new JenkinsUtils().isMainBranch())
        scopeClass = MasterScope.class

    return getPipelineMethods(objPipeline, scopeClass)
}

private boolean isMethodExisted(def appMethods, def newMethod) {
    for(int i=0; i < appMethods.size(); i++) {
        if(newMethod == appMethods[i]) return true
    }
    return false
}

private def getPipelineMethods(def objPipeline, def scopeClass){
    def appMethods = []
    def mapOrder = [:]
    int maxIndex = 0
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

private def getStageLabels(def objPipeline, def pipelineStages){
    def mapLabels = [:]
    for(int i=0; i < pipelineStages.size(); i++) {
        def stageList = pipelineStages[i]
        for(int j=0; j < stageList.size(); j++) {
            String buildStage = stageList[j]
            String lbl = buildStage.replaceAll('[A-Z]'){ c -> " ${c}" }
            mapLabels[buildStage] =  lbl.capitalize()
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
