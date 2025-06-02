package com.sony.sie.cicd.cd.utilities

import groovy.json.JsonSlurperClassic
import com.sony.sie.cicd.helpers.notifications.SlackNotifications
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import org.codehaus.groovy.GroovyException

def sendSecuritySlackMsg(def conf) {
    def notifications = new SlackNotifications()
    def securityEnv= conf.psenv
    def helmDeployK8s = null
    String newChartVersion = conf.releaseVersion
    String githubRepoName = "${env.ORG_NAME}/${env.REPO_NAME}"
    String versionsComparation = ''
    if(!conf.requestUser) conf.requestUser = conf.latestApprover ?: ""
    String lastReleaseName = ''
    if(conf.releaseType == "lambda" ) {
        versionsComparation = "*This is lambda deployment and tagged version is ${newChartVersion}.*" +
                "\nTo know what changed in this deployment, please click on " +
                "\n" +
                "URL as https://github.sie.sony.com/${githubRepoName}/releases/tag/${newChartVersion}"
    } else {
        new JenkinsUtils().k8sAccessConfig conf
        helmDeployK8s = new HelmUtils().getHelmKubeHelper(conf.clusterId, conf.namespace)
        if (helmDeployK8s.ifReleaseNameExist(conf.newReleaseName, "--deployed")) {
            lastReleaseName = conf.newReleaseName
        }

        if (lastReleaseName == '') {
            versionsComparation = "*This is first deployment of this repo and tagged version is ${newChartVersion}.*" +
                    "\nTo know what changed in this deployment, please click on " +
                    "\n" +
                    "URL as https://github.sie.sony.com/${githubRepoName}/releases/tag/${newChartVersion}"
        } else {
            String lastReleaseChartVersion = helmDeployK8s.getChartVersion(lastReleaseName)
            versionsComparation = "New tagged version is ${newChartVersion} and last deployed/tagged version is ${lastReleaseChartVersion}" +
                    "\nTo know what changed from the last deployment, please click on URL as:  " +
                    "\n" +
                    "https://github.sie.sony.com/${githubRepoName}/compare/${lastReleaseChartVersion}...${newChartVersion}"
        }
    }
    String message = ''
    String buildStatus = ''
    if (env.ACTION == "EMERGENCY_DEPLOYMENT") {
        message = "####################### " +
                "\n*REPO_NAME is ${githubRepoName}*" +
                "\n" +
                "\nPlease *refer* following *Emergency Deployment* intended to release to ${securityEnv} as ${env.BUILD_URL}. " +
                "\n" +
                // "\n" +
                // "\nDash url for mobile phone access is ${env.DASH_URL} . " +
                // "\n" +
                "\nGithub repo of this app is https://github.sie.sony.com/${githubRepoName}" +
                "\n" +
                "\n${versionsComparation}" +
                "\n" +
                "\nDeployment requester/contact person is *${conf.requestUser}*." +
                "\n" +
                "\n*************************"
        buildStatus = "NOT_BUILD"
    } else if(env.ACTION == "CANARY_DEPLOYMENT") {
        message ="####################### " +
                "\n*REPO_NAME is ${githubRepoName}*" +
                "\n" +
                "\nPlease *approve* following *Standalone Canary Deployment* intended to release to ${securityEnv} as ${env.BUILD_URL} . " +
                "\n" +
                // "\nDash url for mobile phone access is ${env.DASH_URL} . " +
                // "\n" +
                "\nGithub repo of this app is https://github.sie.sony.com/${githubRepoName} ." +
                "\n" +
                "\n${versionsComparation} " +
                "\n" +
                "\nDeployment requester/contact person is ${conf.requestUser} " +
                "\n" +
                "\n*************************"
    } else { //CD_ONLY
        message ="####################### " +
                "\n*REPO_NAME is ${githubRepoName}*" +
                "\n" +
                "\nPlease *approve* following *Regular Deployment* intended to release to ${securityEnv} as ${env.BUILD_URL} . " +
                "\n" +
                // "\nDash url for mobile phone access is ${env.DASH_URL} . " +
                // "\n" +
                "\nGithub repo of this app is https://github.sie.sony.com/${githubRepoName} ." +
                "\n" +
                "\n${versionsComparation} " +
                "\n" +
                "\nDeployment requester/contact person is ${conf.requestUser} " +
                "\n" +
                "\n*************************"
    }
    String prodsecNotifyChannel = "prodsec-engine-notify"
    //Uncomment the following line if the security channel is ready to receive slack messages
    notifications.sendSimpleSlackNotification(prodsecNotifyChannel, message, buildStatus, "", "@here")
    echo "Slack Message Sent to Security Team successfully"
}

