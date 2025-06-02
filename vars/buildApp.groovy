import com.sony.sie.cicd.ci.pipelines.unified.PipelineFactory
import com.sony.sie.cicd.ci.pipelines.unified.PipelineProcessor
import com.sony.sie.cicd.ci.utilities.NotifyUtils
import com.sony.sie.cicd.helpers.enums.BuildAction
import com.sony.sie.cicd.helpers.enums.StageName
import com.sony.sie.cicd.helpers.utilities.GitUtils
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import org.codehaus.groovy.GroovyException
import org.jenkinsci.plugins.pipeline.modeldefinition.actions.ExecutionModelAction
import org.jenkinsci.plugins.pipeline.modeldefinition.ast.ModelASTStages

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson

def call(def configure) {
    setProperties()
    pipelineDefinition = [:]
    configure.resolveStrategy = Closure.DELEGATE_FIRST
    configure.delegate = pipelineDefinition
    configure()
    jenkinsUtils = new JenkinsUtils()
    def failureReason = ""
    ansiColor('xterm') {
        try {
            timestamps {
                echo "Starting buildApp Jenkins Job..."
                setupEnvInfo()

                def pipelineFactory = null
                jenkinsUtils.navNode(templateType: "checkout", isNewPod: true) {
                    stage('Preparation') {
                        dir(env.REPO_WORKDIR) {
                            cleanWs()
                            jenkinsUtils.checkoutGitSCM()
                            loadCIconfig "unified-ci-config.yaml"
                            setVersionFileInfo()
                            pipelineDefinition.enablePublishECR = true
                            pipelineDefinition.enableGithubRelease = true
                            pipelineDefinition.enablePackcli = pipelineDefinition.pipelineType.contains("buildpack")

                            def notifyUtils = new NotifyUtils()
                            pipelineDefinition.slackChannels = notifyUtils.loadSlackConfigInfo(pipelineDefinition, StageName.BUILD_APP.formattedName)
                            notifyUtils.startOfPipelineNotify(pipelineDefinition, StageName.BUILD_APP.formattedName)

                            echo "=== pipelineDefinition ===\n${prettyPrint(toJson(pipelineDefinition))}\n=== /pipelineDefinition ==="
                        }
                        pipelineFactory = new PipelineFactory(pipelineDefinition)
                        pipelineFactory.preparation()
                        env.LANGUAGE_VERSION = pipelineDefinition.languageVersion
                        echo "=== ENV ==="
                        sh "env"
                        echo "=== /ENV ==="
                    }
                }

                checkpoint '*Initialization Complete*'

                process()
                // DO NOT CHANGE, ORCHESTRATION RELIES ON THIS FORMAT.
                currentBuild.description = "Release ${env.CHART_VERSION}"
            }
        } catch (GroovyException err) {
            failureReason = err.getMessage()
            if(!jenkinsUtils.isBuildAborted()) {
                failureReason = err.getMessage()
                if (!failureReason) failureReason = 'Unknown error!'
                echo err.getMessage()
                currentBuild.result = "FAILURE"
                throw err
            }
            throw err
        } finally {
            new NotifyUtils().endOfPipelineNotify(pipelineDefinition, StageName.BUILD_APP.formattedName)
        }
    }
}

def process() {
    def pipelineProcessor = new PipelineProcessor()
    def pipelineFactory = new PipelineFactory(pipelineDefinition)
    def objPipeline = pipelineFactory.createPipeline()
    if (!currentBuild.rawBuild.getAction(ExecutionModelAction)) {
        currentBuild.rawBuild.addAction(new ExecutionModelAction(new ModelASTStages(null)))
    }

    def nodeConf = [
            infrastructure: "navigator-cloud", //use prodAdmin for unified CI
            languageVersion: pipelineDefinition.languageVersion ?: "",
            templateType: pipelineDefinition.pipelineType,
            isNewPod: true,
            clusterList: ["helm-ci"]
    ]

    jenkinsUtils.jenkinsNode(nodeConf) {
        dir(env.REPO_WORKDIR) {
            cleanWs()
            jenkinsUtils.checkoutGitSCM()
        }
        if(env.APP_VERSION==null || env.APP_VERSION == "") env.APP_VERSION = pipelineFactory.getNewReleaseVersion()
        if(env.CHART_VERSION==null || env.CHART_VERSION == "") env.CHART_VERSION = pipelineFactory.getChartVersion()
        pipelineProcessor.process(objPipeline)
    }
}

