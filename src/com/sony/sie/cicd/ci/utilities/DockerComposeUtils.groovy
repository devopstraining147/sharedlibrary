package com.sony.sie.cicd.ci.utilities

import static com.sony.sie.cicd.helpers.utilities.KmjEnv.SONARQUBE_PROD_URL
import static com.sony.sie.cicd.helpers.utilities.KmjEnv.SONAR_CREDENTIALS_ID
import com.sony.sie.cicd.helpers.api.SonarAPI
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import org.codehaus.groovy.GroovyException

def setupProperties(Map conf) {
    env.DOCKER_COMPOSE_PREFIX = conf.composePrefix
    if (env.CHANGE_ID) {
        env.githubPRNumber = env.CHANGE_ID
        env.branchName = env.CHANGE_BRANCH
        env.sonarAnalysisMode = "preview"
    } else {
        env.githubPRNumber = ''
        env.branchName = env.BRANCH_NAME
        env.sonarAnalysisMode = "publish"
    }
    env.BRANCH_NAME = env.branchName
    env.sonarHostUrl = SONARQUBE_PROD_URL
    env.buildType = 'ci'
    env.RAPTOR_IMAGES_PATH = '../raptor-docker-images'
    env.githubAccessToken = ''
    env.sonarAccessToken = ''
    env.GIT_IMAGES_BASE = "../raptor-docker-images/"
}

def process(def componentTest) {
    def conf = [composePrefix: "docker-compose -f docker-compose.yml -f docker-compose.jenkins.yml",
        workDir: env.REPO_WORKDIR, composeSubDir: ""] << componentTest
    if(conf.composeSubDir && conf.composeSubDir != "") {
        conf.workDir += "/" + conf.composeSubDir
    }
    def userId = 500
    def groupId = 500
    if(conf.userId) {
        userId = conf.userId
        groupId = conf.groupId
    }
    conf.composePrefix = "UID=${userId} GID=${groupId} " + conf.composePrefix
    setupProperties(conf)
    DOCKER_COMPOSE_PREFIX = conf.composePrefix
    STASH_KAMAJI_CASSANDA = conf.STASH_KAMAJI_CASSANDA
    jenkinsUtils =  new JenkinsUtils()
    dir(conf.workDir){
        if (conf.preparation) sh "${conf.preparation}"
        sh """
            set +e
            export GIT_IMAGES_BASE=${env.GIT_IMAGES_BASE}
            export GIT_COMMIT=${env.GIT_COMMIT}
            mkdir pokemon
            chown -R ${userId}:${groupId} ../*
        """
        startComponentTest conf
        new DockerCleanup().process()
        releaseContainer()
    }
}

void checkoutRepo(def repoName, def branchName = 'master', def org = env.ORG_NAME, boolean changelog=false){
    checkoutRepo repoName: repoName, branchName: branchName, org: org, changelog: changelog
}

void checkoutRepo(Map conf){
    //params: repoName, branchName, org, workDir
    conf = [branchName: 'master', org: env.ORG_NAME, changelog: false] << conf
    if(!conf.workDir) conf.workDir = conf.repoName
    dir(conf.workDir) {
        checkoutGitSCM(conf.repoName, conf.branchName, conf.org, conf.changelog)
    }
}

void checkoutGitSCM(def repoName, def branchName = 'master', def org = env.ORG_NAME, boolean changelog=false) {
    new JenkinsUtils().checkoutGitSCM(repoName, branchName, org, changelog)
}

def startComponentTest(Map conf) {
    echo "INFO: -- Starting component test --"
    try {
        for(int i=0; i < conf.containers.size(); i++) {
            def item = conf.containers[i]
            this."${item.method}" item
        }
    } catch (Exception err) {
        echo "startComponentTest(...) failed: " + err.getMessage()
        throw err
    } finally {
        getTestReports()
        archiveContainerLogs()
    }
}

def getTestReports() {
    try {
        step([$class: 'JUnitResultArchiver', allowEmptyResults: true, testResults: '**/junitreports/TEST-*.xml'])
    } catch (Exception err) {
        echo "getTestReports failed: "+ err.getMessage()
    }
}

def releaseContainer(String composePrefix = DOCKER_COMPOSE_PREFIX) {
    try {
        sh "${composePrefix} down -v --remove-orphans --rmi 'all'"
    } catch (Exception err) {
        echo "releaseContainer failed: "+ err.getMessage()
    }
}

