package com.sony.sie.cicd.ci.pipelines.unified

import com.sony.sie.cicd.helpers.utilities.CICDUtils
import com.sony.sie.cicd.helpers.utilities.FileUtils
import com.sony.sie.cicd.helpers.utilities.JenkinsSteps
import com.sony.sie.cicd.helpers.enums.BuildAction
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import com.sony.sie.cicd.helpers.utilities.HelmUtils
import com.sony.sie.cicd.helpers.enums.TriggerStatus
import org.codehaus.groovy.GroovyException
import com.sony.sie.cicd.ci.pipelines.kamaji.*
import com.sony.sie.cicd.ci.pipelines.navigator.*
import com.sony.sie.cicd.ci.pipelines.laco.*
import com.sony.sie.cicd.ci.pipelines.roadster.*

class PipelineFactory extends JenkinsSteps {
    def pipelineDefinition

    //Use factory and createPipeline to make sure we get the pipeline and env/currentBuild correctly
    PipelineFactory(def pipelineDefinition){
        this.pipelineDefinition = pipelineDefinition
    }

    def createPipeline(){
        def objPipeline = null
        def pipelineType = pipelineDefinition.pipelineType
        switch(pipelineDefinition.infrastructure) {
        case "kamaji-cloud":
            objPipeline = createKmjPipeline(pipelineType)
            break
        case "navigator-cloud":
            objPipeline = createNavPipeline(pipelineType)
            break
        case "laco-cloud":
            objPipeline = createLcoPipeline(pipelineType)
            break
        case "roadster-cloud":
            objPipeline = createRdsPipeline(pipelineType)
            break
        }
        objPipeline.setProperties()
        echo "Selected Pipeline: ${objPipeline.getClass().toString()}"
        return objPipeline
    }

    def createKmjPipeline(def pipelineType ){
        switch (pipelineType) {
            case 'maven':
                return new KmjMavenPipeline(pipelineDefinition)
                break
            case 'gradle':
                return new KmjGradlePipeline(pipelineDefinition)
                break
            default:
                return createUnifiedPipeline(pipelineType)
        }
    }

    def createNavPipeline(def pipelineType){
        switch (pipelineType) {
            case 'maven':
                return new NavMavenPipeline(pipelineDefinition)
                break
            case 'gradle':
                return new NavGradlePipeline(pipelineDefinition)
                break
            default:
                return createUnifiedPipeline(pipelineType)
        }
    }
    
    def createLcoPipeline(def pipelineType){
        switch (pipelineType) {
            case 'maven':
                return new LcoMavenPipeline(pipelineDefinition)
                break
            case 'gradle':
                return new LcoGradlePipeline(pipelineDefinition)
                break
            default:
                return createUnifiedPipeline(pipelineType)
        }
    }
    
    def createRdsPipeline(def pipelineType){
        switch (pipelineType) {
            case 'maven':
                return new RdsMavenPipeline(pipelineDefinition)
                break
            case 'gradle':
                return new RdsGradlePipeline(pipelineDefinition)
                break
            default:
                return createUnifiedPipeline(pipelineType)
        }
    }

    def createUnifiedPipeline(def pipelineType) {
        // TODO better solution for pipeline retrieval. If build tools are out of order this will fail.
        Map<String, Class> pipelineMap = [
            'maven-buildpack' : MavenBuildpackPipeline.class,
            'gradle-buildpack': GradleBuildpackPipeline.class,
            'docker'          : DockerPipeline.class,
            'docker-nodejs22' : DockerPipeline.class,
            'sbt'             : SbtPipeline.class,
            'go-buildpack'    : GolangBuildpackPipeline.class,
            'buildpack-go'    : GolangBuildpackPipeline.class
        ]
        Class<?> pipeline = pipelineMap.get(pipelineType)
        if (pipeline == null) {
            throw new GroovyException("invalid pipeline: [${pipelineType}]")
        }
        return pipeline.newInstance(pipelineDefinition)
    }

    void preparation(){
        new CICDUtils().getGitCommit()
        checkTriggerStatus()
        if(pipelineDefinition.pipelineType == "chart" || pipelineDefinition.triggerStatus == TriggerStatus.BY_HELMCHART) {
            String version = getChartVersion()
            env.CHART_VERSION = new CICDUtils().createNewAppVersion(version, env.VERSION_TIMESTAMP)
        } else {
            env.APP_VERSION = getNewReleaseVersion()
            env.CHART_VERSION = getChartVersion()
        }
    }

    private def checkTriggerStatus() {
        pipelineDefinition.triggerStatus = TriggerStatus.NO_FILE_CHANGED
        BuildAction buildAction = pipelineDefinition.buildAction
        dir(env.REPO_WORKDIR) {
            HelmUtils helmUtils = new HelmUtils()
            TriggerStatus validChanges = new JenkinsUtils().validChangeStatus(pipelineDefinition)
            echo "validChanges is set to ${validChanges}"
            pipelineDefinition.triggerStatus = validChanges
        }
    }

    String getNewReleaseVersion(){
        String newVersion = ''
        dir(env.REPO_WORKDIR) {
            String version = new FileUtils().getValue(pipelineDefinition.versionFileInfo)
            if (version == '') {
                throw new GroovyException("Can not get release version! versionFileInfo: ${pipelineDefinition.versionFileInfo}")
            }
            env.APP_PREV_VERSION = version
            newVersion = new CICDUtils().createNewAppVersion(version, env.VERSION_TIMESTAMP)
        }
        if(newVersion == null || newVersion == '') {
            throw new GroovyException( "The app version is empty!")
        }
        echo "newVersion = ${newVersion}"
        return newVersion
    }

    String getChartVersion(){
        HelmUtils helmUtils = new HelmUtils()
        String chartVersion = ''

        if(env.HELM_CHART_PATH != "" && env.HELM_CHART_PATH != "./")
            chartVersion = helmUtils.getNewChartVersion("${env.REPO_WORKDIR}/${env.HELM_CHART_PATH}")
        else
            chartVersion = helmUtils.getNewChartVersion("${env.REPO_WORKDIR}")
                
        echo "chartVersion = ${chartVersion}"
        return chartVersion
    }
}
