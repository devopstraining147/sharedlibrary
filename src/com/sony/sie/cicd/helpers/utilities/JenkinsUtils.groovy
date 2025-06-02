package com.sony.sie.cicd.helpers.utilities

import org.codehaus.groovy.GroovyException
import com.sony.sie.cicd.helpers.enums.TriggerStatus
import com.sony.sie.cicd.helpers.podTemplates.*
import com.sony.sie.cicd.ci.utilities.AWSUtils
import com.sony.sie.cicd.cd.deployProcessors.*

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson

String getBuildStatus() {
    if(currentBuild.result!=null && currentBuild.result != ""){
        return currentBuild.result
    }
    return "SUCCESS"
}

boolean isBuildStatusOK(){
    String buildStatus = getBuildStatus()
    return (buildStatus=="SUCCESS" || buildStatus=="UNSTABLE")
}

boolean isBuildAborted(){
    String buildStatus = getBuildStatus()
    return (buildStatus=="ABORTED" || buildStatus=="NOT_BUILT")
}

// void removeLockedResource(String lockedResourceName){
//     echo "Remove the locked resource of ${lockedResourceName}"
//     def all_lockable_resources = GlobalConfiguration.all().get(org.jenkins.plugins.lockableresources.LockableResourcesManager.class).resources
//     all_lockable_resources.removeAll { it.name.contains(lockedResourceName)}
// }

//load resource file
def loadResource(String sourceFile, String destFile = ''){
    if(destFile =='') destFile = sourceFile
    def request = libraryResource sourceFile
    writeFile file: destFile, text: request
}

//check if file is changed in current build
def isFileChanged(String fileName) {
    def changeLogSets = currentBuild.changeSets
    for (int i = 0; i < changeLogSets.size(); i++) {
        def entries = changeLogSets[i].items
        for (int j = 0; j < entries.length; j++) {
            def entry = entries[j]
            def files = new ArrayList(entry.affectedFiles)
            for (int k = 0; k < files.size(); k++) {
                def file = files[k]
                String filePath = file.path
                if(filePath == fileName){
                    echo "change detected on ${fileName} , executing sync"
                    return true
                }
            }
        }
    }
    return false
}

def waitForDocker(timeoutSeconds = 60, intervalSeconds = 5) {
    while (true) {
        def exitCode = sh(script: "docker version > /dev/null 2>&1; echo \$?", returnStatus: true)

        if (exitCode == 0) {
            echo "Docker is running"
            return true
        }

        echo "Docker is not running yet. Retrying in ${intervalSeconds} sec"
        sleep(time: intervalSeconds, unit: "SECONDS")
    }
}

def nodeIsAvailable() {
    try {
        NODE_NAME
    } catch (Exception ignored) {
        return false
    }
    return true
}

def jenkinsNode(def conf, Closure body) {
    conf = [objTimeout: 120] << conf
    if (conf.objTimeout == null || conf.objTimeout < 0) conf.objTimeout = 120
    int startMillis = System.currentTimeMillis()
    try{
        timeout(conf.objTimeout) {
            // conf.infrastructure = "navigator-cloud"
            // new NavPodTemplates().createNode(conf, body)
            switch(conf.infrastructure) {
            case "kamaji-cloud":
                new KmjPodTemplates().createNode(conf, body)
                break
            case "navigator-cloud":
                conf.infrastructure = "navigator-cloud"
                new NavPodTemplates().createNode(conf, body)
                break
            case "laco-cloud":
                new LacoPodTemplates().createNode(conf, body)
                break
            case "roadster-cloud":
                new RoadsterPodTemplates().createNode(conf, body)
                break
            default:
                throw new GroovyException("The infrastructure field is not provided!")
            }
        }
    } catch (Exception err){
        int timeoutMillis = conf.objTimeout * 60 * 1000
        int endMillis = System.currentTimeMillis()
        if(endMillis - startMillis >= timeoutMillis) {
            currentBuild.result = "FAILURE"
            throw new GroovyException("jenkinsNode Timeout Exception")
        } else {
            if (isBuildAborted()) {
                String msg =  err.getMessage()
                if (!msg) msg = 'Unknown error!'
                echo "jenkinsNode Exception: " + msg
                currentBuild.result = "FAILURE"
            }
            throw err
        }
    }
}

