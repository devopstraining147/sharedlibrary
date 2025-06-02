package com.sony.sie.cicd.helpers.utilities

import com.sony.sie.cicd.cd.deployProcessors.K8sBaseDeployProcessor
import com.sony.sie.cicd.cd.utilities.DeployUtils
import org.codehaus.groovy.GroovyException

class PostDeploymentPhase extends JenkinsSteps {
    final DeployUtils deployUtils = new DeployUtils()
    final K8sBaseDeployProcessor k8sBaseDeployProcessor

    PostDeploymentPhase(K8sBaseDeployProcessor k8sBaseDeployProcessor) {
        this.k8sBaseDeployProcessor = k8sBaseDeployProcessor
    }

    void execute(Map conf) {
        def testJobs = []
        def failedJobs = []
        def lineEnv = conf.psenv.split('-')
        def gitUrl = "git@github.sie.sony.com:${conf.orgName}/${conf.repoName}.git"
        stage("${conf.psenv} Rollout Validation") {
            echo "=== testConf ===\n${conf.testConf}\n=== ==="
            def parallelJobs = [:]
            echo "conf.testConf.testJobs.size(): ${conf.testConf.testJobs.size()}"
            for (int i = 0; i < conf.testConf.testJobs.size(); i++) {
                def remoteJobUrl = 'https://core.jenkins.hyperloop.sonynei.net/engine-tools/job/deployment/job/deployment_validator'
                def testJob = conf.testConf.testJobs[i]
                def testJobParams = ""
                def testJobCredentials = 'TEST_JOB_TOKEN'

                echo "testJob: ${testJob}"
                if (testJob.remoteJobUrl) {
                    remoteJobUrl = testJob.remoteJobUrl
                    testJobParams = "CustomParameters=testEnv=${conf.psenv},appVersion=${env.APP_VERSION},crName=${conf.serviceNowConfig[0].crName},repoName=${conf.repoName},releaseVersion=${env.CHART_VERSION},deployment_type=uks\nPROJECT_DIRECTORY=${testJob.projectDir}\nTestEnv=isoperf\nLine=isoperf\nLINE_ENV=isoperf\nCHART_VERSION=${env.CHART_VERSION}"
                    // check if it's a shield server
                    echo "testJobParams: ${testJobParams}"
                    if(remoteJobUrl.contains(".jenkins.tools.ce.playstation.net")) {
                        testJobCredentials = 'MONARCH_SHIELD_JENKINS_HYPERLOOPOPS_API_TOKEN'
                    }
                    if(testJob.remoteJobParams) {
                        testJobParams += ("\n" + testJob.remoteJobParams + "\n")
                    }
                } else {
                    // get default branch if env.GIT_COMMIT not defined
                    def githubApi = new GitUtils()
                    def deploymentValidatorRepoBranch = env.GIT_COMMIT ?: githubApi.getDefaultBranch(conf.orgName, conf.repoName)
                    testJobParams = "InputParameters=component_name:${conf.appName};appVersion:${env.APP_VERSION};crName:${conf.serviceNowConfig[0].crName};releaseVersion:${env.CHART_VERSION};repoName:${conf.repoName};environment:${conf.psenv};version:${env.CHART_VERSION};git_repo:${gitUrl};git__branch:${deploymentValidatorRepoBranch};deployment_type:uks;project_dir:${testJob.projectDir}"
                }

                def testJobConfMap = [
                        parameters: testJobParams,
                        remoteJenkinsUrl: remoteJobUrl,
                        psEnv: conf.psenv,
                        tokenCredential: testJobCredentials,
                        serviceNowConfig: conf.serviceNowConfig,
                ]
                testJobs.add(testJobConfMap)

                def testJobLabel = "Validation Job #${i + 1}"
                parallelJobs[testJobLabel] = {
                    stage(testJobLabel) {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            try {
                                clientTestSuiteJobTrigger(testJobConfMap)
                            } catch (Exception ex) {
                                echo "WARNING: test job ${remoteJobUrl} failed! Message=${ex.toString()}"
                                if (testJob.rollbackOnTestFailure) {
                                    failedJobs.add(remoteJobUrl)
                                }
                                throw ex
                            }
                        }
                    }
                }
            }

            parallel(parallelJobs)

            if(testJobs && testJobs.size() > 0) {
                conf.postDeploymentTestEvidence = []
                for(int i = 0; i < testJobs.size(); i++) {
                    if(testJobs[i].testEvidenceMap) {
                        conf.postDeploymentTestEvidence.add(testJobs[i].testEvidenceMap)
                    }
                }
            }
        }
        if(failedJobs.size() > 0) {
            String msgSlack = "Unable to successfully run:\n- ${failedJobs.join(",\n- ")}\nfor post deployment testing.\n\nJenkins Job: ${env.BUILD_URL}"
            if (conf.slackChannels) {
                for(int i = 0; i < conf.slackChannels.size(); i++) {
                    def slackItem = conf.slackChannels[i]
                    if(slackItem.onDeployFailed) {
                        k8sBaseDeployProcessor.sendSlackNotification(slackItem.name, msgSlack, "FAILURE", slackItem.audience)
                    }
                }
            }
            k8sBaseDeployProcessor.sendSlackNotification("engine-workflow-notify", msgSlack, "FAILURE", '@e2-np-test-flanker')

            stage("Override: ${conf.psenv} Test Failure") {
                String label = "Override: ${conf.psenv} Test Job Failure?"
                long startMillis = 0
                if (conf.deployApprovalTimeOut == null || conf.deployApprovalTimeOut <= 0) {
                    conf.deployApprovalTimeOut = 1
                }
                int timeoutSeconds = conf.deployApprovalTimeOut * 24 * 60 * 60
                try {
                    if(conf.slackChannels) {
                        for(int i=0; i < conf.slackChannels.size(); i++) {
                            def slackItem = conf.slackChannels[i]
                            if(slackItem.onDeployApproval) {
                                ansi_echo "Waiting for [$label]\n override review from [${slackItem.name}]"
                                deployUtils.slackApprovalStatusMsg(slackItem.name, "WAITING FOR OVERRIDE", conf.psenv, "WAITING", slackItem.audience)
                            }
                        }  
                    }
                    startMillis = System.currentTimeMillis()
                    timeout(time: conf.deployApprovalTimeOut, unit: 'HOURS') {
                        def approveOverrideList = "${conf.approver}".replaceAll(/[\[\]\s]/, '')
                        echo "approveOverrideList: ${approveOverrideList}"
                        String msgOverride = "${label}\n" +
                                "[Override]: Override the ${conf.psenv} test failure by authorized users and approve rollout.\n" +
                                "[Abort]: Abort the new rollout request and rollback the deployment due to test failure.\n" +
                                "[Authorized users: ${approveOverrideList}]\n"
                        def overrideInput = input id: 'testFailureOverride', ok: "Override", message: msgOverride, submitter: approveOverrideList,
                                parameters: [text(description: 'Please enter a short reason for the override:', name: 'Override Reason')]
                        conf.testJobFailureOverrideReason = "Validation Job Failure Detected:\\n- ${failedJobs.join(",\\n- ")}\\nFailure Overridden by: ${deployUtils.getLatestApprover()}\\n" +
                                "Override Justification:\\n------------------------\\n${overrideInput.replaceAll("\n", '\\\\n')}\\n------------------------\\n"
                    }
                    def overriddenBy = deployUtils.getLatestApprover()
                    if(conf.slackChannels) {
                        for(int i=0; i < conf.slackChannels.size(); i++) {
                            def slackItem = conf.slackChannels[i]
                            if(slackItem.onDeployApproval) {
                                deployUtils.slackApprovalStatusMsg(slackItem.name, "OVERRIDDEN BY ${overriddenBy}", conf.psenv, "SUCCESS", slackItem.audience)
                            }
                        }  
                    }
                } catch (Exception err) {
                    echo "INFO: PostDeploymentPhase Override: ${err.getMessage()}"
                    currentBuild.result = "ABORTED"
                    def abortedBy = deployUtils.isTimeout(startMillis, timeoutSeconds) ? "TIMEOUT" : deployUtils.getLatestApprover()
                    if(conf.slackChannels) {
                        for(int i=0; i < conf.slackChannels.size(); i++) {
                            def slackItem = conf.slackChannels[i]
                            if(slackItem.onDeployApproval) {
                                deployUtils.slackApprovalStatusMsg(slackItem.name, "ABORTED BY ${abortedBy}", conf.psenv, "ABORTED", slackItem.audience)
                            }
                        }  
                    }
                    throw new GroovyException("The ${conf.psenv} rollout override was declined by: ${abortedBy}")
                }
            }
        }
    }

    def clientTestSuiteJobTrigger(Map testJobConf) {
        Map testJob = [tokenCredential : 'mt-hyperloop-jenkins-token', blockBuildUntilComplete: true, abortTriggeredJob: true, enhancedLogging: false,
                       parameters      : "", pollInterval: 20,
                       remoteJenkinsUrl: "https://mt.hyperloop.sonynei.net/job/GPOE/view/OTSPS4/job/ps4-e1-np-ots/"] << testJobConf

        echo "starting client test job for ${testJob.psEnv}"
        def handle = null
        def errorMsg = ""
        def crTicketNames = []
        for (int i = 0; i < testJob.serviceNowConfig.size(); i++) {
            crTicketNames.add(testJob.serviceNowConfig[i].crName)
        }

        try {
            handle = triggerRemoteJob(
                    auth: CredentialsAuth(credentials: testJob.tokenCredential),
                    job: testJob.remoteJenkinsUrl,
                    blockBuildUntilComplete: testJob.blockBuildUntilComplete,
                    abortTriggeredJob: testJob.abortTriggeredJob,
                    enhancedLogging: testJob.enhancedLogging,
                    parameters: testJob.parameters,
                    pollInterval: testJob.pollInterval
            )
            String clientTestJobBuildStatus = handle.getBuildStatus().toString()
            String clientTestJobBuildResult = handle.getBuildResult().toString()

            
            echo "BuildStatus: ${clientTestJobBuildStatus}, BuildResult: ${clientTestJobBuildResult}"
            // For shield server jobs, trigger the test results processor job
            
            def isShield = testJob.remoteJenkinsUrl.contains(".jenkins.tools.ce.playstation.net")
            echo "Is Shield testResultsProcessor job needed: ${isShield} "
            if (isShield) {
               String remoteBuildUrl = handle.getBuildUrl().toString()
               echo "Triggering shield test processor job for remoteBuildUrl: ${remoteBuildUrl}" 
                // Trigger the shield test processor job silently
                try {
                    def shieldProcessorParams = testJob.parameters
                    shieldProcessorParams = shieldProcessorParams + "\nremoteBuildUrl=${remoteBuildUrl}"
                    echo "shieldProcessorParams: ${shieldProcessorParams}"
                    def shieldProcessorHandle = triggerRemoteJob(
                                auth: CredentialsAuth(credentials: 'engine-workflow-jenkins-token'),
                                job: 'https://core.jenkins.hyperloop.sonynei.net/engine-tools/job/Shield-TestResults-Processor',
                                parameters: shieldProcessorParams,
                                blockBuildUntilComplete: false,  //unblock the build after the job is triggered
                                pollInterval: 30,
                                enhancedLogging: true
                    )
                    
                    echo "Shield test processor job status: ${shieldProcessorHandle.getBuildStatus()}"
                    echo "Shield test processor job result: ${shieldProcessorHandle.getBuildResult()}"
                } catch (Exception shieldErr) {
                    echo "WARNING: Shield test processor job failed but continuing pipeline: ${shieldErr.getMessage()}"
                }
            }
   
            // Record TestEvidence, Date, Status, and Eline CR creation for P CR data
            def testEvidenceJob = handle.getBuildUrl().toString()
            Date currentDate = new Date();
            echo "testEvidence:{\"runDate\": ${currentDate.getTime()},\"chartVersion\":\"${env.CHART_VERSION}\",\"evidenceURL\":\"${testEvidenceJob}\",\"status\":\"${clientTestJobBuildResult}\",\"crName\":\"${crTicketNames.join(', ')}\"}"
            if (clientTestJobBuildResult == "SUCCESS") {
                testJobConf.put("testEvidenceMap", [
                    runDate     : currentDate.getTime(),
                    chartVersion: env.CHART_VERSION,
                    evidenceURL : testEvidenceJob,
                    status      : clientTestJobBuildResult,
                    crName      : crTicketNames.join(', ')
                ])
            } else {
                error "client test suite failed with job status as ${clientTestJobBuildResult}"
            }
        } catch (Exception err) {
            errorMsg = err.getMessage()
            echo "clientTestSuiteJobTrigger failed: ${errorMsg}"
            testJobConf.put("testEvidenceMap", [runDate     : new Date().getTime(),
                                                chartVersion: env.CHART_VERSION,
                                                crName      : crTicketNames.join(', '),
                                                evidenceURL : testJob.remoteJenkinsUrl,
                                                status      : "FAILURE"])

            if (env.ACTION == "EMERGENCY_DEPLOYMENT") {
                k8sBaseDeployProcessor.emergencyDeploymentConfirmationOnPostTestFailed()
                currentBuild.result = "UNSTABLE"
            } else {
                if (errorMsg == null || errorMsg == "") {
                    error "Unable to connect to ${testJob.remoteJenkinsUrl} for the post deployment testing."
                } else {
                    throw new GroovyException(errorMsg)
                }
            }
        }
    }
}
