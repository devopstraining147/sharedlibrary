
package com.sony.sie.cicd.cd.deployProcessors

import com.sony.sie.cicd.helpers.utilities.JenkinsSteps
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import com.sony.sie.cicd.helpers.utilities.PostDeploymentPhase
import com.sony.sie.cicd.helpers.utilities.YamlFileUtils
import com.sony.sie.cicd.cd.utilities.DeployUtils
import com.sony.sie.cicd.cd.utilities.HelmUtils
import org.codehaus.groovy.GroovyException
import com.sony.sie.cicd.helpers.notifications.*
import com.sony.sie.cicd.cd.deploy.*
import com.sony.sie.cicd.cd.utilities.Helm3KubeHelper
import java.security.MessageDigest

abstract class K8sBaseDeployProcessor extends JenkinsSteps {
    final JenkinsUtils jenkinsUtils = new JenkinsUtils()
    final DeployUtils deployUtils = new DeployUtils()
    final HelmUtils helmUtils = new HelmUtils()
    final YamlFileUtils yamlFileUtils = new YamlFileUtils()
    abstract getKubeConfig(Map conf)

    def getSlackMsg(String msgDeployStatus, def item) {
        return "\n" +
            "Job Name: `${env.JOB_NAME}`\n" +
            "${msgDeployStatus}\n" +
            "Chart Version: `${env.CHART_VERSION}`\n" +
            "Approver: `${item.latestApprover}`\n" +
            "Cluster: `${item.clusterId}`\n" +
            "Namespace: `${item.namespace}`\n" +
            "Jenkins job: ${env.BUILD_URL}\n" 
    }

    def getDebugSlackMsg(def item) {
        return "\n" +
            "Job Name: `${env.JOB_NAME}`\n" +
            "Rollout Status: FAILURE\n" +
            "Chart Version: `${env.CHART_VERSION}`\n" +
            "Action required: Debug or Abort\n" +
            "Expiration: One hour \n" +
            "App Version: `${item.appVersion}` \n" +
            "Cluster: `${item.clusterId}`\n" +
            "Jenkins job: `${env.BUILD_URL}` \n" +
            "Debugging: https://confluence.sie.sony.com/x/ShmVJw"
    }

    void checkoutSource(){
        dir("${env.REPO_WORKDIR}") {
            cleanWs()
            unstash env.STASH_HELM
        }
    }

    def holdForDebugOnFailed(){
        try{
            ansi_echo "Hold for debugging", 31
            String msg = "The application failed to start successfully and will be held for an hour so the logs can be reviewed.\n\n" +
                    "Review this page for how to debug the application: https://confluence.sie.sony.com/x/ShmVJw \n\n" +
                    "If its still unclear why the rollout failed, contact the slack support channel: \n" +
                    "#engine-support \n\n" +
                    "Please click either 'Done' or 'Abort' once you are finished reviewing the failed rollout's logs.\nThis will delete the unsuccessful rollout and restore your service to the original state."
            timeout(60) {
                input message: msg, ok: "Done"
            }
        } catch (Exception err) {
        }
    }

    def holdForDarktest(def conf, def helmDeployK8s){
        stage("Hold for Dart Test") {
            try{
                ansi_echo "Hold for the dart test"
                String msg = "The dark test rollout will be held upto ${conf.testTimeoutInHours} hours.\n\n" +
                        "Please click 'Done' or 'Abort' after you complete the testing.\nThis will delete the dark test rollout."
                timeout(conf.testTimeoutInHours*60) {
                    input message: msg, ok: "Done"
                }
            } catch (Exception err) {
            }
        }
        //delete dark test 
        stage("Finish") {
            jenkinsUtils.jenkinsNode(templateType: 'helm', infrastructure: conf.infrastructure, clusterList: [conf.clusterId]) {
                k8sAccessConfig conf
                if(helmDeployK8s.ifReleaseNameExist(conf.newReleaseName, "--deployed")){
                    helmDeployK8s.deleteDeployment(conf.newReleaseName)
                }
            }
        }
    }

