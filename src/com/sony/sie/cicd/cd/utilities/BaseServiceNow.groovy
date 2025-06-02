package com.sony.sie.cicd.cd.utilities

import com.sony.sie.cicd.helpers.notifications.SlackNotifications

import java.time.*
import java.lang.Math;
import java.util.Calendar
import com.sony.sie.cicd.cd.utilities.DeployUtils
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import com.sony.sie.cicd.helpers.utilities.JenkinsSteps
import hudson.FilePath
import hudson.tasks.MailAddressResolver
import java.text.SimpleDateFormat
import org.codehaus.groovy.GroovyException
import com.sony.sie.cicd.helpers.api.ServiceNowAPI
import com.sony.sie.cicd.cd.utilities.SecurityUtils

abstract class BaseServiceNow extends JenkinsSteps {
    final JenkinsUtils jenkinsUtils = new JenkinsUtils()
    final DeployUtils deployUtils = new DeployUtils()
    final String SERVICE_NOW_SERVER = 'live'
    final String serviceNowOverrideChannel = "blackout-override-request"
    public String crType = 'normal'
    public Map deployConfiguration
    final int maxTry = 10 //up-to 5 minutes
    final int waitForSeconds = 30

    public BaseServiceNow(Map deployConfiguration){
        this.deployConfiguration = deployConfiguration
    }

    def getApprovalChannel () {
        return this.SERVICE_NOW_SERVER == 'live' ? 'cab':'test-servicenow-blackout-override'
    }
    
    def getGlobalNotificationChannel () {
        return this.SERVICE_NOW_SERVER == 'live' ? 'core-jenkins-deployments':'test-servicenow-blackout-override'
    }

    def internal_GetHost() {
        def host = ''
        def cred = ''
        switch (this.SERVICE_NOW_SERVER) {
        case 'live':
            host = 'https://playstation.service-now.com'
            cred = 'SERVICE_NOW_UNIFIED_LIVE'
            break
        case 'stage':
            host = 'https://playstationstage.service-now.com'
            cred = 'SERVICE_NOW_UNIFIED_STAGING'
            break
        case 'test':
            host = 'https://playstationtest.service-now.com'
            cred = 'SERVICE_NOW_TESTING'
            break
        }
        return [host, cred]
    }

