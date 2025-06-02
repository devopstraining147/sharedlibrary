import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import org.codehaus.groovy.GroovyException
import static groovy.json.JsonOutput.*


def call() {
    setProperties()
    setBaseUrl()
    jenkinsUtils = new JenkinsUtils()
    try {
        timestamps {
            ansiColor('xterm') {
                echo "Starting Jenkins job 123..."
                def version = buildStage().replace("Release ", "")
                currentBuild.description = "Version: ${version}"
                echo "Build complete with version: ${version}"
            }
        }
    } catch (GroovyException err) {
        if(!jenkinsUtils.isBuildAborted()) {
            String msg = err.getMessage()
            if (!msg) msg = 'Unknown error!'
            echo "Groovy Exception: " + msg
            currentBuild.result = "FAILURE"
        }
    }
}

void setBaseUrl() {
    def baseJobPath = "gradle-catalyst-example"
    if (params.LIB_VERSION) {
        env.BASE_URL = "${env.JENKINS_URL}job/iso-perf-test-jobs/job/engine-iso-perf-framework-test-jobs/job/${baseJobPath}/job"
    } else {
        env.BASE_URL = "${env.JENKINS_URL}job/iso-perf-test-jobs/job/${baseJobPath}/job"
    }
    echo "Using BASE_URL: ${env.BASE_URL}"
}

void setEnv() {
    env.GIT_URL = jenkinsUtils.getRepoUrl()
    env.ORG_NAME = jenkinsUtils.determineOrgName()
    env.REPO_NAME = jenkinsUtils.determineRepoName()
    env.BRANCH_NAME = (env.CHANGE_ID ? env.CHANGE_BRANCH : env.BRANCH_NAME)
    env.REPO_WORKDIR = env.REPO_NAME
}

String buildStage() {
    def jenkinsUtils = new JenkinsUtils()
    stage("Build") {
        node {
            def serviceReleaseVersion = jenkinsUtils.removeWhiteSpaces(params.SERVICE_RELEASE_VERSION) ?: ""
            echo "params.SERVICE_RELEASE_VERSION: ${serviceReleaseVersion}"
            String jobUrl = env.BASE_URL + "/build-app"
            Map jobParams = ["BRANCH_OVERRIDE": "${params.BRANCH_OVERRIDE}"]
            addLibVersion(jobParams)

            def jobInfo = jenkinsUtils.triggerRemoteJenkinsJob(
                    [remoteJenkinsUrl: jobUrl,
                     parameters      : jobParams]
            )
            return jobInfo.description ?: "Release ${serviceReleaseVersion}"
        }
    }
}

def setProperties() {
    def settings = [
            //string(name: 'BRANCH_OVERRIDE', defaultValue: '', description: 'Optional: defaults to main or master github branch'),
            //string(name: 'DEPENDENCY_PATH', defaultValue: '', description: 'Optional: Comma separated repo specific path to each of the helm charts directories for dependencies.'),
            //string(name: 'NUMBER_OF_TEST_PODS', defaultValue: '1', description: 'Optional: number of pods to execute with, default 1.'),
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
def addLibVersion(Map jobParams) {
    if(params.LIB_VERSION) {
        jobParams["LIB_VERSION"] = "${params.LIB_VERSION}"
    }
}