//podTemplate for Kamaji cloud
def kmjNode(def conf, Closure body) {
    conf = [infrastructure: "kamaji-cloud"] << conf
    jenkinsNode(conf, body)
}

//podTemplate for Navigator cloud
def navNode(def conf, Closure body) {
    conf = [infrastructure: "navigator-cloud"] << conf
    jenkinsNode(conf, body)
}

//podTemplate for Laco cloud
def lacoNode(def conf, Closure body) {
    conf = [infrastructure: "laco-cloud"] << conf
    jenkinsNode(conf, body)
}

//podTemplate for Roadster cloud
def roadsterNode(def conf, Closure body) {
    conf = [infrastructure: "roadster-cloud"] << conf
    jenkinsNode(conf, body)
}

String fetchDefaultBranch(String orgName, String repoName) {
    String defaultBranch = new GitUtils().getDefaultBranch(orgName, repoName)

    echo "fetched default branch for repo: ${defaultBranch}"
    if (!defaultBranch) {
        throw new GroovyException("failed to fetch defaultBranch for ${repoName}")
    }
    return defaultBranch
}

def checkoutGitSCM(def repoName = env.REPO_NAME, def branchName = env.BRANCH_NAME, def org = env.ORG_NAME, boolean changelog = true) {
    checkout changelog: changelog,
        scm: [
            $class: 'GitSCM',
            branches: [[name: "${branchName}"]],
            userRemoteConfigs: [[
                url: "git@github.sie.sony.com:${org}/${repoName}.git",
                credentialsId: 'svcacct-snei-ghe-hud-ssh-private-key'
            ]]
        ]
}

boolean isTestRepo(){
    switch (env.REPO_NAME) {
        case "maven-sam-app":
        case "sam-app":
        case "hello-mono":
        case "k8s-helloworld-platform":
        // case "catalyst-example":
        case "catalyst-example-maven":
        case "catalyst-example-cicd":
        case "catalyst-scala":
        case "catalyst-mlmodel-scala":
        case "catalyst-test-bazel":
        case "catalyst-example-gradle":
        case "ci-catalyst":
        case "sample-python":
        case "catalyst-example-docker-compose":
        case "sample-sbt-cicd":
        case "catalyst-example-go-cicd":
        case "catalyst-example-js-cicd":
        case "catalyst-example-ts-cicd":
        case "catalyst-example-go":
            return true
        default:
            return false
    }
}

def removeWhiteSpaces(def myString){
    StringBuilder newString = new StringBuilder(myString.length());
    for (int offset = 0; offset < myString.length();) {
        int codePoint = myString.codePointAt(offset);
        offset += Character.charCount(codePoint);
        // Replace invisible control characters and unused code points
        switch (Character.getType(codePoint))
        {
            case Character.CONTROL:     // \p{Cc}
            case Character.FORMAT:      // \p{Cf}
            case Character.PRIVATE_USE: // \p{Co}
            case Character.SURROGATE:   // \p{Cs}
            case Character.UNASSIGNED:  // \p{Cn}
                break;
            default:
                newString.append(Character.toChars(codePoint));
                break;
        }
    }
    String str = newString.toString()
    return str.trim()
}

void mavenCache(def imageList) {
    if(imageList==null) return
    echo "Fetching maven-cache image from ECR..."
    try {
        for(int i=0; i < imageList.size(); i++) {
            def image = imageList[i]
            sh  """
                docker create --entrypoint /bin/sh 890655436785.dkr.ecr.us-west-2.amazonaws.com/${image.organization}/${image.appName}-cache:latest > /tmp/imageName.txt
                cat /tmp/imageName.txt | xargs -I{} docker cp {}:/layers/engine_maven/cache/repository ${WORKSPACE}
                cp -R ${WORKSPACE}/repository /root/.m2
                """
        }
    } catch (err) {
        echo "Could not find cache-image in UCR, skipping...${err}"
    }
}


@NonCPS
String getRepoUrl() {
    String url = scm.getUserRemoteConfigs()[0].getUrl()
    return "${url}"
}

@NonCPS
String determineRepoName() {
    return getRepoUrl().tokenize('/').last().split("\\.")[0]
}

