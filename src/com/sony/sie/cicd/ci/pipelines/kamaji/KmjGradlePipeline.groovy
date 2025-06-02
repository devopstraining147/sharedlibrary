package com.sony.sie.cicd.ci.pipelines.kamaji

import com.sony.sie.cicd.helpers.annotations.BranchScope
import com.sony.sie.cicd.helpers.annotations.MasterScope
import com.sony.sie.cicd.helpers.annotations.PRScope
import com.sony.sie.cicd.helpers.annotations.StageLabel
import com.sony.sie.cicd.helpers.annotations.StageOrder
import com.sony.sie.cicd.helpers.utilities.FileUtils
import com.sony.sie.cicd.ci.utilities.EcrUtils
import org.codehaus.groovy.GroovyException
import com.sony.sie.cicd.ci.pipelines.unified.BaseGradlePipeline

/**
 * Use gradle to handle build, component test, publish to artifactory, and publish to ecr
 */
class KmjGradlePipeline extends BaseGradlePipeline {

    KmjGradlePipeline(def pipelineDefinition){
        super(pipelineDefinition)
    }

    @StageLabel(value="Setup")
    @PRScope
    @MasterScope
    @BranchScope
    @StageOrder(id=25)
    def runPreparation(){
        super.runPreparation()
        if(pipelineDefinition.enablePublishECR){
            ecrConfig('build-tools', ["https://761590636927.dkr.ecr.us-west-2.amazonaws.com"])
        }
    }

    @PRScope
    @MasterScope
    @BranchScope
    @StageOrder(id=71)
    @StageLabel(value="")
    def buildImage() {
        if (pipelineDefinition.dockerFileList && pipelineDefinition.enablePublishECR) {
            stage('Build and Push Image') {
                containerExt([containerName: 'build-tools']){
                    def lifeCycleVersion = getImageLifeCycleVersion()
                    dir ("${WORKSPACE}/${env.REPO_WORKDIR}") {
                        for (int i = 0; i < pipelineDefinition.dockerFileList.size(); i++) {
                            def dockerVersionTag = ""
                            def dockerLifeCycleTag = ""
                            def item = pipelineDefinition.dockerFileList[i]
                            def filePathArg = item.filepath ? "-f ${item.filepath}" : ""
                            def prefix = item.organization ?: "engine"
                            dockerVersionTag += " 890655436785.dkr.ecr.us-west-2.amazonaws.com/${prefix}/${item.appName}:${env.APP_VERSION}"
                            dockerLifeCycleTag += " 890655436785.dkr.ecr.us-west-2.amazonaws.com/${prefix}/${item.appName}:${lifeCycleVersion}"
                            sh """
                                docker build --network=host -t${dockerVersionTag} -t${dockerLifeCycleTag} . ${filePathArg}
                                docker push ${dockerVersionTag}
                                docker push ${dockerLifeCycleTag}
                            """
                        }
                    }
                }
            }
        } else if (pipelineDefinition.releaseType == 'mono-helm' || pipelineDefinition.releaseType == 'helm') {
            stage('Build and Push Image') {
                echo "The dockerFileList is missing in Jenkinsfile for helm related repos"
                throw new GroovyException("The dockerFileList is missing in Jenkinsfile for helm related repos!")
            }
        }
    }
}
