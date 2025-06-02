package com.sony.sie.cicd.cd.utilities

import com.sony.sie.cicd.helpers.enums.BuildAction
import com.sony.sie.cicd.helpers.notifications.SlackNotifications
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import org.codehaus.groovy.GroovyException

boolean isEmergencyDeployment(){
    BuildAction buildAction = env.ACTION.replace('-', '_')
    return buildAction == BuildAction.EMERGENCY_DEPLOYMENT || buildAction == BuildAction.SCALE_ROLLOUT
}

def deployApproval(Map item) {
    String psenvList = item.clusterName
    String approvalType = "Approve"
    String lblStage = "${approvalType}: ${psenvList} rollout"
    def teamSlackChannel = item.slackOnDeployApproval ? item.teamSlackChannel : ""
    int startMillis = 0
    if (item.deployApprovalTimeOut == null || item.deployApprovalTimeOut <= 0) {
        item.deployApprovalTimeOut = 1
    }
    int timeoutSeconds = item.deployApprovalTimeOut * 24 * 60 * 60
    stage(lblStage) {
        try {
            ansi_echo "Waiting for $lblStage"
            slackApprovalStatusMsg(teamSlackChannel, "WAITING FOR APPROVAL", psenvList, "WAITING")
            startMillis = System.currentTimeMillis()
            timeout(time: item.deployApprovalTimeOut, unit: 'DAYS') {
                def approverList = "${item.approver}"
                String pMaintenanceRequest = ''
                if (item.psenv.startsWith("p")) pMaintenanceRequest = "Please check for approval from #core-jenkins-deployments before clicking [Approve].\n"
                String msgApproval = "${approvalType} rollout to ${psenvList}? \n" +
                    pMaintenanceRequest +
                    "[Approve]: Approve the new rollout request by authorized users\n" +
                    "[Abort]: Decline the new rollout request and the new rollout will be aborted!!\n" +
                    "[Authorized users: ${approverList}]"

                input id: 'deployApproval', ok: "Approve", message: msgApproval, submitter: approverList
            }
            def approvedBy =  getLatestApprover()
            slackApprovalStatusMsg(teamSlackChannel, "APPROVED BY ${approvedBy}", psenvList, "SUCCESS")
            return approvedBy
        } catch (Exception err) {
            currentBuild.result = "ABORTED"
            def deniedBy =  isTimeout(startMillis, timeoutSeconds) ? "TIMEOUT" : getLatestApprover()
            slackApprovalStatusMsg(teamSlackChannel, "DECLINED BY ${deniedBy}", psenvList, "ABORTED")
            throw new GroovyException("The ${item.psenv} rollout approval was declined by: ${deniedBy}")
        }
    }
}

def scaleRolloutApproval(Map item) {
    String psenvList = item.clusterName
    String approvalType = "Approve"
    String lblStage = "${approvalType}: ${psenvList} Scale Rollout"
    def teamSlackChannel = item.slackOnDeployApproval ? item.teamSlackChannel : ""
    int startMillis = 0
    if (item.deployApprovalTimeOut == null || item.deployApprovalTimeOut <= 0) {
        item.deployApprovalTimeOut = 1
    }
    int timeoutSeconds = item.deployApprovalTimeOut * 24 * 60 * 60
    stage(lblStage) {
        try {
            ansi_echo "Waiting for $lblStage"
            slackApprovalStatusMsg(teamSlackChannel, "WAITING FOR APPROVAL", psenvList, "WAITING")
            startMillis = System.currentTimeMillis()
            timeout(time: item.deployApprovalTimeOut, unit: 'DAYS') {
                def approverList = "${item.approver}"
                String pMaintenanceRequest = ''
                if (item.psenv.startsWith("p")) pMaintenanceRequest = "Please check for approval from #core-jenkins-deployments before clicking [Approve].\n"
                String msgApproval = "${approvalType} rollout to ${psenvList}? \n" +
                    pMaintenanceRequest +
                    "[Approve]: Approve the scale rollout request by authorized users\n" +
                    "[Abort]: Decline the scale rollout request and the scale rollout will be aborted!!\n" +
                    "[Authorized users: ${approverList}]"

                input id: 'scaleRolloutApproval', ok: "Approve", message: msgApproval, submitter: approverList
            }
            def approvedBy =  getLatestApprover()
            slackApprovalStatusMsg(teamSlackChannel, "APPROVED BY ${approvedBy}", psenvList, "SUCCESS")
            return approvedBy
        } catch (Exception err) {
            currentBuild.result = "ABORTED"
            def deniedBy =  isTimeout(startMillis, timeoutSeconds) ? "TIMEOUT" : getLatestApprover()
            slackApprovalStatusMsg(teamSlackChannel, "DECLINED BY ${deniedBy}", psenvList, "ABORTED")
            throw new GroovyException("The ${item.psenv} scale rollout approval was declined by: ${deniedBy}")
        }
    }
}