@NonCPS
String determineOrgName() {
    return getRepoUrl().tokenize('/')[-2]
}

def fetchMvnSecret(def infrastructure, def containerName = "build-tools") {
    container(containerName) {
        return new AWSUtils().fetchSecret('engine/xnav-artifactory-secret')
    }
}

def mavenConfig(def infrastructure, def mavenSecret, def folderName = ["/root/.m2", "/usr/conf"]) {
    def credentialsId = ''
    def templateFileName = 'uks_'
    def passwordTemplateName = 'uks-artifactory-secret'
    def viewFile = ''
    def fileName = "settings.xml"
    def configTemplateFile = "maven_settings/"+templateFileName+fileName
    loadResource(configTemplateFile)
    for (int i = 0; i < folderName.size(); i++) { 
        if(!fileExists(folderName[i])) {
            sh "mkdir -p ${folderName[i]}"
        }
        withEnv(["mavenSecret=${mavenSecret}"]) {
            sh(returnStdout: false, label: "Update Settings Template...",script: """
                #!/bin/sh -e
                set +x
                sed -e \"s/{${passwordTemplateName}}/\${mavenSecret}/\" ${configTemplateFile} > ${folderName[i]}/${fileName}
            """)
       }
    }
    sh "rm -rf ${configTemplateFile}"
}
def gradleConfig(def infrastructure, def mavenSecret) {
    def credentialsId = ''
    def templateFileName = 'uks_'
    def passwordTemplateName = 'uks-artifactory-secret'
    def viewFile = ''
    def folderName = ["~/.gradle"]
    def fileName = "gradle.properties"
    def configTemplateFile = "maven_settings/"+templateFileName+fileName
    loadResource(configTemplateFile)
    for (int i = 0; i < folderName.size(); i++) { 
        if(!fileExists(folderName[i])) {
            sh "mkdir -p ${folderName[i]}"
        }
        withEnv(["mavenSecret=${mavenSecret}"]) {
            sh(returnStdout: false, label: "Update Settings Template...",script: """
                #!/bin/sh -e
                set +x
                sed -e \"s/{${passwordTemplateName}}/\${mavenSecret}/\" ${configTemplateFile} > ${folderName[i]}/${fileName}
            """)
       }
    }
    sh "rm -rf ${configTemplateFile}"
}

def boolean isEngineLibrary(){
    String repoName= "${env.REPO_NAME}".toLowerCase()
    if(repoName){
        def engineRepos=["engine-cd-framework","engine-ci-framework","engine-cicd-utilities"]
        return engineRepos.contains(repoName)
    }else{
        return false
    }
}