void setupEnvInfo() {
    def jenkinsUtils = new JenkinsUtils()

    env.REPO_NAME = pipelineDefinition.repoName
    env.ORG_NAME = pipelineDefinition.orgName
    env.VERSION_TIMESTAMP = new Date().format("yyyyMMddHHmmss") + "-perf"
    env.githubRepoName = pipelineDefinition.repoName
    env.GIT_URL = "git@github.sie.sony.com:${env.githubRepoName}.git"
    if(!env.GIT_COMMIT) env.GIT_COMMIT = "HEAD"
    String tmp = "engine_${env.VERSION_TIMESTAMP}_${env.BUILD_ID}"
    //stash source code for CI
    env.STASH_NAME = "stash_${tmp}"
    env.REPO_WORKDIR = "${env.REPO_NAME}"
    env.STASH_SOURCE_OK = "FALSE"
    env.SONARQUBE_TASK_ID = ''
    env.CHART_VERSION = ''
    env.APP_VERSION = ''
    env.BRANCH_NAME = jenkinsUtils.removeWhiteSpaces(params.BRANCH_OVERRIDE) ?: jenkinsUtils.fetchDefaultBranch(pipelineDefinition.orgName, pipelineDefinition.repoName)
    //if (env.BRANCH_NAME == "") throw new GroovyException("The BRANCH_NAME was not provided, please input.")
    env.GIT_BRANCH = "origin/${env.BRANCH_NAME}"
    env.CUSTOM_DESCRIPTION = ''
}

void setVersionFileInfo(){
    if(pipelineDefinition.versionFileInfo == null) {
        switch (pipelineDefinition.pipelineType) {
            case ~/.*maven.*/:
            case ~/.*mlpython.*/:
            case ~/.*docker-compose.*/:
            case ~/.*bazel.*/:
            case ~/.buildpack.*/:
                pipelineDefinition.versionFileInfo = ["filepath": 'pom.xml', "filetype": 'xml', "keyword": "version"]
                break
            case ~/.*github-lib.*/:
                pipelineDefinition.versionFileInfo = ["filepath": 'github', "filetype": 'github', "keyword": "release"]
                break
            case ~/.*chart.*/:
                pipelineDefinition.versionFileInfo = ["filepath": "${env.HELM_CHART_PATH}/Chart.yaml", "filetype": 'yaml', "keyword": "version"]
                break
            case ~/.*gradle.*/:
                pipelineDefinition.versionFileInfo = ["filepath": "gradle.properties", "filetype": 'properties', "keyword": "version"]
                break
            default:
                pipelineDefinition.versionFileInfo = ["filepath": 'version.yaml', "filetype": 'yaml', "keyword": "version"]
                break
        }
    }
}

