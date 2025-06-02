package com.sony.sie.cicd.ci.pipelines.unified

import com.sony.sie.cicd.helpers.annotations.*
import com.sony.sie.cicd.ci.tasks.*
import com.sony.sie.cicd.ci.utilities.MavenUtils
import com.sony.sie.cicd.ci.utilities.BuildpackUtils
import org.codehaus.groovy.GroovyException
import static com.sony.sie.cicd.helpers.utilities.KmjEnv.ECR_HOST_URL
import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson
import com.sony.sie.cicd.ci.utilities.DockerUtils 

class GradleBuildpackPipeline extends BaseGradlePipeline {
    def buildpackUtils=new BuildpackUtils()
    def packcliConfig
    def nodeConf =[]
    def builder = ""
    def isAmd64
    def isArm64

    GradleBuildpackPipeline(def pipelineDefinition) {
        super(pipelineDefinition)
        basePipelineClassList.add(GradleBuildpackPipeline.class)
    }
    @StageOrder(id=25)
    @StageLabel(value="Setup")
    @PRScope
    @MasterScope
    @BranchScope
    def runPreparation(){
        super.runPreparation()

        dir("${WORKSPACE}/${env.REPO_NAME}"){
            packcliConfig= buildpackUtils.getPackcliConfig(pipelineDefinition)
            echo "=== packcliConfig ===\n${prettyPrint(toJson(packcliConfig))}\n=== /packcliConfig ==="
            builder = buildpackUtils.getBuildpackBuilder()
        }

        container("packcli") {
            jenkinsUtils.gradleConfig(pipelineDefinition.infrastructure, awsMavenSecret)
        }
        if(pipelineDefinition.enablePublishECR && pipelineDefinition.repoInfo.type != "flink"){
            if(pipelineDefinition.infrastructure=="kamaji-cloud"){
                ecrConfig('packcli', ["https://890655436785.dkr.ecr.us-west-2.amazonaws.com","https://761590636927.dkr.ecr.us-west-2.amazonaws.com"])
            }else{
                ecrConfig("packcli")
            }
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

    @PRScope
    @MasterScope
    @BranchScope
    @StageLabel(value="")
    def publish(){
        if (pipelineDefinition.enablePublishECR && pipelineDefinition.repoInfo.type != "flink") {
            
            packDeploy()
            
            if(pipelineDefinition.repoInfo.publishArtifact){
                stage("Publish Artifacts"){
                    gradleDeploy()
                }
            }
        }
    }

    def packDeploy() {
        if(pipelineDefinition.enablePackcli){
            
            if(isAmd64) {
                stage("Build AMD64"){
                    packCliProcess("amd64")
                }
            }
            if(isArm64) {
                dir(env.REPO_WORKDIR) {
                    stash includes: '**', name: env.STASH_NAME, useDefaultExcludes: false
                }
                stage("Build ARM64"){
                    jenkinsUtils.jenkinsNode(nodeConf) {
                        echo "Build ARM64 - NODE_LABELS: ${env.NODE_LABELS}"
                        if(env.NODE_LABELS && NODE_LABELS != "") nodeConf.nodeLabel = env.NODE_LABELS.split()[0]
                        dir(env.REPO_WORKDIR) {
                            cleanWs()
                            unstash env.STASH_NAME
                        }
                        if(pipelineDefinition.enablePublishECR && pipelineDefinition.repoInfo.type != "flink"){
                            if(pipelineDefinition.infrastructure=="kamaji-cloud"){
                                ecrConfig('packcli', ["https://890655436785.dkr.ecr.us-west-2.amazonaws.com","https://761590636927.dkr.ecr.us-west-2.amazonaws.com"])
                            }else{
                                ecrConfig("packcli")
                            }
                        }
                        packCliProcess('arm64')
                    }
                }
            }
            createManifest(isAmd64, isArm64)

        }else{
            def msg="Can not find a buildpack builder for the source code."
            echo "${msg}"
            throw new GroovyException(msg)
        }
    }

    def packCliProcess(String arch) {
        def workDir = "${WORKSPACE}/${env.REPO_NAME}"
        def lifeCycleVersion = getImageLifeCycleVersion()
        container("packcli") {
            jenkinsUtils.gradleConfig(pipelineDefinition.infrastructure, awsMavenSecret)
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "engine-artifactory-access", usernameVariable: 'username', passwordVariable: 'password']]) {
                writeFile file: "artPwd.txt", text: password
                sh "cat artPwd.txt | docker login -u ${userName} --password-stdin engine-docker-prod-local.artifactory.sie.sony.com"
                sh "rm artPwd.txt"
            }
            
            dir(workDir) {
                DockerUtils dockerUtils = new DockerUtils()
                dockerUtils.waitForDockerDaemon()
                
                // backup gradle.properties
                if (fileExists("gradle.properties")) {
                    sh "cp gradle.properties gradle.properties.bak"
                }
                
                sh """
                    echo "" >> ./gradle.properties
                    cat ~/.gradle/gradle.properties >> ./gradle.properties
                """
                
                def parallelPackBuild = [:]
                packcliConfig.each { image, values ->
                    String serviceName = image
                    String imagePrefix = values.repoPrefix ?: "psn"
                    String dockerVersionTag = "${ECR_HOST_URL}/${imagePrefix}/${serviceName}:${env.APP_VERSION}-${arch}"
                    String dockerLifeCycleTag = "${ECR_HOST_URL}/${imagePrefix}/${serviceName}:${lifeCycleVersion}-${arch}"

                    def cache = "--cache 'type=build;format=image;name=${ECR_HOST_URL}/${imagePrefix}/${serviceName}-cache:latest-${arch}'"
                    def cli_envs = ""
                    def envFile = buildpackUtils.getEnvFile(serviceName)
                    
                    if (envFile) {
                        cli_envs = "--env-file ${envFile}"
                    } else {
                        if (pipelineDefinition.languageVersion) {
                            cli_envs += " -e 'BP_JVM_VERSION=${pipelineDefinition.languageVersion}'"
                        }
                        if (values.env) {
                            for (int i = 0; i < values.env.size(); i++) {
                                cli_envs += " -e '${values.env[i].name}=${values.env[i].value}'"
                            }
                        }
                    }
                    
                    parallelPackBuild["Pack Build ${image}"] = {
                        sh """
                            pack --version
                            pack build --network=host ${cache} ${dockerVersionTag} --platform linux/${arch} --path . --builder ${builder} --trust-builder ${cli_envs} -t ${dockerLifeCycleTag} -v --publish
                            docker pull ${dockerVersionTag}
                            docker pull ${dockerLifeCycleTag} 
                        """
                    }
                }
                
                if (jenkinsUtils.waitForDocker()) {
                    parallel(parallelPackBuild)
                }
                
                sh "rm gradle.properties"
                
                // restore gradle.properties
                if (fileExists("gradle.properties.bak")) {
                    sh "mv gradle.properties.bak gradle.properties"
                }
            }
        }
    }
}