void slackApprovalStatusMsg(String teamSlackChannel, String approvalStatus, String psenv, String slackStatus, String approvalType = "Deploy") {
    if (teamSlackChannel && teamSlackChannel != '') {
        //Do not format this code
        String msgSlack = """
${approvalType} Approval Status: ${approvalStatus}
Line: ${psenv}
Jenkins job: ${env.BUILD_URL}
Dash url: ${env.DASH_URL}
         """
        new SlackNotifications().sendSimpleSlackNotification(teamSlackChannel, msgSlack, slackStatus)
    }
}

@NonCPS
def getLatestApprover(def userName = false) {
    def latest = null
    // this returns a CopyOnWriteArrayList, safe for iteration
    def acts = currentBuild.rawBuild.getAllActions()
    for (act in acts) {
        if (act instanceof org.jenkinsci.plugins.workflow.support.steps.input.ApproverAction) {
            if (userName) {
                latest = getNameFormatted(act.getUserName())
            } else {
                latest = act.userId
            }

        }
    }
    return latest
}

boolean isTimeout(int startMillis, int timeoutSeconds) {
    return isTimeoutMilli( startMillis, timeoutSeconds * 1000)
}

boolean isTimeoutMilli(int startMillis, double timeoutMillis) {
    int endMillis = System.currentTimeMillis()
    return (endMillis - startMillis >= timeoutMillis)
}

def exeClosure(Closure body) {
    if(body != null){
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body.delegate = this
        body()
    }
}

private boolean timeRestrictionsForDeploy(){
    def timeZone = TimeZone.getTimeZone('PST')
    Date date = new Date()
    int hours = date.format("HH", timeZone).toInteger()
    ansi_echo "current time: ${hours}"
    //Restrictions deployment between 9AM to 3PM
    return (hours >= 9 && hours < 15)
}

def getAloyApproverGroup(def deployConfiguration) {
    switch(deployConfiguration.infrastructure){
        case "kamaji-cloud":
            return new KmjServiceNow(deployConfiguration).getAloyApproverGroup()
            break
        case "navigator-cloud":
            return new NavServiceNow(deployConfiguration).getAloyApproverGroup()
            break
        case "laco-cloud":
            return new LacoServiceNow(deployConfiguration).getAloyApproverGroup()
            break
        case "roadster-cloud":
            return new RoadsterServiceNow(deployConfiguration).getAloyApproverGroup()
            break
        default:
            throw new GroovyException("The infrastructure field is not provided!")
    }
}

def deploymentConfirmation(def conf) {
    try {
        int approvalTimeOut = 2
        String approverList = conf.approvers.devApprover + "," + conf.approvers.coApprover
        stage("Confirm: Rollout") {
            ansi_echo "Waiting for starting a new rollout confirmation"
            String msgApproval = "Confirm to start a new rollout?\n" +
                    "[Confirm]: Confirm to start a new rollout by authorized users\n" +
                    "[Abort]: Cancel this new rollout!\n" +
                    "NOTE: If there is NO confirmation action after ${approvalTimeOut} days, this new rollout will be ABORTED!\n" +
                    "[Authorized users: ${approverList}]"
            timeout(time: approvalTimeOut, unit: 'DAYS') {
                input id: 'rolloutConfirmation', ok: "Confirm", message: msgApproval, submitter: approverList
            }
        }
    } catch (Exception err) {
        currentBuild.result = "ABORTED"
        throw new GroovyException("The Rollout Processing Approval ABORTED")
    }
}

