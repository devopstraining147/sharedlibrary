
package com.sony.sie.cicd.helpers.utilities

import org.codehaus.groovy.GroovyException

def getSbtCredential() {
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: KmjEnv.ENGINE_CHARTS_CREDENTIAL_ID, usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
        def cred = '''realm=Artifactory Realm
            host=artifactory.sie.sony.com
            user=$USERNAME
            password=$PASSWORD'''

        def repositories = """[repositories]
            local
            my-ivy-proxy-releases: https://artifactory.sie.sony.com/artifactory/sie-maven/, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]
            my-maven-proxy-releases: https://artifactory.sie.sony.com/artifactory/sie-maven/"""

        sh """mkdir -p /root/.sbt
            echo "${repositories}" > /root/.sbt/repositories
            chmod 600 /root/.sbt/repositories
            echo "${cred}" > /root/.sbt/.credentials  
            chmod 600 /root/.sbt/.credentials"""
    }
}

def uploadFiles(def repo, def credentialsId, def pattern = "*.tar.gz") {
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: credentialsId, usernameVariable: 'username', passwordVariable: 'password']]) {
        def url = KmjEnv.ART_URL + repo
        def files = findFiles(glob: "${pattern}")
        for (int i = 0; i < files.size(); i++) {
            def filepath = "${files[i]}"
            sh """
                curl -u ${username}:${password} -X PUT "${url}/${filepath}" -T ${filepath}
            """
        }
    }
}

def downloadFiles(def repo, def credentialsId, def files) {
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: credentialsId, usernameVariable: 'username', passwordVariable: 'password']]) {
        for (int i = 0; i < files.size(); i++) {
            sh """
                curl -sSf -u ${username}:${password} -O '${KmjEnv.ART_URL}${repo}/${files[i]}'
            """
        }
    }
}

def uploadFile(def url, def credentialsId, def filepath) {
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: credentialsId, usernameVariable: 'username', passwordVariable: 'password']]) {
        def result = sh script: "curl -o /dev/null -s -w \"%{http_code}\n\" -u ${username}:${password} -X PUT ${url}/${filepath} -T ${filepath}", returnStdout: true
        echo "${result}"
        return result
    }
}

def downloadFile(def url, def credentialsId, def filepath) {
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: credentialsId, usernameVariable: 'username', passwordVariable: 'password']]) {
        sh """
            curl -sSf -u ${username}:${password} -O '${url}/${filepath}'
        """
    }
}

def getFileInfo(def repo, def credentialsId, def filepath) {
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: credentialsId, usernameVariable: 'username', passwordVariable: 'password']]) {
        String cmd = "curl -s -k -D- -X GET -u ${username}:${password} -O '${KmjEnv.ART_URL}${repo}/${filepath}'"
        return sh(returnStdout: true, script: cmd)
    }
}

def publishHelmChartToArtifactory(def conf) {
    HelmUtils helmUtils = new HelmUtils()
    def helmChartList = conf.helmChartConfigs
    echo "helmChartList: ${helmChartList}"
    for (int i = 0; i < helmChartList.size(); i++) {
        def item = helmChartList[i]
        echo "Looking for Helm Chart in: ${WORKSPACE}/${env.REPO_NAME}/${item.helmChartPath}"
        dir("${WORKSPACE}/${env.REPO_NAME}/${item.helmChartPath}") {
            String fileName = "Chart.yaml"
            if(fileExists(fileName)){
                echo "Found Helm Chart: ${WORKSPACE}/${env.REPO_NAME}/${item.helmChartPath}/${fileName}"
                def updatedFiles = []
                if (helmUtils.updateChartVersion4File(fileName, env.APP_VERSION, env.CHART_VERSION)) {
                    updatedFiles.add(fileName)
                }
                fileName = "values.yaml"
                if (helmUtils.updateChartVersion4File(fileName, env.APP_VERSION, env.CHART_VERSION)) {
                    updatedFiles.add(fileName)
                }
                def files = findFiles(glob: "values-*.yaml")
                for (int ii = 0; ii < files.size(); ii++) {
                    fileName = files[ii]
                    if (helmUtils.updateChartVersion4File(fileName, env.APP_VERSION, env.CHART_VERSION)) {
                        updatedFiles.add(fileName)
                    }
                }
                if(updatedFiles != []) {
                    packageAndUploadHelmChart() 
                }
            } else {
                echo "${item.helmChartPath}/Chart.yaml does not exist"
            }
        }
    }
}

def packageAndUploadHelmChart() {
    String fileName = "Chart.yaml"
    def chartMap = readYaml file: fileName
    def artifactory = [path: chartMap.name, repository: KmjEnv.ENGINE_CHARTS_REPO_URL, credentialsId: "engine-artifactory-access"]
    def artifactoryFileName = "${chartMap.name}-${chartMap.version}.tgz"
    if(chartMap.dependencies) {
        boolean updateChart = false
        if(chartMap.apiVersion == "v1") {
            updateChart = true
            chartMap.apiVersion = "v2"
        }
        for (int i = 0; i < chartMap.dependencies.size(); i++) {
            def item = chartMap.dependencies[i]
            if(chartMap.dependencies[i].repository.contains("engine-charts")){
                updateChart = true
                chartMap.dependencies[i].repository = artifactory.repository
            }
        }
        if(updateChart) {
            fileName = "Chart.yaml"
            echo "update ${fileName}"
            sh "rm ${fileName}"
            writeYaml file: fileName, data: chartMap
            sh "cat ${fileName}"
        }
    } else if (fileExists("requirements.yaml")) {
        fileName = "requirements.yaml"
        def requirementsMap = readYaml file: "requirements.yaml"
        boolean updateChart = false
        for (int i = 0; i < requirementsMap.dependencies.size(); i++) {
            def item = requirementsMap.dependencies[i]
            if(requirementsMap.dependencies[i].repository.contains("engine-charts")){
                updateChart = true
                requirementsMap.dependencies[i].repository = artifactory.repository
            }
        }
        if(updateChart) {
            echo "update ${fileName}"
            sh "rm ${fileName}"
            writeYaml file: fileName, data: requirementsMap
            sh "cat ${fileName}"
        }
    }
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: artifactory.credentialsId, usernameVariable: 'username', passwordVariable: 'password']]) {
        sh """
            helm repo add engine-helm-virtual ${artifactory.repository} --username ${username} --password ${password}
            helm package . -u
            ls -la
        """
    }
    if(fileExists(artifactoryFileName)) {
        uploadFile("${artifactory.repository}/${artifactory.path}", artifactory.credentialsId, artifactoryFileName) 
    } else {
        currentBuild.result = "FAILURE"
        ansi_echo "The ${artifactoryFileName} file can not be generated! Please check the helm chart settings.", 31
        new GroovyException("The ${artifactoryFileName} file can not be generated! Please check the helm chart settings.")
    }
}

void ansi_echo(String txt, Integer color = 34) {
    //color code: black: 30, red: 31, green: 32, yellow: 33, blue: 34, purple: 35
    echo "\033[01;${color}m ${txt}...\033[00m"
}

return this