void containerExt(Closure body){
    container('build-tools'){
        exeClosure(body)
    }
}

def startContainer(Map conf) {
    conf = [composePrefix: DOCKER_COMPOSE_PREFIX, detached: true, noDeps: false, build: false, jobTimeOut: 20, returnStdout: false] << conf
    def flagDetached = conf.detached ? "-d" : ""
    def flagNoDeps = conf.noDeps ? "--no-deps" : ""
    def flagBuild = conf.build ? "--build" : ""
    def strScript = "${conf.composePrefix} up $flagBuild $flagDetached $flagNoDeps $conf.name"
    String strlogs = ''
    timeout(conf.jobTimeOut) {
        if (conf.returnStdout) {
            strlogs = sh(returnStdout: true, script: strScript)
            echo "${strlogs}"
        } else {
            sh strScript
        }
        if (!conf.detached) {
            sh "exit \$(${conf.composePrefix} ps -aq $conf.name | xargs docker inspect -f '{{ .State.ExitCode }}')"
        }
        if(conf.returnStdout) return strlogs
    }
}

def startContainer(String name, int jobTimeOut = 20) {
    startContainer([name: name, jobTimeOut: jobTimeOut])
}

def exeClosure(Closure body) {
    if(body != null){
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body.delegate = this
        body()
    }
}

def waitForContainer(String name) {
    waitForContainer([name: name])
}

def waitForContainer(Map conf) {
    conf = [timeOut: 5, unit: 'MINUTES', status: 'healthy'] << conf
    sh script: "${DOCKER_COMPOSE_PREFIX} logs -f ${conf.name} > ${conf.name}-container.log &"
    try {
        timeout(time: conf.timeOut, unit: conf.unit) {
            def status = containerStatus conf.name
            echo "${conf.name} status is: $status"
            while(status != conf.status) {
                sleep 5
                status = containerStatus conf.name
                echo "${conf.name} status is: $status"
            }
        }
    } finally {
        try {
            sh "cat ${conf.name}-container.log"
        } catch (Exception err) {
            echo "Can not open ${conf.name}-container.log: ${err.getMessage()}"
        }
    }
}

def containerStatus(String name) {
    sh(returnStdout: true, script: "${DOCKER_COMPOSE_PREFIX} ps -q $name | xargs docker inspect -f '{{ .State.Health.Status }}'").trim()
}

def startBuildAndTest(Map conf) {
    try {
        DOCKER_COMPOSE_PREFIX = conf.composePrefix
        if (conf.unitTest) {
            exeClosure conf.unitTest
        } else {
            startContainer name: 'build', detached: false
        }
    } catch (Exception err) {
        echo "startBuildAndTest failed: " + err.getMessage()
        currentBuild.result = "FAILURE"
        releaseContainer()
        throw err
    }
}

def archiveContainerLogs(String composePrefix = DOCKER_COMPOSE_PREFIX){
    createConainterLogs(composePrefix)
    try {
        cpDockerArtifacts 'tomcat_', '/usr/share/tomcat/logs'
    } catch (Exception err) {
        echo "cpDockerArtifacts for tomcate failed: " + err.getMessage()
    }
    archiveArtifacts artifacts: '**/*.log'
}

def createConainterLogs(String composePrefix = DOCKER_COMPOSE_PREFIX) {
    def services = sh(
            script: "$composePrefix config --services",
            returnStdout: true
    ).trim().split()
    for (int i = 0; i < services.size(); i++) {
        service = services[i]
        sh script: "$composePrefix logs -f $service > $service-container.log &"
    }
}

def cpDockerArtifacts(String containerName, String artifactsSource) {
    try {
        def fullContainerName = sh(
                script: "docker ps --format {{.Names}} -f name=$containerName",
                returnStdout: true
        ).trim()
        if (fullContainerName) {
            sh "docker cp $fullContainerName:$artifactsSource ."
        }
    }
    catch (Exception err) {
        echo "cpDockerArtifacts failed: " + err.getMessage()
    }
}

//--- code coverage ---

