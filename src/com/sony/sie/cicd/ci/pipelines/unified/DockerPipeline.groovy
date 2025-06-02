package com.sony.sie.cicd.ci.pipelines.unified

import com.sony.sie.cicd.helpers.utilities.FileUtils
import com.sony.sie.cicd.helpers.annotations.*

class DockerPipeline extends BasePipeline {

    DockerPipeline(def pipelineDefinition){
        super(pipelineDefinition)
    }

    def buildPreparation() {
        pipelineDefinition.defaultBuildContainer = 'build-tools'
    }

    @PRScope
    @MasterScope
    @BranchScope
    @StageLabel(value="")
    @StageOrder(id=1)
    def runPreparation() {
        if(pipelineDefinition.preparation){
            stage('Setup') {
                exeClosure pipelineDefinition.preparation
            }
        }
    }

    @PRScope
    @MasterScope
    @BranchScope
    def buildAndTest() {
        dockerBuildImage()
    }

    @MasterScope
    @BranchScope
    def publish() {
        dockerPushImage()
    }

    // @PRScope
    // @MasterScope
    // @BranchScope
    void setGithubVersion(){
        // dir(env.REPO_WORKDIR) {
        //     new FileUtils().setValue(pipelineDefinition.versionFileInfo, env.APP_VERSION)
        // }
    }

}
