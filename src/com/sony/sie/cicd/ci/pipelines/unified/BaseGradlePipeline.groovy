package com.sony.sie.cicd.ci.pipelines.unified

import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import com.sony.sie.cicd.helpers.utilities.KmjEnv

import static com.sony.sie.cicd.helpers.utilities.KmjEnv.ECR_HOST_URL
import com.sony.sie.cicd.helpers.annotations.BranchScope
import com.sony.sie.cicd.helpers.annotations.MasterScope
import com.sony.sie.cicd.helpers.annotations.PRScope
import com.sony.sie.cicd.helpers.annotations.StageLabel
import com.sony.sie.cicd.helpers.annotations.StageOrder
import com.sony.sie.cicd.helpers.utilities.FileUtils

abstract class BaseGradlePipeline extends BasePipeline {
    def awsMavenSecret = ''
    BaseGradlePipeline(def pipelineDefinition){
        super(pipelineDefinition)
    }

    def buildPreparation() {
        pipelineDefinition.defaultBuildContainer = 'gradle-build'
        basePipelineClassList.add(BaseGradlePipeline.class)
    }

    @StageOrder(id=20)
    @StageLabel(value="")
    @PRScope
    @MasterScope
    @BranchScope
    void setGithubVersion(){
        env.REPO_WORKDIR = pipelineDefinition.repoInfo.projectDir ? env.REPO_NAME + "/" + pipelineDefinition.repoInfo.projectDir : env.REPO_NAME
        if(pipelineDefinition.enablePublishECR) {
            stage("Set Version") {
                dir(env.REPO_WORKDIR) {
                    new FileUtils().setValue(pipelineDefinition.versionFileInfo, env.APP_VERSION)
                }
            }
        }
    }

    @StageOrder(id=25)
    @StageLabel(value="Setup")
    @PRScope
    @MasterScope
    @BranchScope
    def runPreparation(){
        if(pipelineDefinition.enablePublishECR){
            ecrConfig('build-tools')
        }
        if(pipelineDefinition.infrastructure=="kamaji-cloud"){
            ecrConfig('gradle-build', ["https://761590636927.dkr.ecr.us-west-2.amazonaws.com"])
        }
        awsMavenSecret =  jenkinsUtils.fetchMvnSecret(pipelineDefinition.infrastructure)
        container("gradle-build") {
            jenkinsUtils.gradleConfig(pipelineDefinition.infrastructure, awsMavenSecret)
        }  
    }

//    @PRScope
//    @MasterScope
//    @BranchScope
    def buildAndTest() {
        gradleBuild()
    }

    @PRScope
    @MasterScope
    @BranchScope
    @StageLabel(value="")
    def publish(){
        if (pipelineDefinition.enablePublishECR) {
            stage("Publish"){
                gradleDeploy()
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

    def gradleBuild() {
        containerExt([containerName: 'gradle-build']){
            withEnv(["mavenSecret=${awsMavenSecret}","mavenUser=hyperloopops"]) {
                sh "./gradlew --full-stacktrace --no-daemon -x test assemble --info"
//                executeTests()
//                if(env.GRADLE_TESTCONTAINER_TEST_RESULT_STASH == "true") {
//                    unstash "${env.STASH_NAME}_test_containers"
//                }
                sh "./gradlew --full-stacktrace --no-daemon -x test jar --info"
            }
            if(fileExists("./build/test-results/test")) {
                sh "ls -lah ./build/test-results/test/"
                try {
                    step([$class: 'JUnitResultArchiver', allowEmptyResults: true, testResults: '**/build/test-results/test/TEST-*.xml'])
                } catch (Exception err) {
                    echo "getTestReports failed: " + err.getMessage()
                    currentBuild.result = "UNSTABLE"
                }
            }
        }
    }

    def gradleDeploy(){
        containerExt(){
            String artifactId = env.RELEASE_PATTERN == 'snapshot' ? 'engine-maven-snapshot-local' : 'engine-maven-release-local'
            String mavenUrl = KmjEnv.ART_URL + artifactId
            echo "${mavenUrl}"
            withEnv(["mavenSecret=${awsMavenSecret}","mavenUser=hyperloopops","mavenUrl=${mavenUrl}"]) {
                sh "./gradlew --no-daemon publish"
            }
        }
    }

    def executeTests() {
        String workingDir = sh returnStdout: true, script: "pwd"
        workingDir = workingDir.trim()
        // check for testcontainers dependency
        def testContainersFindOutput = sh returnStdout: true, script: "find /root/.gradle/ -name org.testcontainers"
        def testContainersPresent = testContainersFindOutput.trim().contains("org.testcontainers")
        if(!testContainersPresent) {
            sh "./gradlew --full-stacktrace --no-daemon test --info"
        } else {
            def jenkinsUtils = new JenkinsUtils()
            def nodeConf = [
                    infrastructure: "navigator-cloud",
                    languageVersion: pipelineDefinition.languageVersion,
                    templateType: "docker-compose",
                    isNewPod: true
            ]
            jenkinsUtils.jenkinsNode(nodeConf) {
                container("docker-compose") {
                    dir(workingDir) {
                        cleanWs()
                        ecrConfig(POD_CONTAINER)
                        def awsMavenSecret =  jenkinsUtils.fetchMvnSecret(pipelineDefinition.infrastructure, POD_CONTAINER)
                        jenkinsUtils.checkoutGitSCM()
                        def dockerRegion = "us-west-2"
                        String cred = sh (returnStdout: true, script: "aws ecr get-login-password --region ${dockerRegion}")
                        writeFile file: "ecrCred.txt", text: cred
                        String containerName = "${env.REPO_NAME}-${env.APP_VERSION}-${System.currentTimeMillis()}".take(255)
                        String containerImage = pipelineDefinition.languageVersion == "17" ?
                                "${ECR_HOST_URL}/engine/gradle-build-java17:release-0.0.2" :
                                "${ECR_HOST_URL}/engine/gradle-build-java11:release-20230510103200"
                        sh """
                            set +x
                            echo "ORG_GRADLE_PROJECT_artifactoryPass=${awsMavenSecret}" > docker_gradle_env.txt  
                            echo "ORG_GRADLE_PROJECT_artifactoryUser=hyperloopops" >> docker_gradle_env.txt
                            echo "TESTCONTAINERS_TINYIMAGE_CONTAINER_IMAGE=library/alpine:3.16" >> docker_gradle_env.txt
                            echo "TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX=${ECR_HOST_URL}/uks-external/" >> docker_gradle_env.txt
                            set -x 
                            docker run --network="bridge" --rm -v $workingDir:$workingDir -w $workingDir \
                                -v /var/run/docker.sock:/var/run/docker.sock \
                                --env-file=docker_gradle_env.txt \
                                --name ${containerName} \
                                ${containerImage} \
                                sh -c "cat ecrCred.txt | docker login -u AWS --password-stdin 890655436785.dkr.ecr.us-west-2.amazonaws.com && \
                                ./gradlew --full-stacktrace --no-daemon test --info"                                      
                            rm ecrCred.txt
                            rm docker_gradle_env.txt
                        """
                        env.GRADLE_TESTCONTAINER_TEST_RESULT_STASH = "true"
                        stash name: "${env.STASH_NAME}_test_containers", includes: "**"
                    }
                }
            }
        }
    }
}
