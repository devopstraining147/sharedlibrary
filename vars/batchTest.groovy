import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import org.codehaus.groovy.GroovyException

def call() {
    jenkinsUtils = new JenkinsUtils()

    try {
        timestamps {
            ansiColor('xterm') {
                echo "Starting Jenkins job ..."
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

void setEnv() {
    env.GIT_URL = jenkinsUtils.getRepoUrl()
    env.ORG_NAME = jenkinsUtils.determineOrgName()
    env.REPO_NAME = jenkinsUtils.determineRepoName()
    env.BRANCH_NAME = (env.CHANGE_ID ? env.CHANGE_BRANCH : env.BRANCH_NAME)
    env.REPO_WORKDIR = env.REPO_NAME
}

