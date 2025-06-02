package com.sony.sie.cicd.ci.pipelines.laco

import com.sony.sie.cicd.helpers.annotations.*
import com.sony.sie.cicd.ci.utilities.AWSUtils
import org.codehaus.groovy.GroovyException
import static com.sony.sie.cicd.helpers.utilities.KmjEnv.SONARQUBE_PROD_URL
import com.sony.sie.cicd.ci.pipelines.unified.BaseMavenPipeline


class LcoMavenPipeline extends BaseMavenPipeline {
    LcoMavenPipeline(def pipelineDefinition) {
        super(pipelineDefinition)
    }

    @PRScope
    @MasterScope
    @BranchScope
    def buildAndTest() {
        if(pipelineDefinition.buildClosure) {
            exeClosure pipelineDefinition.buildClosure
        } else {
            mvnBuild()
        }
    }
    
    @PRScope
    @MasterScope
    @BranchScope
    @StageLabel(value="")
    def publish(){
        if (pipelineDefinition.enablePublishECR) {
            stage("Publish to ECR"){
                echo "Not implement yet!"
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
