import com.sony.sie.cicd.ci.utilities.NotifyUtils
import com.sony.sie.cicd.helpers.enums.StageName
import com.sony.sie.cicd.helpers.utilities.GitUtils
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils

import static groovy.json.JsonOutput.*
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

def call(def configure) {
    setProperties()
    pipelineDefinition = [:]
    configure.resolveStrategy = Closure.DELEGATE_FIRST
    configure.delegate = pipelineDefinition
    configure()
    jenkinsUtils = new JenkinsUtils()
    timestamps {
        ansiColor('xterm') {
            try {
                jenkinsUtils.jenkinsNode(templateType: 'basic', infrastructure: pipelineDefinition.infrastructure) {
                    container("build-tools") {
                        execute()
                    }
                }
            } catch (Exception err) {
                log.error err
                if (!jenkinsUtils.isBuildAborted()) {
                    String msg = err.getMessage()
                    if (!msg) msg = 'Unknown error!'
                    log.error msg
                    currentBuild.result = "FAILURE"
                }
                currentBuild.description = err.getMessage()
                throw err
            }
        }
    }
}

def execute() {

    try {
        log.info "starting orchestration"

        def cdConfigFile = jenkinsUtils.removeWhiteSpaces(pipelineDefinition.configFile)
        if (!cdConfigFile) throw new Exception("Config file required.")
        def repo = "engine-cd-configurations"
        dir(repo) {
            jenkinsUtils.getFileFromGithub(repo, "master", "SIE", cdConfigFile, cdConfigFile)
        }
        def cdConfig = createPerfTestSuite.getPipelineDefinition(repo, cdConfigFile)

        // https://core.jenkins.hyperloop.sonynei.net/gaminglife-core/job/iso-perf-test-jobs/job/gradle-catalyst-example/job/build-app/

        echo "params: ${pipelineDefinition.repoName}, ${params.BRANCH_OVERRIDE}, ${pipelineDefinition.orgName}"
        pipelineDefinition.slackChannels = new NotifyUtils().initiateNotification(pipelineDefinition)
        if (params.LIB_VERSION) {
            env.BASE_URL = env.JENKINS_URL + "job/iso-perf-test-jobs/job/engine-iso-perf-framework-test-jobs/job/" + cdConfig.jenkinsJobPath + "/job"
        } else {
            env.BASE_URL = env.JENKINS_URL + "job/iso-perf-test-jobs/job/" + cdConfig.jenkinsJobPath + "/job"
        }

        deployDepsStage()

        def version = buildStage().replace("Release ", "")

        currentBuild.description = "Version: ${version}"

        deployStage(version)

        isoPerfTestStage()

        if (params.CLEANUP_ON_COMPLETE) {
            cleanUpStage(version)
        }
    } finally {
        try {

            // delete CI created branch with pattern engine/isoperf-test- if exists
            def jenkinsUtils = new JenkinsUtils();

            def overridenBranchName = jenkinsUtils.removeWhiteSpaces(params.BRANCH_OVERRIDE) ?: ""
            if (overridenBranchName && overridenBranchName.contains("engine/isoperf-test-")) {
                jenkinsUtils.checkoutGitSCM(pipelineDefinition.repoName, overridenBranchName, pipelineDefinition.orgName)
                new GitUtils().gitDeleteBranch([orgName: pipelineDefinition.orgName, repoName: pipelineDefinition.repoName, branchName: overridenBranchName])
            }
        } catch (Exception e) {
            echo "IsoPerf: Failed to delete the branch: ${e.getMessage()}"
        }

        sendNotifications(pipelineDefinition)
    }
}

String buildStage() {
    def jenkinsUtils = new JenkinsUtils()
    stage("Build") {
        def serviceReleaseVersion = jenkinsUtils.removeWhiteSpaces(params.SERVICE_RELEASE_VERSION) ?: ""
        echo "params.SERVICE_RELEASE_VERSION: ${serviceReleaseVersion}"
        if (!serviceReleaseVersion) {
            String jobUrl = env.BASE_URL + "/build-app"
            Map jobParams = ["BRANCH_OVERRIDE": "${params.BRANCH_OVERRIDE}"]
            addLibVersion(jobParams)
            def jobInfo = jenkinsUtils.triggerRemoteJenkinsJob(
                    [remoteJenkinsUrl: jobUrl,
                     parameters      : jobParams]
            )
            return jobInfo.description
        } else {
            echo "skipping build stage - since service release version is available"
            Utils.markStageSkippedForConditional(STAGE_NAME)
            return "Release ${serviceReleaseVersion}"
        }
    }
}

