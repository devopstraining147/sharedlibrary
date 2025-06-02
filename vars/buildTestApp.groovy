import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import org.codehaus.groovy.GroovyException

/*
    repoName = "sie/catalyst-example"
    infrastructure = "kamaji-cloud"
    dockerFileList = [["filepath": "performance-test/Dockerfile", "appName": "perf-catalyst-example"]]
    versionFileInfo = ["filepath": 'pom.xml', "filetype": 'xml', "keyword": "version"]
*/
def call(Closure body) {
    jenkinsUtils = new JenkinsUtils()
    try {
        timestamps {
            ansiColor('xterm') {
                echo "Starting Jenkins job ..."
                pipelineDefinition = [:]
                body.resolveStrategy = Closure.DELEGATE_FIRST
                body.delegate = pipelineDefinition
                body()
                buildApp.setupEnvInfo(pipelineDefinition.repoName)
                buildApp.setProperties()
                pipelineDefinition = [
                    pipelineType: "docker",
                    enablePublishECR: true,
                    enableGithubRelease: false
                ] << pipelineDefinition
                echo "pipelineDefinition\n${pipelineDefinition}"
                buildApp.process(pipelineDefinition)
                currentBuild.description = "Build: ${env.APP_VERSION}"
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
