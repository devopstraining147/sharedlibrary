package com.sony.sie.cicd.ci.utilities

import com.sony.sie.cicd.helpers.enums.StageName
import com.sony.sie.cicd.helpers.notifications.SlackNotifications
import com.sony.sie.cicd.helpers.utilities.GitUtils
import com.sony.sie.cicd.helpers.utilities.JenkinsSteps
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils

class NotifyUtils extends JenkinsSteps {
    JenkinsUtils jenkinsUtils

    NotifyUtils() {
        this.jenkinsUtils = new JenkinsUtils()
    }

    def initiateNotification(def pipelineDefinition = [:], type = StageName.ISOPERF_ORCH.formattedName, initiateStartOfPipeline = true) {
        def jenkinsUtils = new JenkinsUtils()
        def branchName = params.BRANCH_OVERRIDE ?: jenkinsUtils.fetchDefaultBranch(pipelineDefinition.orgName, pipelineDefinition.repoName)

        def slackChannels = loadSlackConfigInfo(new GitUtils().readSlackNotificationsFile(pipelineDefinition.orgName, pipelineDefinition.repoName, branchName))
        echo "slackChannels: ${slackChannels}"
        pipelineDefinition.slackChannels = slackChannels

        if (initiateStartOfPipeline) {
            startOfPipelineNotify(pipelineDefinition, type)
        }

        return pipelineDefinition.slackChannels
    }

    def loadSlackConfigInfo(def slackConf) {
        echo "load Slack Config Info from yaml ..."
        def slackChannels
        if (slackConf?.slackChannels) {
            slackChannels = []
            for (int i = 0; i < slackConf.slackChannels.size(); i++) {
                def slackItem = slackConf.slackChannels[i]
                echo "slackItem: ${slackItem}"

                if (slackItem.slackChannel) slackItem.name = slackItem.slackChannel
                def newItem = [:]

                echo "newItem: ${newItem}"
                def branchMap = [
                        onBuildStarted : slackItem.onBuildStarted ?: false,
                        onBuildPassed  : slackItem.onBuildPassed ?: false,
                        onBuildFailed: slackItem.onBuildFailed ?: false,
                        onBuildCanceled  : slackItem.onBuildCanceled ?: false
                ]

                newItem.putAll(branchMap)
                newItem.name = slackItem.name
                newItem.audience = slackItem.audience ?: ""
                slackChannels.add(newItem)
            }
        }
        echo "slackChannels:\n${slackChannels}"
        return slackChannels
    }

    def loadSlackConfigInfo(def conf, def type) {
        echo "load Slack Config Info ..."
        def slackChannels
        if(fileExists("./performance-test/iso-perf/iso-perf-notifications.yaml")) {
            def slackConf = readYaml file: "./performance-test/iso-perf/iso-perf-notifications.yaml"
            echo "slackConf: ${slackConf}"
            if (slackConf?.slackChannels) {
                slackChannels = []
                for (int i = 0; i < slackConf.slackChannels.size(); i++) {
                    def slackItem = slackConf.slackChannels[i]
                    echo "slackItem: ${slackItem}"

                    if (slackItem.slackChannel) slackItem.name = slackItem.slackChannel
                    def newItem = [:]

                    echo "newItem: ${newItem}"
                    def branchMap = [
                            onBuildStarted : slackItem.onBuildStarted ?: false,
                            onBuildPassed  : slackItem.onBuildPassed ?: false,
                            onBuildFailed: slackItem.onBuildFailed ?: false,
                            onBuildCanceled  : slackItem.onBuildCanceled ?: false
                    ]

                    newItem.putAll(branchMap)
                    newItem.name = slackItem.name
                    newItem.audience = slackItem.audience ?: ""
                    slackChannels.add(newItem)
                }
            }
        }
        echo "slackChannels:\n${slackChannels}"
        return slackChannels
    }

    def startOfPipelineNotify(pipelineDefinition, type) {
        echo "pipelineDefinition.slackChannels: ${pipelineDefinition.slackChannels}"
        if(pipelineDefinition.slackChannels){
            try {
                String buildStatus = "IN_PROGRESS"
                String stageLabel = "Starting"
                String msgSlack = getFormattedMessage(type, stageLabel, buildStatus)
                echo "Slack message:\n${msgSlack}"
                String channelsSent = ""
                for(int i=0; i < pipelineDefinition.slackChannels.size(); i++) {
                    def slackItem = pipelineDefinition.slackChannels[i]
                    echo "slackItem: ${slackItem}"
                    if (slackItem.getClass() == LinkedHashMap && slackItem.onBuildStarted && !channelsSent.contains(slackItem.name)) {
                        echo "slack CI build status To ${slackItem.name} Channel"
                        new SlackNotifications().sendSimpleSlackNotification(slackItem.name, msgSlack, buildStatus, "", slackItem.audience)
                        channelsSent += "," + slackItem.name
                    }
                }
            } catch (Exception err) {
                echo "Can not send notification to slack: ${err.getMessage()}"
            }
        }
    }

    private String getFormattedMessage(String stageName, String event, String buildStatus, String message = '', String code = null) {
        def buildStatusLst = ['FAILURE', 'ABORTED', 'SUCCESS']
        "*<${env.BUILD_URL}|Iso-Perf ${stageName} Build ${event}>*\n" +
                "*Build Executor*: ${currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause').userName}\n" +
                "Build Status: ${buildStatus}\n" +
                (message ? "*Message*: ${message}\n" : "")
    }

    def endOfPipelineNotify(def pipelineDefinition, type = "orchestrator") {
        //send slack to teams
        echo "pipelineDefinition?.slackChannels: ${pipelineDefinition?.slackChannels}"
        if (pipelineDefinition?.slackChannels) {
            String buildStatus = jenkinsUtils.getBuildStatus()
            boolean buildFailed = buildStatus == "FAILURE"
            boolean buildPassed = jenkinsUtils.isBuildStatusOK()
            boolean buildAborted = jenkinsUtils.isBuildAborted()

            buildStatus = buildAborted ? "ABORTED" : buildFailed ? "FAILED" : "SUCCESS"
            String msgSlack = getFormattedMessage(type, "Ended", buildStatus)
            echo "Slack message:\n${msgSlack}"
            try{
                String channelsSent = ""
                for(int i=0; i < pipelineDefinition.slackChannels.size(); i++) {
                    def slackItem = pipelineDefinition.slackChannels[i]
                    echo "slackItem: ${slackItem}"
                    if (slackItem.getClass() == LinkedHashMap && !channelsSent.contains(slackItem.name) && (
                            buildFailed && slackItem.onBuildFailed ||
                                    buildPassed && slackItem.onBuildPassed ||
                                    buildAborted && slackItem.onBuildCanceled)) {
                        echo "slack CI build status To ${slackItem.name} Channel"
                        new SlackNotifications().sendSimpleSlackNotification(slackItem.name, msgSlack, buildStatus, "", slackItem.audience)
                        channelsSent += "," + slackItem.name
                    }
                }
            } catch (Exception err) {
                String msg = err.getMessage() ?: "Unknown error!"
                echo msg
            }
        }
    }

}