def sendSlackMessageOnError(def channelName, def message , def buildStatus , def subject = '', def audience = "@here") {
    def messagUrl = message + "\n Check more details in: ${env.BUILD_URL}."
    new SlackNotifications().sendSimpleSlackNotification(channelName, messagUrl, buildStatus, subject, audience)
}

def securityApproval(def conf) {
    if(new JenkinsUtils().isTestRepo()) return
    DeployUtils deployUtils = new DeployUtils()
    def teamSlackChannel = conf.slackOnSecurityApproval ? conf.teamSlackChannel : ""
    //the following approver will be changed to use Aloy security team
    String approver = "svc-psn-CoreJenkinsProd-ProdSecSvc@aloy.playstation.net,PSN-CoreJenkinsProd-ProductSec"
    String msg = "The Production deployment request could not be auto approved by the Product Security Team.\n" +
            "The Product Security Team has already been notified and will do a manual review of this deployment.\n" +
            "If this request has not been approved by the security team within 2 days,\n" +
            "please notify the security team in the #prodsec (slack channel)."
    int startMillis = 0
    int timeoutSeconds = 2 * 24 * 60 * 60

    try {
        echo "Waiting for Security Approval"
        deployUtils.slackApprovalStatusMsg(teamSlackChannel, "WAITING FOR APPROVAL", conf.psenv, "WAITING", "Security")
        startMillis = System.currentTimeMillis()
        timeout(time: 2, unit: 'DAYS') {
            input id: 'SecurityApprovalProd', message: msg, submitter: approver
        }
        def approvedBy =  deployUtils.getLatestApprover()
        env.RAA_PATCH_APPROVE = true
        env.RAA_PATCH_APPROVER = approvedBy
        deployUtils.slackApprovalStatusMsg(teamSlackChannel, "APPROVED BY ${approvedBy}", conf.psenv, "SUCCESS", "Security")
    } catch (Exception err) {
        echo "Security Approval was DECLINED"
        currentBuild.result = "ABORTED"
        def deniedBy =  deployUtils.isTimeout(startMillis, timeoutSeconds) ? "TIMEOUT" : deployUtils.getDeniedBy(err)
        deployUtils.slackApprovalStatusMsg(teamSlackChannel, "DECLINED BY ${deniedBy}", conf.psenv, "ABORTED", "Security")
        throw new GroovyException("The security approval was declined by: ${deniedBy}")
    }
}

def securityApprovalRequest(int deployApprovalTimeOut) {
    if(new JenkinsUtils().isTestRepo()) return
    String msg = "Send slack message to security team for approval?"
    try {
        timeout(time: deployApprovalTimeOut, unit: 'DAYS') {
            String user = input(id: 'SecurityApprovalReq', message: msg, submitterParameter: 'submitter')
            return user
        }
    } catch (Exception err) {
        echo "Sending slack message ABORTED"
        currentBuild.result = "ABORTED"
        throw new GroovyException("Sending slack message ABORTED")
    }
}

def checkSecurityScanStatus(def conf, boolean showPlayload = true) {
    String scanURL = "https://prodsec-raa.gt.sonynei.net/v1.0/scanStatus"
    String dataScanStatusApi = "{\"teamName\":\"${conf.raaTeamName}\",\"applicationName\":\"${conf.raaAppName}\",\"applicationVersion\":\"${conf.raaVersion}\"}"
    container("build-tools") {
        int maxTry = 3
        for (int i = 0; i < maxTry; i++) {
            try {
                sh "curl -o scanoutput -X POST -H \"Content-Type:application/json\" -d \'${dataScanStatusApi}\' ${scanURL}"
                if (fileExists('scanoutput')) {
                    sh 'cat scanoutput'
                    response = readJSON file: 'scanoutput'
                    return response.status
                } else {
                    if (i < maxTry){
                        sleep 30
                    } else {
                        return "FAILURE"
                    }
                }
            } catch (Exception err) {
                if (i < maxTry){
                    sleep 30
                } else {
                    echo "checkSecurityScanStatus failed: " + err.getMessage()
                    String channelName = new JenkinsUtils().getEngineWorkflowSlackChannel()
                    sendSlackMessageOnError(channelName,"checkSecurityScanStatus API is failed","FAILED","","@here")
                }
            }
        }
    }
}