TriggerStatus validChangeStatus(def pipelineDefinition) {
    TriggerStatus validChanges = TriggerStatus.NO_FILE_CHANGED
    if(!isAutoTrigger()) return validChanges
    def changeLogSets = currentBuild.changeSets
    // iterate over all commits to find all changes
    String skipCiCdAuthor = "svcacct-sie-kmjsonar"
    String skipJenkinsAuthor = "jenkins"
    for (int i = 0; i < changeLogSets.size(); i++) {
        def entries = changeLogSets[i].items
        // iterate over all changeset to find all entries
        for (int j = 0; j < entries.length; j++) {
            def entry = entries[j]
            if (invalidAuthorCheck(entry, skipCiCdAuthor) || invalidAuthorCheck(entry, skipJenkinsAuthor)){
                if(validChanges == TriggerStatus.NO_FILE_CHANGED) validChanges = TriggerStatus.BY_JENKINS
                continue
            }
            def changedFiles= entry.affectedFiles.path
            echo "changedFiles: $changedFiles"
            if(!isEngineLibrary() && !(changedFiles.empty)){
                //remove all files changed in engine-cd-framework,engine-ci-framework,engine-cicd-utilities
                def skipCiCdPath = [ "vars/","resources/","src/com/sony/sie/cicd/","jenkinsfiles"]
                for (int k = 0; k < skipCiCdPath.size() && !(changedFiles.empty); k++) {
                    changedFiles.removeAll{ it.toLowerCase().startsWith("${skipCiCdPath[k]}")}
                }
                if(changedFiles.empty && (validChanges == TriggerStatus.NO_FILE_CHANGED || validChanges == TriggerStatus.BY_JENKINS)) {
                    validChanges = TriggerStatus.BY_CICD_LIBRARY
                }
            }
            //check config files changed
            if(!(changedFiles.empty)){
                //remove all config files changed
                def configFilePaths = ["performance-test/"]
                if (pipelineDefinition.skipCiPathList) configFilePaths += pipelineDefinition.skipCiPathList
                for ( k = 0; k < configFilePaths.size() && !(changedFiles.empty); k++) {
                    changedFiles.removeAll{ it.toLowerCase().startsWith("${configFilePaths[k].toLowerCase()}")}
                }

                def configFileExtensions = [".md"]
                if ( pipelineDefinition.skipCiFileExtensions) configFileExtensions += pipelineDefinition.skipCiFileExtensions
                for ( k = 0; k < configFileExtensions.size() && !(changedFiles.empty); k++) {
                    changedFiles.removeAll{ it.toLowerCase().endsWith("${configFileExtensions[k].toLowerCase()}")}
                }

                if(changedFiles.empty) {
                    validChanges = TriggerStatus.CONFIG_CHANGE_ONLY
                }
            }
            //check charts config files changed
            if(!(changedFiles.empty)){
                if(pipelineDefinition.helmChartConfigs) {
                    def helmChartList = pipelineDefinition.helmChartConfigs
                    // echo "helmChartList: ${helmChartList}"
                    for (int ii = 0; ii < helmChartList.size(); ii++) {
                        def item = helmChartList[ii]
                        changedFiles.removeAll{ it.toLowerCase().startsWith("${item.helmChartPath}/")}
                        if(changedFiles.empty) {
                            validChanges = TriggerStatus.BY_HELMCHART
                            break
                        }
                    }
                }
            }
            //check if still have other files changed
            if(!(changedFiles.empty)){
                //valid files changed
                return TriggerStatus.BY_FILE_CHANGED
            }
        }
    }
    return validChanges
}

@NonCPS
def getSubmitter() {
    for(def cause : currentBuild.rawBuild.getCauses()) {
        if (cause instanceof hudson.model.Cause.UserIdCause) {
            return cause.getUserName()
        }
    }
}

@NonCPS
def getCauser() {
    String user = 'auto-build'
    try{
        /* The cause.properties.shortDescription will look like:
         * if it is manual trigger: Started by user xxxxx
         * if it is downdream job of kmj-cicd-framework test job: Started by upstream project "KMJ-CICD-TEST/kmj-cicd-framework/PR-409" build number 3
         * if it is trigger by github: Push event to branch master
         */
        def causes = currentBuild.rawBuild.getCauses()
        for(cause in causes) {
            def shortDesc = cause.properties.shortDescription
            echo "${shortDesc}"
            if(shortDesc.contains("engine-ci-framework")) {
                return "engine-ci-framework"
            } else if (shortDesc.contains("Started by user")) {
                return "manual-build"
            }
        }
    }
    catch (Exception err) {
        user = 'auto-build'
    }
    return user
}

boolean isAutoTrigger() {
    return getCauser()=='auto-build'
}

def invalidAuthorCheck (def entry, def skipCiCdAuthor ){
    def authors = entry.author.toString().toLowerCase()
    echo "author=${authors}"
    return authors.contains("${skipCiCdAuthor}")
}

void setParam(String paramName, String paramValue) {
    List<ParameterValue> newParams = new ArrayList<>()
    newParams.add(new StringParameterValue(paramName, paramValue))
    echo "param : ${paramName}, ${paramValue}"
    if(newParams) {
        $build().addOrReplaceAction($build().getAction(ParametersAction.class).createUpdated(newParams))
    }
}

