import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import org.codehaus.groovy.GroovyException

def call(def conf, Closure body) {
    JenkinsUtils jenkinsUtils = new JenkinsUtils()
    ansiColor('xterm') {
        try {
            timestamps {
                echo "Starting Jenkins Job..."
                jenkinsUtils.jenkinsNode(conf, body)
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
}


