package com.sony.sie.cicd.helpers.utilities

import org.codehaus.groovy.GroovyException

def getReplicasFromFile(String fileName, String key){
    int replicas=0
    if(fileExists(fileName)) {
        def data = readYaml file: fileName
        if(data.replicas) {
            replicas = Integer.valueOf(data.replicas)
        } else if(data[key] && data[key].replicas){
            replicas = Integer.valueOf(data[key].replicas)
        }
    }
    return replicas
}

String getAppVersionFromFile(String filePath='') {
    String fileName=filePath==''?"Chart.yaml":"${filePath}/Chart.yaml"
    echo "Get app version from file in ${fileName}"
    return getValue(fileName, 'appVersion')
}

String getChartVersionFromFile(String filePath='') {
    String fileName=filePath==''?"Chart.yaml":"${filePath}/Chart.yaml"
    echo "Get chart version from file in ${fileName}"
    return getValue(fileName, 'version')
}

String getChartVersion(String workDir=env.HELM_CHART_PATH) {
    String version = getValue("${workDir}/Chart.yaml", 'version')
    //echo "chart version: ${version}"
    if (version == null || version == '') {
        throw new GroovyException("The chart version can not be found in ${workDir}/Chart.yaml!")
    }
    return version
}

String getNewChartVersion(String workDir=env.HELM_CHART_PATH, String timestamp = env.VERSION_TIMESTAMP) {
    String version = getChartVersion(workDir)
    return new CICDUtils().createNewChartVersion(version, timestamp)
}

String getNewAppVersion(String workDir=env.HELM_CHART_PATH) {
    if(env.APP_VERSION == '') {
        env.APP_PREV_VERSION = getAppVersion(workDir)
        env.APP_VERSION = new CICDUtils().createNewAppVersion(env.APP_PREV_VERSION, env.VERSION_TIMESTAMP)
    }
    return env.APP_VERSION
}