def triggerRemoteJenkinsJob(Map conf) {
    conf = [tokenCredential: 'engine-workflow-jenkins-token', 
            blockBuildUntilComplete: true, abortTriggeredJob: true, enhancedLogging: true,
            parameters: "", pollInterval: 20, remoteJenkinsUrl: "local"] << conf

    log.info "Job conf ${conf}"
    def handle = ''
    if (conf.remoteJenkinsUrl.contains('local')) {
        handle = triggerRemoteJob(
            remoteJenkinsName: 'local',
            job: conf.jobName,
            blockBuildUntilComplete: conf.blockBuildUntilComplete,
            abortTriggeredJob: conf.abortTriggeredJob,
            enhancedLogging: conf.enhancedLogging,
            parameters: conf.parameters,
            pollInterval: conf.pollInterval
        )
    } else { 
        handle = triggerRemoteJob(
            auth: CredentialsAuth(credentials: conf.tokenCredential),
            job: conf.remoteJenkinsUrl,
            blockBuildUntilComplete: conf.blockBuildUntilComplete,
            abortTriggeredJob: conf.abortTriggeredJob,
            enhancedLogging: conf.enhancedLogging,
            parameters: conf.parameters,
            pollInterval: conf.pollInterval
        )
    }
    log.info "triggerRemoteJob response: ${handle}"
    if(conf.blockBuildUntilComplete) {
        String clientTestJobBuildStatus = handle.getBuildStatus().toString()
        String clientTestJobBuildResult = handle.getBuildResult().toString()
        echo "BuildStatus: ${clientTestJobBuildStatus}, BuildResult: ${clientTestJobBuildResult}"
        if (clientTestJobBuildResult != "SUCCESS") {
            currentBuild.result = clientTestJobBuildResult
            if(clientTestJobBuildResult == "ABORTED") {
                throw new GroovyException("the downstream job aborted")
            } else {
                throw new GroovyException("the downstream job failed with job status as ${clientTestJobBuildResult}")
            }
        }

        // retrieve build info
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: conf.tokenCredential,
                          usernameVariable: 'JENKINS_API_USERNAME', passwordVariable: 'JENKINS_API_PASSWORD']]) {
            String targetUrl = handle.getBuildUrl().toString() + 'api/json'
            log.info "Requesting job data from [${targetUrl}]"
            def jobApiScript = 'curl --fail-with-body --user "$JENKINS_API_USERNAME:$JENKINS_API_PASSWORD" ' + targetUrl
            def curlResponse = sh(returnStdout: true, script: jobApiScript)
            def buildInfoJson = readJSON text: curlResponse
            return buildInfoJson
        }
    }
}

def shWrapper(String Command, ReturnStdout = false, EchoCommand = false) {
    def execCommand = (EchoCommand ? '' : '#!/bin/sh -e\nset +x\n') + Command
    if (ReturnStdout) {
        def retval = sh script: execCommand, returnStdout: true
        return retval
    } else {
        def statusCode = sh script: execCommand, returnStatus: true
        if (statusCode != 0) {
            error "** ERROR in shell exec, returned ${statusCode}"
        }
    }
}

def isMainBranch(def branchName = env.BRANCH_NAME){
    return (branchName == "master" || branchName.startsWith("main")) 
}

def k8sAccessConfig(Map conf) {
    switch(conf.infrastructure) {
        case "kamaji-cloud":
            new KmjK8sDeployProcessor().k8sAccessConfig(conf)
            break
        case "navigator-cloud":
            new NavK8sDeployProcessor().k8sAccessConfig(conf)
            break
        case "laco-cloud":
            new LacoK8sDeployProcessor().k8sAccessConfig(conf)
            break
        case "roadster-cloud":
            new RoadsterK8sDeployProcessor().k8sAccessConfig(conf)
            break
    }
}

def skubaSetup() {
    try{
        echo "skuba config ..."
        sh """
            set +e
            cp -R /home/skuba/.skuba/ ~/.skuba
            chown -R root ~/.skuba
            echo -e "aloy_login: true\necr_login: true\neks_login: true\nkks_login: true\nucr_login: true" >> ~/.skuba/config.yaml
            rm -rf ~/.aws
            skuba sync
        """
    } catch (Exception err) {
        echo "skubaSetup failed: ${err.getMessage()}"
    }
}
  
def getConsoleLogContent(String keyword = "", int lines = 10, int index = 0) {
    try {
        def logContent = "${currentBuild.rawBuild.getLog(lines)}"
        if(keyword != "" && index < 3 && !logContent.contains(keyword)) {
            return getConsoleLogContent(keyword, lines, index+1)
        } else {
            return "${logContent}"
        }
    } catch (Exception err) {
        echo "*** Unable to get current job console log ***"
        throw err
    }
    return ""
}