def securityApprovalStatus(def conf) {
    String raaURL = 'https://prodsec-raa.gt.sonynei.net/v2.6/rest/applications/release/approval/status'
    String dataApprovalApi = "{\"team\":\"${conf.raaTeamName}\",\"application\":\"${conf.raaAppName}\",\"version\":\"${conf.raaVersion}\",\"hotfix\":${conf.hotfix},\"environment\":\"${conf.psenv}\",\"requestedBy\":\"unified\",\"loggingMode\": \"${conf.loggingMode}\"}"
    def response = [:]
    container("build-tools") {
        int maxTry = 3
        for (int i = 0; i < maxTry; i++) {
            try {
                sh "curl -o raaoutput -X POST -H \"Content-Type:application/json\" -d \'${dataApprovalApi}\' ${raaURL}"
                if (fileExists('raaoutput')) {
                    sh 'cat raaoutput'
                    response = readJSON file: 'raaoutput'
                    return [response.status == "pass", response]
                } else {
                    if (i < maxTry){
                        sleep 30
                    } else {
                        return [false, response]
                    }
                }
            } catch (Exception err) {
                if (i < maxTry){
                    sleep 30
                } else {
                    echo "securityApprovalStatus failed: " + err.getMessage()
                    String channelName = new JenkinsUtils().getEngineWorkflowSlackChannel()
                    sendSlackMessageOnError(channelName,"securityApprovalStatus API is failed","FAILED","","@here")
                }
            }
        }
    }
}

def securityAutoApproval(def conf) {
    try {
        //Check security approval status to make sure the auto approval is granted
        Boolean raaStatus = false
        Map raaResults = [:]
        (raaStatus, raaResults) = securityApprovalStatus(conf)
        if(raaStatus) {
            return [true, raaResults]
        }
        //Auto approval failed, check the security scan status
        String scanStatus = checkSecurityScanStatus(conf)
        if(scanStatus == "PENDING") {
            echo "In an effort to reduce manual security approvals, the security team has requested that if the security scan status is \"PENDING\",\n" +
                    "subsequent checks will need to be made for up to 10 minutes before proceeding further.\n" +
                    "These status checks will begin now:"
            try {
                timeout(10) {
                    while (scanStatus == "PENDING") {
                        sleep 60
                        scanStatus = checkSecurityScanStatus(conf, false)
                    }
                }
            } catch (Exception err) {
                // security team wants to have 10 minutes deplay for the PENDING status, should not throw error
                echo "10 minutes timeout"
            }
            //Check security approval status again
            (raaStatus, raaResults) = securityApprovalStatus(conf)
        }
        return [raaStatus, raaResults]
    } catch (Exception err) {
        echo "Security Auto Approval Failed: " + err.getMessage()
        String channelName = new JenkinsUtils().getEngineWorkflowSlackChannel()
        sendSlackMessageOnError(channelName,"securityAutoApproval API is failed","FAILED","","@here")
    }
}

