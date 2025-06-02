package com.sony.sie.cicd.ci.pipelines.navigator

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
class NavGradlePipeline extends BaseGradlePipeline {

    NavGradlePipeline(def pipelineDefinition){
        super(pipelineDefinition)
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
                    def dockerVersionTag = ""
                    def dockerLifeCycleTag = ""
                    def lifeCycleVersion = getImageLifeCycleVersion()
                    def workDir = "${WORKSPACE}/${env.REPO_WORKDIR}"
                    for (int i = 0; i < pipelineDefinition.dockerFileList.size(); i++) {
                        def item = pipelineDefinition.dockerFileList[i]
                        dockerVersionTag += " 890655436785.dkr.ecr.us-west-2.amazonaws.com/engine/${item.appName}:${env.APP_VERSION}"
                        dockerLifeCycleTag += " 890655436785.dkr.ecr.us-west-2.amazonaws.com/engine/${item.appName}:${lifeCycleVersion}"
                        dir (workDir) {
                            sh """
                                docker build --network=host -t${dockerVersionTag} -t${dockerLifeCycleTag} .
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