String getAppVersion(String workDir=env.HELM_CHART_PATH) {
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

def loadHelmValuesFile(String clusterId, int initReplicas, String deploymentStrategy){
    dir("${env.REPO_WORKDIR}/${env.HELM_CHART_PATH}/config"){
        String fileName = "values-${clusterId}.yaml"
        def json = readYaml file: fileName
        def data = []
        if(initReplicas == null || initReplicas<1) initReplicas = 1
        json.each { key, value ->
            if(key != "global" && key != "rules" ) {
                def desiredReplicas = null
                int hpaMaxReplicas = 0
                int hpaMinReplicas = 0
                boolean enableHPA = false
                if(value.autoscaling && value.autoscaling.enabled) {
                    if (deploymentStrategy != "rolling") {
                        if (value.autoscaling.minReplicas == null)
                            throw new GroovyException("The minReplicas is not set for the service of ${key} in ${fileName}!")
                        else if (value.autoscaling.minReplicas < 1)
                            throw new GroovyException("The minReplicas is set to ${value.autoscaling.minReplicas} for the service of ${key} in ${fileName}!")
                    }
                    hpaMinReplicas = value.autoscaling.minReplicas
                    if (deploymentStrategy != "rolling") {
                        if (value.autoscaling.maxReplicas == null)
                            throw new GroovyException("The maxReplicas is not set for the service of ${key} in ${fileName}!")
                        else if (value.autoscaling.maxReplicas < 1)
                            throw new GroovyException("The maxReplicas is set to ${value.autoscaling.maxReplicas} for the service of ${key} in ${fileName}!")
                    }
                    hpaMaxReplicas = value.autoscaling.maxReplicas
                    desiredReplicas = hpaMaxReplicas
                    enableHPA = true
                } else if (value.replicas != null) {
                    desiredReplicas = value.replicas
                }
                if(desiredReplicas != null) {
                    int iReplicas = initReplicas > desiredReplicas ? desiredReplicas : initReplicas
                    data.add([name: key, desiredReplicas: desiredReplicas, initReplicas: iReplicas, hpaMaxReplicas: hpaMaxReplicas, hpaMinReplicas: hpaMinReplicas, enableHPA: enableHPA])
                } else if (deploymentStrategy == "rolling") {
                    data.add([name: key, desiredReplicas: initReplicas, initReplicas: initReplicas])
                } else {
                    echo "The replicas is not set for the service of ${key} in ${fileName}!"
                    throw new GroovyException("The replicas is not set for the service of ${key} in ${fileName}!")
                }
            }
        }
        if(data==[] && deploymentStrategy != "rolling") throw new GroovyException("Can not read config data from ${fileName}!")
        echo "$data"
        return data
    }
}

def updateChartFileVersion(String filename, String appVersion, String chartVersion=''){
    def logMessage = "updating file: ${filename}"
    try {
        def excludeImages = "raptor/grpc-cli"
        something_changed = false
        if (chartVersion == '') chartVersion = env.CHART_VERSION
        def data = readYaml file: filename
        if (!(data == null || data == [])) {
            if (filename.contains('values')) {
                if (appVersion != null && appVersion != "") {
                    data.each { topKey, topValues -> // key, Values.*
                        if(topKey == "image" && topValues instanceof Map && topValues.tag != appVersion && topValues.repository && !excludeImages.contains("${topValues.repository}")) {
                            container("build-tools") {
                                line = sh(script: "yq --header-preprocess=false '.image.tag|line' ${filename}", returnStdout: true).trim()
                                sh "sed -i -e '${line} s/${topValues.tag}/${appVersion}/g' ${filename}"
                            }
                        } else if(topKey != "global" && topValues instanceof Map && topValues.image && topValues.image instanceof Map && topValues.image.tag) {
                            if(topValues.image.tag != appVersion && topValues.image.repository && !excludeImages.contains("${topValues.image.repository}")){
                                container("build-tools") {
                                    line = sh(script: "yq --header-preprocess=false '.${topKey}.image.tag|line' ${filename}", returnStdout: true).trim()
                                    sh "sed -i -e '${line} s/${topValues.image.tag}/${appVersion}/g' ${filename}"
                                }
                            }
                        }
                    }
                }
            } else if (filename.contains("Chart")) {
                if (chartVersion != null && chartVersion != "" && data.version != chartVersion) {
                    container("build-tools") {
                        line = sh(script: "yq --header-preprocess=false '.version|line' ${filename}", returnStdout: true).trim()
                        sh "sed -i -e '${line} s/${data.version}/${chartVersion}/g' ${filename}"
                    }
                    something_changed = true
                    logMessage += "\nupdated chart version to: ${chartVersion}"
                }
                if (appVersion != null && appVersion != "" && data.appVersion != appVersion) {
                    container("build-tools") {
                        line = sh(script: "yq --header-preprocess=false '.appVersion|line' ${filename}", returnStdout: true).trim()
                        sh "sed -i -e '${line} s/${data.appVersion}/${appVersion}/g' ${filename}"
                    }
                    logMessage += "\nupdated chart appVersion to: ${appVersion}"
                    something_changed = true
                }
                if(something_changed && data.tillerVersion) {
                    container("build-tools") {
                        line = sh(script: "yq --header-preprocess=false '.tillerVersion|line' ${filename}", returnStdout: true).trim()
                        sh "sed -i -e '${line} s/\"//g' ${filename}"
                        sh "sed -i -e \"${line} s/\'//g\" ${filename}"
                        sh "sed -i -e '${line} s/${data.tillerVersion}/\"${data.tillerVersion}\"/g' ${filename}"
                    }
                    logMessage += "\ndata formatted tillerVersion: ${data.tillerVersion}"
                }
            }
        } else {
            logMessage += "\n${filename} was empty"
        }
    } catch (Exception err) {
      echo logMessage
      String msg = err.getMessage()
      throw new GroovyException("[updateChartFileVersion]: read/write file ${filename} failed: ${msg}")
    }
    if(!something_changed) {
        logMessage += "\nnothing changed for ${filename}"
    }
    echo logMessage
    return something_changed
}

def updateChartVersion4File(String fileName, String appVersion, String chartVersion){
    if(fileExists(fileName)) {
        updateChartFileVersion(fileName, appVersion, chartVersion)
        return true
    }
    return false
}

String getValue(String fileName, String keyword){
    if(fileExists(fileName)) {
        def data = readYaml file: fileName
        if(data == null || data==[])
            return ''
        if(data[keyword] != null) return data[keyword]
    }
    return ''
}

void setValue(String fileName, String keyword, String newValue){
    if(fileExists(fileName)) {
        def data = readYaml file: fileName
        if(data && data!=[]) {
            data[keyword] = newValue
            sh "rm ${filename}"
            writeYaml file: filename, data: data
        }
    }
}

String findValue(String fileName, String keyword){
    def files = findFiles(glob: "**/${fileName}")
    String value = ''
    for(int i=0; i < files.size(); i++){
        String filepath = files[i]
        value = getValue(filepath, keyword)
        if(value!='') return value
    }
    return ''
}

def runHelmCommand(Map conf, int retryIndex = 1){
    conf = [returnStdout: false, returnStatus: false] << conf
    String returnResult = ""
    container(conf.clusterId){
        try {
            if(conf.returnStdout){
                returnResult = sh(returnStdout: true, script: conf.command)
                echo "${returnResult}"
                return returnResult
            } else if (conf.returnStatus) {
                returnResult = sh(returnStatus: true, script: conf.command)
                echo "Status: ${returnResult}"
                return returnResult
            } else {
                String command = env.K8S_DEBUG == "TRUE" ? conf.command.replace("helm", "helm -v=8").replace("kubectl", "kubectl -v=8") : conf.command
                returnResult = sh(returnStdout: true, script: command)
                echo "${returnResult}"
            }
        } catch (Exception err) {
            echo "${returnResult}"
            if(retryIndex <= 16 && returnResult.contains("Timeout")){
                int sleepTime = 30 * retryIndex
                echo "Retry on error in ${sleepTime} seconds"
                sleep sleepTime
                runHelmCommand(conf, retryIndex*2)
            } else {
                throw err
            }
        }
    }
}

def updateHelmFilesForService(def pipelineDefinition, String appVersion, String chartVersion) {
    echo "updating files"
    try {
        def updatedFiles = []
        HelmUtils helmUtils = new HelmUtils()
        if(pipelineDefinition.helmChartConfigs) {
            def helmChartList = pipelineDefinition.helmChartConfigs
            for (int i = 0; i < helmChartList.size(); i++) {
                def fileName = "${helmChartList[i].helmChartPath}/Chart.yaml"
                if (helmUtils.updateChartVersion4File(fileName, appVersion, chartVersion)) {
                    updatedFiles.add(fileName)
                }
                fileName = "${helmChartList[i].helmChartPath}/values.yaml"
                if (pipelineDefinition.repoInfo.type != "flink") {
                    if (helmUtils.updateChartVersion4File(fileName, appVersion, chartVersion)) {
                        updatedFiles.add(fileName)
                    }
                }
            }
        }
        echo "updated files: ${updatedFiles}"
    } catch (Exception e) {
        echo "error whilst updating files: ${e.getMessage()}"
    }
}

// def getHelmKubeHelper(String clusterId, String namespace){
//     return new Helm3KubeHelper(clusterId, namespace)
// }

void helm3Validation(String clusterId){
    String fileName = fileExists("requirements.yaml") ? "requirements.yaml" : "Chart.yaml"
    def data = readYaml file: fileName
    if (data.dependencies != null) {
        for (int i = 0; i < data.dependencies.size(); i++) {
            def item = data.dependencies[i]
            if (item.name == "jar-app") {
                item.version.findAll(/(\d+)\.(\d+)\.(\d+)/) { full ->
                    int major = full[1].toInteger()
                    int minor = full[2].toInteger()
                    if (!(major > 4 || major == 4 && minor >= 7)) {
                        throw new GroovyException("Please upgrade the jar-app version of the dependencies in ${fileName} to 4.7.0 or higher!")
                    }
                }
            }
        }
    }
}

return this

