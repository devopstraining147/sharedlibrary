package com.sony.sie.cicd.cd.utilities

import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import org.codehaus.groovy.GroovyException

String getChartVersion(String workDir=env.APP_CHART_PATH) {
    String version = getValue("${workDir}/Chart.yaml", 'version')
    //ansi_echo "chart version: ${version}"
    if (version == null || version == '') {
        throw new GroovyException("The chart version can not be found in ${workDir}/Chart.yaml!")
    }
    return version
}

String getAppVersion(String workDir=env.APP_CHART_PATH) {
    String version = getValue("${workDir}/Chart.yaml", 'appVersion')
    if (version == null || version == '') {
        throw new GroovyException("The app version can not be found in ${workDir}/Chart.yaml!")
    }
    return version
}

@NonCPS
String formatChartVersion(String inputVersion){
    String chartVersion = ''
    if(inputVersion!=null && inputVersion!=''){
        inputVersion.findAll(/(\d+)\.(\d+)\.(\d+)\-(\d+)/) { full ->
            chartVersion = full[0]
        }
    }
    return chartVersion
}

String getValue(String fileName, String keyword){
    if(fileExists(fileName)) {
        def data = readYaml file: fileName
        if(data == null || data==[])
            return ''
        if(data[keyword] != null) return data[keyword]
    } else {
        echo "${fileName} not found!"
        throw new GroovyException("${fileName} not found!")
    }
    return ''
}

def runHelmCommand(Map conf, int retryIndex = 1){
    conf = [returnStdout: false, returnStatus: false, returnOnFail: false] << conf
    String returnResult = ""
    if (conf.returnOnFail) conf.command += '|| exit 0'
    container(conf.clusterId){
        try {
            if(conf.returnStdout){
                returnResult = sh(returnStdout: true, script: "${conf.command}").trim()
                ansi_echo "${returnResult}"
                return returnResult
            } else if (conf.returnStatus) {
                returnResult = sh(returnStatus: true, script: conf.command)
                ansi_echo "Status: ${returnResult}"
                return returnResult
            } else {
                String command = env.K8S_DEBUG == "TRUE" ? conf.command.replace("helm", "helm -v=8").replace("kubectl", "kubectl -v=8") : conf.command
                returnResult = sh(returnStdout: true, script: command)
                ansi_echo "${returnResult}"
            }
        } catch (Exception err) {
            ansi_echo "${returnResult}", 31
            if(retryIndex <= 16 && returnResult.contains("Timeout")){
                int sleepTime = 30 * retryIndex
                ansi_echo "Retry on error in ${sleepTime} seconds", 31
                sleep sleepTime
                runHelmCommand(conf, retryIndex*2)
            } else {
                throw err
            }
        }
    }
}

def getHelmKubeHelper(String clusterId, String namespace){
    return new Helm3KubeHelper(clusterId, namespace)
}

void ansi_echo(String txt, Integer color = 34) {
    //color code: black: 30, red: 31, green: 32, yellow: 33, blue: 34, purple: 35
    echo "\033[01;${color}m ${txt}...\033[00m"
}

def createNamespace(Map conf) {
    echo "Running createNamespace: ${conf.namespace}"
    new JenkinsUtils().loadResource('create-namespace.yaml')
    runHelmCommand([command: "sed 's/NAMESPACE/${conf.namespace}/g' create-namespace.yaml | skuba kubectl apply -f -"] << conf)
    String command = "skuba kubectl label namespace ${conf.namespace}"
    if(conf.istio_injection){
        command += " istio-injection=enabled"
    } else {
         command += " istio-injection=disabled"
    }
    runHelmCommand([command: command] << conf)
}

def deleteNamespace(Map conf){
    echo "Running deleteNamespace: ${conf.namespace}"
    new JenkinsUtils().loadResource('create-namespace.yaml')
    runHelmCommand([command: "sed 's/NAMESPACE/${conf.namespace}/g' create-namespace.yaml | skuba kubectl delete -f -"] << conf)
}

return this