/*
# unified-ci-config.yaml
name: catalyst-example
kind: ci
infrastructure: kamaji-cloud

repoInfo:
  # support types: service, library, image, chart, ml, flink, lambda….
  type: service
  projectDir: ./
  language:
    name: java
    version: 8
  buildTools:
  - maven
  - buildpack
  # optional
  versionFileInfo:
    filepath: version.yaml
    filetype: yaml
    keyword: version
  # optional for maven and gradle but mandatory for docker pipeline
  dockerFileList:
  - filepath: Dockerfile
    appName: engine-service-metadata
    organization: engine
    extraOptions: ""
  specFiles:
  - api_manifest.yaml
  buildpackConfig:
    buildType: buildpack
    imageName: cnb-buildpack-java11
    includeDependencies: true

# optional and the default value is false.
# Users will need to use the "-auth" image for permissions to work properly
tests:
  integrationTest: false
  componentTest:
    #checkout:
    #- kamaji-cassandra
    #userId: 500
    #groupId: 500
    #composePrefix: "docker-compose -f docker-compose.yml -f docker-compose.jenkins.yml"
    #composeSubDir: "ComposeDir"
    #preparation: "preparation of start containers"
    containers:
    - method: startContainer
      name: cassandra-cql
    - method: startContainer
      name: mockserver
    - method: startContainer
      name: vaultspec
    - method: startContainer
      name: music-api
      detached: true
      noDeps: true
    - method: waitForContainer
      name: music-api
    - method: startContainer
      name: tests
      detached: false
      noDeps: true

# optional and the default value is false
pullrequest:
  publishArtifacts: false #-- for library
  enableRelease: false  #-- for service

# optional
securityAnalysis:
  raaTeamName: catalyst-example
  fortifyTeamName: catalyst-example
  raaAppName: catalyst-example

# optional
slackConfig:
- name: catalyst-test

# optional
autoCDtrigger:
  deployUpto: E1-PMGT_NP
  jenkinsJob: https://core.jenkins.hyperloop.sonynei.net/gaminglife-cd/job/engine-pipeline-example/job/orchestration/

# optional
codeAnalysis:
  qualityGateName: kmj-cloud-incremental
  qualityProfileName: high-severity-rules-only
  portfolioName: Core Experience
  githubTeamName: ci-catalyst-devteam

# optional:
#   1. If the helm chart is not defined, the helmChartConfigs is not required
#   2. If the auto pilot is not enabled, only the helmChartPath is required, otherwise, all fields are required
#   3. If the service repo does not have chart repo, the github and defaultBranch should not be defined
#   4. If it is a mono repo, it should have multiple helm charts
helmChartConfigs:
- helmChartPath: helm-unified
  github: sie/catalyst-example-chart
  defaultBranch: main
  # optional and it is not applable if github is provided
  autoCDtrigger:
  - jenkinsJobUrl: https://core.jenkins.hyperloop.sonynei.net/gaminglife-cd/job/engine-pipeline-example/job/orchestration/
    parameters:
     - name: "DEPLOY_UP_TO"
       value: "E1-PMGT_NP"
*/

void loadCIconfig(def configFileName) {
    def conf = readYaml file: configFileName
    pipelineDefinition << conf
    if(pipelineDefinition.repoInfo == null) {
        throw new GroovyException("The ${configFileName} file is outdated and need to update to the new format: https://core.jenkins.hyperloop.sonynei.net/shared-tools/job/workflow-pipelines/job/onboarding/job/unified-ci-onboard/build?delay=0sec")
    } else {
        pipelineDefinition = [
                releaseType: "app",
                pipelineType: pipelineDefinition.repoInfo.buildTools.join("-"),
                languageType: pipelineDefinition.repoInfo.language?.name,
                languageVersion: pipelineDefinition.repoInfo.language?.version,
                buildAction: BuildAction.CI_ONLY,
                branchName: env.BRANCH_NAME
        ] << pipelineDefinition
        if(pipelineDefinition.helmChartConfigs) env.HELM_CHART_PATH = pipelineDefinition.helmChartConfigs[0].helmChartPath
        if(pipelineDefinition.repoInfo.versionFileInfo) pipelineDefinition.versionFileInfo = pipelineDefinition.repoInfo.versionFileInfo
        if(pipelineDefinition.repoInfo.dockerFileList) pipelineDefinition.dockerFileList = pipelineDefinition.repoInfo.dockerFileList
        if(pipelineDefinition.pipelineType == "buildpack-maven") pipelineDefinition.pipelineType = "maven-buildpack"
        pipelineDefinition.pipelineType = pipelineDefinition.pipelineType.toLowerCase()
    }
}

def setProperties(){
    def settings = [
            string(name: 'BRANCH_OVERRIDE', defaultValue: '', description: 'Optional: defaults to main or master github branch')
    ]
    if(params.LIB_VERSION) {
        settings.add(string(name: 'LIB_VERSION', defaultValue: 'main', description: 'CICD USE ONLY. Version/branch of the engine-iso-perf-framework to use.'))
    }
    properties([
            parameters(settings),
            buildDiscarder(
                    logRotator(
                            artifactDaysToKeepStr: '30',
                            artifactNumToKeepStr: '30',
                            daysToKeepStr: '60',
                            numToKeepStr: '20')
            ),
            disableConcurrentBuilds(),
            pipelineTriggers([])
    ])
    echo "=== PARAMS ===\n${prettyPrint(toJson(params))}\n=== /PARAMS ==="
}