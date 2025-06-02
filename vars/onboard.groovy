import com.sony.sie.cicd.helpers.utilities.JenkinsUtils

def call(def configure) {
    pipelineDefinition = [:]
    configure.resolveStrategy = Closure.DELEGATE_FIRST
    configure.delegate = pipelineDefinition
    configure()
    jenkinsUtils = new JenkinsUtils()
    submitter = jenkinsUtils.getSubmitter()
    setJobProperties()
    timestamps {
        ansiColor('xterm') {
            try {
                jenkinsUtils.jenkinsNode(templateType: 'basic', infrastructure: pipelineDefinition.infrastructure) {
                    container("build-tools") {
                        execute()
                    }
                }
            } catch (Exception err) {
                String message = ""
                if(submitter) {
                    message += new String("Submitted by: ${submitter}\n")
                }
                message += "Error:" + err.getMessage()
                log.error(message)
                err.printStackTrace()
                if (!jenkinsUtils.isBuildAborted()) {
                    String msg = err.getMessage()
                    if (!msg) msg = 'Unknown error!'
                    log.error msg
                    currentBuild.result = "FAILURE"
                }
                String description = (submitter ? "Submitted by: ${submitter}<br/>" : "")
                description += "Error: " + err.getMessage()
                currentBuild.description = description
            }
        }
    }
}

def execute() {
    library('engine-cicd-utilities')
    log.info "starting onboard"

    def cdConfigFile = jenkinsUtils.removeWhiteSpaces(params.CONFIG_FILE)
    if(!cdConfigFile) throw new Exception("Config file required.")
    //https://core.jenkins.hyperloop.sonynei.net/gaminglife-core/job/onboarding/job/generate-iso-perf-test-jobs/]
    def controller = cdConfigFile.split("/")[0]
    def baseUrl = "https://core.jenkins.hyperloop.sonynei.net/${controller}-core"
    def targetUrl = "${baseUrl}/job/onboarding/job/generate-iso-perf-test-jobs"
    onboardStage(targetUrl, cdConfigFile)
    def repo = "engine-cd-configurations"
    dir(repo) {
        jenkinsUtils.getFileFromGithub(repo, "master", "SIE", cdConfigFile, cdConfigFile)
    }
    def pipelineDef = createPerfTestSuite.getPipelineDefinition(repo, cdConfigFile)
    String description = (submitter ? "Submitted by: ${submitter}<br/>" : "")
    description += new String("Job: <a href=\"${baseUrl}/job/iso-perf-test-jobs/job/${pipelineDef.jenkinsJobPath}\">${pipelineDef.jenkinsJobPath}</a>")
    currentBuild.description = description
}

def onboardStage(String target, String cdConfigFile) {
    stage("Onboard") {
        String jobParams = """CONFIG_FILE=${cdConfigFile}
        """
        jenkinsUtils.triggerRemoteJenkinsJob(
                [remoteJenkinsUrl: target,
                 parameters      : jobParams]
        )
    }
}

def setJobProperties() {
    settings = [
            string(
                    defaultValue: "",
                    description: 'Required: config file name of engine-cd-configurations. i.e. gaminglife/cd/gradle-catalyst-example.yaml',
                    name: 'CONFIG_FILE'
            )
    ]
    properties([
            buildDiscarder(
                    logRotator(
                            artifactDaysToKeepStr: '30',
                            artifactNumToKeepStr: '30',
                            daysToKeepStr: '60',
                            numToKeepStr: '20')
            ),
            parameters(settings),
            pipelineTriggers([])
    ])
}
