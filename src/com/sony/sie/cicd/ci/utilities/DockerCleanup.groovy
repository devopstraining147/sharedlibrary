
package com.sony.sie.cicd.ci.utilities

private def runDockerScript(String strScript) {
    try {
        return sh(returnStdout: true, script: strScript).trim()
    } catch (Exception err) {
        echo "runDockerScript failed: " + err.getMessage()
        echo "Docker Script: ${strScript} "
        return ""
    }
}

private def runDockerCommand(String command) {
    try {
        sh "${command}"
    } catch (Exception err) {
        echo "runDockerCommand failed: " + err.getMessage()
        echo "Docker Command: ${command} "
    }
}

private def getDockerContainers() {
    String temp = runDockerScript("docker ps -a")
    String allContainers = ""
    if(temp && temp != "") {
        def data = temp.split("\n")
        if (data) {
            for (int i = 1; i < data.length; i++) {
                if (!isExcludedContainer(data[i])) {
                    def arr = data[i].trim().split()
                    if (arr && !allContainers.contains(arr[0])) {
                        allContainers += " " + arr[0]
                    }
                }
            }
        }
    }
    return allContainers
}

private boolean isExcludedContainer(def container) {
    if (container.contains("jenkins-slave") || container.contains("CONTAINER") || container.contains("k8s_")){
        return true
    }
    return false
}

private def getDockerImages() {
    String temp = runDockerScript("docker images | grep -v 'jenkins-slave' | grep -v 'kamaji-dtr' | grep -v 'dockerhub' | grep -v " +
            "'registry.docker.sie.sony.com/raptor/tomcat' | grep -v 'gcr.io' | grep -v 'quay.io' | grep -v 'cloudpassage' ")
    String allImages = ""
    if(temp && temp != "") {
        def data = temp.split("\n")
        if (data) {
            for (int i = 1; i < data.length; i++) {
                def arr = data[i].trim().split()
                if (arr && !allImages.contains(arr[2])) {
                    allImages += " " + arr[2]
                }
            }
        }
    }
    return allImages
}

def getImagesQualys() {
    String temp = runDockerScript("docker images | grep ${env.VERSION_TIMESTAMP}")
    String allImages = ""
    if (temp && temp != "") {
        def data = temp.split("\n")
        if (data) {
            for (int i = 0; i < data.length; i++) {
                def arr = data[i].trim().split()
                if (arr && !allImages.contains(arr[2])) {
                    if(allImages == "") {
                        allImages = arr[2]
                    } else {
                        allImages += "," + arr[2]
                    }
                }
            }
        }
    }
    return allImages
}

def getDockerNetworkIds() {
    String keyword = "bridge"
    String temp = runDockerScript("docker network ls | grep bridge")
    String allNetworkIds = ""
    if(temp && temp != "") {
        def data = temp.split("\n")
        if (data) {
            for (int i = 0; i < data.length; i++) {
                def arr = data[i].trim().split()
                if (arr && arr[1].indexOf(keyword) < 0 && arr[0].indexOf("NETWORK") < 0) {
                    if (!allNetworkIds.contains(arr[0])) allNetworkIds += " " + arr[0]
                }
            }
        }
    }
    return allNetworkIds
}
def getDockerVolumes() {
    String strVolumes = runDockerScript("docker volume ls -qf dangling=true")
    if (strVolumes && strVolumes != "") {
        strVolumes = strVolumes.replaceAll("\n", " ")
        return strVolumes
    }
    return ""
}

void process(){
    container('docker-compose'){
        dockerCleanup()
    }
}

def dockerCleanup() {
    try {
        startMillis = System.currentTimeMillis()
        timeoutDockerPsMillis = 180000
        timeout(time: timeoutDockerPsMillis, unit: 'MILLISECONDS') {
            //remove all containers
            String allContainers = getDockerContainers()
            if (allContainers != "") {
                runDockerCommand "docker rm -v -f ${allContainers}"
            }

            //Remove all images
            String strImages = getDockerImages()
            if (strImages != "") {
                runDockerCommand "docker rmi -f $strImages"
            }

            //Deleting the unwanted volumes
            String strVolumes = getDockerVolumes()
            if (strVolumes != "") {
                runDockerCommand "docker volume rm ${strVolumes}"
            }

            //Remove Networks
            String allNetworkIds = getDockerNetworkIds()
            if (allNetworkIds != "") {
                runDockerCommand "docker network rm $allNetworkIds"
            }
        }
    } catch (Exception err) {
        endMillis = System.currentTimeMillis()
        if (endMillis - startMillis >= timeoutDockerPsMillis) {
            echo 'docker ps timeout'
        } else {
            echo "dockerCleanup failed: " + err.getMessage()
        }
    }
}

return this

