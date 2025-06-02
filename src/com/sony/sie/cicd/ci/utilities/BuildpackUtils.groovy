package com.sony.sie.cicd.ci.utilities

import com.sony.sie.cicd.helpers.utilities.JenkinsSteps
import static com.sony.sie.cicd.helpers.utilities.KmjEnv.ECR_HOST_URL

class BuildpackUtils extends JenkinsSteps {
    private static final String DEFAULT_BUILDER_REPO = "engine/cnb-builder-java"
    private static final String DEFAULT_BUILDER_TAG = "release-1.2.3-20231130093251"

    def updateGoAppVersionGitCommit(outputFile, serviceName, appVersion, gitCommit) {
        def fileName= getEnvFile(serviceName)
        if(fileName != "" && fileExists(fileName)){
            def fileContent = readFile(file: fileName)
            fileContent = fileContent.replace('${APP_VERSION}', appVersion)
                    .replace('${GIT_COMMIT}', gitCommit)
            try {
                writeFile(file: outputFile, text: fileContent)
            } catch (Exception e) {
                echo "Failed to write to ${outputFile}: ${e.message}"
            }
        }
    }

    def getBuildpackBuilder() {
        def bpBuilderRepo = DEFAULT_BUILDER_REPO
        def bpBuilderTag = DEFAULT_BUILDER_TAG
        def bpBuilderInfo = readBuilderFile("./buildpack/.builder")
        if (bpBuilderInfo) {
            log.warn "Use bp builder configured in buildpack/.builder: ${bpBuilderInfo}"
            bpBuilderRepo = bpBuilderInfo.name
            bpBuilderTag = bpBuilderInfo.tag
        }
        return "${ECR_HOST_URL}/${bpBuilderRepo}:${bpBuilderTag}"
    }

    def getPackcliConfig(def conf) {
        def packcliConfig = [:]
        def imageList = conf?.repoInfo?.imageList
        if (imageList) {
            for (int i = 0; i < imageList.size(); i++) {
                def image = imageList[i]
                packcliConfig[image.appName] = ["repoPrefix": image.organization]
            }
        } else if (fileExists("packcli-config.yaml")) {
            packcliConfig = readYaml file: "packcli-config.yaml"
        }
        return packcliConfig
    }

    def getEnvFile(String imageName) {
        def fileName = "buildpack/${imageName}.env"
        if (fileExists(fileName)) {
            return fileName
        }
        fileName = "${imageName}.env}"
        if (fileExists(fileName)) {
            return fileName
        }
        return ""
    }

    def readBuilderFile(filePath) {
        if (!fileExists(filePath)) {
            echo "Did not find ${filePath}"
            return null  // File does not exist
        }

        def bpBuilderConfig = readProperties(file: filePath)
        // Check if 'name' and 'tag' keys exist in the bpBuilderConfig
        def name = bpBuilderConfig['name']
        def tag = bpBuilderConfig['tag']

        if (name && tag) {
            return bpBuilderConfig
        } else {
            log.info "Name or tag not found in ${filePath}"
            return null  // 'name' and 'tag' keys not found
        }
    }
}
