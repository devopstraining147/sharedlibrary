package com.sony.sie.cicd.ci.pipelines.unified

import com.sony.sie.cicd.helpers.annotations.*
import com.sony.sie.cicd.ci.tasks.*
import com.sony.sie.cicd.ci.utilities.BuildpackUtils
import org.codehaus.groovy.GroovyException
import static com.sony.sie.cicd.helpers.utilities.KmjEnv.ECR_HOST_URL

class MavenBuildpackPipeline extends BaseMavenPipeline {
    def buildpackUtils=new BuildpackUtils()
    def packcliConfig
    def nodeConf =[]
    def isAmd64
    def isArm64
    MavenBuildpackPipeline(def pipelineDefinition) {
        super(pipelineDefinition)
    }

    @PRScope
    @MasterScope
    @BranchScope
    @StageLabel(value="Config Builder")
    @StageOrder(id=1)
    def runPreparation() {
        dir("${WORKSPACE}/${env.REPO_NAME}") {
            packcliConfig = buildpackUtils.getPackcliConfig(pipelineDefinition)
        }

        def serviceNameList = []
        packcliConfig.each { key, val ->
            serviceNameList.add(key)
        }

        super.runPreparation()
        
        container("maven-build") {
            ecrConfig()
            jenkinsUtils.mavenCache(pipelineDefinition.repoInfo?.imageList)
        }
        
        if (pipelineDefinition.enablePublishECR) {
            ecrConfig("packcli")
        }

        nodeConf = [
            infrastructure: "navigator-cloud",
            languageVersion: pipelineDefinition.languageVersion,
            templateType: "graviton-packcli",
            isNewPod: true
        ]
        
        def platformList = pipelineDefinition.repoInfo?.platform
        isAmd64 = !platformList || platformList?.contains('linux/amd64')
        isArm64 = platformList && platformList?.contains('linux/arm64')
        
        echo "isAmd64: ${isAmd64}"
        echo "isArm64: ${isArm64}"
    }

//    @PRScope
//    @MasterScope
//    @BranchScope
    def buildAndTest() {
        mvnBuild()
    }

    @PRScope
    @MasterScope
    @BranchScope
    @StageLabel(value="")
    def publish(){
        if (pipelineDefinition.enablePublishECR) {
            packDeploy()
        }
    }

    def packDeploy() {
        if (pipelineDefinition.enablePackcli) {
            if (isAmd64) {
                stage("Build AMD64") {
                    packCliProcess("amd64")
                }
            }
            
            if (isArm64) {
                dir(env.REPO_WORKDIR) {
                    stash includes: '**', name: env.STASH_NAME, useDefaultExcludes: false
                }
                
                stage("Build ARM64") {
                    jenkinsUtils.jenkinsNode(nodeConf) {
                        dir(env.REPO_WORKDIR) {
                            cleanWs()
                            unstash env.STASH_NAME
                        }
                        
                        if (pipelineDefinition.enablePublishECR) {
                            ecrConfig("packcli")
                        }
                        
                        packCliProcess("arm64")
                    }
                }
            }
            
            createManifest(isAmd64, isArm64)
        } else {
            def msg = "Can not find a buildpack builder for the source code."
            echo "${msg}"
            throw new GroovyException(msg)
        }
    }

    def packCliProcess(String arch = 'amd64') {
        def workDir = "${WORKSPACE}/${env.REPO_NAME}"
        def lifeCycleVersion = getImageLifeCycleVersion()
        containerExt([containerName: 'packcli']) {
            jenkinsUtils.mavenConfig(pipelineDefinition.infrastructure, awsMavenSecret)
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "engine-artifactory-access", usernameVariable: 'username', passwordVariable: 'password']]) {
                writeFile file: "artPwd.txt", text: password
                sh "cat artPwd.txt | docker login -u ${userName} --password-stdin engine-docker-prod-local.artifactory.sie.sony.com"
                sh "rm artPwd.txt"
            }
            
            dir(workDir) {
                def builder = buildpackUtils.getBuildpackBuilder()
                echo "builder: ${builder}"
                
                sh """
                    ls -althr ./
                    pack --version
                    cp -rf /usr/conf ./.m2
                """
                
                def parallelPackBuild = [:]
                packcliConfig.each { key, val ->
                    String serviceName = key
                    String imagePrefix = val.repoPrefix ?: "psn"
                    String dockerVersionTag = "${ECR_HOST_URL}/${imagePrefix}/${serviceName}:${env.APP_VERSION}-${arch}"
                    String dockerLifeCycleTag = "${ECR_HOST_URL}/${imagePrefix}/${serviceName}:${lifeCycleVersion}-${arch}"

                    String cache = "--cache 'type=build;format=image;name=${ECR_HOST_URL}/${imagePrefix}/${serviceName}-cache:latest-${arch}'"
                    String cli_envs = ""
                    String envFile = buildpackUtils.getEnvFile(serviceName)
                    
                    if (envFile) {
                        cli_envs = "--env-file ${envFile}"
                    } else {
                        if (pipelineDefinition.languageVersion) {
                            cli_envs += " -e 'BP_JVM_VERSION=${pipelineDefinition.languageVersion}'"
                        }
                        if (val.env) {
                            for (int i = 0; i < val.env.size(); i++) {
                                cli_envs += " -e '${val.env[i].name}=${val.env[i].value}'"
                            }
                        }
                    }
                    
                    cli_envs += " -e 'BP_MAVEN_SETTINGS_PATH=/workspace/.m2/settings.xml'"

                    parallelPackBuild["Pack Build ${serviceName}"] = {
                        sh """
                            pack build --network=host ${cache} ${dockerVersionTag} --path . --builder ${builder} --trust-builder ${cli_envs} -t ${dockerLifeCycleTag} -v --publish
                            docker pull ${dockerVersionTag}
                            docker pull ${dockerLifeCycleTag} 
                        """
                    }
                }
                
                if (jenkinsUtils.waitForDocker()) {
                    parallel(parallelPackBuild)
                }
            }
        }
    }

    // @PRScope
    // @MasterScope
    // void chartDependencyCheck(){
    //     runChartDependencyCheck()
    // }

    // @PRScope
    // @MasterScope
    // void helmTemplateLint(){
    //     runHelmTemplateLint()
    // }
}
