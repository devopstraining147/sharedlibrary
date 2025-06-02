package com.sony.sie.cicd.ci.utilities

import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import org.codehaus.groovy.GroovyException

def waitForDockerDaemon(timeoutSeconds = 60, intervalSeconds = 5) {
    try {
        timeout(time: timeoutSeconds, unit: 'SECONDS') {
            while (true) {
                // Check if Docker is running
                def exitCode = sh(script: "docker version > /dev/null 2>&1; echo \$?", returnStatus: true)
                if (exitCode == 0) {
                    echo "Docker is running"
                    return true
                }
                echo "Docker is not running yet. Retrying in ${intervalSeconds} seconds..."
                sleep(intervalSeconds)
            }
        }
    } catch (err) {
        echo "Docker daemon did not start within ${timeoutSeconds} seconds."
        return false
    }
}