def loadCDDefaultSettings(def infrastructure) {
    String yaml = libraryResource "deployment-default-settings.yaml"
    def configMap = new YamlFileUtils().convertYamlToJson(yaml)
    def defaultConfigMap = configMap[infrastructure]
    echo "Cluster/LineEnv Mapping: ${prettyPrint(toJson(defaultConfigMap))}"
    return defaultConfigMap
}

def getFileFromGithub(def repoName, def branchName, def org, def fileName, def outputFile) {
    container('build-tools') {
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'github-token-username', usernameVariable: 'githubUsername', passwordVariable: 'githubAccessToken']]) {
            sh """
                curl -H \'Authorization: token $githubAccessToken\' \
                    -H \'Accept: application/vnd.github.v3.raw\' \
                    --create-dirs \
                    -o $outputFile \
                    -L \'https://github.sie.sony.com/api/v3/repos/$org/$repoName/contents/$fileName?ref=$branchName\'
            """
            sh "ls -la ${outputFile}"
        }
    }
}

def readHelmValuesFromCiJob(url, chartVersion, localExtractDir = 'extracted-chart') {
    try {
        withCredentials([
                [$class: 'UsernamePasswordMultiBinding', credentialsId: 'engine-workflow-jenkins-token',
                 usernameVariable: 'JENKINS_API_USERNAME', passwordVariable: 'JENKINS_API_PASSWORD']
        ]) {
            url = url + "artifact/${chartVersion}-isoperf.tgz"
            def tgzFileName = "${chartVersion}-isoperf.tgz"

            // Download the .tgz artifact
            sh 'curl -sSL --user "$JENKINS_API_USERNAME:$JENKINS_API_PASSWORD" ' + url + ' -o ' + tgzFileName
            sh 'mkdir -p ' + localExtractDir
            untar file: tgzFileName, dir: localExtractDir

            echo "Extracted Helm chart to: ${localExtractDir}"
            return "${localExtractDir}/${env.REPO_NAME}"
        }
    } catch (Exception e) {
        echo "error whilst fetching file from jenkins ${e.getMessage()}"
        return ""
    }
}

def fetchNpmSecret(def containerName = "build-tools", def secretName = 'engine/xnav-artifactory-npm-secret') {
    container(containerName) {
        return new AWSUtils().fetchSecret(secretName).trim()
    }
}

def fetchSiePrivateNpmSecret(def containerName = "build-tools", def secretName = 'engine/sie-private-artifactory-npm-secret') {
    container(containerName) {
        return new AWSUtils().getAWSSecret(secretName, secretName).trim()
    }
}

def npmConfig(def npmSecret, def sieNpmToken, def folderName = ["/root"]) {
    def credentialsId = ''
    def passwordTemplateNameArtifactory = '{uks-artifactory-npm-secret}'
    def npmRcFileName = ".npmrc"
    def npmRcTemplateFile = "maven_settings/.npmrc"
    def sieNpmTokenTemplateNameArtifactory = '{uks-artifactory-sie-npm-token}'
    loadResource(npmRcTemplateFile)
    def tempFile = "sie-npmrc.txt"
    for (int i = 0; i < folderName.size(); i++) {
        if(!fileExists(folderName[i])) {
            sh "mkdir -p ${folderName[i]}"
            echo "==>mkdir -p ${folderName[i]}"
        }
        withEnv(["npmSecret=${npmSecret}", "sieNpmToken=${sieNpmToken}"]) {
            sh(returnStdout: false, label: "Update .npmrc Template...", script: """
                #!/bin/sh -e
                set +x
                sed -e \"s/${passwordTemplateNameArtifactory}/\${npmSecret}/\" ${npmRcTemplateFile} > ${tempFile}
                sed -e \"s/${sieNpmTokenTemplateNameArtifactory}/\${sieNpmToken}/\" ${tempFile} > ${folderName[i]}/${npmRcFileName}
            """)
        }
    }

    sh """
        rm -rf ${npmRcTemplateFile}
        rm -rf ${tempFile}
        """
}

return this
