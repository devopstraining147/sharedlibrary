package com.sony.sie.cicd.ci.pipelines.unified

import com.sony.sie.cicd.ci.pipelines.roadster.*
import com.sony.sie.cicd.helpers.annotations.*
import com.sony.sie.cicd.ci.tasks.*
import com.sony.sie.cicd.ci.utilities.BuildpackUtils
import org.codehaus.groovy.GroovyException
import com.sony.sie.cicd.ci.utilities.DockerComposeUtils
import static com.sony.sie.cicd.helpers.utilities.KmjEnv.ECR_HOST_URL
import com.sony.sie.cicd.helpers.utilities.FileUtils
import com.sony.sie.cicd.helpers.utilities.CICDUtils
import com.sony.sie.cicd.helpers.utilities.GitUtils

class GolangBuildpackPipeline extends BasePipeline {

    def buildpackUtils = new BuildpackUtils()
    def packcliConfig
    def builder
    def enablePublishECR
    def enableCache
    def srcPath = "."

    GolangBuildpackPipeline(def pipelineDefinition) {
        super(pipelineDefinition)
        enablePublishECR = pipelineDefinition.enablePublishECR
        enableCache = !(pipelineDefinition.repoInfo.buildpack?.disableCache?:false)
        basePipelineClassList.add(GolangBuildpackPipeline.class)
        srcPath = (pipelineDefinition.repoInfo?.projectDir ?:".").trim()
        if (srcPath.endsWith("/")) srcPath = srcPath[0..-2]
    }

    def buildPreparation() {
        pipelineDefinition.defaultBuildContainer = 'go-build'
    }

    @PRScope
    @MasterScope
    @BranchScope
    @StageLabel(value="Config Builder")
    @StageOrder(id=1)
    def runPreparation() {
        dir("${WORKSPACE}/${env.REPO_NAME}") {
            packcliConfig = buildpackUtils.getPackcliConfig(pipelineDefinition)
            builder = buildpackUtils.getBuildpackBuilder()
            echo "builder: ${builder}"
        }

        if (pipelineDefinition.preparation) {
            stage('Setup') {
                exeClosure pipelineDefinition.preparation
            }
        }

        if (enablePublishECR) {
            ecrConfig("packcli")
        }
        
        container("packcli") {
            if (enablePublishECR) {

                if (jenkinsUtils.waitForDocker()) {
                    sh "docker pull ${builder}"
                }
            }
        }
    }

    @PRScope
    @MasterScope
    @BranchScope
    void setGithubVersion() {
        if (pipelineDefinition.enablePublishECR) {
            env.REPO_WORKDIR = pipelineDefinition.repoInfo.projectDir ? env.REPO_NAME + "/" + pipelineDefinition.repoInfo.projectDir : env.REPO_NAME
            if (pipelineDefinition.enablePublishECR) {
                stage("Set Version") {
                    dir(env.REPO_WORKDIR) {
                        if (!fileExists(pipelineDefinition.versionFileInfo.filepath)) throw new GroovyException("The ${pipelineDefinition.versionFileInfo.filepath} file cannot be found in ${env.REPO_WORKDIR}") 
                        new FileUtils().setValue(pipelineDefinition.versionFileInfo, env.APP_VERSION)
                    }
                }
            }
        }
    }

    @PRScope
    @MasterScope
    @BranchScope
    def buildAndTest() {
        // golangBuildImage()
        def workDir = "${WORKSPACE}/${env.REPO_NAME}"
        containerExt([containerName: 'go-build']) {
            String exportproxy  = "export GOPROXY=https://artifactory.sie.sony.com/artifactory/api/go/cgei-go-release-virtual"
            String buildCommand = "go test --coverprofile=coverage.out ./... "
            dir(workDir) {
                sh """
                    ${exportproxy} &&
                    ${buildCommand}
                """
            }
        }
    }

    @PRScope
    @MasterScope
    @BranchScope
    @StageLabel(value="")
    def publish() {
        if (enablePublishECR) {
            stage("Publish image(s) to ECR") {
                def workDir = "${WORKSPACE}/${env.REPO_NAME}"
                def lifeCycleVersion = getImageLifeCycleVersion()

                containerExt([containerName: 'packcli']) {
                    dir(workDir) {
                        packcliConfig.each { key,val->
                            def serviceName = key
                            def imagePrefix = val.repoPrefix?:"psn"
                            dockerVersionTag   = "${ECR_HOST_URL}/${imagePrefix}/${serviceName}:${env.APP_VERSION}"
                            dockerLifeCycleTag = "${ECR_HOST_URL}/${imagePrefix}/${serviceName}:${lifeCycleVersion}"

                            def cache = (enableCache ? "--clear-cache --cache 'type=build;format=image;name=${ECR_HOST_URL}/${imagePrefix}/${serviceName}-cache:latest'" : "")
                            def envFile = buildpackUtils.getEnvFile(serviceName)
                            def cli_envs = ""
                            def outputFile = ""

                            if (envFile != "") {
                                outputFile = envFile.replace(".env", ".tmp")
                                buildpackUtils.updateGoAppVersionGitCommit(outputFile, serviceName, env.APP_VERSION, env.GIT_COMMIT)
                                cli_envs += "--env-file ${outputFile}"
                            }

                            if (jenkinsUtils.waitForDocker()) {
                                sh """
                                pack build --env GOPROXY=https://artifactory.sie.sony.com/artifactory/api/go/cgei-go-release-virtual --network=host ${cache} ${dockerVersionTag} --path ${srcPath} --builder ${builder} --trust-builder ${cli_envs} -t ${dockerLifeCycleTag} -v --publish
                                docker pull ${dockerVersionTag}
                                docker pull ${dockerLifeCycleTag}
                            """
                            }

                            if (outputFile != "") {
                                sh """
                                    rm ${outputFile}
                                """
                            }

                            if (pipelineDefinition.repoInfo?.publishImageToArtifactory) {
                                imageAndVersion = "${imagePrefix}/${serviceName}:${env.APP_VERSION}"
                                publishImageToArtifactory(dockerVersionTag, imageAndVersion)
                            }
                        }
                    }
                }
            }
        }
    }
}
