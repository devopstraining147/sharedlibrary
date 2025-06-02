package com.sony.sie.cicd.ci.pipelines.laco

import com.sony.sie.cicd.helpers.annotations.BranchScope
import com.sony.sie.cicd.helpers.annotations.MasterScope
import com.sony.sie.cicd.helpers.annotations.PRScope
import com.sony.sie.cicd.helpers.annotations.StageLabel
import com.sony.sie.cicd.helpers.annotations.StageOrder
import com.sony.sie.cicd.ci.utilities.EcrUtils
import org.codehaus.groovy.GroovyException
import com.sony.sie.cicd.ci.pipelines.unified.BaseGradlePipeline

/**
 * Use gradle to handle build, component test, publish to artifactory, and publish to ecr
 */
class LcoGradlePipeline extends BaseGradlePipeline {

    LcoGradlePipeline(def pipelineDefinition){
        super(pipelineDefinition)
    }

    @PRScope
    @MasterScope
    @BranchScope
    @StageOrder(id=71)
    @StageLabel(value="")
    def buildImage() {
        if (pipelineDefinition.dockerFileList) {
            stage('Build Image') {
                dockerBuildImage()
            }
        } else if (pipelineDefinition.releaseType == 'mono-helm' || pipelineDefinition.releaseType == 'helm') {
            stage('Build Image') {
                echo "The dockerFileList is missing in Jenkinsfile for helm related repos"
                throw new GroovyException("The dockerFileList is missing in Jenkinsfile for helm related repos!")
            }
        }
    }

    @MasterScope
    @BranchScope
    @StageOrder(id=82)
    @StageLabel(value="")
    def publishECR() {
        if (pipelineDefinition.dockerFileList) {
            stage('Push Image') {
                dockerPushImage()
            }
        }
    }
}