    def emergencyDeploymentConfirmationOnPostTestFailed(){
        stage("Keep Rollout Confirmation") {
            try{
                ansi_echo "Emergency deployment Confirmation on post deployment test failed"
                String msg = "The post deployment test failed. Do you want to keep this rollout?\n\n" +
                        "Please click 'Proceed' to keep this rollout or 'Abort' to concel this rollout and rollback."
                timeout(60) {
                    input message: msg
                }
            } catch (Exception err) {
                throw new GroovyException("The user chose to abort this rollout") 
            }
        }
    }

    // def checkIfDarktestRolloutExist(def conf, def helmDeployK8s) {
    //     def darktestReleaseName = conf.newReleaseName + "-darktest"
    //     if(helmDeployK8s.ifReleaseNameExist(darktestReleaseName, "--deployed")){
    //         stage("Delete Dark Test Rollout") {
    //             try{
    //                 String msg = "The dark test rollout is existed.\n\n" +
    //                         "Please click 'Delete' to delete the dark test rollout or click 'Abort' to cancel this rollout."
    //                 timeout(60) {
    //                     input message: msg, ok: "Delete"
    //                 }
    //             } catch (Exception err) {
    //                 throw new GroovyException("The user chose to abort this rollout to keep dark test rollout")
    //             }
    //             if(helmDeployK8s.ifReleaseNameExist(darktestReleaseName, "--deployed")){
    //                 helmDeployK8s.deleteDeployment(darktestReleaseName)
    //             }
    //         }
    //     }
    // }

    boolean isCatalystTestRepo() {
        return jenkinsUtils.isTestRepo()
    }

    def checkIfBadReleaseExists(def helmDeployK8s) {
        if(helmDeployK8s.ifReleaseNameExist(env.RELEASE_NAME, "--all")){
            if(!helmDeployK8s.ifReleaseNameExist(env.RELEASE_NAME, "--deployed")){
                stage("Uninstall Existing Failed Helm Release") {
                    try{
                        String msg = "The existing rollout is in failed state\n\n" +
                            "[Uninstall] Select this option to remove the existing rollout and proceed with the new rollout.\n\n" +
                            "Note: This could cause a service outage if your service is already receiving traffic.\n\n" +
                            "[Abort] Click this button to cancel the new rollout and keep the existing rollout. Contact #engine-support for additional information.\n\n"
                        timeout(600) {
                            input message: msg, ok: "Uninstall"
                        }
                    } catch (Exception err) {
                        throw new GroovyException("The user chose to keep this helm release running")
                    }
                    //if no succeeded deployment and user chose uninstall, cleanup
                    ansi_echo "There is a FAILED release thats exists in namespace:${helmDeployK8s.namespace}. It will now be uninstalled.", 31
                    helmDeployK8s.deleteDeployment(env.RELEASE_NAME)
                }
            }
        }
    }

