
package com.sony.sie.cicd.helpers.notifications

import com.sony.sie.cicd.helpers.utilities.JenkinsUtils

def sendNotification(Map conf) {
    conf = [slackCredentialId: 'slack', addMessage: ""] << conf
    def buildStatus = conf.buildStatus
    def teamDomain = 'SIE'
    def colorCode = colorCodeSelector(buildStatus)
    def audience = getAudience()
    def addMessage = conf.addMessage
    def subject = "@${audience} ${buildStatus}: ${addMessage} Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'"
    def summary = "${subject} (${env.BUILD_URL}console)"
    sendSlack(conf.channel_name, colorCode, summary, conf.slackCredentialId, teamDomain)
}

def sendContractTestSlackNotification(String channelName, String provider, String consumer, String buildStatus = 'FAILED') {
    sendNotification([channel_name: channelName, buildStatus: buildStatus, addMessage: "CONTRACT_TEST (provider:${provider}, consumer:${consumer}"])
}

def sendSimpleSlackNotification(String channelNameList, String msg, def buildStatus, String subject = '', String audience = '') {
    def jenkinsUtils = new JenkinsUtils()
    def notificationClosure = {
        def teamDomain = 'SIE'
        def colorCode = colorCodeSelector(buildStatus)
        def summary = "${audience} ${subject} ${msg}"
        summary = summary.replaceAll("%2F", "/")
        def channelNames = channelNameList.split(",")
        for (int i = 0; i < channelNames.size(); i++) {
            String channelName = channelNames[i].trim()
            if (channelName != '') {
                sendSlack(channelName, colorCode, summary, 'SLACK_API_TOKEN', teamDomain)
            }
        }
    }
    catchError() {
        retry(2) {
            if (jenkinsUtils.nodeIsAvailable()) {
                notificationClosure()
            } else {
                jenkinsUtils.navNode(templateType: "checkout", notificationClosure)
            }
        }
    }

}
   
def sendSonarError(Exception err, String audience = ''){
        def buildStatus = "UNSTABLE"
        def msg = "Unified SonarQube code coverage failed: " + err.getMessage()
        def subject = "${buildStatus}: ${msg} Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'"
        def summary = "${subject} (${env.BUILD_URL}console)"
        sendSimpleSlackNotification("unified-sonarqube-notify", summary, buildStatus, "", audience)
        echo summary
}

private def sendSlack( String channel_name,  String colorCode,  String summary,  String slackCredentialId,  String teamDomain) {
    slackSend(
            channel: channel_name,
            color: colorCode,
            message: summary,
            tokenCredentialId: slackCredentialId,
            teamDomain: teamDomain
    )
}

private def colorCodeSelector(def buildStatus) {
    if (buildStatus == null) buildStatus = "SUCCESS"
    String color = 'good'
    switch (buildStatus) {
        case 'UNSTABLE':
            color = 'warning'
            break
        case 'FAILED':
            color = 'danger'
            break
        case 'FAILURE':
            color = 'danger'
            break
        case 'ABORTED':
            color = '#70706b'
            break
        case 'NOT_BUILD':
            color = '#909090'
            break
        case 'WAITING':
            color = '#008672'
            break
        default:
            color = 'good'
            break
    }
    return color
}

@NonCPS
private def getAudience() {
    return env.CHANGE_ID?env.CHANGE_AUTHOR:'channel'
}

return this
