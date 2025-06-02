package com.sony.sie.cicd.cd.utilities

import com.sony.sie.cicd.cd.pipelines.*
import com.sony.sie.cicd.helpers.utilities.JenkinsSteps
import org.codehaus.groovy.GroovyException

class PipelineFactory extends JenkinsSteps {

    def pipelineDefinition

    //Use factory and createPipeline to make sure we get the pipeline and env/currentBuild correctly
    PipelineFactory(def pipelineDefinition){
        this.pipelineDefinition = pipelineDefinition
    }

    def createPipeline(){
        def objPipeline = null
        switch (pipelineDefinition.infrastructure) {
            case "kamaji-cloud":
                objPipeline = new KCDQPipeline(pipelineDefinition)
                break
            case "navigator-cloud":
                objPipeline = new NavDQPipeline(pipelineDefinition)
                break
            case "laco-cloud":
                objPipeline = new LacoDQPipeline(pipelineDefinition)
                break
            case "roadster-cloud":
                objPipeline = new RoadsterDQPipeline(pipelineDefinition)
                break
            default:
                throw new GroovyException( "invalid infrastructure type: ${pipelineDefinition.infrastructure}")
        }
        objPipeline.deploymentSetup()
        echo "Selected Pipeline: ${objPipeline.getClass().toString()}"
        return objPipeline
    }
}