    def sendNotification(def deployConfiguration, String deployStatus, String securityApprovalFlag){
        String msgDeployStatus = deployStatus == "" ? "Rollout PASSED" : "Rollout FAILED and the build has been rolled back"
        ansi_echo "slack rollout status To Slack Channels"
        try{
            String buildStatus = jenkinsUtils.getBuildStatus()
            boolean buildFailed = buildStatus == "FAILURE"
            boolean buildPassed = jenkinsUtils.isBuildStatusOK()
            boolean isTestRepo = isCatalystTestRepo()
            def item = deployConfiguration
            //Sending a notification only if the new release is created
            String msgSlack = getSlackMsg(msgDeployStatus, item)
            if(item.slackChannels) {
                for(int ii=0; ii < item.slackChannels.size(); ii++) {
                    def slackItem = [slackIfDeployFailed: true, slackIfDeployPassed: true] << item.slackChannels[ii]
                    if (buildFailed && slackItem.slackIfDeployFailed || buildPassed && slackItem.slackIfDeployPassed) {
                        ansi_echo "slack rollout status To ${slackItem.name} Channel"
                        sendSlackNotification(slackItem.name, msgSlack, buildStatus)
                    }
                }
            }
            // if(isTestRepo){
            //     ansi_echo "Skip sending rollout status notification because this is a test app."
            // } else {
            //     String channel = "navigator-release"
            //     switch (item.psenv) {
            //         case "e1-pmgt":
            //             channel = "e1-pmgt-cluster"
            //             break
            //         case "e1-np":
            //             channel = "e1-np-all-changes"
            //             break
            //         default:
            //             channel = "unified-release"
            //     }
            //     if (item.deploymentOK || channel == "unified-release") {
            //     //    ansi_echo "slack rollout status To ${channel} channel"
            //     //  sendSlackNotification(channel, msgSlack, buildStatus)
            //     } else {
            //         ansi_echo "Skip sending rollout status To ${channel} channel because the rollout failed before the light-side update."
            //     }
            // }
            item.buildStatus = buildStatus
            // new RelayServerNotifications().sendDeploymentInfo(item)
        } catch (Exception err){
            ansi_echo "Sending Notification Failed: "+err.getMessage(), 31
            throw err
        }
    }

    def executeDeployment(Map conf, String securityApprovalFlag = "") {
        jenkinsUtils.jenkinsNode(templateType: 'helm', infrastructure: conf.infrastructure, clusterList: [conf.clusterId], awsRegion: conf.region) {
            def helmDeployK8s = helmUtils.getHelmKubeHelper(conf.clusterId, conf.namespace)
            k8sAccessConfig conf
            checkoutSource()
            // No need to check the helm release state
            // Helm can execute helm upgrade against failed helm release
            // checkIfBadReleaseExists(helmDeployK8s)
            conf.lastReleaseName = env.RELEASE_NAME
            conf.creatingNewRelease = true
            if(conf.workload != "k8s-dep") conf.configMapHash = calcConfigMapHash(conf)
            String msgDeployStatus = ""
            echo "conf.workload: ${conf.workload}"
            def objDeploy = conf.workload == "k8s-dep" ? new DeployBatch(conf) : new DeployRolling(conf)
            try{
                echo "start to deploy..."
                objDeploy.start()
                //The k8s debugging is no needed on failed because the rollout is succesful
                conf.holdForDebugOnFailed = false

                if (conf.testConf?.enabled && jenkinsUtils.isBuildStatusOK()) {
                    if(conf.infrastructure != "roadster-cloud") {
                        echo "start post deployment testing..."
                        PostDeploymentPhase postDeploymentPhase = new PostDeploymentPhase(this)
                        postDeploymentPhase.execute(conf)
                    }
                }
            } catch (Exception err){
                if(!jenkinsUtils.isBuildAborted()){
                    currentBuild.result = "FAILURE"
                }
                msgDeployStatus = err.getMessage()
                String buildStatus = jenkinsUtils.getBuildStatus()
                if(buildStatus == "FAILURE" && (conf.holdForDebugOnFailed == null || conf.holdForDebugOnFailed == true)){
                    def labels = "${conf.clusterId}"
                    def psenvs = conf.psenv
                    def appVersion = conf.appVersion
                    stage("DEBUG: Holding ${psenvs}/${labels}") {
                        if (conf.slackChannels) {
                            ansi_echo "slack to ${conf.slackChannels[0].name} Channel for debugging ${psenvs}/${labels}", 31
                            String msgSlack = getDebugSlackMsg(conf)
                            for(int i=0; i < conf.slackChannels.size(); i++) {
                                sendSlackNotification(conf.slackChannels[i].name, msgSlack, buildStatus, '@here')
                            }
                        }
                        deployUtils.skubaDebugOnFailed(conf)
                        holdForDebugOnFailed()
                    }
                }
                if(helmDeployK8s.ifReleaseNameExist(conf.newReleaseName)){
                    if(conf.releaseRevision == "") { //new and first deployment
                        stage("Uninstall: ${conf.newReleaseName}") {
                            ansi_echo "Uninstalling the new rollout because this is the first release and it failed.", 31
                            helmDeployK8s.deleteDeployment(conf.newReleaseName)
                        }
                    } else {
                        boolean rollbackSuccessful = false
                        stage("Rollback: ${conf.clusterName}") {
                            ansi_echo("The new rollout is ${buildStatus}! Rollback to the previous release", 31)
                            rollbackSuccessful = objDeploy.rollbackOnError()
                        }
                        //Argo rollout to the last release
                        if(rollbackSuccessful && conf.workload != "k8s-dep"){
                            String argoRolloutNames = helmDeployK8s.getArgoRolloutNames(conf.lastReleaseName)
                            if(argoRolloutNames != "") {
                                objDeploy.processArgoRollouts(argoRolloutNames, true, conf)
                            }
                        }
                    }
                }
                throw err
            } finally {
                try {
                    stage("Send Notification") {
                        sendNotification(conf, msgDeployStatus, securityApprovalFlag)
                    }
                } catch (Exception err){
                    if(jenkinsUtils.isBuildStatusOK()){
                        currentBuild.result = "UNSTABLE"
                    }
                }
            }
        }

    }
    