def skubaDebugOnFailed(def conf, boolean log = false){
    if(conf.serviceNames) {      
        container(conf.clusterId) {
            for(int i=0; i < conf.serviceNames.size(); i++){
                String serviceName = conf.serviceNames[i]
                if(serviceName != ""){
                    String skubaCmd = "skuba diagnose ${serviceName} -n ${conf.namespace}"
                    try {
                        if (log) { 
                           sh "${skubaCmd} || exit 0"
                           return
                        }
                        sh skubaCmd
                    } catch (Exception err) {
                        echo "skubaDebugOnFailed failed on ${serviceName}: " + err.getMessage()
                    }
                }
            }
        }
    }
}

def argoRolloutApproval(def helmDeployK8s, Map item, def rolloutNames, int currentStep = 0) {
    int startMillis = System.currentTimeMillis()
    double timeoutMillis = 60*1000 //check rollout status eveny minute
    if(currentStep == 0) currentStep = getRolloutStep(helmDeployK8s, rolloutNames[0])
    try {
        echo "Promoting Argo rollouts for ${rolloutNames.join(", ")}."
        for(int i=0; i < rolloutNames.size(); i++){
            helmDeployK8s.promoteArgoRollout(rolloutNames[i])
        }
    } catch (Exception err) {
        def deniedBy =  isTimeoutMilli(startMillis, timeoutMillis) ? "SYSTEM" : getDeniedBy(err)
        if (deniedBy != 'SYSTEM') { //user aborted the rollout
            for(int i=0; i < rolloutNames.size(); i++){
                helmDeployK8s.abortArgoRollout(rolloutNames[i])
            }
            currentBuild.result = "ABORTED"
            throw new GroovyException("The argo rollout approval was declined.")
        }
    }
}

int getRolloutStep(def helmDeployK8s, String rolloutName) {
    return helmDeployK8s.getArgoRolloutStep(rolloutName) 
}

void ansi_echo(String txt, Integer color = 34) {
    //color code: black: 30, red: 31, green: 32, yellow: 33, blue: 34, purple: 35
    echo "\033[01;${color}m ${txt}...\033[00m"
}

@NonCPS
def getNameFormatted(Name) {
    def matcher = Name =~ /([a-zA-Z]+), ([a-zA-Z]+)/
    if (matcher.matches()) {
        return "${matcher.group(2)} ${matcher.group(1)}"
    }
    return Name
}

def checkPreviousDeployments(def conf) {
    if (conf.traffic && !isEmergencyDeployment()) {
        stage("Check Previous Rollouts") {
            def containerName = conf.clusterId
            new JenkinsUtils().jenkinsNode(infrastructure: "${conf.infrastructure}", templateType: 'helm', clusterList: [containerName]) {
                container(containerName) {
                    def envSysId = new NavServiceNow(conf).environmentSysId(conf.psenv)
                    def deployedBefore = false
                    def checkEnv = ''
                    (deployedBefore, checkEnv) =  new NavServiceNow().checkPreviousDeployments(conf, envSysId)
                    if (!deployedBefore){
                        currentBuild.result = "ABORTED"
                        throw new GroovyException("Application has to be deployed to ${checkEnv} first...aborting...")
                    } else {
                        ansi_echo("Application was already deployed to ${checkEnv}...continuing...", 34)
                    }
                }
            }
        }
    }
}

@NonCPS
def getDeniedBy(def err) {
    def deniedBy = "SYSTEM"
    try {
        deniedBy = err.getCauses()[0].getUser().toString()
    } catch(Exception e) { 
        echo "getDeniedBy Error: ${e.getMessage()}"
    }
    return deniedBy
}

return this