def startCodeCoverage(Map conf) {
    boolean java11 = env.LANGUAGE_VERSION == "11"
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'github-token-username', usernameVariable: 'githubUsername', passwordVariable: 'githubAccessToken'],
                     [$class: 'StringBinding', credentialsId: SONAR_CREDENTIALS_ID, variable: 'sonarqube_token']]) {
        conf = [sonarCredentialsId: sonarqube_token, githubCredentialsId: githubAccessToken] << conf
    }
    conf.analysisMode = env.CHANGE_ID? 'preview' : 'publish'
    try {
        stage('Code Coverage Analysis') {
            String strLogs = publishToSonar(url: SONARQUBE_PROD_URL, githubCredentialsId: conf.githubCredentialsId, credentialsId: conf.sonarCredentialsId, returnStdout: true)
            env.SONARQUBE_TASK_ID = getSonarTaskID(strLogs)
            echo "SONARQUBE_TASK_ID: ${env.SONARQUBE_TASK_ID}"
            if (env.SONARQUBE_TASK_ID != '') {
                String strStatus = getSonarStatus(env.SONARQUBE_TASK_ID)
                echo "SonarQube Status: $strStatus"
                if (strStatus == "FAILED") {
                    throw new GroovyException("The data was posted to Sonarqube failed")
                }
            }
            if (conf.analysisMode == 'preview') {
                previewAnalysis([url: SONARQUBE_PROD_URL] << conf)
            }
            if (conf.analysisMode == 'publish' && (new JenkinsUtils().isMainBranch())) {
                publishToSonar(url: SONARQUBE_PROD_URL, githubCredentialsId: conf.githubCredentialsId, credentialsId: conf.sonarCredentialsId)
            }
        }
    } catch (Exception err) {
        echo "SonarQube code coverage failed: " + err.getMessage()
        currentBuild.result = "UNSTABLE"
    } finally {
        releaseContainer(env.DOCKER_COMPOSE_PREFIX)
    }
}

private def publishToSonar(Map conf) {
    conf = [returnStdout: false] << conf
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'github-token-username', usernameVariable: 'githubUsername', passwordVariable: 'githubAccessToken'],
                     [$class: 'StringBinding', credentialsId: SONAR_CREDENTIALS_ID, variable: 'sonarqube_token']]) {
        def e = ["sonarHostUrl=${SONARQUBE_PROD_URL}", 'sonarAnalysisMode=publish']
        if (!(new JenkinsUtils().isMainBranch())) {
            e << "branchName=${env.BRANCH_NAME}"
            echo " eBranch is: $e"
        }
        withEnv(e) {
            retry(3) {
                startContainer(name: 'coverage', detached: false, noDeps: true, returnStdout: conf.returnStdout)
            }
        }
    }
}

private def previewAnalysis(Map conf) {

    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'github-token-username', usernameVariable: 'githubUsername', passwordVariable: 'githubAccessToken'],
                     [$class: 'StringBinding', credentialsId: SONAR_CREDENTIALS_ID, variable: 'sonarqube_token']]) {
        withEnv(["sonarHostUrl=${conf.url}", "sonarAnalysisMode=preview", "githubPRNumber=${env.CHANGE_ID}", "githubRepoName=${env.githubRepoName}", "githubChangeTarget=${env.CHANGE_TARGET}"]) {
            retry(3) { startContainer name: 'coverage', detached: false, noDeps: true }
        }
    }
}

def getSonarStatus(String taskId) {
    withCredentials([[$class: 'StringBinding', credentialsId: SONAR_CREDENTIALS_ID, variable: 'sonarqube_token']]) {
        echo "Verify sonarqube status. Please wait..."
        String sonarUrl = SONARQUBE_PROD_URL
        def obj = new SonarAPI("${sonarUrl}", sonarqube_token)
        String status = obj.getStatus(taskId)
        if (status == "") {
            timeout(10) {
                while (status == "") {
                    echo "Sonarqube is not ready yet. Please wait another 30 seconds to make another call..."
                    sleep(time: 30, unit: "SECONDS")
                    status = obj.getStatus(taskId)
                }
            }
        }
        return status
    }
}

private def getSonarTaskID(String data) {
    String startStr = "$SONARQUBE_PROD_URL/api/ce/task?id="
    if(env.LANGUAGE_VERSION == "11") startStr = "kmj-" + startStr
    String endStr = "\n"
    int startIndex = data.indexOf(startStr)
    int endIndex = startIndex >= 0? data.indexOf(endStr, startIndex) : -1
    if (startIndex >= 0 && endIndex > 0) {
        String str = data.substring(startIndex, endIndex)
        str = str.replace(startStr, "")
        str = str.replace(endStr, "")
        return str
    }
    return ""
}

//-- end of code coverage ---

return this
