package com.sony.sie.cicd.ci.pipelines.kamaji

import com.sony.sie.cicd.helpers.annotations.*
import com.sony.sie.cicd.ci.utilities.AWSUtils
import org.codehaus.groovy.GroovyException
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import static com.sony.sie.cicd.helpers.utilities.KmjEnv.SONARQUBE_PROD_URL
import com.sony.sie.cicd.ci.pipelines.unified.BaseMavenPipeline

class KmjMavenPipeline extends BaseMavenPipeline {
    KmjMavenPipeline(def pipelineDefinition) {
        super(pipelineDefinition)
    }

    @PRScope
    @MasterScope
    @BranchScope
    def buildAndTest() {
        mvnBuild()
    }
  
    @PRScope
    @MasterScope
    @BranchScope
    @StageLabel(value="")
    def publish(){
        if (pipelineDefinition.enablePublishECR) {
            stage("Publish to ECR"){
                ecrConfig('maven-build', ["https://761590636927.dkr.ecr.us-west-2.amazonaws.com"])
                mvnDeploy()
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
