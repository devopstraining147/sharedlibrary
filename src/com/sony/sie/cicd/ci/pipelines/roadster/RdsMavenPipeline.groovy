package com.sony.sie.cicd.ci.pipelines.roadster

import com.sony.sie.cicd.helpers.annotations.*
import com.sony.sie.cicd.ci.utilities.AWSUtils
import org.codehaus.groovy.GroovyException
import static com.sony.sie.cicd.helpers.utilities.KmjEnv.SONARQUBE_PROD_URL
import com.sony.sie.cicd.ci.pipelines.unified.BaseMavenPipeline

class RdsMavenPipeline extends BaseMavenPipeline {
    RdsMavenPipeline(def pipelineDefinition) {
        super(pipelineDefinition)
    }

    @PRScope
    @MasterScope
    @BranchScope
    def buildAndTest() {
        echo "Unit Test"
        containerExt("mvn clean package -Djacoco.destFile=target/jacoco.exec org.jacoco:jacoco-maven-plugin:prepare-agent -DexcludedGroups=integration -DskipDocker")
    }

    // @PRScope
    // @MasterScope
    // @BranchScope
    // @StageOrder(id=35)
    // @StageLabel(value="")
    // def integrationTest() {
    //     //Use roadster tool agent for the integration test
    //     if(pipelineDefinition.tests?.integrationTest) {
    //         def nodeConf = [
    //             infrastructure: pipelineDefinition.infrastructure,
    //             languageVersion: pipelineDefinition.languageVersion,
    //             templateType: pipelineDefinition.pipelineType,
    //             isNewPod: true
    //         ]
    //         stage("Integration Test") {
    //             jenkinsUtils.jenkinsNode(nodeConf) {
    //                 dir(env.REPO_WORKDIR) {
    //                     cleanWs()
    //                     unstash env.STASH_NAME
    //                 }
    //                 container("maven-build") {
    //                     jenkinsUtils.mavenConfig(pipelineDefinition.infrastructure, awsMavenSecret)
    //                 }
    //                 echo "Integration Test"
    //                 containerExt("mvn -B -T 1C clean verify -DskipDocker -Pdevelopment")
    //             }
    //         }
    //     }
    // }
    
    @PRScope
    @MasterScope
    @BranchScope
    @StageLabel(value="")
    def publish(){
        if (pipelineDefinition.enablePublishECR && pipelineDefinition.dockerFileList) {
            stage("Publish to UCR"){
                ecrConfig('maven-build', ["https://758491395512.dkr.ecr.us-west-2.amazonaws.com"])
                dockerBuildImage 'maven-build'
                dockerPushImage 'maven-build'
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
