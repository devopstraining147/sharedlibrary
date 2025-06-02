
package com.sony.sie.cicd.ci.pipelines.unified

import com.sony.sie.cicd.ci.pipelines.unified.BasePipeline
import com.sony.sie.cicd.helpers.annotations.*
import com.sony.sie.cicd.helpers.utilities.ArtifactoryUtils
import com.sony.sie.cicd.helpers.utilities.FileUtils

/**
 * Use sbt to handle build, component test, publish to artifactory, and publish to ecr
 *
 * Expected Jenkinsfile interface:
 *     pipelineType = 'sbt' // select the SbtPipeline
 *     releaseType = 'helm' //
 *     languageType = 'scala' //not used
 *     versionFileInfo = [filepath:'version.yaml', filetype:'yml', keyword:'version'] //must include app version in versionFile
 */
class SbtPipeline extends BasePipeline {

    SbtPipeline(def pipelineDefinition){
        super(pipelineDefinition)
    }

    def buildPreparation() {
        pipelineDefinition.defaultBuildContainer = 'sbt-build'
    }

    @StageOrder(id=1)
    @StageLabel(value="Setup")
    @PRScope
    @MasterScope
    @BranchScope
    def runPreparation(){
        containerExt() {
            new ArtifactoryUtils().getSbtCredential()
        }
        def dockerRegistryList = ["https://890655436785.dkr.ecr.us-west-2.amazonaws.com", "https://761590636927.dkr.ecr.us-west-2.amazonaws.com"]
        ecrConfig('sbt-build', dockerRegistryList)
        if(pipelineDefinition.preparation){
            exeClosure pipelineDefinition.preparation
        }
    }

    @StageOrder(id=20)
    @StageLabel(value="Set Version")
    @PRScope
    @MasterScope
    @BranchScope
    void setGithubVersion(){
        dir(env.REPO_WORKDIR) {
            new FileUtils().setValue(pipelineDefinition.versionFileInfo, env.APP_VERSION)
        }
    }

//    @StageOrder(id=25)
//    @PRScope
//    @MasterScope
//    @BranchScope
//    def StyleCheck() {
//        containerExt(){
//            sh """
//                export SBT_CREDENTIALS="/root/.sbt/.credentials"
//                export SBT_OPTS="-Dsbt.override.build.repos=true"
//                sbt scalastyle
//            """
//        }
//    }

    @PRScope
    @MasterScope
    @BranchScope
    def buildAndTest() {
        containerExt(){
            sh """
                export SBT_CREDENTIALS="/root/.sbt/.credentials"
                export SBT_OPTS="-XX:+UseG1GC -XX:+CMSClassUnloadingEnabled -Xms3G -Xmx4G -Dsbt.override.build.repos=true"
                sbt -v sbtVersion
                sbt compile
            """
        }
    }

    @MasterScope
    @BranchScope
    @PRScope
    @StageOrder(id=95)
    def publish() {
        String version = env.APP_VERSION
        def integrationTest = pipelineDefinition.integrationTest
        boolean hasIntegrationTest = integrationTest?.trim()
        if (env.CHANGE_ID && hasIntegrationTest) {
            String patch_version = (new Date()).format("yyyyMMddHHmmSS")
            version = "0.0.${patch_version}-INTEGRATION-TEST"
            env.IMAGE_VERSION = version
        }

        containerExt() {
            String cmd = /sbt "set version := \"${version}\"" "Docker\/publish"/
            sh cmd
            if (jenkinsUtils.isMainBranch()) {
                String prodImage = /sbt "set version := \"prod\"" "Docker\/publish"/
                sh prodImage
            }

        }
    }

    void versionCheck(){}
}