    def clientTestSuiteJobTrigger(Map conf) {
        conf = [tokenCredential: 'mt-hyperloop-jenkins-token', blockBuildUntilComplete: true, abortTriggeredJob: true, enhancedLogging: true,
                parameters: "", pollInterval: 20,
                remoteJenkinsUrl: "https://mt.hyperloop.sonynei.net/job/GPOE/view/OTSPS4/job/ps4-e1-np-ots/"] << conf

        echo "starting client test job for ${conf.psEnv}"
        def handle = null
        def errorMsg = ""
        try{
            handle = triggerRemoteJob(
                auth: CredentialsAuth(credentials: conf.tokenCredential),
                job: conf.remoteJenkinsUrl,
                blockBuildUntilComplete: conf.blockBuildUntilComplete,
                abortTriggeredJob: conf.abortTriggeredJob,
                enhancedLogging: conf.enhancedLogging,
                parameters: conf.parameters,
                pollInterval: conf.pollInterval
            )
        } catch (Exception err) {
            errorMsg = err.getMessage()
            echo "clientTestSuiteJobTrigger failed: ${errorMsg}"
            handle = null  
        }
        if(handle == null) {
            String msgSlack = "Unable to connect to ${conf.remoteJenkinsUrl} for the post deployment testing\nJenkins Job: ${env.BUILD_URL}"
            if (conf.slackChannels) {
                for(int i=0; i < conf.slackChannels.size(); i++) {
                    sendSlackNotification(conf.slackChannels[i].name, msgSlack, "FAILURE", '@here')
                }
            }
            sendSlackNotification("engine-workflow-notify", msgSlack, "FAILURE", '@here')
            if (env.ACTION == "EMERGENCY_DEPLOYMENT") {
                emergencyDeploymentConfirmationOnPostTestFailed()
                currentBuild.result = "UNSTABLE"
            } else {
                if(errorMsg==null || errorMsg == "") errorMsg = "Unable to connect to ${conf.remoteJenkinsUrl} for the post deployment testing."
                throw new GroovyException(errorMsg)
            }
        } else {
            String clientTestJobBuildStatus = handle.getBuildStatus().toString()
            String clientTestJobBuildResult = handle.getBuildResult().toString()
            echo "BuildStatus: ${clientTestJobBuildStatus}, BuildResult: ${clientTestJobBuildResult}"
            // Record TestEvidence, Date, Status, and Eline CR creation for P CR data
            env.TestEvidenceJob = handle.getBuildUrl().toString()
            Date currentDate = new Date();
            def crTicketNames = [] 
            for(int i=0; i < conf.serviceNowConfig.size(); i++){
                crTicketNames.add(conf.serviceNowConfig[i].crName)
            }
            echo "testEvidence:{\"runDate\": ${currentDate.getTime()},\"chartVersion\":\"${env.CHART_VERSION}\",\"evidenceURL\":\"${env.TestEvidenceJob}\",\"status\":\"${clientTestJobBuildResult}\",\"crName\":\"${crTicketNames.join(', ')}\"}"
            if (clientTestJobBuildResult != "SUCCESS") {
                throw new GroovyException("client test suite failed with job status as ${clientTestJobBuildResult}")
            }
        }
    }