def securityRAAOverride(def conf) {
    try {
        String raaOverrideApiData = "{\"team\":\"${conf.raaTeamName}\",\"application\":\"${conf.raaAppName}\",\"version\":\"${conf.raaVersion}\",\"loggingMode\":false,\"requestedBy\":\"unified\",\"metadata\": { \"type\": \"jenkins_url\",\"data\": \"${env.BUILD_URL}input/SecurityApprovalProd/\"}}"
        echo "raaOverrideApiData: ${raaOverrideApiData}"
        String raaOverrideApi =  "curl -k -i -X POST \'https://prodsec-watchers-api.gt.sonynei.net/v1.0/project-release-metadata\' -d \'${raaOverrideApiData}\' -H \'Content-Type:application/json\' | grep \"HTTP/1.1\""
        echo "raaOverrideApi: ${raaOverrideApi}"
        String raaOverrideApiStatus = ""
        int maxTry = 3
        for (int i = 0; i < maxTry; i++) {
            try {
                raaOverrideApiStatus = ["sh", "-c", raaOverrideApi].execute().text
            } catch (Exception err) {
                if (i < maxTry){
                    sleep 30
                } else {
                    throw err
                }
            }
        }
        if (!raaOverrideApiStatus.contains("204")) {
            echo "raaOverrideApiStatus: ${raaOverrideApiStatus}"
            echo "RAA Override Api Status was unsuccessful, please contact PRODSEC team to manually approve this deployment"
            sendSlackMessageOnError("prodsec-engine-notify","RAA Override API is failed","FAILED","","@here")
            String channelName = new JenkinsUtils().getEngineWorkflowSlackChannel()
            sendSlackMessageOnError(channelName,"securityRAAOverride API has failed","FAILED","","@here")
        } else {
            echo "RAA Override Api Status is a success"
        }
    } catch (Exception err) {
        echo "Security RAA Override Failed, please contact in #engine-support channel. " + err.getMessage()
        String channelName = new JenkinsUtils().getEngineWorkflowSlackChannel()
        sendSlackMessageOnError(channelName,"securityRAAOverride API has failed","FAILED","","@here")
        
    }
}

def securityApprovalStage(def conf) {
    JenkinsUtils jenkinsUtils = new JenkinsUtils()
    Boolean raaStatus = false
    Map raaResults = [return: 'auto']
    conf = [raaAppName: env.REPO_NAME, raaVersion: env.APP_VERSION, releaseVersion: env.CHART_VERSION, 
            raaTeamName: "Korra", hotfix: false, loggingMode: "false", requestedBy: "unified"] << conf
    conf.raaTeamName = conf.raaTeamName.capitalize()
    if(jenkinsUtils.isTestRepo()) {
        stage("Approve: Security Check") {
            echo "Skip security approval for test repo"
        }
        return raaResults
    }
    String securityApprovalFlag = "auto"
    String stageLabel = (env.ACTION == "EMERGENCY_DEPLOYMENT" || conf.loggingMode == "true")? "Security Scan Pre-Check" : "Approve: Security Check"
    stage(stageLabel) {
        jenkinsUtils.navNode(templateType: 'security') {
            if (env.ACTION == "EMERGENCY_DEPLOYMENT" || conf.loggingMode == "true") {
                conf.hotfix = env.ACTION == "EMERGENCY_DEPLOYMENT"
                (raaStatus, raaResults) = securityApprovalStatus(conf)
            } else {
                (raaStatus,raaResults) = securityAutoApproval(conf)
                if (!raaStatus) {
                    securityApprovalFlag = "manual"
                    echo "Auto approval failed for security, triggering manual security approval phase"
                } else {
                    echo "Security auto approval success for ${conf.psenv} with ${env.CHART_VERSION}"
                }
            }
        }
        if (!raaStatus) {
            if ((conf.infrastructure != "laco-cloud" || conf.raaEnabled) && !conf.psenv.startsWith('p')) {
                catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                    error "${stageLabel} is unstable due to RAA auto approval failure..."
                }
            }
            if ((conf.loggingMode != "true" || env.ACTION == "EMERGENCY_DEPLOYMENT") && conf.psenv.startsWith('p')) {
                jenkinsUtils.jenkinsNode(templateType: 'helm', infrastructure: conf.infrastructure, clusterList: [conf.clusterId]) {
                    sendSecuritySlackMsg(conf)
                }
            }
        } else if (env.ACTION == "EMERGENCY_DEPLOYMENT"){
            jenkinsUtils.jenkinsNode(templateType: 'helm', infrastructure: conf.infrastructure, clusterList: [conf.clusterId]) {
                sendSecuritySlackMsg(conf)
            }            
        }
        if(securityApprovalFlag == "manual" && conf.loggingMode != "true") {
            jenkinsUtils.navNode(templateType: 'security') {
                securityRAAOverride(conf)
            }
            securityApproval(conf)
        }
    }
    raaResults.return = securityApprovalFlag
    return raaResults
}

return this
