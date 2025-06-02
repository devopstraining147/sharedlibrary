import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

def call(Closure configure) {
    jenkinsUtils = new JenkinsUtils()
    pipelineDefinition = [:]
    configure.resolveStrategy = Closure.DELEGATE_FIRST
    configure.delegate = pipelineDefinition
    configure()
    setProperties()
    // run example pipeline orchestration to validate changes.
    jenkinsUtils.jenkinsNode(templateType: 'basic', infrastructure: pipelineDefinition.infrastructure) {
        container("build-tools") {
            checkout scm
            prMainValidation()
        }
    }
}

def runJob(String jobUrl, Map jobParams) {
    return {
        def retVal = true
        try {
            def jobInfo = jenkinsUtils.triggerRemoteJenkinsJob(
                    [remoteJenkinsUrl: jobUrl,
                     parameters      : jobParams,
                     enhancedLogging : false,
                     pollInterval    : 60]
            )
            log.info jobInfo
        } catch (FlowInterruptedException fie) {
            throw fie
        } catch (Exception ex) {
            log.warn(ex)
            retVal = false
        }
        return retVal
    }
}
def setProperties() {
    properties([
            buildDiscarder(
                    logRotator(
                            artifactDaysToKeepStr: '60',
                            artifactNumToKeepStr: '60',
                            daysToKeepStr: '60',
                            numToKeepStr: '20')
            ),
            disableConcurrentBuilds()
    ])
}

def prMainValidation() {
    stage("PR/main CI") {
        runWhen((env.CHANGE_ID != null && env.CHANGE_TARGET == 'main') || env.BRANCH_NAME == 'main') {
            log.info "Beginning CI Validation"
            def parallelStages = [:]
            parallelStages["gradle"] = {
                stage("gradle") {
                    def gradleJob = runJob(env.JENKINS_URL + "job/iso-perf-test-jobs/job/engine-iso-perf-framework-test-jobs/job/gradle-catalyst-example/job/orchestration",
                            ["DEPENDENCY_PATH": "performance-test/helm/dependencies",
                             "LIB_VERSION"    : "${env.BRANCH_NAME}",
                             "CLEANUP_ON_COMPLETE": true])
                    retryOption(gradleJob)
                }
            }
            parallelStages["maven"] = {
                stage("maven") {
                    def mavenJob = runJob(env.JENKINS_URL + "job/iso-perf-test-jobs/job/engine-iso-perf-framework-test-jobs/job/catalyst-example/job/orchestration",
                            ["LIB_VERSION": "${env.BRANCH_NAME}", "CLEANUP_ON_COMPLETE": true])
                    retryOption(mavenJob)
                }
            }
            parallelStages["go"] = {
                stage("go") {
                    def goJob = runJob(env.JENKINS_URL + "job/iso-perf-test-jobs/job/engine-iso-perf-framework-test-jobs/job/engine-go-pipeline-example/job/orchestration",
                            ["LIB_VERSION": "${env.BRANCH_NAME}", "CLEANUP_ON_COMPLETE": true])
                    retryOption(goJob)
                }
            }
            parallel(parallelStages)
        }
    }
}

def retryOption(Closure closure) {
    def buildSuccess = false
    while (!buildSuccess) {
        buildSuccess = closure()
        if(jenkinsUtils.isBuildAborted()) {
            throw new Exception("Job Aborted")
        }
        if(!buildSuccess) {
            try {
                input message: "Stage failed, retry?"
            } catch(Exception ex) {
                currentBuild.result = "ABORTED"
                throw ex
            }
        }
    }
}