    // def rdsSmokeTestStage(Map conf) {
    //     stage("${conf.psenv} rollout validations") {
    //         def parallelJobs = [:]
    //         conf.testConf.testJobs.each { testJob ->
    //             def testJobLabel = "Post Deployment Testing Job: ${testJob.projectDir}"
    //             parallelJobs[testJobLabel] = {
    //                 stage(testJobLabel) {
    //                     if (testJob.rollbackOnTestFailure) {
    //                         triggerRdsSmokeTest(testJob.projectDir, conf)
    //                     } else {
    //                         catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
    //                             triggerRdsSmokeTest(testJob.projectDir, conf)
    //                         }
    //                     }
    //                 }
    //             }
    //         }
    //         parallel(parallelJobs)
    //     }
    // }

    // def triggerRdsSmokeTest(String projectDir, Map conf) {
    //     // Check if job name contains service name
    //     if (!projectDir.contains(conf.newReleaseName)) {
    //         echo "post deployment testing job name doesn't contain service function name"
    //         return
    //     }

    //     // Prepare arguments for triggerRemoteJob
    //     args = [
    //         auth: CredentialsAuth(credentials: "roadster-smoke-test-nonprod"),
    //         remoteJenkinsUrl: "https://nlb001-e2ejnk-e101-usw2-lt-np.ad.lt.playstation.net",
    //         job: projectDir,
    //         parameters: "lineEnv=${conf.psenv}",
    //         blockBuildUntilComplete: true,
    //         abortTriggeredJob: true,
    //         enhancedLogging: true,
    //         pollInterval: 10
    //     ]
    //     if (conf.psenv.contains("p1")) {
    //         args.auth = CredentialsAuth(credentials: "roadster-smoke-test-prod")
    //         args.remoteJenkinsUrl = "https://nlb001-e2ejnk-prod01-usw2-lt-np.ad.lt.playstation.net/"
    //     }

    //     // Trigger remote job
    //     try {
    //         handle = triggerRemoteJob(args)
    //     } catch (Exception err) {
    //         // Post slack message
    //         String slackMsg = "Unable to connect to ${args.remoteJenkinsUrl} for the post deployment testing\nJenkins Job: ${env.BUILD_URL}"
    //         if (conf.slackChannels) {
    //             for(int i=0; i < conf.slackChannels.size(); i++) {
    //                 sendSlackNotification(conf.slackChannels[i].name, slackMsg, "FAILURE", '@here')
    //             }
    //         }
    //         sendSlackNotification("engine-workflow-notify", slackMsg, "FAILURE", '@here')
    //         // Throw exception
    //         String errorMsg = err.getMessage()
    //         if(errorMsg==null || errorMsg == "") errorMsg = "Unable to connect to ${conf.remoteJenkinsUrl} for the post deployment testing."
    //         echo "triggerRdsSmokeTest failed: ${errorMsg}"
    //         throw new GroovyException(errorMsg)
    //     }

    //     // Check the remote job status
    //     String clientTestJobBuildStatus = handle.getBuildStatus().toString()
    //     String clientTestJobBuildResult = handle.getBuildResult().toString()
    //     echo "BuildStatus: ${clientTestJobBuildStatus}, BuildResult: ${clientTestJobBuildResult}"
    //     if (clientTestJobBuildResult != "SUCCESS") {
    //         throw new GroovyException("Post deployment testing job failed with status as ${clientTestJobBuildResult}")
    //     }
    // }