def deployStage(String version) {
    stage("Deploy") {
        def ciJobVar = jenkinsUtils.removeWhiteSpaces(params.CI_JOB) ?: ""
        echo "params.CI_JOB: ${ciJobVar}"
        if (ciJobVar) {
            String jobUrl = env.BASE_URL + "/deploy-app"
            Map jobParams = ["VERSION": "${version}", "CI_JOB": ciJobVar]
            addLibVersion(jobParams)
            jenkinsUtils.triggerRemoteJenkinsJob(
                    [remoteJenkinsUrl: jobUrl,
                     parameters: jobParams]
            )
        } else {
            echo "skipping deploy stage - since NO CI JOB value is available"
            Utils.markStageSkippedForConditional(STAGE_NAME)
        }
    }
}

def cleanUpStage(String version) {
    stage("Clean-Up") {
        String jobUrl = env.BASE_URL + "/cleanup-resources"
        Map jobParams = [:]
        addLibVersion(jobParams)
        jenkinsUtils.triggerRemoteJenkinsJob(
                [remoteJenkinsUrl: jobUrl,
                 parameters      : jobParams]
        )
    }
}

def deployDepsStage() {
    stage("Deploy Dependencies") {
        if(!params.DEPENDENCY_PATH) {
            Utils.markStageSkippedForConditional(STAGE_NAME)
            return
        }
        // for each dep
        for(String dep : params.DEPENDENCY_PATH.split(",")) {
            if (dep) {
                log.info "Installing ${dep}"
                String jobUrl = env.BASE_URL + "/deploy-deps"
                Map jobParams = ["DEPENDENCY_PATH": "${dep}"]
                if(params.BRANCH_OVERRIDE) {
                    jobParams["BRANCH_OVERRIDE"] = "${params.BRANCH_OVERRIDE}"
                }
                addLibVersion(jobParams)
                jenkinsUtils.triggerRemoteJenkinsJob(
                        [remoteJenkinsUrl: jobUrl,
                         parameters: jobParams]
                )
            }
        }
    }
}

def isoPerfTestStage() {
    stage("Iso Perf Test") {
        String jobUrl = env.BASE_URL + "/iso-perf-test"
        Map jobParams = ["BRANCH_OVERRIDE": "${params.BRANCH_OVERRIDE}",
            "NUMBER_OF_TEST_PODS": "${params.NUMBER_OF_TEST_PODS}",
            "K6_SCRIPT_CMD": "${params.K6_SCRIPT_CMD}"]
        addLibVersion(jobParams)
        jenkinsUtils.triggerRemoteJenkinsJob(
                [remoteJenkinsUrl: jobUrl,
                 parameters      : jobParams]
        )
    }
}

def sendNotifications(pipelineDefinition) {
    stage("Send Notifications") {
        new NotifyUtils().endOfPipelineNotify(pipelineDefinition, StageName.ISOPERF_ORCH.formattedName)
    }
}

def addLibVersion(Map jobParams) {
    if(params.LIB_VERSION) {
        jobParams["LIB_VERSION"] = "${params.LIB_VERSION}"
    }
}

def setProperties() {
    def settings = [
            string(name: 'BRANCH_OVERRIDE', defaultValue: '', description: 'Optional: defaults to main or master github branch'),
            string(name: 'DEPENDENCY_PATH', defaultValue: '', description: 'Optional: Comma separated repo specific path to each of the helm charts directories for dependencies.'),
            string(name: 'NUMBER_OF_TEST_PODS', defaultValue: '1', description: 'Optional: number of pods to execute with, default 1.'),
            string(name: 'K6_SCRIPT_CMD', defaultValue: '', description: 'Optional: Job Param will override helm chart value, and helm chart value will override default value located in the pod definition <a href=\"https://github.sie.sony.com/SIE/engine-performance-test/blob/main/helm/engine-performance-test/templates/batch-job.yaml\">here</a>'),
            booleanParam(name: 'CLEANUP_ON_COMPLETE', defaultValue: false, description: 'if selected, the k8s resources created for the isoperf test are deleted'),
            string(name: 'SERVICE_RELEASE_VERSION', defaultValue: '', description: 'Optional: When triggering IsoPerf for a service, pass its release version. BuildStage is skipped'),
            string(name: 'CI_JOB', defaultValue: '', description: 'Optional: When passed in, this job will attempt to retrieve a helm artifact from the specified CI job. The format of the file should match CHART_VERSION-isoperf.tgz')
    ]
    if(params.LIB_VERSION) {
        settings.add(string(name: 'LIB_VERSION', defaultValue: 'main', description: 'CICD USE ONLY. Version/branch of the engine-iso-perf-framework to use.'))
    }
    properties([
            parameters(settings),
            buildDiscarder(
                    logRotator(
                            artifactDaysToKeepStr: '365',
                            artifactNumToKeepStr: '60',
                            daysToKeepStr: '365',
                            numToKeepStr: '60')
            ),
            pipelineTriggers([]),
            disableConcurrentBuilds()
    ])
    log.info "=== PARAMS ===\n${prettyPrint(toJson(params))}\n=== /PARAMS ==="
}
