package com.sony.sie.cicd.ci.pipelines.navigator

import com.sony.sie.cicd.helpers.annotations.*
import org.codehaus.groovy.GroovyException
import static com.sony.sie.cicd.helpers.utilities.KmjEnv.SONARQUBE_PROD_URL
import com.sony.sie.cicd.ci.pipelines.unified.BaseMavenPipeline


class NavMavenPipeline extends BaseMavenPipeline {
    NavMavenPipeline(def pipelineDefinition) {
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
                def publishCmd = ""
                def dockerVersionTag = ""
                def dockerLifeCycleTag = ""
                def imageList= pipelineDefinition.repoInfo.imageList?:pipelineDefinition.dockerFileList
                for(int i=0; i < imageList.size(); i++) {
                    def image= imageList[i]
                    def lifeCycleVersion = getImageLifeCycleVersion()
                    def extraOpts = pipelineDefinition.repoInfo.deployOptions ?:''
                    if (extraOpts != '' ) echo "Using user set deploy options: ${pipelineDefinition.repoInfo.deployOptions}"
                    publishCmd += """
                        mvn -B clean deploy ${extraOpts} -P!docker
                    """
                    dockerVersionTag += " 890655436785.dkr.ecr.us-west-2.amazonaws.com/${image.organization}/${image.appName}:${env.APP_VERSION}"
                    dockerLifeCycleTag += " 890655436785.dkr.ecr.us-west-2.amazonaws.com/${image.organization}/${image.appName}:${lifeCycleVersion}"
                }
                containerExt([containerName: 'maven-build']){ 
                    sh "${publishCmd}"
                    def workDir = "${WORKSPACE}/${env.REPO_WORKDIR}"
                    if (pipelineDefinition.dockerDirectory != "./") workDir = "${WORKSPACE}/${env.REPO_NAME}/${pipelineDefinition?.dockerDirectory}"
                    if (fileExists('Dockerfile') || fileExists("${workDir}Dockerfile")) {
                        fetchOpsJars(workDir)
                        echo "Building Dockerfile set by user: ${workDir}..."
                        dir(workDir) {
                            sh """
                                docker build --network=host -t${dockerVersionTag} -t${dockerLifeCycleTag} .
                                docker push ${dockerVersionTag}
                                docker push ${dockerLifeCycleTag}
                            """
                        } 
                    }
                }
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
    
    def fetchOpsJars(def outputFolder = "") {
    sh """
        mvn -B -U dependency:copy -Dartifact=com.sony.sie.dts:one-eks-sekiro:1.0.32:jar -DoutputDirectory=${outputFolder}/target/dockerbuild
        cp ${outputFolder}/target/dockerbuild/one-eks-sekiro-1.0.32.jar ${outputFolder}/target/dockerbuild/one-eks-sekiro-LATEST.jar
        cp ${outputFolder}/target/dockerbuild/one-eks-sekiro-1.0.32.jar ${outputFolder}/target/dockerbuild/one-eks-sekiro-RELEASE.jar
        
        mvn -B -U dependency:copy -Dartifact=com.datadoghq:dd-java-agent:0.75.0:jar -DoutputDirectory=${outputFolder}/target/dockerbuild
        cp ${outputFolder}/target/dockerbuild/dd-java-agent-0.75.0.jar ${outputFolder}/target/dockerbuild/dd-java-agent-STABLE.jar
        
        mvn -B -U dependency:copy -Dartifact=com.datadoghq:dd-java-agent:0.97.0:jar -DoutputDirectory=${outputFolder}/target/dockerbuild
        cp ${outputFolder}/target/dockerbuild/dd-java-agent-0.97.0.jar ${outputFolder}/target/dockerbuild/dd-java-agent-RELEASE.jar
        
        mvn -B -U dependency:copy -Dartifact=com.sony.sie.apm:nav-aws-ca-apm-agent:1.0.23:zip -DoutputDirectory=${outputFolder}/target/
        unzip ${outputFolder}/target/nav-aws-ca-apm-agent-1.0.23.zip -d ${outputFolder}/target/dockerbuild
       """
    }
    
    
}