    def getTimeOffset(int offset) {
        def cal = Calendar.getInstance()
        def duration = new Date(cal.getTimeInMillis() + (offset * 60000)).format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone('UTC'))
        return duration
    }

    def getData(def apiUrl) {
        def host
        def cred
        (host, cred) = internal_GetHost()
        apiUrl = apiUrl.replace("(", "%28").replace(")", "%29")
        echo "-----getData apiUrl:\n ${apiUrl}"
        withCredentials([string(credentialsId: "${cred}", variable: 'token')]) { 
            for (int i = 0; i < maxTry; i++) {
                try {
                    sh "curl -s -k -D- -H \"Authorization: Bearer ${token}\" -o output.json ${host}/${apiUrl}"
                    def response = readJSON file: 'output.json'
                    sh "rm output.json"
                    // echo "---- response data:\n${response}"
                    return response
                } catch (Exception err) {
                    if (i < maxTry){
                        sleep waitForSeconds
                    } else {
                        String channelName = jenkinsUtils.getEngineWorkflowSlackChannel()
                        def errorMsg = "ServiceNow call getData failed:\n${apiUrl} \nError: ${err.getMessage()}\nJob Link: ${env.BUILD_URL}"
                        new SecurityUtils().sendSlackMessageOnError(channelName, errorMsg, "FAILED", "", "")
                        p1npNotificationOnError("ServiceNow call getData failed: ${err.getMessage()}\napiUrl: ${apiUrl}")
                        throw err
                    }
                }
            }
        }
    }

    def getServiceNowAPIClient(def credId) {
        withCredentials([string(credentialsId: "${credId}", variable: 'token')]) {  
            return new ServiceNowAPI(token)
        }
    }

    def postData(def apiUrl, def data) {
        def host
        def cred
        (host, cred) = internal_GetHost()
        apiUrl = apiUrl.replace("(", "%28").replace(")", "%29")
        echo "-----postData apiUrl:\n ${apiUrl}"
        writeFile file: 'servicenow-data.json', text: data
        withCredentials([string(credentialsId: "${cred}", variable: 'token')]) { 
            for (int i = 0; i < maxTry; i++) {
                try {
                    sh "curl -s -k -D- -X POST -H \"Authorization: Bearer ${token}\" -H \"Content-Type: application/json\" -o output.json -d \'@servicenow-data.json\' ${host}/${apiUrl}"
                    def response = readJSON file: 'output.json'
                    echo "---- response data:\n${response}"
                    sh """
                        rm servicenow-data.json
                        rm output.json
                        """
                    return response
                } catch (Exception err) {
                    if (i < maxTry){
                        sleep waitForSeconds
                    } else {
                        String channelName = jenkinsUtils.getEngineWorkflowSlackChannel()
                        def errorMsg = "ServiceNow call postData failed:\n${apiUrl} \nError: ${err.getMessage()} \nJob Link: ${env.BUILD_URL}"
                        new SecurityUtils().sendSlackMessageOnError(channelName, errorMsg, "FAILED", "", "")
                        throw err
                    }
                }
            }
        }
    }

    def patchData(def apiUrl, def data) {
        def host
        def cred
        (host, cred) = internal_GetHost()
        apiUrl = apiUrl.replace("(", "%28").replace(")", "%29")
        echo "-----patchData apiUrl:\n ${apiUrl}"
        echo "-----patch data:\n ${data}"
        writeFile file: 'servicenow-data.json', text: data
        withCredentials([string(credentialsId: "${cred}", variable: 'token')]) { 
            for (int i = 0; i < maxTry; i++) {
                try {
                    sh "curl -s -k -D- -X PATCH -H \"Authorization: Bearer ${token}\" -H \"Content-Type: application/json\" -o output.json -d \'@servicenow-data.json\' ${host}/${apiUrl}"
                    def response = readJSON file: 'output.json'
                    // echo "---- response data:\n${response}"
                    sh """
                        rm servicenow-data.json
                        rm output.json
                        """
                    return response
                } catch (Exception err) {
                    if (i < maxTry){
                        sleep waitForSeconds
                    } else {
                        String channelName = jenkinsUtils.getEngineWorkflowSlackChannel()
                        def errorMsg = "ServiceNow call patchData failed:\n${apiUrl} \nError: ${err.getMessage()} \nJob Link: ${env.BUILD_URL}"
                        new SecurityUtils().sendSlackMessageOnError(channelName, errorMsg, "FAILED", "", "")
                        // p1npNotificationOnError(errorMsg)
                        throw err
                    }
                }
            }
        }
    }

    def p1npNotificationOnError(def errorMsg) {
        def conf = deployConfiguration
        if (conf.psenv == "p1-np" && conf.slackChannels && !deployUtils.isEmergencyDeployment()) {
            String msgSlack = getSlackMsgOnError(errorMsg)
            for(int i=0; i < conf.slackChannels.size(); i++) {
                new SlackNotifications().sendSimpleSlackNotification(conf.slackChannels[i].name, msgSlack, "FAILURE")
            }
        }
    }
    def getSlackMsgOnError(def errorMsg) {
        def conf = deployConfiguration
        return "The following deployment failed due to a ServiceNow connection issue:\n" +
            "Repo Name: ${env.REPO_NAME}\n" +
            "Line: ${conf.psenv}\n" +
            "Cluster: ${conf.clusterId}\n" +
            "Chart Version: ${conf.chartVersion} \n" +
            "Error Message: ${errorMsg}\n" +
            "Jenkins job: ${env.BUILD_URL} \n" +
            "If this is a deployment that must occur before the ServiceNow connectivity issue is resolved, please reattempt the deployment with the 'Emergency Deployment' option.\n" +
            "More information regarding the outage can be found here:\nhttps://sie.statuspage.io/"
    }

    def patchCRState (def SysId, def State) {
       try {
            def response = patchData("api/sie/psn_change_management/${crType}/${SysId}", "{ \"comments\":\"Updating state\",\"state\":\"${State}\"}") 
            if (response?.result?.number?.value == null) {
                error '** ERROR: curl servicenow psn_change_management PATCH returned an error'
            } else {
                ansi_echo "** Updated ${response.result.number.value} set state to ${State}."
            }
            return response.result.sys_id.value
        } catch (Exception err) {
            String errorMsg = "patchCRState Exception: " + err.getMessage()
            ansi_echo errorMsg, 31
            p1npNotificationOnError(errorMsg)
            return ""
        }
    }
    def getCrUrl(def crSysID) {
        def host
        def cred
        (host, cred) = internal_GetHost()
        def snowURL = ""
        def snowTicket = ""
        if (crSysID == '') {
            def matcher = manager.getLogMatcher(".*ServiceNow CR Created .*(PSNCHG[0-9]+), ([0-9a-f]+).*")
            if (matcher?.matches()) {
                snowURL = "${host}/nav_to.do?uri=%2Fx_sie_psn_change_request.do%3Fsys_id%3D${matcher.group(2)}%26sysparm_stack%3D%26sysparm_view%3D"
                snowTicket = "${matcher.group(1)} : ${snowURL}"
            }
        } else {
            snowURL = "${host}/nav_to.do?uri=%2Fx_sie_psn_change_request.do%3Fsys_id%3D${crSysID}%26sysparm_stack%3D%26sysparm_view%3D"
            snowTicket = snowURL
        }
        return snowTicket
    }

    def getCRToState(def crSysId, def currentCRState, def endState) {
        /* ----- ServiceNow Documentation: State model and transitions -------
         *https://docs.servicenow.com/bundle/rome-it-service-management/page/product/change-management/concept/c_ChangeStateModel.html
         */
        def validCRStates = ["New", "Authorize", "Scheduled", "Implement", "Review", "Closed"]
        if(endState == "Canceled") validCRStates = ["New", "Authorize", "Scheduled", "Implement", "Canceled"]
        if (crType == "standard") validCRStates = ["New", "Implement", "Review", "Closed", "Canceled"]
        def startStateIndex = ((validCRStates.findIndexOf { it == currentCRState }) +1)
        def endStateIndex = validCRStates.findIndexOf { it == endState }
        echo "currentCRState: ${currentCRState} and ${startStateIndex}, endState: ${endState} and ${endStateIndex+1}"
        if ((startStateIndex) < (endStateIndex+1)) {    
            startStateIndex.upto(endStateIndex, {
                patchCRState(crSysId,"${validCRStates[it]}")
                sleep 1
            })
        } else {
            echo "Current state ${validCRStates[startStateIndex]} is same, or less than future state ${validCRStates[endStateIndex+1]}, nothing to do..."
        }
    }

    def patchCR (def SysId, def Field, def Text) {
        try{
            def response = patchData("api/sie/psn_change_management/${crType}/${SysId}", "{ \"comments\":\"Updating ${Field}\",\"${Field}\":\"${Text}\"}")
            if (response?.result?.number?.value == null) {
                error '** ERROR: curl servicenow psn_change_management PATCH returned an error'
            }
            echo "** Updated ${response.result.number.value} set ${Field} to ${Text}."
            return response.result.sys_id.value
        } catch (Exception err) {
            String errorMsg = "patchCR Exception: " + err.getMessage()
            ansi_echo errorMsg, 31
            p1npNotificationOnError(errorMsg)
            return ""
        }
    }

    def getRestrictions (def pollCounter = 0) {
        def conf = deployConfiguration
        boolean canDeploy = false
        def getResponse = null
        env.eventMsg = ''
        def endTimes = []
        def openTime = ''
        String msgApproval = ''
        def overrideBlackOutGateInput = ''
        String deniedBy = ''
        def (aloyApproverGroup, contactMsg) = getAloyApproverGroup()
        conf.overrideReason = "No Blackouts detected for this CR.\n\n"
        approvalDate = new Date().format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone('America/Los_Angeles'))

        boolean isEmergencyDeployment = deployUtils.isEmergencyDeployment()
        try {
            getResponse = callRestrictionApi(conf, approvalDate)
            if (getResponse?.result?.keySet() == null) {
                error '** ERROR: curl servicenow callRestrictionApi GET returned an error'
            }
            boolean canDeployKeySet = getResponse.result.keySet().contains('canDeploy')
            boolean eventsKeySet = getResponse.result.keySet().contains('events')
            if (!canDeployKeySet && eventsKeySet) {
                endTimes = eventParser(getResponse, conf)
                if (endTimes == 'Override'){
                    msgApproval =  "Rollouts are blocked 2 hours in advance for upcoming blackout events.\n\n" +
                        "No open timeslots were found due to following planned blackout events:" +
                        "\n-------------\n" +
                        "${env.eventMsg.trim()}" +
                        "\n-------------\n" +
                        "•[Request Override] Select this option to request to bypass these blackout events.\n\n" +
                        "•[Abort] Click this button to cancel the rollout.\n\n" +
                        "More information regarding the Blackout Event Override process can be found here:\n"+ 
                        "https://confluence.sie.sony.com/x/dIiiX"
                    timeout(time: 2, unit: 'HOURS') {
                        input id: 'SnowBlackoutOverride', ok: "Request Override", message: "${msgApproval}"
                    }

                    String slackId = ""
                    String jenkinsUser = "${currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause').userName}"
                    if (jenkinsUser) slackId = jenkinsUser.replaceAll("\\[", "").replaceAll("\\]", "")
                    new SlackNotifications().sendSimpleSlackNotification(serviceNowOverrideChannel, 
                        ":wave: `${slackId}` You have requested to deploy `${env.REPO_NAME}` during the following blackout event:\n" +
                        "```\n" + env.eventMsg.trim()+ "\n```\n\n" +
                        "If you already recieved approval from one of the ${conf.infrastructure} authorized approvers,\n" +
                        "(More info regarding approval groups: https://confluence.sie.sony.com/x/nXIjXw)\n" +
                        "tag the authorized user to click the \"Override\" button in the dialog presented in the Jenkins Job link provided below:\n" +
                        "${env.RUN_DISPLAY_URL}\n\n" +
                        "Otherwise, you may provide the following information in the slack message to speed up the approval process:\n" +
                        "• *Confluence Approval link*\n" +
                        "• *Description of the change*\n" +
                        "```\nProvide a Jira story link if available. Be sure to include details regarding the scope, criticality, and if other teams or services are involved or affected.\n```\n" +
                        "• *Risk*\n" + 
                        "```\nProvide some background on the risk related to this change request.\n```\n" +
                        "• *Justification for the Blackout Override Request*\n" + 
                        "```\nProvide a justification for why this blackout event should be overridden.\n```\n\n" + 
                        "(More info regarding the override process: https://confluence.sie.sony.com/x/dIiiX)",
                        "FAILED")

                    echo "The details regarding this request have been sent to the Slack channel: #${serviceNowOverrideChannel}\n" +
                        "Click the link below to navigate to this channel: \nhttps://sie.slack.com/archives/C04A8RZS2JV"

                    msgApproval = "Developers: Navigate to the\n#blackout-override-request\n" + 
                        "channel to tag the individual from the \"${conf.infrastructure}\" list on this page to request an override:\n" +
                        "https://confluence.sie.sony.com/x/nXIjXw\n\n" +
                        "•[Override] (Approval Group - \n\"${conf.infrastructure}\")\nSelect this option to override these blackout events.\n\n" +
                        "•[Abort] Click this button to cancel the rollout."
                        "(More information regarding the Blackout Event Override process can be found here:)\n"+ 
                        "https://confluence.sie.sony.com/x/dIiiX"
                    timeout(time: 2, unit: 'HOURS') {
                        overrideBlackOutGateInput = input id: 'SnowBlackoutOverride', ok: "Override", message: "${msgApproval}", submitter: aloyApproverGroup, parameters: [text(description: 'Please enter a short reason for bypassing the blackout events:', name: 'overrideReason')]
                    }
                    conf.overrideReason = "Blackout events for Platform ${conf.infrastructure}\\nConflict Overriden by: ${deployUtils.getLatestApprover()}"
                    if(overrideBlackOutGateInput && overrideBlackOutGateInput != "")
                        conf.overrideReason += "\\nOverride Justification:\\n------------------------\\n${overrideBlackOutGateInput.replaceAll("\n", '\\\\n')}\\n------------------------\\n"
                    canDeploy = true
                } else if (endTimes == 'Deploy') {
                    canDeploy = true
                }
            } else {
                if (canDeployKeySet) {
                    canDeploy = getResponse.result.canDeploy
                } else if(isEmergencyDeployment){
                    canDeploy = true
                } else {
                    error ("canDeploy is in an unexpected state...")
                }
            }
        } catch (Exception err) {
            echo "Error: ${err}"
            deniedBy = deployUtils.getDeniedBy(err)
            if (deniedBy == 'SYSTEM') {
                echo "Restriction API returned unexpected result... ${ err.getMessage()}"
                msgApproval = "Restriction API returned unexpected result.\n\n" +
                    "•[Request Override] Select this option to request to bypass Restriction API Error.\n\n" +
                    "•[Abort] Click this button to cancel the rollout."
                timeout(time: 4, unit: 'HOURS') {
                    input id: 'SnowBlackoutOverride', ok: "Request Override", message: "${msgApproval}"
                }
                new SlackNotifications().sendSimpleSlackNotification(serviceNowOverrideChannel, 
                    "The following rollout is requesting a ServiceNow Blackout Override:\n" +
                    "Infrastructure: ${deployConfiguration.infrastructure}\n" +
                    "Environment: ${env.JOB_BASE_NAME}\n" +
                    "Git Repo Name: ${env.REPO_NAME}\n" +
                    "Blackout Event:\n-------------\n${env.eventMsg.trim()}\n-------------\n" +
                    "Jenkins Job Link: ${env.BUILD_URL}\n\n" +
                    "Authorized Users - \"${conf.infrastructure} Approval Group\"\n" +
                    "(More info regarding approval groups: https://confluence.sie.sony.com/x/nXIjXw)\n" +
                    "If you wish to override the Blackout Event, please click \"Override\" in the dialog presented in the Jenkins Job link provided above.",
                    "FAILED")

                if(isEmergencyDeployment){
                    msgApproval = "Restriction API returned unexpected result.\n\n" +
                        "•[Override] Select this option to override these blackout events.\n(Authorized Users:\nAnyone)\n\n" +
                        "•[Abort] Click this button to cancel the rollout."
                    timeout(time: 2, unit: 'HOURS') {
                        overrideBlackOutGateInput = input id: 'SnowBlackoutOverride', ok: "Override", message: "${msgApproval}", parameters: [text(description: 'Please enter a short reason for bypassing:', name: 'overrideReason')]
                    }

                } else {
                    msgApproval = "Restriction API returned unexpected result.\n\n" +
                        "•[Override] Select this option to override these blackout events.\n(\"${conf.infrastructure} Approval Group\")\n\n" +
                        "•[Abort] Click this button to cancel the rollout."
                    timeout(time: 2, unit: 'HOURS') {
                        overrideBlackOutGateInput = input id: 'SnowBlackoutOverride', ok: "Override", message: "${msgApproval}", submitter: aloyApproverGroup, parameters: [text(description: 'Please enter a short reason for bypassing:', name: 'overrideReason')]
                    }
                }

                conf.overrideReason = "Restriction API error on Platform ${conf.infrastructure}\\nError Overriden by: ${deployUtils.getLatestApprover()}"
                if(overrideBlackOutGateInput && overrideBlackOutGateInput != "")
                    conf.overrideReason += "\\nOverride Justification:\\n------------------------\\n${overrideBlackOutGateInput.replaceAll("\n", '\\\\n')}\\n------------------------\\n"
                canDeploy = true
            } else {
                throw err
            }
        }

        if (canDeploy == false && jenkinsUtils.isBuildStatusOK()) {
            double waitMillis = getAutoApproveTimeout(endTimes)
            def endDate = endTimes.max()
            int startMillis = System.currentTimeMillis()
            
            //notify the users via slack
            String slackMsg = "This rollout is blocked: \n" + env.BUILD_URL + "\n" + "Due to these upcoming events in the next 2 hours:\n" + env.eventMsg
            if (conf.slackChannels != null) {
                for(int i=0; i < conf.slackChannels.size(); i++) {
                    new SlackNotifications().sendSimpleSlackNotification(conf.slackChannels[i].name, slackMsg, "FAILED", "", '@here')
                }
            }
            try {
                if (isEmergencyDeployment) {
                    //allow anyone to bypass if its an emergency deployment
                    msgApproval = "Rollouts are blocked 2 hours in advance for upcoming blackout events.\n\n" +
                        "The following blackout events are planned:" + 
                        "\n-------------\n" +
                        "${env.eventMsg.trim()}" +
                        "\n-------------\n" +
                        "•[Override] Select this option and click \"Proceed\" to bypass this blackout event.\n" +
                        "(Use only for urgent emergencies)\n\n" +
                        "•[Abort] Click this button to cancel the rollout."
                    echo msgApproval
                    timeout(time: 2, unit: 'HOURS') {
                        input id: 'emergencyBlackoutSchedule', message: msgApproval, ok: "Override"
                    }
                }
                else {
                    //standard deployment - only aloyApproverGroup can override the blackout event
                    msgApproval = "Rollouts are blocked 2 hours in advance for upcoming blackout events.\n\n" +
                        "The following blackout events are planned:" + 
                        "\n-------------\n" +
                        "${env.eventMsg.trim()}" +
                        "\n-------------\n" +
                        "•[Wait] Select this option and click \"Proceed\" for the rollout to automatically continue after ${env.SCHEDULE_TIME}.\n\n" +
                        "•[Override] Select this option and click \"Proceed\" to bypass this blackout event.\n\n" +
                        "•[Abort] Click this button to cancel the rollout.\n\n" +
                        "More information regarding the Blackout Event Override process can be found here:\n"+ 
                        "https://confluence.sie.sony.com/x/dIiiX"
                    echo msgApproval
                    String inputReturn
                    timeout(time: 2, unit: 'HOURS') {
                        inputReturn = input id: 'SnowBlackoutSchedule', message: msgApproval, parameters: [choice(choices: ['Wait', 'Override'], name: 'blackoutChoice')]
                    }
                    if (inputReturn == 'Override') {
                        //send a slack message to the channel to let the relavant people know that an blackout override is being requested  
                        new SlackNotifications().sendSimpleSlackNotification(serviceNowOverrideChannel, 
                            "The following rollout is requesting a ServiceNow Blackout Override:\n" +
                            "Infrastructure: ${deployConfiguration.infrastructure}\n" +
                            "Environment: ${env.JOB_BASE_NAME}\n" +
                            "Git Repo Name: ${env.REPO_NAME}\n" +
                            "Blackout Event:\n-------------\n${env.eventMsg.trim()}\n-------------\n" +
                            "Jenkins Job Link: ${env.BUILD_URL}\n\n" +
                            "Authorized Users - \"${conf.infrastructure} Approval Group\"\n" +
                            "(More info regarding approval groups: https://confluence.sie.sony.com/x/nXIjXw)\n" +
                            "If you wish to override the Blackout Event, please click \"Override\" in the dialog presented in the Jenkins Job link provided above.",
                            "FAILED")

                        msgApproval = "-------------\n${env.eventMsg.trim()}\n-------------\n" + 
                            "•[Override] Bypass the blackout and allow the deployment to proceed. \n(Authorized Users: \"${conf.infrastructure} Approval Group\")\n" +
                            "(More info regarding approval groups: https://confluence.sie.sony.com/x/nXIjXw)\n\n" +
                            "•[Abort] Click this button to cancel the rollout.\n\n" +
                            "(More info regarding the override process: https://confluence.sie.sony.com/x/xQtkTQ)"
                        echo msgApproval
                        def overrideInput
                        timeout(time: 2, unit: 'HOURS') {                    
                            overrideInput = input id: 'SnowBlackoutOverride', ok: "Override", message: "${msgApproval}", submitter: aloyApproverGroup, parameters: [text(description: 'Please enter a short reason for bypassing the override:', name: 'overrideReason')]
                        }
                        conf.overrideReason = "Blackout Detected: ${env.SCHEDULE_NAME} ends at ${timeFormat(env.SCHEDULE_TIME)} for Platform ${conf.infrastructure}\\nConflict Overriden by: ${deployUtils.getLatestApprover()}\\nOverride Justification:\\n------------------------\\n${overrideInput.replaceAll("\n", '\\\\n')}\\n------------------------\\n"
                        env.SCHEDULE_TIME = ''
                    } else if (inputReturn == 'Wait') {
                        waitMillis = getAutoApproveTimeout(endTimes)
                        echo "Waiting until ${env.SCHEDULE_TIME} to proceed due to the Blackout event: \"${env.SCHEDULE_NAME}\""
                        sleep(time: waitMillis, unit: 'MILLISECONDS')
                    }
                }
            } catch (Exception err) {
                ansi_echo "DEBUG on Error: ${err.getMessage()}", 31
                deniedBy =  deployUtils.isTimeoutMilli((int)startMillis, (double)waitMillis) ? "SYSTEM" : deployUtils.getDeniedBy(err)
                if(deniedBy == "SYSTEM") {
                    if(pollCounter < 5) {
                        getRestrictions (pollCounter+1)
                    } else {
                        echo "ServiceNow Blackout Override declined due to no open time slots after ${pollCounter} attempts."
                        p1npNotificationOnError("The deployment was aborted due to blackout widows in place with no available deployment time slots.")
                        currentBuild.result = 'ABORTED'
                        error 'The deployment was aborted due to blackout widows in place with no available deployment time slots, please try again later.'
                    }
                    if (pollCounter == 0) ansi_echo "The ServiceNow Blackout is over."
                } else {
                    echo "ServiceNow Blackout Override declined by ${deniedBy}"
                    currentBuild.result = 'ABORTED'                    
                    error "ServiceNow Blackout Override declined by ${deniedBy}"
                }
            }
        }
    }

    def eventParser(def events, def conf, def pollCounter = 1) {
        def endTimes = []
        def scheduleName = []
        if (pollCounter < 5) {
            events.result.events.each { event ->      
                //Check the event titles for events we can exclude before building the message:
                //-Only respect the event "Gamescom" if its for the p1-np, otherwise do not respect it
                //-Dont respect the event called "Fortnite Spike" on any line
                if(event.Event.contains("Gamescom") && conf.psenv=="p1-np"){
                    env.eventMsg += "${event.Event}:\n${event.Start} - \n${event.End} ${timeZoneName()}\n\n"
                    endTimes.add("${event.End}")
                    scheduleName.add("${event.Schedule}")
                
                } else if(!event.Event.contains("Fortnite Spike") && !event.Event.contains("Gamescom") && !event.Event.contains("Navigator Splunk maintenance")){
                    env.eventMsg += "${event.Event}:\n${event.Start} - \n${event.End} ${timeZoneName()}\n\n"
                    endTimes.add("${event.End}")
                    scheduleName.add("${event.Schedule}")
                }
            }
            //if there are events but the last endTime is over 6 hours away, stop calling ServiceNow for more events
            //if its an emergency deployment, return the event which the user can override
            //otherwise return "override" so the user can request an overide
            if (endTimes.max() != null && howMuchInFuture(endTimes.max()) > 6) {
                if(deployUtils.isEmergencyDeployment()) return endTimes
                else return 'Override'
            }
        } else {
            //if max attempts have been exceeded and its an emergency deployment, return all the events
            //else prompt for an override due to no open slots
            if (deployUtils.isEmergencyDeployment())
                return endTimes
            else
                return 'Override'
        }
        if (endTimes.size() > 0) { 
            def endDate = timeAddOneSecond(endTimes.max())
            def futureOpenTime = callRestrictionApi(conf, endDate)
            if(futureOpenTime) {
                boolean canDeployKeySet = futureOpenTime.result.keySet().contains('canDeploy')
                if (canDeployKeySet) {
                    env.SCHEDULE_TIME = endDate
                    env.SCHEDULE_NAME = scheduleName.last()
                } else if(!canDeployKeySet) {
                    env.SCHEDULE_TIME = endDate
                    env.SCHEDULE_NAME = scheduleName.last() 
                    return eventParser (futureOpenTime, conf, pollCounter+1)
                }
            }
            return endTimes
        } else if (env.SCHEDULE_TIME == null) {
            return 'Deploy' 
        } else {
            return endTimes
        }
    }

    def getDeploymentEnv(def psenv) {
        String deployEnv = null
        switch (psenv.toLowerCase()) {
            //case 'q1-np':
            case 'e1-np':
                deployEnv = "E1-NP"
                break   
            case 'p1-np':
                deployEnv = "NP"
                break 
            case 'mgmt':
            case 'p1-mgmt':
            case 'p1-pmgt':
                deployEnv = "MGMT"
                break
            case 'pqa':
            case 'p1-pqa':
                deployEnv = "Prod-QA"
                break
            case 'spint':
            case 'p1-spint':
                deployEnv = "SP-INT"
                break
        }
        return deployEnv
    }

    def callRestrictionApi(def conf, def approvalDate) {
        def dlsSet = TimeZone.getTimeZone("America/Los_Angeles").inDaylightTime(new Date())
        if (dlsSet == false) approvalDate = timeFormat(approvalDate)
        approvalDate = java.net.URLEncoder.encode(approvalDate, "UTF-8")
        String platform = getRestrictionPlatform()
        String deployEnv = getDeploymentEnv(conf.psenv)
        def url = "api/sie/change_restriction_api/restriction/${approvalDate}?seconds=7200\\&when=after\\&platform=${platform}\\&environment=${deployEnv}"
        def response = getData(url)
        return response
    }

    def timeFormat(def dateTime, boolean addHour = false) {
        def dlsSet = TimeZone.getTimeZone("America/Los_Angeles").inDaylightTime(new Date())
        def timeOffset = -1
        if (dlsSet) timeOffset = 0
        if (addHour) timeOffset = +1
        def dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        dateFormat.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles"))
        def parsedDate = dateFormat.parse(dateTime)
        Calendar calendar = new GregorianCalendar();
        calendar.setTime(parsedDate);
        calendar.add(Calendar.HOUR, timeOffset); 
        return dateFormat.format(calendar.getTime())
    }

    def timeZoneName() {    
        return TimeZone.getTimeZone("America/Los_Angeles").getDisplayName(TimeZone.getTimeZone("America/Los_Angeles").inDaylightTime(new Date()), 0)
    }

    int getTimeOffset() {
        def dlsSet = TimeZone.getTimeZone("America/Los_Angeles").inDaylightTime(new Date())
        return dlsSet ? 7 : 8
    }

    def timeUTCFormat(def dateTime) {
        def timeOffset = getTimeOffset() 
        def dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"))
        def parsedDate = dateFormat.parse(dateTime)
        Calendar calendar = new GregorianCalendar()
        calendar.setTime(parsedDate)
        calendar.add(Calendar.HOUR, +timeOffset)
        return dateFormat.format(calendar.getTime())
    }
    
     def timeAddOneSecond(def dateTime) {
        def dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        def parsedDate = dateFormat.parse(dateTime)
        Calendar calendar = new GregorianCalendar()
        calendar.setTime(parsedDate)
        calendar.add(Calendar.SECOND, +1)
        return dateFormat.format(calendar.getTime())
    }
    
    def isTimeInFuture(def rawStartDate, def rawEndDate) {
        boolean ret = true
        def dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        def startDate  = ''
        def endDate = ''
        def currentTime = System.currentTimeMillis() 
        try {
            startDate  = dateFormat.parse(rawStartDate).getTime()
            endDate  = dateFormat.parse(rawEndDate).getTime()
        } catch (Exception e) {
            error "** ERROR: The submitted Start Time or End Time were invalid. Please input the time in the 24 hour format HH:mm (Ex. \"20:00\") **"
        }
        if (endDate <= startDate) ret = false
        
        return ret
    }
            
    def howMuchInFuture(def endDate) {
        currentDate = new Date().format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone('America/Los_Angeles'))
        def dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        def startDate  = ''
        startDate  = dateFormat.parse(currentDate).getTime()
        endDate  = dateFormat.parse(endDate).getTime()
        def difference =((endDate - startDate)/3600000)
        return difference
    }

    def timePTFormat(def dateTime) {
        def timeOffset = getTimeOffset() 
        def dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        dateFormat.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles"))
        def parsedDate = dateFormat.parse(dateTime)
        Calendar calendar = new GregorianCalendar()
        calendar.setTime(parsedDate)
        calendar.add(Calendar.HOUR, -timeOffset)
        return dateFormat.format(calendar.getTime())
    }

    def snowTimeGate(def startTime) {
        boolean canStart = false
        def dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"))
        def parsedDate = dateFormat.parse(startTime).getTime()
        def currentTime = System.currentTimeMillis()
        if (parsedDate < currentTime) canStart = true 
        return canStart
    }

    def snowEndTimeGate(def startTime) {
        boolean canStart = false
        def dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"))
        def parsedDate = dateFormat.parse(startTime).getTime()
        def currentTime = System.currentTimeMillis()
        if (parsedDate > currentTime) canStart = true 
        return canStart
    }    

    def getAutoApproveTimeout(def endTimes) {
        def latestDate = endTimes.max()
        def dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        dateFormat.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles"))
        def parsedDate = dateFormat.parse(latestDate)
        Date currDate = new Date()
        def numOfMillis = parsedDate.getTime() - currDate.getTime()
        return numOfMillis
    }

    def getCRState (def SysId) {    
        def url = "api/sie/psn_change_management/${crType}/${SysId}"
        def getResponse = getData(url)
        if (getResponse?.result?.number?.value == null) {
            error '** ERROR: curl servicenow psn_change_management GET returned an error'
        }
        return getResponse.result.state.display_value
    }

    def getAppSysId (def appName) {
        def url = "api/now/table/cmdb_ci?sysparm_query=name=${appName}\\&u_entity=psn\\&sysparm_fields=sys_id"
        def getResponse = getData(url)
        if (getResponse?.result?.number?.value == null) {
            error '** ERROR: curl servicenow psn_change_management GET returned an error'
        }
        def sysId = ""
        if (getResponse?.result?.size()>0) {
            sysId = getResponse.result[0].sys_id ?: ""
        } 
        if(sysId == "") {
            error "${appName} was not found, or has not been created yet.\n" +
            "Please go to the following page for the ServiceNow CR Approval Proccess:\n" +
            "https://confluence.sie.sony.com/x/ZIvxU\n" +
            "or to our support channel in Slack: #engine-support\n"

        }
        return sysId
    }
    
    def getCloseNotes() {
        def url = "/api/now/table/x_sie_psn_change_request?"
            url += "sysparm_fields=number,close_notes\\&" //return
            url += "sysparm_query=u_platform=psn"
            url += "^approval=approved"
            url += "^active=true"
            url += "^type=normal"
            url += "^state=-1"                                  //^^ hardcoded
            url += "^number=${env.SERVICE_TICKET}"              //serviceTicket (PSNCHG1234567)
        
        def getResponse = getData(url)
        if (getResponse?.result?.number?.value == null) {
            error '** ERROR: curl servicenow psn_change_management GET returned an error'
        }
        def close_notes = ""
        if (getResponse?.result?.size()>0) {
            close_notes = getResponse.result[0].close_notes ?: ""
        } 

        return close_notes
    }

    def getCRTask (def SysId) {
        def url = "api/sie/psn_change_management/${SysId}/task"
        def getResponse = getData(url)
        if (getResponse?.result?.number?.value == null) {
            error '** ERROR: curl servicenow psn_change_management GET returned an error'
        }
        
        return getResponse.result.sys_id.value
    }

    // Sys_id for all Environment values
    def environmentSysId(String psenv) {
        def retval = ''
        switch (psenv.toLowerCase()) {
        case 'e1-np':
        case 'tools-nonprod':
            retval = '4f1853e7dbffef006e52d1c2ca96190a'
            break
        case 'p1-np-usw2':
        case 'p1-np-use2':
        case 'p1-np':
        case 'np':
        case 'tools-prod':
        case 'tools-preprod':
            retval = '4c489ba7dbffef006e52d1c2ca961949'
            break
        case 'p1-pqa':
        case 'pqa':
            retval = '843853e7dbffef006e52d1c2ca9619f9'
            break
        case 'p1-mgmt':
        case 'mgmt':
        case 'p1-pmgt':
        case 'pmgt':
            retval = 'de385fa7dbffef006e52d1c2ca961950'
            break
        case 'p1-spint':
        case 'spint':
            retval = '6528d7a7dbffef006e52d1c2ca961940'
            break
        }
        return retval
    }
    
     def sysIdToEnvironment(String envSysId) {
        def retval = ''
        switch (envSysId) {
        case '4c489ba7dbffef006e52d1c2ca961949':
            retval = 'np'
            break
        case "843853e7dbffef006e52d1c2ca9619f9":
            retval = 'pqa'
            break
        case "de385fa7dbffef006e52d1c2ca961950":
            retval = 'mgmt'
            break
        case "6528d7a7dbffef006e52d1c2ca961940":
            retval = 'spint'
            break
        }
        return retval
    }
    

    def checkPreviousDeployments(def deployConfiguration, def envSysId) {
        Boolean ticketsExist = false
        def checkEnv = determineLineCheck(deployConfiguration.psenv)
        def prevEnv = environmentSysId(checkEnv)
        def serviceNowCIName = deployConfiguration.serviceNowConfig[0].serviceNowCIName
        //use image.tag (RAA_VERSION) for Roadster appVersion, otherwise use APP_VERSION
        def appVersion = deployConfiguration.infrastructure == "roadster-cloud" ? deployConfiguration.raaVersion : env.APP_VERSION
        echo "deployConfiguration: $deployConfiguration"
        /* Query for previously created CRs that are:
            - Platform = PSN
            - Short Description contains the app name
            - The CR is for the pre-requisite environment
            - App version is the same
            - State = 3 (Closed)
            - Closed Code = Sucessful (no failed deployments)
        */
        def url = "api/now/table/x_sie_psn_change_request?sysparm_fields=number,sys_created_on\\"+
            "&sysparm_query=short_descriptionLIKE${serviceNowCIName.replaceAll('-psn','')}"+
            "^u_environment=${prevEnv}"+
            "^app_version=${appVersion}"+
            "^state=3"+
            "^u_platform=psn"+
            "^close_code=successful\\&sysparm_limit=5"
        def getResponse = getData(url)
        echo "***Check Previous Rollout Response: $getResponse"
        if (getResponse?.result.size() != 0) {
            ticketsExist = true
        } else { //not deployed to previous yet, slack to the team channels
            if (deployConfiguration.slackChannels) {
                String msgSlack = "The following rollout was prevented from continuing: ${env.BUILD_URL}\n"+
                    "Reason: It must first be deployed to `${checkEnv}`.\n" +
                    "Ensure a CR exists for a sucessful deployment to `${checkEnv}` with the following specifications:\n"+ 
                    "ServiceNow Platform: `PSN`\n" +
                    "ServiceNow CI: `${serviceNowCIName}`\n" +
                    "App Version: `${appVersion}`\n" + 
                    "CR Short Description contains `${serviceNowCIName.replaceAll('-psn','')}`\n" +
                    "CR State: `Closed`\n" +
                    "Closed Code: `Successful`"
                for(int i=0; i < deployConfiguration.slackChannels.size(); i++) {
                    new SlackNotifications().sendSimpleSlackNotification(deployConfiguration.slackChannels[i].name, msgSlack, "FAILURE")
                }
            }
        }
        return [ticketsExist, checkEnv]
    }

    def determineLineCheck(def psenv) {
        //check the one env below the cluster attempting to deploy to
        def envsToCheck = 'e1-np'
        //switch (psenv) {
        //    case ~/.*p1.*/:
        //        envsToCheck = "e1-np"
        //        break
        //    case ~/.*e1.*/:
        //        envsToCheck = "q1-np"
        //       break
        //}
        echo "Env Needed to Proceed: ${envsToCheck}"
        return envsToCheck
    }

    def getTestEnvs(){
        def useEnv = ''
        def prevEnv = ''
        switch (deployConfiguration.psenv) {
            case ~/.*d0.*/:
            case ~/.*d1.*/:
            case ~/.*q1.*/:
                useEnv = 'Q1'
                prevEnv = 'D1'
                break   
            case ~/.*e1.*/:
                useEnv = 'E1'
                prevEnv = 'Q1'
                break   
            case ~/.*p1.*/:
                useEnv = 'P1'
                prevEnv = 'E1'
                break   
        }
        return [useEnv, prevEnv]
    }

    def CRApprovalWaitGate(def crSysId, def crName, def crApprovalGroup, def serviceName){
        int startMillis = 0
        int timeoutSeconds = 2 * 24 * 60 * 60
        def isImplement = false
        def crState = ''
        def inputReturn = ''
        while (!isImplement) {
            try {
                String crUrl = getCrUrl(crSysId)
                echo "Waiting for approval for ServiceNow Ticket: ${crName} - ${crUrl}"
                new SlackNotifications().sendSimpleSlackNotification(getApprovalChannel(), 
                    "Notification: *${crName}* is awaiting approval\n" +
                    "CR link: ${crUrl}\n" +
                    "Environment: ${env.JOB_BASE_NAME}\n" +
                    "Jenkins Job Link: ${env.BUILD_URL}}",
                    "FAILED")
            
                String msgApproval = "The ServiceNow Change Request ${crName} is now awaiting approvals.\n\n" +
                    "This rollout will automatically continue once all the required approvals have been met within ServiceNow Change Request: ${crName}.\n\n" +
                    "The CAB team has been automatically notified of this request in the slack channel #CAB." +
                    "\n--------------------------\n" +
                    "•[Check Status] Authorized Users - ${crApprovalGroup}: Check if the CR is currently in the implement state.\n\n" +
                    "•[Override] Authorized Users - Jenkins Admins: Used only if ServiceNow is unavailable .\n\n" +
                    "•[Abort] Cancel the current rollout"
                startMillis = System.currentTimeMillis()
                timeout(unit: 'DAYS', time: 2) {  
                    inputReturn = input id: "ServiceNowWaitGateInput-${serviceName}", message: msgApproval, ok: 'Select', parameters: [choice(choices: ['Check Status', 'Override'], name: 'CR_CHOICE')], submitter: crApprovalGroup
                    if (isImplement == false && inputReturn == 'Check Status') {
                        crState = getCRState(crSysId)
                        if (crState == "Implement") {
                            isImplement = true
                        } else {
                            echo "\n\nCR is still in ${crState} state, please get it approved and in Implement state; Looping back to Input state!\n\n"
                        }
                    } else if (inputReturn == 'Override' || inputReturn == null) {
                        // If null, input was submitted by approval job via proceedEmpty call
                        isImplement = true
                    }
                }
            } catch(Exception err) { 
                // timeout reached or input false
                def deniedBy =  deployUtils.isTimeout(startMillis, timeoutSeconds) ? "TIMEOUT" : deployUtils.getDeniedBy(err)
                echo "Current user: [${deniedBy}]..."
                currentBuild.result = 'ABORTED'
                error ("Aborted by: [${deniedBy}]...")
            }
        }
    }

    // Post Stage functions
    //
    def handlePostBuildFailure(def crSysId, def crCurrentState, def conf) {
        if  (conf.orchMultiEnvCR) {
            def matcher = manager.getLogMatcher(".*helm rollback (.*) --namespace.*")
            def addNote = 'Rollback was not successful!'
            if (matcher?.matches()) {
                addNote = "\\nRollback was a success, rolled back to: ${matcher.group(1)}"
            }
            matcher = null
            def closeNotes=constructCloseNotes(conf, addNote) 
            lock(env.SERVICE_TICKET) {
                patchCR(crSysId,'close_notes', closeNotes)
            }
        }
        patchCR(crSysId,'u_implementation_status','Backed Out')
        patchCR(crSysId,'close_code','Unsuccessful')
        getCRToState(crSysId, crCurrentState, "Closed")
    }
    
    def handlePostBuildSuccess(def crSysId, def crCurrentState, def conf){
        if (env.SERVICE_TICKET) {
            def closeNotes=constructCloseNotes(conf, getLocalTestEvidence())
            patchCR(crSysId,'close_notes', closeNotes)
        } else {
            patchCR(crSysId,'close_notes', getLocalTestEvidence())
        }
        patchCR(crSysId,'u_implementation_status','Implemented')
        patchCR(crSysId,'close_code','Successful')
        getCRToState(crSysId, crCurrentState, "Review")
        sleep 5
        def crState = getCRState(crSysId)
        if (crState == "Review") {
            patchCRState(crSysId, "Closed")
        }
    }

    def handlePostBuildAborted(def crSysId, def crCurrentState, def conf){
        if  (conf.orchMultiEnvCR) {
            def closeNotes=constructCloseNotes(conf)
            lock(env.SERVICE_TICKET) {
                patchCR(crSysId,'close_notes', closeNotes)
            }
        }
        patchCR(crSysId,'u_implementation_status','Backed Out')
        patchCR(crSysId,'comments','Aborted')
        getCRToState(crSysId, crCurrentState, "Canceled")
    }

    def cRTicketClosure(def crSysId, def postEventType, def conf) {
        if (crSysId != "" && crSysId != null ) {
            try{
                if (env.CR_TYPE != null) crType = env.CR_TYPE
                if (crType != 'standard') crType = deployUtils.isEmergencyDeployment() == false ? 'normal' : 'emergency'
                echo "postEventType: ${postEventType} crType: ${crType} orchMultiEnvCR: ${conf.orchMultiEnvCR}"
                crCurrentState = getCRState(crSysId)
                def currentTime = new Date(Calendar.getInstance().getTimeInMillis()).format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone('America/Los_Angeles'));
                echo "Setting Actual End Date to ${currentTime} ${timeZoneName()}"
                env.WORK_END_TIME = timeUTCFormat(currentTime)
                if (!conf.orchMultiEnvCR) patchCR(crSysId,'work_end',env.WORK_END_TIME)
                switch(postEventType){
                    case "success":
                        if  (!conf.orchMultiEnvCR) {
                            if (crType == 'emergency') {
                                handlePostBuildEmergency(crSysId, crCurrentState)
                            } else if (crType == 'standard') {
                                handlePostBuildStandard(crSysId, crCurrentState)
                            } else {
                                handlePostBuildSuccess(crSysId, crCurrentState, conf)
                            }
                        } else {
                            lock(env.SERVICE_TICKET) {
                                def closeNotes = getCloseNotes()
                                def envRead = ''
                                def envList = []
                                int expectedEnvs = conf.multiEnvs.size()
                                def envMatcher = closeNotes =~ /ENV:([A-Za-z0-9_-]*),/
                                int completedEnvs = envMatcher.size()
                                for (int i = 0; i < completedEnvs; i++) {
                                    envList.add(envMatcher[i][1])
                                }
                                envMatcher = null
                                echo "expectedEnvs: ${conf.multiEnvs.join(', ')} completedEnvs: ${envList.join(', ')}"
                                if (!envList.contains(conf.psenv)) {
                                    crCurrentState = getCRState(crSysId)
                                    if (crCurrentState == 'Implement') {
                                        if ((expectedEnvs - completedEnvs) > 1) {
                                            echo "All expected enviorments not yet complete, adding Notes..."
                                            handleMultiEnvCRNote(crSysId, crCurrentState, conf)
                                        } else if ((expectedEnvs - completedEnvs) == 1){
                                            echo "All expected enviorments complete, closing..."
                                            handlePostBuildSuccess(crSysId, crCurrentState, conf)
                                        }
                                    } else {
                                        echo "This ticket is no longer in implement state, and was closed in a different multi-environment orchestration job..." 
                                    }
                                } else {
                                    echo "${conf.psenv} already exists in the close_notes...skipping"
                                }
                            }
                        }
                        break
                    case "failure":
                        handlePostBuildFailure(crSysId, crCurrentState, conf)
                        break
                    case "unstable":
                        handlePostBuildFailure(crSysId, crCurrentState, conf)
                        break
                    case "aborted":
                        handlePostBuildAborted(crSysId, crCurrentState, conf)
                        break
                }
            } catch (Exception err) {
                if(!jenkinsUtils.isBuildAborted()) {
                    def errorMsg = "Attempting to close a ServiceNow CR failed: ${env.BUILD_URL}"
                    new SecurityUtils().sendSlackMessageOnError("engine-workflow-notify", errorMsg, "FAILED", "", "")
                }
                throw err
            }
        } 
    }
    
    def constructCloseNotes(def conf, def addition = '') {
        def closeNoteMsg = "ENV:${conf.psenv},STATUS:${currentBuild.currentResult},START_TIME:${env.WORK_START_TIME},END_TIME:${env.WORK_END_TIME},BUILD_URL:${BUILD_URL},MULTI_ENV:${conf.orchMultiEnvCR}"
        def closeNotes = getCloseNotes()
        
        if (closeNotes != '') {
            closeNotes = closeNotes.replace('\n','\\n')
            echo "Close notes had other entries: ${closeNotes}, adding: ${closeNoteMsg}"
            closeNotes += "\\n"+closeNoteMsg+"\\n"+addition
        } else {
            echo "Close Notes was empty, adding: ${closeNoteMsg}"
            closeNotes = closeNoteMsg+"\\n"+addition
        }
        return closeNotes
    }
    
    def handleMultiEnvCRNote(def crSysId, def crCurrentState, def conf) {
        def closeNotes = constructCloseNotes(conf)
        patchCR(crSysId,'close_notes', closeNotes)
    }
    
    def handlePostBuildEmergency(def crSysId, def crCurrentState) {
        patchCR(crSysId,'u_implementation_status','Implemented')
        patchCR(crSysId,'close_code','Successful')
        patchCR(crSysId,'close_notes', getLocalTestEvidence())
    }
    
    def handlePostBuildStandard(def crSysId, def crCurrentState) {
        patchCR(crSysId,'u_implementation_status','Implemented')
        patchCR(crSysId,'close_code','Successful')
        patchCR(crSysId,'close_notes', getLocalTestEvidence())
        getCRToState(crSysId, crCurrentState, "Closed")

    }

    def timeOffset(def startTime, int offset) {
        def dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"))
        def parsedDate = dateFormat.parse(startTime)
        Calendar calendar = new GregorianCalendar();
        calendar.setTime(parsedDate);
        def duration = new Date(calendar.getTimeInMillis() + (offset * 60000)).format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone('UTC'));
        return duration
    }

    // def getActivityType(def conf) {
    //     //TODO: Stage Activity, get for other envs
    //     def activityType = SERVICE_NOW_SERVER == "stage" ? 'ab47f3131b1d8910bea584ccdd4bcba7' : '1a939e731bde4110efb697d58d4bcb67'        
    //     try {
    //         def rawHelmHistory = ''
    //         sh "aws eks --region ${conf.region} update-kubeconfig --name ${conf.clusterId}"
    //         rawHelmHistory = sh (script: "helm history ${conf.appName} --max 1 --namespace ${conf.namespace} -o yaml", returnStdout: true).trim()
    //         if (rawHelmHistory != null && rawHelmHistory != '' && rawHelmHistory.contains('app_version')) {
    //             def currentChart = readYaml text: rawHelmHistory
    //             env.ROLLBACK_REVISION = "${currentChart.revision[0]} and version: ${currentChart.app_version[0]}"
    //             if (currentChart.app_version[0] != conf.appVersion) {
    //                 ansi_echo("Current Version and Version in Chart is not the same - Current: ${currentChart.app_version[0]} New: ${conf.appVersion}", 34)
    //                 //activityType = '3447c5971b0f94102d200e1dcd4bcb41'
    //             } else {
    //                 //activityType = 'afb609571b0f94102d200e1dcd4bcb03'
    //                 ansi_echo("Current Version and Version in Chart is the same - Current: ${currentChart.app_version[0]} New: ${conf.appVersion}", 34)
    //             }
    //         }   
    //     } catch (Exception err) {
    //         ansi_echo "${conf.appName} does not have a release in this cluster...${err.getMessage()}", 31
    //     } 
    //     return activityType
    // }

    def getRaaApproval (SysId) {
        def (host, cred) = internal_GetHost()
        def url = "api/now/table/sysapproval_approver?sysparm_query=approver=6341a13bdb097810f4cb378239961965^sysapproval=${SysId}"
        def getResponse = getData(url)
        if (getResponse?.result.size() == 0) {
            echo "** INFO: RAA Approval not needed..."
        } else {
            echo "** INFO: RAA Approval needed..."
            patchRaaApproval(SysId)
        }
}
    
    def patchRaaApproval (CrSysId) {
        def conf = deployConfiguration
        def (host, cred) = internal_GetHost()
        def raaState = ''
        def state = ''
        def comment = ''
        if (conf.raaReturn?.status != null) {
            if (env.RAA_PATCH_APPROVE == null) {
                state = conf.raaReturn.status
                comment = "${conf.raaReturn.message.join(' ').replaceAll('"','')}"
            } else {
                state = "pass"
                comment = "RAA failed, but was overridden by ${env.RAA_PATCH_APPROVER}"
            }
    
            if (state == 'pass') {
                raaState = 'approved'
            } else if (state == 'fail') {
                raaState = 'cancelled'
            }

            url = "api/sie/psn_change_management/${CrSysId}/approvals"

            writeFile file: 'servicenow-raa-state-update.json', text: "{\"state\":\"${raaState}\", \"comments\":\"${comment}\"}"
            withCredentials([string(credentialsId: 'RAA_APPROVER_TOKEN_LIVE', variable: 'token')]) {
                for (int i = 0; i < maxTry; i++) {
                    try {
                        sh "curl -s -k -D- -X PATCH -H \"Authorization: Bearer ${token}\" -H \"Content-Type: application/json\" -o patch-raaApprove.output -d \'@servicenow-raa-state-update.json\' ${host}/${url}"
                        response = readJSON file: 'patch-raaApprove.output'
                        if (response?.result?.number?.value == null) {
                            sh 'cat patch-raaApprove.output'
                            sh "rm patch-raaApprove.output"
                            echo '** Could not auto-approve RAA, moving forward with manual approval...'
                            sleep waitForSeconds
                        } else {
                            sh "rm patch-raaApprove.output"
                            echo "** Updated RAA Approval ${response?.result?.number?.value} set state to ${state}."
                            break
                        }
                    } catch (Exception err) {
                        if (i < maxTry){
                            sleep waitForSeconds
                        } else {
                            String msg =  err.getMessage()
                            echo "** Could not get data from api/sie/psn_change_management: ${msg}"
                        }
                    }
                }
            }
        } else {
            echo "** RAA Return was empty, moving forward with manual RAA Approval..."
        }
}
    
    def cRApprovalStage() {
        def conf = deployConfiguration
        def serviceNowConfig = conf.serviceNowConfig
        boolean multiDeploy = (serviceNowConfig.size() > 1)      
        def aloyApproverGroup = ''
        def contactMsg = ''
        (aloyApproverGroup, contactMsg) = getAloyApproverGroup()
        def waitGateParallel = [:]
        for(int i=0; i < serviceNowConfig.size(); i++){
            def item = serviceNowConfig[i]
            def crState = ''
            echo "serviceNow Info: ${item}"
            if (item.crSysId != null) crState = getCRState(item.crSysId)
            echo "Got CR State ${crState} for ${item}"
            if (crState == "Authorize") {
                getRaaApproval(item.crSysId)
                if (multiDeploy) {
                    waitGateParallel["Waiting on Approval (${item.crName})"] = {
                        CRApprovalWaitGate(item.crSysId, item.crName, aloyApproverGroup, item.serviceNowCIName)
                        setWorkStart(item.crSysId, item.crName, item.serviceNowCIName)
                    }
                } else {
                    CRApprovalWaitGate(item.crSysId, item.crName, aloyApproverGroup, item.serviceNowCIName)
                    setWorkStart(item.crSysId, item.crName, item.serviceNowCIName)
                }
            } else if (crState == "Implement") {
                setWorkStart(item.crSysId, item.crName, item.serviceNowCIName)
            }
        }
        if (multiDeploy && waitGateParallel.size() > 0) {
            waitGateParallel.failFast = true
            parallel waitGateParallel
        }
    }

    def setWorkStart(def crSysId, def crName, def serviceNowCIName) {
        def currentTime = new Date(Calendar.getInstance().getTimeInMillis()).format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone('America/Los_Angeles'));
        echo "Setting Actual Start Date to ${currentTime} ${timeZoneName()}" 
        env.WORK_START_TIME = timeUTCFormat(currentTime)
        if (!deployConfiguration.orchMultiEnvCR.toBoolean()) patchCR(crSysId,'work_start',env.WORK_START_TIME)
        String crUrl = getCrUrl(crSysId)
        new SlackNotifications().sendSimpleSlackNotification(getGlobalNotificationChannel(),
            "Deployment for ServiceNow CR ${crName} is starting.\n" +
            "Environment: ${env.JOB_BASE_NAME}\n" +
            "CR Domain: PSN\n" +
            "Infrastructure: ${deployConfiguration.infrastructure}\n" +
            "Git Repo Name: ${env.REPO_NAME}\n" +
            "ServiceNow CI Name: ${serviceNowCIName}\n" +
            "Jenkins Job Link: ${env.BUILD_URL}\n" +
            "CR link: ${crUrl}",
            "SUCCESS")
    }

    def getUserNameFromJob(def Url) {
        def jobInfo = getJobUrlParts(Url)
        if (jobInfo == null) {
            echo "Jenkins URL unknown: ${Url}"
        }
        def retval = 'Hyperloop Service'
        def userId = ''
        def userEmail = ''
        def userFromName = ''
        def build = getJenkinsInfo(jobInfo[0], jobInfo[1])
        if(build==null) {
            echo "*** can not find the user email"
            return [retval, userEmail]
        }
        //echo "DEBUG Build ${build.getClass()} ${build['_class']}\n\t{$build}"
        def cause = getLastCause(build.actions)
        if (cause['_class'] == 'hudson.model.Cause$UserIdCause') {
            // If the name is in Last Name, First Name format, change it to First Name Last Name format for ServiceNow
            retval = getNameFormatted(cause.userName)
            userId = cause.userId
            userEmail = hudson.tasks.MailAddressResolver.resolve(User.getById(userId, false))
            echo "*** Found userName ${retval} with email: ${userEmail}"   
            if ((retval == 'Hyperloop Service') || (retval.contains("CICD"))) {
                (retval, userEmail) = getUserNameFromJob(getRemoteCaller(build.actions))
            }
        } else if (cause['_class'] == 'hudson.model.Cause$UpstreamCause' || cause['_class'] == 'org.jenkinsci.plugins.workflow.support.steps.build.BuildUpstreamCause' || cause['_class'] == 'com.sonyericsson.rebuild.RebuildCause') {
            // Look for upstream cause or rebuild, and loop to next cause till a root is found, which can be on a legacy CI host.    
            def  controller = ''
            if ("${jobInfo[0].toLowerCase()}".contains('core')) {
                controller = System.getProperty('MASTER_DOMAIN')+"/"
            }
            (retval, userEmail) = getUserNameFromJob("${jobInfo[0].toLowerCase()}.hyperloop.sonynei.net/${controller}${cause.upstreamUrl}${cause.upstreamBuild}/")
        } else if (cause['_class'] == 'org.jenkinsci.plugins.workflow.cps.replay.ReplayCause') {
            // If job was replayed, it will look back till it finds original job and gets the user that ran it.
            def  controller = ''
            if ("${jobInfo[0].toLowerCase()}".contains('core')) {
                controller = System.getProperty('MASTER_DOMAIN')+"/"
            }
            (retval, userEmail) = getUserNameFromJob("${env.JOB_URL}${cause['shortDescription'].replaceAll('Replayed #','')}/")
        } else if (cause['_class'] == 'com.cloudbees.jenkins.GitHubPushCause') {
            // If job was started by a GitHub Push, it will get the name used, and use that to fetch the email for lookup.
            def shortDesc = cause['shortDescription']
            def matcher = shortDesc =~ /Started by GitHub push by (.*)/
            if (matcher.matches()) {
                def gitUserName = User.get(matcher.group(1),false).toString()
                if (gitUserName != 'null') {
                    userFromName = User.get(gitUserName, false, Collections.emptyMap()).getId()        
                    if (!userFromName.contains("@aloy.playstation.net") && env.BUILD_URL.contains('core.jenkins')) {
                        userFromName = userFromName+"@aloy.playstation.net"    
                        userEmail = hudson.tasks.MailAddressResolver.resolve(User.getById(userFromName, false))
                    } else if (!env.BUILD_URL.contains('core.jenkins')){
                        userEmail = hudson.tasks.MailAddressResolver.resolve(User.getById(userFromName, false))
                    }
                    retval = getNameFormatted(gitUserName)
                    echo "*** Found userName ${retval} with email: ${userEmail}"   
                }
            }
        } else if (cause['_class'] == 'org.jenkinsci.plugins.ghprb.GhprbCause') {
            echo "*** Orchestration job started by Pull-Request, using default username: ${retval}"
        } else {
            echo "*** Found ${cause.getClass()}. Returning hyperloopops userName"
        }
        return [retval, userEmail]
    }

    // get remote caller URL from parameters
    def getRemoteCaller(def Actions) {
        def retval
        //echo "DEBUG Parameters ${Actions.getClass()}\n\t${Actions}"
        Actions.find { action ->
            //echo "DEBUG Parameters find ${action.getClass()}\n\t${action}"
            if (action['_class'] == 'hudson.model.ParametersAction') {
                def params = action.parameters
                action.parameters.find { param ->
                    //echo "DEBUG Parameter find ${param.getClass()} ${param.name}"
                    if (param.name == 'REMOTE_CALLER') {
                        if (param.value != '') {
                            echo "*** Found REMOTE_CALLER ${param.value}"  
                            retval = param.value
                            return true // exit action.parameter.find after setting retval
                        }
                    } 
                }
                return true // exit Actions.find after looking at ParametersAction
            }
        }
        if (retval == null) {
            //TODO: Implement REMOTE_CALLER 
            //error "*** No REMOTE_CALLER parameter found"
            echo "*** No REMOTE_CALLER parameter found"
        }
        return retval
    }

    def getJobUrlParts(def Url) {
        def matcher = Url =~ /.*(ci|cd|core.jenkins|core_jenkins).hyperloop.sonynei.net\/(.+)\//
        if (matcher.matches()) {
            def host = 'CI'
            if (matcher.group(1) == 'cd') {
                host = 'CD'
            }
            if (matcher.group(1) == 'core.jenkins' || matcher.group(1) == 'core_jenkins' ) {
                host = 'CORE_JENKINS'
            }     
            return [host, matcher.group(2)]
        }
    }

    // get CauseAction from build info
    def getLastCause(def Actions) {
        def retval
        //echo "DEBUG Causes ${Actions.getClass()}\n\t${Actions}"
        Actions.find { action ->
            //echo "DEBUG Causes each ${action.getClass()}\n\t${action}"
            if (action['_class'] == 'hudson.model.CauseAction') {
                retval = action.causes.last()
                echo "*** Found last cause ${retval['_class']}\n\t${retval}"
                return true // exit Build.actions.each after setting retval
            }
        }
        if (retval == null) {
            error "*** No known cause found"
        }
        return retval
    }

    def getJenkinsInfo(def JenkinsHost, def Path) {
        withCredentials([string(credentialsId: "${JenkinsHost}_HYPERLOOPOPS_API_TOKEN", variable: 'HYPERLOOPOPS_API_TOKEN')]) {
            def host = 'ci.hyperloop.sonynei.net'
            if (JenkinsHost == 'CD') {
                host = 'cd.hyperloop.sonynei.net'
            }
            if (JenkinsHost == 'CORE_JENKINS') {
                host = 'core.jenkins.hyperloop.sonynei.net'
            }   
            for (int i = 0; i < maxTry; i++) {
                try {     
                    sh "curl -u hyperloopops:${HYPERLOOPOPS_API_TOKEN} https://${host}/${Path}/api/json -o output.json"
                    def retval = readJSON file: 'output.json'
                    // echo " --- getJenkinsInfo: ${retval}"
                    return retval
                } catch (Exception err) {
                    if (i < maxTry){
                        sleep waitForSeconds
                    } else {
                        throw err
                    }
                }
            }
        }
    }

    def getNameFormatted(def Name) {
        def matcher = Name =~ /([a-zA-Z]+), ([a-zA-Z]+)/
        if (matcher.matches()) {
            return "${matcher.group(2)} ${matcher.group(1)}"
        }
        return Name
    }

    def String getPostCrData(def crDefinition) {
        def startDate = ''
        def endDate = ''
        
        if (!env.START_DATE) {
            startDate = getTimeOffset(0)
            endDate = getTimeOffset(120)
        } else {
            def rawStartDate = "${START_DATE} ${START_TIME}:00"
            def rawEndDate = "${END_DATE} ${END_TIME}:00"
            if (isTimeInFuture(timeUTCFormat(rawStartDate), timeUTCFormat(rawEndDate))) {
                startDate = timeUTCFormat(rawStartDate)
                endDate = timeUTCFormat(rawEndDate)
            } else if (startDate == endDate) { 
                error "** ERROR: The submitted Start Time is equal to the submitted End Time. Please input two unique Date / Times. **" 
            } else { 
                error "** ERROR: The submitted Start Time (\"${startDate}\") is after the End Time (\"${endDate}\"). Please input the time in the 24 hour format HH:mm (Ex. \"20:00\") **"
            }   
        }
        def nonLiveTrafficMsg = 'Non-live-traffic UKS test deployment, for use with onboarind to UKS.'
        String crStringNormal = """{\"requested_by\":\"${crDefinition.requestedBy}\", 
        \"u_data_center\":\"${crDefinition.dataCenter}\", 
        \"u_environment\":\"${crDefinition.environmentSysId}\", 
        \"u_platform\":\"${crDefinition.platform}\", 
        \"category\":\"${crDefinition.category}\", 
        \"cmdb_ci\":\"${crDefinition.serviceNameSysId}\", 
        \"short_description\": \"${crDefinition.shortDescription}\", 
        \"description\":\"${crDefinition.description}\", 
        \"implementation_plan\":\"${crDefinition.implementationPlan}\", 
        \"u_activity\":\"${crDefinition.activityType}\", 
        \"u_deployment_framework\":\"${crDefinition.deploymentFramework}\", 
        \"u_validation_plan\":\"${crDefinition.jobLink}\", 
        \"u_test_evidence\":\"${crDefinition.testEvidence}\", 
        \"backout_plan\":\"${crDefinition.backoutPlan}\", 
        \"justification\": \"${crDefinition.overrideReason}\", 
        \"app_version\": \"${crDefinition.appVersion}\",
        \"start_date\":\"${startDate}\", 
        \"end_date\":\"${endDate}\"}
        """

    String crStringStandard = """{\"requested_by\":\"${crDefinition.requestedBy}\", 
        \"u_data_center\":\"${crDefinition.dataCenter}\", 
        \"u_environment\":\"${crDefinition.environmentSysId}\", 
        \"u_platform\":\"${crDefinition.platform}\", 
        \"category\":\"${crDefinition.category}\", 
        \"cmdb_ci\":\"${crDefinition.serviceNameSysId}\", 
        \"short_description\": \"${crDefinition.shortDescription} for service ${crDefinition.realServiceName} (No live traffic)\", 
        \"description\":\"${crDefinition.description} for service ${crDefinition.realServiceName}\\nThis change is a test deployment on UKS that will NOT enable live traffic.\", 
        \"implementation_plan\":\"${crDefinition.implementationPlan}\", 
        \"u_activity\":\"${crDefinition.activityType}\", 
        \"u_deployment_framework\":\"${crDefinition.deploymentFramework}\", 
        \"u_validation_plan\":\"${crDefinition.jobLink}\", 
        \"u_test_evidence\":\"${crDefinition.testEvidence}\", 
        \"backout_plan\":\"${crDefinition.backoutPlan}\", 
        \"justification\": \"${nonLiveTrafficMsg}\", 
        \"app_version\": \"${crDefinition.appVersion}\",
        \"start_date\":\"${startDate}\", 
        \"end_date\":\"${endDate}\"}
        """

    String crStringEmergency = """{\"requested_by\":\"${crDefinition.requestedBy}\", 
        \"u_data_center\":\"${crDefinition.dataCenter}\", 
        \"u_environment\":\"${crDefinition.environmentSysId}\", 
        \"u_platform\":\"${crDefinition.platform}\", 
        \"category\":\"${crDefinition.category}\", 
        \"cmdb_ci\":\"${crDefinition.serviceNameSysId}\", 
        \"short_description\": \"${crDefinition.shortDescription}\", 
        \"description\":\"${crDefinition.description}\", 
        \"implementation_plan\":\"${crDefinition.implementationPlan}\", 
        \"u_activity\":\"${crDefinition.activityType}\", 
        \"u_deployment_framework\":\"${crDefinition.deploymentFramework}\", 
        \"u_validation_plan\":\"${crDefinition.jobLink}\", 
        \"u_test_evidence\":\"${crDefinition.testEvidence}\", 
        \"backout_plan\":\"${crDefinition.backoutPlan}\",
        \"u_reason\": \"${crDefinition.emergencyReason}\",
        \"reason_description\": \"${crDefinition.reasonDescription}\",
        \"justification\": \"${crDefinition.overrideReason}\", 
        \"app_version\": \"${crDefinition.appVersion}\",
        \"start_date\":\"${startDate}\", 
        \"end_date\":\"${endDate}\"}
        """
        if (crType == 'normal') {
            crString = crStringNormal
        } else if (crType == 'standard') {
            crString = crStringStandard     
        } else {
            crString = crStringEmergency 
        }
        echo "PostCrData:\n${crString}"
        return crString
    }

    def createServiceNowCr(def crDefinition) {
        def postUrl = ''
        try{
            if (crDefinition.serviceName == 'UKS-psn') {
                crType = 'standard'
                env.CR_TYPE = crType
                if (SERVICE_NOW_SERVER == 'live') {
                    crDefinition.serviceNameSysId = 'c64e655d8720199001d964283cbb35cf'
                } else if (SERVICE_NOW_SERVER == 'stage') {    
                    crDefinition.serviceNameSysId = '64cbe73b1be05110bea584ccdd4bcbb4'
                }
                postUrl = "api/sie/psn_change_management/${crType}/${crDefinition.serviceNameSysId}"
            } else {
                postUrl = "api/sie/psn_change_management/${crType}"
                crDefinition.serviceNameSysId = getAppSysId (crDefinition.serviceName)
            }
            String crString = getPostCrData(crDefinition)
            def response = postData("${postUrl}", crString)
            if(response.responseCode && response.responseCode != 200) {
                throw new GroovyException("ServiceNow Post CR for service: \"${crDefinition.serviceName}\" status code: ${response.responseCode}")  
            } else if (response.result?.sys_id?.value != null) {
                String crSysId = response.result.sys_id.value.toString()
                String crName = response.result.number.value.toString()
                echo "-----CR sys_id: ${crSysId}"
                if (crType != 'standard') patchCRState(crSysId, "authorize")
                return [crSysId: crSysId, crName: crName]
            } else {
                throw new GroovyException("No Sys_ID returned for CR")
            }
        } catch (Exception err) {
            echo "createServiceNowCr failed: ${err.getMessage()}"
            def errorMsg = "CreateServiceNowCr failed. \nError: ${err.getMessage()} \nJob Link: " + env.BUILD_URL
            new SlackNotifications().sendSimpleSlackNotification("servicenowerror", errorMsg, 'FAILED')
            p1npNotificationOnError("Creating ServiceNow CR failed: ${err.getMessage()}")
            currentBuild.result = "FAILURE"
            throw err
        }
    }
    
    void usePreCreatedServiceNowCR() {
        stage("Use Preexisting ServiceNow CR") {
            def containerName = deployConfiguration.clusterId
            deployConfiguration.crSysIdMulti = []
            jenkinsUtils.jenkinsNode(infrastructure: deployConfiguration.infrastructure, templateType: 'helm', clusterList: [containerName]) {
                container(containerName) {
                    crSysId = validatePreApprovedCR()
                    deployConfiguration.serviceNowConfig[0].crName = env.SERVICE_TICKET
                    deployConfiguration.serviceNowConfig[0].crSysId = crSysId
                    deployConfiguration.crSysIdMulti.add(crSysId)
                    cRApprovalStage()
                }
            }
        }
    }

    def validatePreApprovedCR() {
        //hardcoded:    platform=psn, approval=approved, active, state=implement, type=normal (traffic enabled)
        //pass:         env, chartVersion, appVersion, serviceTicket, CIname
        //return:       Ticket number, start and end time, description
        //validation:   ciName, Before "end time", app_version, chartVersion, 1 returned

        //get corresponding envId for given env from passed ciName
        String envSysId = environmentSysId(deployConfiguration.psenv)
        //get configurationItem id
        String ciName = (deployConfiguration.serviceNowConfig[0].serviceNowCIName).replaceAll('-psn','')
        //tempVV
        echo "INPUTS: \nenvironmentSysId: ${envSysId} \nCI name: ${ciName}"
        echo "Chart Version: ${env.CHART_VERSION} \nApp Version:${env.APP_VERSION} \nTicket: ${env.SERVICE_TICKET}"
        def url = "/api/now/table/x_sie_psn_change_request?"
            url += "sysparm_fields=number,start_date,end_date,short_description,sys_id,type,app_version,u_environment\\&" //return
            url += "sysparm_query=u_platform=psn"
            url += "^approval=approved"
            url += "^active=true"
            url += "^type=normal"
            url += "^state=-1"                                  //^^ hardcoded (-1)
            url += "^u_environment=${envSysId}"                 //envId for given PSENV 
            url += "^app_version=${env.APP_VERSION}"            //app Version (1.1.1-SNAPSHOT-20220322T2047)
            url += "^number=${env.SERVICE_TICKET}"              //serviceTicket (PSNCHG1234567)
            url += "^short_descriptionLIKE${ciName}"            //short_descrition contains CI Name (poki-simple)
            url += "^short_descriptionLIKE${env.CHART_VERSION}" //short_descrition contains Chart Version (1.0.0-20220129T1409)

        def response = getData(url)

        if(response.result?.size() != 1) 
        {
            def errMessage = "The CR provided:${env.SERVICE_TICKET} was not valid for this rollout." + 
                "\nPlease ensure the CR entered meets the following criteria:" +
                "\nEnvironment: ${deployConfiguration.psenv}" +
                "\nApp_version: ${env.APP_VERSION}" +
                "\nshort_description contains: ${ciName}" +
                "\nshort_description contains: ${env.CHART_VERSION}" +
                "\nCR State: Implement" +
                "\nCR Approved: True" +
                "\nCR Type: Normal"
            error errMessage
        }
        //validate: End Date
        if (response.result[0].u_environment.contains(',')) {
            deployConfiguration.orchMultiEnvCR = true
            def resolvedEnv = []
            def multiEnvs = response.result[0].u_environment.split(',')
            for (int i = 0; i < multiEnvs.size(); i++) {
                echo "Adding Env: ${sysIdToEnvironment(multiEnvs[i])} to list..."
                resolvedEnv.add(sysIdToEnvironment(multiEnvs[i]))
            }
            deployConfiguration.multiEnvs = resolvedEnv
        }
        crType = response.result[0].type
        env.CR_TYPE = crType
        def crEndDate = response.result[0].end_date
        def crSysID = response.result[0].sys_id
        boolean deployCanStart = snowEndTimeGate(crEndDate)
        if (!deployCanStart) error "ServiceNow CR Planned End Date: ${timePTFormat(crEndDate)} is out of scope for current start time."
        return crSysID
    }
    
    
    def validatePreApprovedMultiCR(def envList) {
        //hardcoded:    platform=psn, approval=approved, active, state=implement, type=normal (traffic enabled)
        //pass:         env, chartVersion, appVersion, serviceTicket, CIname
        //return:       Ticket number, start and end time, description
        //validation:   ciName, Before "end time", app_version, chartVersion, 1 returned
  
        //get configurationItem id
        String ciName = (deployConfiguration.serviceNowConfig[0].serviceNowCIName).replaceAll('-psn','')
        //tempVV
        echo "INPUTS: \nenvironmentSysId: ${envList} \nCI name: ${ciName}"
        echo "Chart Version: ${env.CHART_VERSION} \nApp Version:${env.APP_VERSION} \nTicket: ${env.SERVICE_TICKET}"
        def url = "/api/now/table/x_sie_psn_change_request?"
            url += "sysparm_fields=number,start_date,end_date,short_description,sys_id,type,app_version,u_environment\\&" //return
            url += "sysparm_query=u_platform=psn"
            url += "^approval=approved"
            url += "^active=true"
            url += "^type=normal"
            url += "^state=-1"                                  //^^ hardcoded (-1)
            url += "^app_version=${env.APP_VERSION}"            //app Version (1.1.1-SNAPSHOT-20220322T2047)
            url += "^number=${env.SERVICE_TICKET}"              //serviceTicket (PSNCHG1234567)
            url += "^short_descriptionLIKE${ciName}"            //short_descrition contains CI Name (poki-simple)
            url += "^short_descriptionLIKE${env.CHART_VERSION}" //short_descrition contains Chart Version (1.0.0-20220129T1409)

        def multiEnvs = envList.split(',')
        for (int i = 0; i < multiEnvs.size(); i++) {
            url += "^u_environmentLIKE"+multiEnvs[i]                  //envId for given PSENV 
        }

        def response = getData(url)
        echo "response: ${response}"
        if(response.result?.size() != 1) 
        {
            def resolvedEnv = []
            for (int i = 0; i < multiEnvs.size(); i++) {
                resolvedEnv.add(sysIdToEnvironment(multiEnvs[i]))
            }
            def errMessage = "The CR provided:${env.SERVICE_TICKET} was not valid for this rollout." + 
                "\nPlease ensure the CR entered meets the following criteria:" +
                "\nEnvironment(s): ${resolvedEnv.join(', ').toUpperCase()}" +
                "\nApp_version: ${env.APP_VERSION}" +
                "\nshort_description contains: ${ciName}" +
                "\nshort_description contains: ${env.CHART_VERSION}" +
                "\nCR State: Implement" +
                "\nCR Approved: True" +
                "\nCR Type: Normal"
            error errMessage
        }
        //validate: End Date
        crType = response.result[0].type
        env.CR_TYPE = crType
        def crEndDate = response.result[0].end_date
        def crSysID = response.result[0].sys_id
        boolean deployCanStart = snowEndTimeGate(crEndDate)
        if (!deployCanStart) error "ServiceNow CR Planned End Date: ${timePTFormat(crEndDate)} is out of scope for current start time."
        return crSysID
    }

    
    void createAllServiceNowCRs() {
        def envSysId = environmentSysId(deployConfiguration.psenv)
        if (envSysId != "") {
            stage("Create ServiceNow CR") {
                def containerName = deployConfiguration.clusterId
                jenkinsUtils.jenkinsNode(infrastructure: deployConfiguration.infrastructure, templateType: 'helm', clusterList: [containerName]) {
                    container(containerName) {
                        deployConfiguration.crSysIdMulti = []
                        def activityType = '1a939e731bde4110efb697d58d4bcb67'
                        def useEnv = ''
                        def prevEnv = ''
                        if (deployConfiguration.containsKey('raaReturn')){
                            if (deployConfiguration?.overrideReason == null) deployConfiguration.overrideReason = ''
                            if(!jenkinsUtils.isTestRepo()) {
                                if (deployConfiguration.raaReturn.status == "fail") deployConfiguration.overrideReason += "Automated Product Security Approval failed."
                                deployConfiguration.overrideReason += "\\nRAA Status: ${deployConfiguration.raaReturn.status}\\nRAA Message: ${deployConfiguration.raaReturn.message.join(' ').replaceAll('"','')}\\nRAA Approval Type: ${deployConfiguration.raaReturn.releaseApprovalType}"
                                if (deployConfiguration.raaReturn.status == "fail") deployConfiguration.overrideReason += "\\nRAA failure approved by: ${deployConfiguration.raaReturn.approvedBy}"
                            }
                        }
                        if (env.NOC_GATE_CR_MSG) {
                            deployConfiguration?.overrideReason = deployConfiguration?.overrideReason == null ? env.NOC_GATE_CR_MSG : deployConfiguration?.overrideReason + "\\n" + env.NOC_GATE_CR_MSG
                        }
                        (useEnv, prevEnv) = getTestEnvs()
                        def testEvidence = getTestEvidenceInfo(deployConfiguration.psenv, prevEnv, deployConfiguration.infrastructure)
                        //use image.tag (RAA_VERSION) for Roadster appVersion, otherwise use APP_VERSION
                        def appVersion = deployConfiguration.infrastructure == "roadster-cloud" ? deployConfiguration.raaVersion : env.APP_VERSION
                        for (int i = 0; i < deployConfiguration.serviceNowConfig.size(); i++) {
                            def serviceNowCIName = deployConfiguration.serviceNowConfig[i].serviceNowCIName
                            def jobLink = "${env.BUILD_URL}input/ServiceNowWaitGateInput-${serviceNowCIName}/proceedEmpty"
                            def crDefinition = [  
                                realServiceName     : deployConfiguration.appName,
                                serviceName         : serviceNowCIName,
                                releaseName         : deployConfiguration.newReleaseName,
                                environmentSysId    : envSysId,
                                chartVersion        : env.CHART_VERSION,
                                appVersion          : appVersion,
                                psenv               : deployConfiguration.psenv,
                                jobLink             : jobLink,
                                platform            : "psn",
                                category            : "application_psn",
                                requestedBy         : getUserSysId(),
                                activityType        : activityType,
                                deploymentFramework : "0752a6e51b874414d1049608bd4bcb58",
                                dataCenter          : "c357d767dbffef006e52d1c2ca96191f",
                                testEvidence        : testEvidence,
                                overrideReason      : deployConfiguration.overrideReason ?: "${env.BUILD_URL}",
                                implementationPlan  : getImplementationPlan(serviceNowCIName.replaceAll('-psn',''), useEnv),
                                backoutPlan         : getBackoutPlan(serviceNowCIName.replaceAll('-psn',''), useEnv),
                                shortDescription    : "${serviceNowCIName.replaceAll('-psn','')} release of ${env.CHART_VERSION} to ${deployConfiguration.psenv}",
                                description         : "This is an auto-created CR for ${serviceNowCIName.replaceAll('-psn','')} release version ${env.CHART_VERSION} to ${deployConfiguration.psenv}"
                            ] << getOverrideCrDefinition(serviceNowCIName)
                            
                            def item = createServiceNowCr(crDefinition)
                            deployConfiguration.serviceNowConfig[i].crName = item.crName
                            deployConfiguration.serviceNowConfig[i].crSysId = item.crSysId
                            deployConfiguration.crSysIdMulti.add(item.crSysId)
                        }
                        echo "serviceNowConfig: ${deployConfiguration.serviceNowConfig}"
                        echo "crSysIdMulti: ${deployConfiguration.crSysIdMulti}"
                        // Wait for backend to Auto-Approve Ticket if CI has auto-approve set. Will change state from Authorize to Implement. 
                        sleep 10
                        if (crType == "normal") {
                            cRApprovalStage()
                        } else {
                            for(int i=0; i < deployConfiguration.serviceNowConfig.size(); i++){
                                def item = deployConfiguration.serviceNowConfig[i]
                                echo "serviceNow Info: ${item}"    
                                setWorkStart(item.crSysId, item.crName, item.serviceNowCIName)
                            }
                        }
                    }
                }
            }
        }
    }
    
    void createAllEmergencyServiceNowCRs() {
        crType = 'emergency'
        def envSysId = environmentSysId(deployConfiguration.psenv)
        if (envSysId != "") {
            stage("Create Emergency ServiceNow CR") {
                def containerName = deployConfiguration.clusterId
                jenkinsUtils.jenkinsNode(infrastructure: deployConfiguration.infrastructure, templateType: 'helm', clusterList: [containerName]) {
                    container(containerName) {
                        deployConfiguration.crSysIdMulti = []
                        def activityType = '1a939e731bde4110efb697d58d4bcb67'
                        def useEnv = ''
                        def prevEnv = ''
                        //dont catch errors from this input, so the job ends if the user clicks "abort"
                        def emergencyReasonInput = input id: 'EmergencyReason', ok: "Submit", message: "Creating Emergency CR Ticket", parameters: [text(description: 'Reason for Emergency CR ticket creation', name: 'emergencyReason')]
                        if (emergencyReasonInput == '' || emergencyReasonInput == null) {
                            //dont catch this error so the job ends
                            error ('Emergency Reason left blank...aborting...')
                        }
                        echo "emergencyReasonInput: ${emergencyReasonInput}"
                        
                        if (deployConfiguration.containsKey('raaReturn')){
                            if (deployConfiguration.overrideReason == null) deployConfiguration.overrideReason = ''
                            if(!jenkinsUtils.isTestRepo()) {
                                if (deployConfiguration.raaReturn.status == "fail") deployConfiguration.overrideReason += "Automated Product Security Approval failed."
                                deployConfiguration.overrideReason += "\\nRAA Status: ${deployConfiguration.raaReturn.status}\\nRAA Message: ${deployConfiguration.raaReturn.message.join(' ').replaceAll('"','')}\\nRAA Approval Type: ${deployConfiguration.raaReturn.releaseApprovalType}"
                                if (deployConfiguration.raaReturn.status == "fail") deployConfiguration.overrideReason += "\\nRAA failure approved by: ${deployConfiguration.raaReturn.approvedBy}"
                            }
                        }
                        if (env.NOC_GATE_CR_MSG) {
                            deployConfiguration?.overrideReason = deployConfiguration?.overrideReason == null ? env.NOC_GATE_CR_MSG : deployConfiguration?.overrideReason + "\\n" + env.NOC_GATE_CR_MSG
                        }
                        (useEnv, prevEnv) = getTestEnvs()
                        def testEvidence = getTestEvidenceInfo(deployConfiguration.psenv, prevEnv, deployConfiguration.infrastructure)
                        //use image.tag (deployConfiguration.raaVersion) for Roadster appVersion, otherwise use APP_VERSION
                        def appVersion = deployConfiguration.infrastructure == "roadster-cloud" ? deployConfiguration.raaVersion : env.APP_VERSION
                        for (int i = 0; i < deployConfiguration.serviceNowConfig.size(); i++) {
                            def serviceNowCIName = deployConfiguration.serviceNowConfig[i].serviceNowCIName
                            def jobLink = "${env.BUILD_URL}input/ServiceNowWaitGateInput-${serviceNowCIName}/proceedEmpty"
                            def crDefinition = [              
                                serviceName         : serviceNowCIName,
                                releaseName         : deployConfiguration.newReleaseName,
                                environmentSysId    : envSysId,
                                chartVersion        : env.CHART_VERSION,
                                appVersion          : appVersion,
                                psenv               : deployConfiguration.psenv,
                                jobLink             : jobLink,
                                platform            : "psn",
                                category            : "application_psn",
                                requestedBy         : getUserSysId(),
                                activityType        : activityType,
                                deploymentFramework : "0752a6e51b874414d1049608bd4bcb58",
                                dataCenter          : "c357d767dbffef006e52d1c2ca96191f",
                                testEvidence        : testEvidence,
                                emergencyReason     : "service_restoration_reactive",
                                reasonDescription   : emergencyReasonInput.replaceAll("\n", '\\\\n'),
                                overrideReason      : deployConfiguration.overrideReason ?: "${env.BUILD_URL}",
                                implementationPlan  : getImplementationPlan(serviceNowCIName.replaceAll('-psn',''), useEnv),
                                backoutPlan         : getBackoutPlan(serviceNowCIName.replaceAll('-psn',''), useEnv),
                                shortDescription    : "${serviceNowCIName.replaceAll('-psn','')} release of ${env.CHART_VERSION} to ${deployConfiguration.psenv}",
                                description         : "This is an auto-created CR for ${serviceNowCIName.replaceAll('-psn','')} release version ${env.CHART_VERSION} to ${deployConfiguration.psenv}"
                            ] << getOverrideCrDefinition(serviceNowCIName)
                            def item
                            String crUrl = ""
                            try {
                                item = createServiceNowCr(crDefinition)
                                deployConfiguration.serviceNowConfig[i].crName = item.crName
                                deployConfiguration.serviceNowConfig[i].crSysId = item.crSysId
                                deployConfiguration.crSysIdMulti.add(item.crSysId)
                                crUrl = getCrUrl(item.crSysId)
                                echo "ServiceNow Ticket Created: ${item.crName} - ${crUrl}"
                            }
                            catch (Exception err) {
                                //catch "Create CR" errors specifically from Emergency Deployments so the job continues
                                String channelName = jenkinsUtils.getEngineWorkflowSlackChannel()
                                def errorMsg = "ServiceNow call createServiceNowCr for Emergency Deployment failed:\nError: ${err.getMessage()} \nJob Link: ${env.BUILD_URL}"
                                new SecurityUtils().sendSlackMessageOnError(channelName, errorMsg, "FAILED", "", "")
                            }
                            
                            //notify #cab and #sie-maintenance that an Emergency CR has been submitted
                            if(this.SERVICE_NOW_SERVER == 'live') {
                                String emergencyMessage = "An Emergency CR (*${item.crName}*) has been submitted for CI:" + 
                                    "```\n" + serviceNowCIName + "\n```\n" + 
                                    "CR Link: " + crUrl + "\n" +
                                    "Jenkins job: ${env.BUILD_URL}\n" +
                                    "Emergency Reason Description:\n"+
                                    "```\n" + emergencyReasonInput + "\n```"
                                new SlackNotifications().sendSimpleSlackNotification("cab", emergencyMessage, "SUCCESS")
                                new SlackNotifications().sendSimpleSlackNotification("sie-maintenance", emergencyMessage, "SUCCESS")
                            }
                        }
                        echo "serviceNowConfig: ${deployConfiguration.serviceNowConfig}"
                        echo "crSysIdMulti: ${deployConfiguration.crSysIdMulti}"
                        // Wait for backend to Auto-Approve Ticket if CI has auto-approve set. Will change state from Authorize to Implement. 
                        sleep 10
                        cRApprovalStage()
                    }
                }
            }
        }
    }
    
    def checkNocGate(def deployType = 'standard') {
        boolean nocGateEnabled = false
        def nocGateMsg = ''
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'github-token-username', usernameVariable: 'githubUsername', passwordVariable: 'githubAccessToken']]) {
            echo "Fetching flag for Noc Gate from Github..."
            def acceptType = 'application/vnd.github.v3.raw'
            def githubUrl = "https://github.sie.sony.com/api/v3/repos/SIE/engine-cd-feature-flags/contents/cd/flags/nocgate.yaml?ref=master"
            jenkinsUtils.shWrapper("curl -H 'Authorization: token ${githubAccessToken}' -H 'Accept: ${acceptType}' ${githubUrl} -o nocgate.yaml", true, true).trim()
            def nocGate = readYaml file: 'nocgate.yaml'
            echo "Fetched nocgate.yaml: ${nocGate}"
            switch (deployConfiguration.infrastructure) {
                case 'navigator-cloud':
                    nocGateEnabled = nocGate.navGate.enabled
                    nocGateApprover = nocGate.navGate.aloyApproverGroup
                    nocGateMsg = nocGate.navGate.nocMsg
                break   
                case 'laco-cloud':
                    nocGateEnabled = nocGate.lcoGate.enabled
                    nocGateApprover = nocGate.lcoGate.aloyApproverGroup
                    nocGateMsg = nocGate.lcoGate.nocMsg
                break   
                case 'kamaji-cloud':
                    nocGateEnabled = nocGate.kmjGate.enabled
                    nocGateApprover =  nocGate.kmjGate.aloyApproverGroup
                    nocGateMsg = nocGate.kmjGate.nocMsg
                break
                case 'roadster-cloud':
                    nocGateEnabled = nocGate.rdsGate.enabled
                    nocGateApprover = nocGate.rdsGate.aloyApproverGroup
                    nocGateMsg = nocGate.rdsGate.nocMsg
                break   
           }
        }

        if (nocGateEnabled) {
            def nocChannel = "sie-maintenance"
            stage ("Deployment Gate"){
                timeout(120) {
                    //if its an Emergency Deployment, anyone can override the NOC gate
                    if (deployUtils.isEmergencyDeployment()) {
                        def emergencyOverrideMsg = "${nocGateMsg}\n\n[Override]: Continue with the rollout request\n"+
                            "(Use only for urgent emergencies)\n\n[Abort]: Cancel the rollout request"
                        input id: 'NOCOverride', ok: "Override", message: emergencyOverrideMsg
                    }
                    //otherwise, prompt the user if they would like to request an override from the nocGateApprover
                    else {
                        String stdOverrideMsg = "${nocGateMsg}\n\n[Request NOC Override] Request an override from the NOC team to bypass this gate after providing a justification.\n\n" +
                            "[Abort]: Cancel the rollout request"
                        input id: 'NOCOverride', ok: "Request NOC-Gate Bypass", message: stdOverrideMsg

                        //user clicks "Request Override"
                        //the developer provides the justification for requesting the NOC gate override
                        def reasonProvided = input ok: "Submit request for bypass", 
                            message: "Provide some justification for your request to bypass the current NOC Deployment Gate:",
                            parameters:[
                                text(name: 'justification', trim: true), 
                            ]

                        //Send a slack message to regarding the request for override
                        String justification = reasonProvided ?: "(No justifcation provided by the deployer.)"
                        String slackId = ""
                        String jenkinsUser = "${currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause').userName}"
                        if (!jenkinsUser?.equals("")) slackId = jenkinsUser.replaceAll("\\[", "").replaceAll("\\]", "")
                        new SlackNotifications().sendSimpleSlackNotification(nocChannel, 
                            "`${slackId}` is currently requesting an override for the \"Deployment Gate\" stage:\n" +
                            "• *Repo:* ${env.REPO_NAME}\n" +
                            "• *Justification provided by the deployer:*\n" + 
                            "```\n" + justification + "\n```\n" +
                            "If you wish to allow this deployment, click the \"Override\" button in the Jenkins job link:\n"+
                            "${env.RUN_DISPLAY_URL}",
                            "FAILED")

                        //Display a gate until NOC overrides, user cancels, or timeout is reached
                        String waitingForOverride = "The request for the override has been sent to the Slack channel:\n#sie-maintenance.\n\n" +
                            "If/when the request is approved, the deployment will progress past this gate.\n\n" +
                            "[Override] (Authorized Users) Click \"Override\" to bypass this gate.\n" +
                            "[Abort]: Cancel the rollout"
                        input id: 'NOCOverride', ok: "Override", message: waitingForOverride, submitter: nocGateApprover

                        //notify NOC that the gate was bypassed
                        new SlackNotifications().sendSimpleSlackNotification(nocChannel, 
                            ":green_ball: The following rollout has proceeded past the \"Deployment Gate\" stage:\n" +
                            "• *Repo:* ${env.REPO_NAME}\n" +
                            "• *Jenkins Job Link:* ${env.BUILD_URL}",
                            "SUCCESS")
                        env.NOC_GATE_CR_MSG = "A Deployment Gate was displayed:\\n\\\"${nocGateMsg}\\\"\\nOverridden By: ${deployUtils.getLatestApprover(true)}"
                    }
                }
            }
        }
    }
        
    void createCR(def deployConfiguration) {
        def envSysId = environmentSysId(deployConfiguration.psenv)
        if (deployConfiguration.psenv.contains(',')) {
            def createEnv = deployConfiguration.psenv.split(', ')
            echo "createEnv: ${createEnv}"
            envSysId = ''
            for (int i = 0; i < createEnv.size(); i++) {
                envSysId += environmentSysId(createEnv[i])+","
            }
        }
        echo "envSysId: ${envSysId}"     
        if (envSysId != "") {
            stage("Create ServiceNow CR") {
                def containerName = deployConfiguration.clusterId
                jenkinsUtils.jenkinsNode(infrastructure: deployConfiguration.infrastructure, templateType: 'helm', clusterList: [containerName]) {
                    container(containerName) {
                        def activityType = '1a939e731bde4110efb697d58d4bcb67'
                        def useEnv = ''
                        def prevEnv = ''
                        if (deployConfiguration.containsKey('raaReturn')){
                            if (deployConfiguration?.overrideReason == null) deployConfiguration.overrideReason = ''
                            if(!jenkinsUtils.isTestRepo()) {
                                if (deployConfiguration.raaReturn.status == "fail") deployConfiguration.overrideReason += "Automated Product Security Approval failed."
                                deployConfiguration.overrideReason += "\\nRAA Status: ${deployConfiguration.raaReturn.status}\\nRAA Message: ${deployConfiguration.raaReturn.message.join(' ').replaceAll('"','')}\\nRAA Approval Type: ${deployConfiguration.raaReturn.releaseApprovalType}"
                                if (deployConfiguration.raaReturn.status == "fail") deployConfiguration.overrideReason += "\\nRAA failure approved by: ${deployConfiguration.raaReturn.approvedBy}"
                            }
                        }
                        def appVersion = deployConfiguration.infrastructure == "roadster-cloud" ? deployConfiguration.raaVersion : env.APP_VERSION
                        def serviceNowCIName = jenkinsUtils.removeWhiteSpaces(env.CI_NAME)
                        def testEvidance = env.TEST_EVIDENCE ? sanitizeString(env.TEST_EVIDENCE) : ''
                        def validation = env.VALIDATION_PLAN ? sanitizeString(env.VALIDATION_PLAN) : ''
                        def justification = env.JUSTIFICATION ? sanitizeString(env.JUSTIFICATION) : ''
                        def implementationPlan = env.IMPLEMENTATION_PLAN ? sanitizeString(env.IMPLEMENTATION_PLAN) : getImplementationPlan(serviceNowCIName.replaceAll('-psn',''), useEnv)
                        def backoutPlan = env.BACKOUT_PLAN ? sanitizeString(env.BACKOUT_PLAN) : getBackoutPlan(serviceNowCIName.replaceAll('-psn',''), useEnv)
                        def jobLink = "${env.BUILD_URL}input/ServiceNowWaitGateInput-${serviceNowCIName}/proceedEmpty"
                        def crDefinition = [  
                            realServiceName     : deployConfiguration.appName,
                            serviceName         : serviceNowCIName,
                            releaseName         : deployConfiguration.newReleaseName,
                            environmentSysId    : envSysId,
                            chartVersion        : env.CHART_VERSION,
                            appVersion          : appVersion,
                            psenv               : deployConfiguration.psenv,
                            jobLink             : validation,
                            platform            : "psn",
                            category            : "application_psn",
                            requestedBy         : getUserSysId(),
                            activityType        : activityType,
                            deploymentFramework : "0752a6e51b874414d1049608bd4bcb58",
                            dataCenter          : "c357d767dbffef006e52d1c2ca96191f",
                            testEvidence        : testEvidance,
                            overrideReason      : justification,
                            implementationPlan  : implementationPlan,
                            backoutPlan         : backoutPlan,
                            shortDescription    : "${serviceNowCIName.replaceAll('-psn','')} release of ${env.CHART_VERSION} to ${deployConfiguration.psenv}",
                            description         : "This ticket was created with the CR Creation Tool for ${serviceNowCIName.replaceAll('-psn','')} release version ${env.CHART_VERSION} to ${deployConfiguration.psenv}"
                        ] << getOverrideCrDefinition(serviceNowCIName)   
                        def item = createServiceNowCr(crDefinition)
                        deployConfiguration.serviceNowConfig[0].crName = item.crName
                        deployConfiguration.serviceNowConfig[0].crSysId = item.crSysId
                        echo "serviceNowConfig: ${deployConfiguration.serviceNowConfig}"
                        String crUrl = getCrUrl(item.crSysId)
                        echo "ServiceNow Ticket Created: ${item.crName} - ${crUrl}"
                        currentBuild.description ="<a href=\"${crUrl}\">Ticket: ${item.crName}</a>"
                    }
                }
            }
        }
    }
    
    String getUserSysId() {
        def defaultRequestedBySysId = '9b3a9afddb630d9090c2f674b99619a1'
        def requestedBySysId = ''
        def buildUser = ''
        def userEmail = ''
        def response = ''
        def url = ''
        try {
            (buildUser, userEmail) = getUserNameFromJob(env.BUILD_URL)
            url = "api/now/table/sys_user?sysparm_query=email%3D${userEmail}\\&sysparm_fields=sys_id"
            response = getData(url)
            requestedBySysId = response.result[0].sys_id
            echo "** Retrieved ${requestedBySysId} for ${buildUser} using email ${userEmail}"
        } catch (Exception e) {
            echo "** WARNING: curl servicenow sys_user GET failed for ${buildUser} using email ${userEmail}: [${e}]. So, using ${defaultRequestedBySysId}"
            requestedBySysId = defaultRequestedBySysId        
        }
        if (SERVICE_NOW_SERVER != 'live') {
            requestedBySysId = defaultRequestedBySysId
        }
        return requestedBySysId
    }
    
    String getBackoutPlan(def serviceName, String useEnv) {
        return "1. Use Manifest Promote CD Jenkins for ${serviceName} job to deploy previous version using auto-rollback to helm revision.\\n2.Rollback to the older version\\n3. Verify health checks.\\n4. Run integration tests on ${useEnv}"
    }

    String getImplementationPlan(def serviceName, String useEnv) {
        def slackChannel = "${useEnv.toLowerCase()}-maintenance"
        return "1. Before starting, announce in Slack channel #${slackChannel} that activity is starting. Cite CR and build numbers for visibility.\\n2. EKS Rollout for service ${serviceName} using ${env.APP_VERSION}\\n3. Post in #${slackChannel} that the rollout is complete"
    }

    String getTestEvidenceInfo(def psenv, def prevEnv, def cloudInfo) {
        def buildUser = ''
        def userEmail = ''
        def crEvidence = ''
        def (testEnv, depEnv) = getTestEvidenceEnvs(psenv)
        String testEvidence = "${env.BUILD_URL}"
        if(testEnv != "") {
            try{
                def conf = testEvidenceConfig(psenv, testEnv, depEnv)
                if(conf.testEvidenceURL != "" && conf.testEvidenceURL != null) {
                    if (conf.testEvidenceDeployer !=  "" && conf.testEvidenceDeployer != null) {
                        buildUser = conf.testEvidenceDeployer  
                    } else {
                        (buildUser, userEmail) = getUserNameFromJob(env.BUILD_URL)
                    }
                    if (conf.crNameEvidence != "" && conf.crNameEvidence != null) {    
                        crEvidence = conf.crNameEvidence
                    } 
                    testEvidence = "${testEnv} Test Evidence: ${conf.testEvidenceURL}\\nTest Status: ${conf.testStatus}\\nTest Date: ${conf.testDate}\\nDeployer: ${buildUser}\\n${crEvidence}"
                }
            } catch (Exception err) {
                echo "getTestEvidenceInfo failed: ${err.getMessage()}"
            }
        } else if (cloudInfo != "navigator-cloud") {
            testEvidence = "This service does not deploy to D or Q, ${psenv.toUpperCase()} is the first rollout environment for ${cloudInfo}\\nPlease check: ${env.BUILD_URL}"
        }
        return testEvidence
    }
    
    def getLocalTestEvidence() {
        def conf = [testEvidenceURL: '', testStatus: '', crNameEvidence: '', testDate: '']
        def matcher = null
        def testEvidenceJson = null
        def crTicketNames = [] 
        for(int i=0; i < deployConfiguration.serviceNowConfig.size(); i++){
            crTicketNames.add(deployConfiguration.serviceNowConfig[i].crName)
        }
        matcher = manager.getLogMatcher(".*testEvidence:(.*)")
        if (matcher?.matches()) {
            testEvidenceJson = matcher.group(1)
            matcher = null
            if (testEvidenceJson.contains('{')) { 
                testEvidenceData = readJSON text: testEvidenceJson.toString().trim()
                echo "testEvidenceData: ${testEvidenceData}"
                conf.testDate = new Date((long)testEvidenceData.runDate).format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone('UTC'))
                conf.testEvidenceURL = testEvidenceData.evidenceURL
                conf.testStatus = testEvidenceData.status       
                conf.crNameEvidence = testEvidenceData.crName
            }
        } else {
            conf.testDate = new Date().format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone('UTC'))
            conf.testEvidenceURL = env.BUILD_URL
            conf.testStatus = deployConfiguration.buildStatus
            conf.crNameEvidence = crTicketNames.join(', ')
        }
        def (buildUser, userEmail) = getUserNameFromJob(env.BUILD_URL)
         
        switch (deployConfiguration.psenv) {
            case ~/.*d0.*/:
            case ~/.*d1.*/:
            case ~/.*q1.*/:
                useEnv = 'Q-line CR#'
                break   
            case ~/.*e1.*/:
            case ~/.*tools-nonprod.*/:
            case ~/.*tools-test.*/:
                useEnv = 'E-line CR#'
                break
            case ~/.*p1.*/:
            case ~/.*tools-preprod.*/:
            case ~/.*tools-prod.*/:
                useEnv = 'P-line CR#'
                break   
        }
        def localTestEvidence = "Deployed Successfully - Validation Evidence:\\n${deployConfiguration.psenv.toUpperCase()} Test Evidence: ${conf.testEvidenceURL}\\nTest Status: ${conf.testStatus}\\nTest Date: ${conf.testDate}\\nDeployer: ${buildUser}\\n${useEnv} created in same helm chart: ${conf.crNameEvidence}"
        if (deployConfiguration.orchMultiEnvCR) localTestEvidence = "\\nMulti-Environment CR Deployed Successfully - Validation Evidence:\\n${deployConfiguration.psenv.toUpperCase()} Test Evidence: ${conf.testEvidenceURL}\\nTest Status: ${conf.testStatus}\\nTest Date: ${conf.testDate}\\nDeployer: ${buildUser}\\n${useEnv} precreated: ${env.SERVICE_TICKET}"

        return localTestEvidence
    }

    def fetchPreviousDeploymentTestEvidence(def conf, def testEnv) {
        def checkEnv = determineLineCheck(conf.psenv)
        def serviceNowCIName = conf.serviceNowConfig[0].serviceNowCIName.replaceAll('-psn','')
        def url = "api/now/table/x_sie_psn_change_request?sysparm_fields=close_notes\\&sysparm_query=short_description=${serviceNowCIName}%20release%20of%20${env.CHART_VERSION}%20to%20${checkEnv}^app_version=${env.APP_VERSION}^approval=approved^state=3"
        def getResponse = getData(url)
        def testEvidenceURL = ''
        def testStatus = ''
        def crNameEvieance = ''
        def testDate = ''
        def deployer = ''    

       if (getResponse.result.close_notes) {
            def testEvidenceReturn = getResponse.result.close_notes[0].readLines()
            for (int i = 0; i < testEvidenceReturn.size(); i++) {
                testEvidenceURL = testEvidenceReturn[i] =~ /.*Test Evidence: (.*)/
                testStatus = testEvidenceReturn[i] =~ /.*Test Status: (.*)/
                crNameEvidence = testEvidenceReturn[i] =~ /.*created in same helm chart: (.*)/
                testDate = testEvidenceReturn[i] =~ /.*Test Date: (.*)/
                deployer = testEvidenceReturn[i] =~ /.*Deployer: (.*)/

                if (testEvidenceURL.matches()) {
                        echo "Found: ${testEvidenceURL.group(1)}"
                        conf.testEvidenceURL = testEvidenceURL.group(1)
                    testEvidenceURL = null    
                }
                if (testStatus.matches()) {
                    echo "Found: ${testStatus.group(1)}"
                    conf.testStatus = testStatus.group(1)
                    testStatus = null
                }
                if (crNameEvidence.matches()) {
                    echo "Found: ${crNameEvidence.group(1)}"
                    conf.crNameEvidence = "${testEnv} Rollout CR: ${crNameEvidence.group(1)}"
                    crNameEvidence = null
                }
                if (testDate.matches()) {
                        echo "Found: ${testDate.group(1)}"
                    conf.testDate = testDate.group(1)
                    testDate = null
                }        
                if (deployer.matches()) {
                        echo "Found: ${deployer.group(1)}"
                    conf.testEvidenceDeployer = deployer.group(1)
                    deployer = null
                }
            }
        }
        return conf
    }

    def testEvidenceConfig(String psenv, String testEnv, String depEnv) {
        def conf = [testEvidenceURL: '', testStatus: '', crNameEvidence: '', testDate: '']
        String testPsenv = getTestEnv(psenv)
        if(testPsenv == "") return conf
        Date currentDate = new Date()   
        def jobName = env.JOB_URL.replaceAll("${psenv}", "${testPsenv}")
        if (psenv.contains('e1')) {
            echo "TestJobURL: ${jobName}"
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "svc-psn-CoreJenkinsEngineWorkflow", usernameVariable: 'username', passwordVariable: 'password']]) {
                sh "curl -u ${username}:${password} ${jobName}api/json -o output.json"
                def json = readJSON file: 'output.json'
                echo " --- json: ${json}"
                if(json?.lastSuccessfulBuild?.url != null) {
                    sh "curl -u ${username}:${password} ${json.lastSuccessfulBuild.url}consoleText -o console.out"
                } else {
                    return conf
                }
            }
        
            def matcher = readFile('console.out') =~ /.*testEvidence:(.*)/
            def testEvidenceJson = matcher ? matcher[0][1] : "${testEnv} Test Evidence JSON was empty."
            matcher = null
            
            if (testEvidenceJson.contains('{')) {
                testEvidenceData = readJSON text: testEvidenceJson.toString().trim()
                conf.testDate = new Date((long)testEvidenceData.runDate).format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone('UTC'))
                if (testEvidenceData.chartVersion != env.CHART_VERSION) {
                    throw new GroovyException("${testEnv} Test Evidence Chart Version: ${testEvidenceData.chartVersion} does not match current version being deployed: ${env.CHART_VERSION}")
                } 
                deployedLength = (currentDate.getTime() - testEvidenceData.runDate)/1000
                if (deployedLength >= 2678400) {
                    throw new GroovyException("${testEnv} Test Evidence Test Date is too old, please run the ${testEnv} deploy again with the chart version: ${env.CHART_VERSION}")
                } else {
                    if (deployedLength < 86400) {
                        echo "${testEnv} Test was tested on ${conf.testDate.toString()}"
                    } else {
                        deployedLength = deployedLength/86400
                        echo "${testEnv} Test is ${deployedLength} days old.."
                    }
                }
                if (depEnv == 'P') {
                    conf.crNameEvidence = "${testEnv} Rollout CR: ${testEvidenceData.crName}"
                }      
                conf.testEvidenceURL = testEvidenceData.evidenceURL
                conf.testStatus = testEvidenceData.status
            }
            return conf
        } else if (psenv.contains('p1')) {
            return fetchPreviousDeploymentTestEvidence(deployConfiguration, testEnv)
        }
    }
    
    def sanitizeString(def dirtyString) {
        def cleanString = dirtyString.replaceAll('"','\\\\\"').replaceAll("\n", '\\\\n').replaceAll('\'','\\\\\'')
        echo "Cleaned String: ${cleanString}" 
        return cleanString
    }

    def serviceNowTicketInput(int inputIndex = 0) {
        try{
            timeout(60) {
                def msg = inputIndex == 0 ? "Please input the approved ServiceNow CR. \ni.e. PSNCHG0111759" : "The approved ServiceNow CR is required and can not be empty. \nPlease input!"
                def serviceNowTicket = input id: 'serviceNowTicket', ok: "Submit", message: msg, parameters: [string(description: '', name: 'SERVICE_TICKET')]
                serviceNowTicket = jenkinsUtils.removeWhiteSpaces(serviceNowTicket)
                if(serviceNowTicket == "") {
                    serviceNowTicketInput(inputIndex+1)
                } else {
                    env.SERVICE_TICKET = serviceNowTicket
                    echo "serviceNow CR: '${serviceNowTicket}'"
                }
            }
        } catch (Exception err) {
            echo "The rollout was cancelled in the approved serviceNow ticket input step."
            currentBuild.result = "ABORTED"
            throw new GroovyException("The rollout was cancelled in the approved serviceNow ticket input step.")
        }
    }
}