    def k8sAccessConfig(Map conf) {
        container(conf.clusterId) {
            cleanWs()
            jenkinsUtils.skubaSetup()
            getKubeConfig(conf)
            String SKUBA_CONTEXT = sh(returnStdout: true, script: "grep 'current-context' ~/.kube/config").replaceAll("current-context:", "").trim()
            echo "jenkinsContext: ${SKUBA_CONTEXT}"
            sh """
                skuba cluster ${conf.clusterId}
                skuba kubectl config use-context ${SKUBA_CONTEXT}
            """
        }
    }

    def sendSlackNotification(String channel, String msgSlack, def buildStatus, String audience = '') {
        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE', message:"The slack message to ${channel} failed") {
            new SlackNotifications().sendSimpleSlackNotification(channel, msgSlack, buildStatus, "", audience)
        }
    }

    def calcConfigMapHash(def conf) {
        // Init
        configMapData = [:]
        for (i = 0; i < conf.serviceNames.size(); i++) {
            configMapData[conf.serviceNames[i]] = ""
        }
        // Collect ConfigMap data field
        dir("${env.REPO_WORKDIR}/${env.APP_CHART_PATH}") {
            // Run helm template command
            helmDeployK8s = new Helm3KubeHelper(conf.clusterId, conf.namespace)
            def opt = ''
            if (conf.valuesConfigFiles) {
                for (int i=0; i < conf.valuesConfigFiles.size(); i++) {
                    if (!opt.contains(conf.valuesConfigFiles[i])) opt += "-f ./${conf.valuesConfigFiles[i]} "
                }
            }
            opt += "--set global.cluster=${conf.clusterId} "
            opt += "--set global.serviceChartVersion=${conf.chartVersion} "
            opt += "--set global.infrastructure=${conf.infrastructure} "
            opt += "--set global.awsRegion=${conf.region} "
            helmDeployK8s.helmTemplateOutput(conf.newReleaseName, opt)
            // Read output of helm template
            if (fileExists("output.yaml")) {
                manifestMap = readYaml file: "output.yaml"
                for (int i = 0; i < manifestMap.size(); i++) {
                    // Process only ConfigMaps
                    if (manifestMap[i] && manifestMap[i].kind == "ConfigMap") {
                        configMap = manifestMap[i]
                        echo "processing: ${configMap.metadata.name}"
                        // Extract data field per services
                        for (j = 0; j < conf.serviceNames.size(); j++) {
                            if (configMap.metadata.name.contains(conf.serviceNames[j])) {
                                configMapData[conf.serviceNames[j]] += yamlFileUtils.convertMapToYaml(configMap.data)
                            }
                        }
                    }
                }
                sh """
                    rm output.yaml
                    ls -lah
                """
            } else {
                echo "No output.yaml"
                return [:]
            }
        }
        // Calculate hash for each data field
        configMapHash = [:]
        configMapData.each { k, v -> if (v) { configMapHash[k] = md5(v) } }
        echo "configMapHash: ${configMapHash}"
        return configMapHash
    }

    def md5(String s) {
        MessageDigest digest = MessageDigest.getInstance("MD5")
        digest.update(s.bytes)
        new BigInteger(1, digest.digest()).toString(16).padLeft(32, '0')
    }

    def executeScaleRollout(Map conf) {
        jenkinsUtils.jenkinsNode(templateType: 'helm', infrastructure: conf.infrastructure, clusterList: [conf.clusterId], awsRegion: conf.region) {
            stage("Scale Rollout") {
                echo "start to scale rollout..."
                k8sAccessConfig conf
                new DeployRolling(conf).scaleRollout()
            }
        }
    }

    def getRolloutInfo(Map conf) {
        jenkinsUtils.jenkinsNode(templateType: 'helm', infrastructure: conf.infrastructure, clusterList: [conf.clusterId], awsRegion: conf.region) {
            def helmDeployK8s = helmUtils.getHelmKubeHelper(conf.clusterId, conf.namespace)
            k8sAccessConfig conf
                
            echo "get rollout info ..."
            stage("Rollout Info") {
                def rolloutNameList = []
                String argoRolloutNames = helmDeployK8s.getArgoRolloutNames(conf.newReleaseName)
                if(argoRolloutNames && argoRolloutNames != "") {
                    def arrRolloutNames = argoRolloutNames.split("\n")
                    if (arrRolloutNames){
                        for(int i=0; i < arrRolloutNames.size(); i++){
                            def temp = arrRolloutNames[i]
                            String rolloutName = temp.split()[0]
                            if(rolloutName != ""){
                                rolloutNameList.add(rolloutName)
                            }
                        }
                    }
                }
                conf.selectedRolloutName = selectRolloutName(rolloutNameList)
            }
            stage("Desired Replicas Input") {
                conf.isUsingKEDA = helmDeployK8s.isUsingKEDA(conf.selectedRolloutName)
                conf.isUsingHPA = helmDeployK8s.isUsingHPA(conf.selectedRolloutName)
                if(conf.isUsingKEDA || conf.isUsingHPA) {
                    //input min and max replicas counts
                    conf.desiredMinReplicasCount = desiredReplicasInput("desiredMinReplicasCount")
                    conf.desiredMaxReplicasCount = desiredReplicasInput("desiredMaxReplicasCount")
                } else  {
                    //input desired replicas count
                    conf.desiredReplicasCount = desiredReplicasInput("desiredReplicasCount")
                }
            }
        }
    }

    def desiredReplicasInput(String inputId) {
        String inputLabel = ""
        try{
            timeout(60) {
                switch(inputId) {
                    case "desiredMinReplicasCount":
                        inputLabel = "desired MIN replicas count"
                        break
                    case "desiredMaxReplicasCount":
                        inputLabel = "desired MAX replicas count"
                        break
                    default:
                        inputLabel = "desired replicas count"
                }
                int inputIndex = 0
                def desiredReplicas = 0
                while(desiredReplicas < 1) {
                    def msg = inputIndex == 0 ? "Please input the ${inputLabel} for scale rollout" : "The ${inputLabel} is required and can not be empty or less than 1. \nPlease input!"
                    def rtnValue = input id: inputId, ok: "OK", message: msg, parameters: [string(description: '', name: 'DESIRED_REPLICAS')]
                    echo "rtnValue: ${rtnValue}"
                    rtnValue = jenkinsUtils.removeWhiteSpaces(rtnValue)
                    if(rtnValue != "") desiredReplicas = rtnValue.toInteger()
                    inputIndex += 1
                }
                echo "${inputLabel}: '${desiredReplicas}'"
                return desiredReplicas
            }
        } catch (Exception err) {
            def msgError = "The scale rollout was cancelled in the ${inputLabel} input step."
            echo msgError
            currentBuild.result = "ABORTED"
            throw new GroovyException(msgError)
        }
    }

    def selectRolloutName(def rolloutNameList) {
        def selectedRolloutName = ""
        if(!rolloutNameList || rolloutNameList == []) {
            throw new GroovyException("No argo rollout found for namespace of ${conf.namespace} in ${conf.psenv} ")
        } else if(rolloutNameList.size() == 1) {
            selectedRolloutName = rolloutNameList[0]
        } else {
            timeout(60) {  
                String msgSelect = "Please select the rollout name for scale rollout"
                selectedRolloutName = input id: "selectRolloutName", message: msgSelect, ok: 'Select', parameters: [choice(choices: rolloutNameList, name: 'RN_CHOICE')]
            }
        }
        echo "selectedRolloutName: ${selectedRolloutName}"
        return selectedRolloutName
    }
}
