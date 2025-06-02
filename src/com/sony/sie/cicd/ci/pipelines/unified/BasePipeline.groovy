package com.sony.sie.cicd.ci.pipelines.unified

import com.sony.sie.cicd.helpers.utilities.JenkinsSteps
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import com.sony.sie.cicd.helpers.utilities.HelmUtils
import com.sony.sie.cicd.helpers.utilities.GitUtils
import com.sony.sie.cicd.helpers.utilities.ArtifactoryUtils
import com.sony.sie.cicd.helpers.annotations.*
import com.sony.sie.cicd.helpers.notifications.*
import com.sony.sie.cicd.helpers.utilities.KmjEnv
import com.sony.sie.cicd.helpers.lint.ChartVersionCheck
import com.sony.sie.cicd.helpers.lint.HelmTemplateLint
import com.sony.sie.cicd.ci.utilities.MLUtils
import com.sony.sie.cicd.ci.utilities.CSIUtils
import com.sony.sie.cicd.ci.utilities.ChartUtils
import com.sony.sie.cicd.ci.utilities.EnginePomUtils
import org.codehaus.groovy.GroovyException
// import com.sony.sie.cicd.ci.tasks.QualysImageScanAnalysis
import com.sony.sie.cicd.ci.utilities.AWSUtils
import static com.sony.sie.cicd.helpers.utilities.KmjEnv.ECR_HOST_URL

abstract class BasePipeline extends JenkinsSteps {
    final def jenkinsUtils = new JenkinsUtils()
    def basePipelineClassList = [BasePipeline.class]
    def pipelineDefinition

    public BasePipeline(def pipelineDefinition){
        this.pipelineDefinition = pipelineDefinition
    }

    def setProperties(){
        buildPreparation()
    }

    abstract def buildPreparation()

    // @StageOrder(id=10)
    // void versionCheck(){}

    // @MasterScope
    // @BranchScope
    // @PRScope
    // @StageOrder(id=11)
    // @StageLabel(value="")
    // def csiValidation() {
    //     if (pipelineDefinition.specFiles) {
    //         try {
    //             stage('API Spec Lint') {
    //                 containerExt(containerName: "csi") {
    //                     new CSIUtils().csiApiScanner(pipelineDefinition.specFiles)
    //                 }
    //             }
    //         }
    //         catch (Exception err) {
    //             echo "The API Spec Lint failed: " + err.getMessage()
    //             currentBuild.result = "UNSTABLE"
    //         }
    //     }
    // }

    @StageOrder(id=20)
    @StageLabel(value="Set Version")
    abstract void setGithubVersion()

    @StageOrder(id=30)
    @StageLabel(value="Build & Test")
    abstract def buildAndTest()

    // @StageOrder(id=40)
    // def componentTest(){}

    // @StageOrder(id=50)
    // @StageLabel(value="")
    // def codeAnalysis(){}

    // @StageOrder(id=55)
    // @StageLabel(value="")
    // abstract void chartDependencyCheck()

    // @StageOrder(id=56)
    // @StageLabel(value="")
    // abstract void helmTemplateLint()

    @StageOrder(id=80)
    abstract def publish()

    // @MasterScope
    // @BranchScope
    // @StageOrder(id=77)
    // @StageLabel(value="")
    // def publishMapi() {
    //     if (pipelineDefinition.mlFlow) {
    //         stage('Publish job to Mastermind-API') {
    //             checkIsValidBranchForPublishing()

    //             echo "starting publishing with MAPI stage ..."
    //             String tagName = "${env.REPO_NAME}-${env.APP_VERSION}"
    //             String baseYamlKey = "${env.REPO_NAME}/${tagName}.yml"

    //             containerExt(containerName: "mapi-submit") {
    //                 // Submit the main model.yml file
    //                 String mapiResponse = new MLUtils().createMapiJob("model.yml", env.BRANCH_NAME, baseYamlKey, tagName)
    //                 String job_id = mapiResponse.split(" ")[-1]

    //                 env.CUSTOM_DESCRIPTION = " - MAPI response job_id: ${job_id}"
    //                 env.JOB_ID = job_id
    //                 echo mapiResponse

    //                 // Submit all extra model files of the form model-conf#.yml
    //                 def files = findFiles(glob: "model-*.yml")
    //                 for (int i = 0; i < files.size(); i++) {
    //                     String fileName = files[i]
    //                     String yamlKey = "${env.REPO_NAME}/${tagName}-${fileName.split('-')[1]}"
    //                     mapiResponse = new MLUtils().createMapiJob(fileName, env.BRANCH_NAME, yamlKey, tagName, true)
    //                     echo mapiResponse
    //                 }
    //             }
    //         }
    //     }
    // }

    // @MasterScope
    // @BranchScope
    // @StageOrder(id=78)
    // @StageLabel(value="")
    // def deployWithKubeflow() {
    //     if (!pipelineDefinition.kubeflowDeploy) {
    //         return
    //     }
    //     stage('Deploying with Kubeflow') {

    //         String job_id = env.JOB_ID.replaceAll("[^A-Za-z0-9]", "")

    //         String pipeline_id = pipelineDefinition.pipelineId
    //         String experiment_id = pipelineDefinition.experimentId
    //         String job_name = pipelineDefinition.jobName
    //         String user = pipelineDefinition.user

    //         def artifact_arg = ""
    //         if (pipelineDefinition.databricks && pipelineDefinition.artifactName) {
    //             artifact_arg = "--artifact_name ${pipelineDefinition.artifactName}"
    //         }

    //         def sh_command = """
    //             /usr/bin/python /app/docker-images/kfp_deploy/kfp_deploy.binary \
    //             --pipeline_id ${pipeline_id} \
    //             --experiment_id ${experiment_id} \
    //             --job_name ${job_name} \
    //             --user ${user} \
    //             --repo ${env.REPO_NAME} \
    //             --version ${env.APP_VERSION} \
    //             --language ${pipelineDefinition.languageType} \
    //             --main_class ${pipelineDefinition.mainClass} \
    //             ${artifact_arg}
    //         """
    //         echo sh_command

    //         containerExt(containerName: "kfp-deploy") {
    //             sh"${sh_command}"
    //         }
    //     }
    // }


    // @StageOrder(id=90)
    // @BranchScope
    // @PRScope
    // @MasterScope
    // @StageLabel(value="")
    // def publishHelmChartToArtifactory() {
    //     if(pipelineDefinition.publishHelmChartToArtifactory) {
    //         stage("Publish To Artifactory") {
    //             containerExt([containerName: 'helm']){
    //                 new ArtifactoryUtils().publishHelmChartToArtifactory pipelineDefinition
    //             }
    //         }
    //     } 
    // }

    // @StageOrder(id=110)
    // @BranchScope
    // @MasterScope
    // @PRScope
    // @StageLabel(value="")
    // def startCDJobs() {
    //     if(pipelineDefinition.publishHelmChartToArtifactory) {
    //         try{
    //             triggerRemoteCDJobs()
    //         } catch (Exception err) {
    //             echo "Start CD jobs failed: " + err.getMessage()
    //             currentBuild.result = "UNSTABLE"
    //         }
    //     }
    // }

    // def triggerRemoteCDJobs() {
    //     if (pipelineDefinition.autoCDtrigger) {//the old settings
    //         stage("Start CD Job") {
    //             containerExt([containerName: 'build-tools']){
    //                 def triggerMap = [tokenCredential: "engine-workflow-jenkins-token", blockBuildUntilComplete: false]
    //                 triggerMap.remoteJenkinsUrl = pipelineDefinition.autoCDtrigger.jenkinsJob
    //                 echo "starting CD Jenkins job: ${triggerMap.remoteJenkinsUrl}"
    //                 triggerMap.parameters = "CHART_VERSION=${env.CHART_VERSION}\nDEPLOY_UP_TO=${pipelineDefinition.autoCDtrigger.deployUpto.toUpperCase()}"
    //                 jenkinsUtils.triggerRemoteJenkinsJob triggerMap
    //             }
    //         }
    //     } else {
    //         def jenkinsJobList = []
    //         def helmChartList = pipelineDefinition.helmChartConfigs
    //         for (int i = 0; i < helmChartList.size(); i++) {
    //             def jenkinsJobs = helmChartList[i].autoCDtrigger
    //             if(jenkinsJobs) {
    //                 def serviceName = helmChartList[i].helmChartPath.split("/")[-1]
    //                 for (int ii = 0; ii < jenkinsJobs.size(); ii++) {
    //                     def item = jenkinsJobs[ii]
    //                     if(env.CHANGE_ID && item.isPR || !env.CHANGE_ID && !item.isPR) {
    //                         item.jobName = serviceName + "/" + item.jenkinsJobUrl.split("job/")[-1].replaceAll("/", "")
    //                         jenkinsJobList.add(item)
    //                     }
    //                 }
    //             }
    //         }
    //         if(jenkinsJobList.size() == 1){
    //             stage("Start CD Job") {
    //                 jenkinsUtils.triggerRemoteJenkinsJob getCDtriggerMap(jenkinsJobList[0])
    //             }
    //         } else if(jenkinsJobList.size() > 1) {
    //             stage("Start CD Jobs") {
    //                 def parallelCDJobs = [:]
    //                 for (int i = 0; i < jenkinsJobList.size(); i++) {
    //                     def jenkinsJob = jenkinsJobList[i]
    //                     def triggerMap = getCDtriggerMap(jenkinsJob)
    //                     def cdLabel = "Start CD Job for ${jenkinsJob.jobName}"
    //                     parallelCDJobs[cdLabel] = {
    //                         stage(cdLabel) {
    //                             echo "starting CD Jenkins job: ${triggerMap.remoteJenkinsUrl}"
    //                             jenkinsUtils.triggerRemoteJenkinsJob triggerMap
    //                         }
    //                     }
    //                 }
    //                 parallel(parallelCDJobs)
    //             }
    //         }
    //     }
    // }

    /* Jenkins job config
        jenkinsJobUrl: https://core.jenkins.hyperloop.sonynei.net/gaminglife-cd/job/engine-pipeline-example/job/orchestration/
        parameters: 
        - name: "DEPLOY_UP_TO"
          value: "E1-PMGT_NP"
    */
    // def getCDtriggerMap(def cdJob) {
    //     def triggerMap = [tokenCredential: "engine-workflow-jenkins-token", 
    //                     remoteJenkinsUrl: cdJob.jenkinsJobUrl,
    //                     blockBuildUntilComplete: true, 
    //                     parameters: "CHART_VERSION=${env.CHART_VERSION}"]
    //     if(cdJob.parameters) {
    //         for (int i = 0; i < cdJob.parameters.size(); i++) {
    //             def parameter = cdJob.parameters[i]
    //             triggerMap.parameters += "\n${parameter.name}=${parameter.value}"
    //         }
    //     }
    //     return triggerMap
    // }
    
    // @StageOrder(id = 115)
    // @PRScope
    // @MasterScope
    // @BranchScope
    // @StageLabel(value = '')
    // def publishEngine() {
    //     if (pipelineDefinition.legacyBuild) {
    //         stage('Publish Engine POM to Artifactory') {
    //             containerExt([containerName: 'maven-build']) {
    //                 enginePomUtils = new EnginePomUtils()
    //                 enginePomUtils.setEnginePoms()
    //             }
    //         }
    //     }
    // }

    // @StageOrder(id=120)
    // @BranchScope
    // @MasterScope
    // @StageLabel(value="")
    // def triggerRemoteCIJob() {
    //     if((pipelineDefinition.buildAfter != null) && (pipelineDefinition.buildAfter.urls != null && pipelineDefinition.buildAfter.urls.size() > 0)) {
    //         stage("Start CI: Build After") {
    //             containerExt([containerName: 'build-tools']){
    //                 def parallelBuildAfterMap = [:]
    //                 for (int i = 0; i < pipelineDefinition.buildAfter.urls.size(); i++) {
    //                     def triggerMap = [tokenCredential: "engine-workflow-jenkins-token", blockBuildUntilComplete: pipelineDefinition.triggersWaitForBuild]
    //                     def jobUrl = pipelineDefinition.buildAfter.urls[i]
    //                     parallelBuildAfterMap["buildAfter${i}"] = {
    //                         triggerMap.remoteJenkinsUrl = jobUrl
    //                         echo "starting CI Jenkins job: ${triggerMap.remoteJenkinsUrl}"
    //                         jenkinsUtils.triggerRemoteJenkinsJob triggerMap
    //                     }
    //                 }
    //                 parallel parallelBuildAfterMap
    //             }
    //         }
    //     } 
    // }
  



    def NotifyTeamChannel(String msgSlack, String buildStatus) {
        if(buildStatus == "FAILURE" && pipelineDefinition.slackIfBuildFailed ||
                (buildStatus=="SUCCESS" || buildStatus=="UNSTABLE") && pipelineDefinition.slackIfBuildPassed){
            try {
                echo "Sending notification to slack channel: ${pipelineDefinition.teamSlackChannel}"
                echo "Slack message:\n${msgSlack}"
                new SlackNotifications().sendSimpleSlackNotification(pipelineDefinition.teamSlackChannel, msgSlack, buildStatus)
            } catch (Exception err) {
                echo "Can not send notification to slack: ${err.getMessage()}"
            }
        }
    }

    def getSlackMessage(String buildStatus, String stageLabel, String errMsg = '' ) {
        String msgSlack =  "Build Status: ${buildStatus}\n"
        if(stageLabel!="") {
            msgSlack +=  "Build Stage: ${stageLabel}\n"
        }
        if(errMsg!="") {
            msgSlack += "Error: ${errMsg}\n"
        }
        msgSlack += "Jenkins job: ${env.JOB_URL}${env.BUILD_ID}\n Dash url: ${env.DASH_URL}"
        return msgSlack
    }

    def process(String name, String stageLabel='') {
        this."$name"()
    }

    void containerExt( String cmd){
        containerExt(){ sh cmd }
    }

    void containerExt(Closure body){
        containerExt([containerName: pipelineDefinition.defaultBuildContainer], body)
    }

    void containerExt(Map conf, Closure body){
        conf = [workDir: env.REPO_WORKDIR] << conf
        if(conf.workDir == "") {
            if (conf.containerName) {
                container(conf.containerName) {
                    exeClosure(body)
                }
            } else {
                exeClosure(body)
            }
        } else {
            dir(conf.workDir) {
                if (conf.containerName) {
                    container(conf.containerName) {
                        exeClosure(body)
                    }
                } else {
                    exeClosure(body)
                }
            }
        }
    }

    def exeClosure(Closure body) {
        if(body != null){
            body.resolveStrategy = Closure.DELEGATE_FIRST
            body.delegate = this
            body()
        }
    }

    void checkoutRepo(def repoName, def branchName = 'master', def org = env.ORG_NAME, boolean changelog=false){
        checkoutRepo repoName: repoName, branchName: branchName, org: org, changelog: changelog
    }

    void checkoutRepo(Map conf){
        //params: repoName, branchName, org, workDir
        conf = [branchName: 'master', org: env.ORG_NAME, changelog: false] << conf
        if(!conf.workDir) conf.workDir = conf.repoName
        dir(conf.workDir){
            checkoutGitSCM(conf.repoName, conf.branchName, conf.org, conf.changelog)
        }
    }

    void checkoutGitSCM(def repoName, def branchName = 'master', def org = env.ORG_NAME, boolean changelog = false) {
        jenkinsUtils.checkoutGitSCM(repoName, branchName, org, changelog)
    }

    def ecrConfig(def containerName = pipelineDefinition.defaultBuildContainer, def dockerRegistryList = ["https://890655436785.dkr.ecr.us-west-2.amazonaws.com"]) {
        def dockerRegion = "us-west-2"
        def cred = ""
        container('build-tools') {
            cred = sh (returnStdout: true, script: "aws ecr get-login-password --region ${dockerRegion}")
        }
        container(containerName){
            writeFile file: "ecrCred.txt", text: cred
            for(def dockerRegistry in dockerRegistryList) {
                sh "cat ecrCred.txt | docker login -u AWS --password-stdin ${dockerRegistry}"
            }
            sh "rm ecrCred.txt"
        }
    }

    def dockerBuildImage(String containerName = 'build-tools', String buildCmd = "") {
        if(pipelineDefinition.pipelineType.contains('nodejs')){
            container(containerName) {
                def awsNpmSecret = jenkinsUtils.fetchNpmSecret()
                def sieNpmToken = jenkinsUtils.fetchSiePrivateNpmSecret()
                jenkinsUtils.npmConfig(awsNpmSecret, sieNpmToken)
            }
        }
        for (int i = 0; i < pipelineDefinition.dockerFileList.size(); i++) {
            def item = pipelineDefinition.dockerFileList[i]
            def lifeCycleVersion = getImageLifeCycleVersion()
            item = [organization: 'psn', version: env.APP_VERSION, extraOptions: '', contextPath: "."] << item
            String dockerVersionTag = "${item.appName}:${item.version}"
            String dockerLifeCycleTag = "${item.appName}:${lifeCycleVersion}"
            String cmd = "docker build --network=host -t ${dockerVersionTag} -t ${dockerLifeCycleTag} --build-arg VERSION=${env.APP_VERSION} -f ${item.filepath} ${item.contextPath} ${item.extraOptions}"
            cmd += ' --build-arg ArtifactoryUsername=$ARTIFACTORY_USERNAME --build-arg ArtifactoryPassword=$ARTIFACTORY_PASSWORD'
            if (pipelineDefinition.infrastructure != "roadster-cloud") {
                cmd += ' --build-arg HTTPS_PROXY=http://squid.internal.aws:3128 --build-arg HTTP_PROXY=http://squid.internal.aws:3128'
            }
            if (buildCmd == '')
                buildCmd = cmd
            else
                buildCmd += " && " + cmd
        }
        def dockerRegistryList = ["https://890655436785.dkr.ecr.us-west-2.amazonaws.com"]
        if(pipelineDefinition.infrastructure=="kamaji-cloud"){
            dockerRegistryList.add("https://761590636927.dkr.ecr.us-west-2.amazonaws.com")
        }
        ecrConfig(containerName, dockerRegistryList)
        containerExt(containerName: containerName){
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "engine-artifactory-access", usernameVariable: 'username', passwordVariable: 'password']]) {
                withEnv(["ARTIFACTORY_USERNAME=${username}", "ARTIFACTORY_PASSWORD=${password}"]) {
                    sh buildCmd
                }
            }
        }
    }

    def dockerPushImage(String containerName = 'build-tools') {
        String publishCmd = ''
        for (int i = 0; i < pipelineDefinition.dockerFileList.size(); i++) {
            def item = pipelineDefinition.dockerFileList[i]
            def lifeCycleVersion = getImageLifeCycleVersion()
            item = [organization: 'psn', version: env.APP_VERSION] << item
            String url = "${ECR_HOST_URL}/${item.organization}"
            String dockerVersionTag = "${item.appName}:${item.version}"
            String dockerLifeCycleTag = "${item.appName}:${lifeCycleVersion}"
            String tagCmd = "docker tag ${dockerVersionTag} ${url}/${dockerVersionTag} && docker tag ${dockerLifeCycleTag} ${url}/${dockerLifeCycleTag}"
            String pushCmd = "docker push ${url}/${dockerVersionTag} && docker push ${url}/${dockerLifeCycleTag}"
            if (publishCmd == '')
                publishCmd = tagCmd + " && " + pushCmd
            else
                publishCmd += " && " + tagCmd + " && " + pushCmd
        }
        ecrConfig(containerName)
        containerExt(containerName: containerName){
            sh publishCmd
        }
    }

    // UCR lifeCycle version following the tagging rule defined in ADR
    // https://github.sie.sony.com/SIE/EnginE/pull/148/files
    def getImageLifeCycleVersion() {
        def lifeCycleVersion = ""
        if(env.CHANGE_ID){
            lifeCycleVersion = "snapshot-"+env.APP_VERSION
        } else {
            if (env.BRANCH_NAME.toLowerCase().contains("hotfix")) {
                lifeCycleVersion = "hotfix-"+env.APP_VERSION
            } else {
                lifeCycleVersion = "release-"+env.APP_VERSION
            } 
        }
        echo "Image tag lifeCycle version: ${lifeCycleVersion}"
        return lifeCycleVersion
    }

    def sendSlackMessageOnError(def channelName, def message , def buildStatus , def subject = '', def audience = "@here") {
        def messagUrl = message + "\n Check more details in: ${env.BUILD_URL}."
        new SlackNotifications().sendSimpleSlackNotification(channelName, messagUrl, buildStatus, subject, audience)
    }

    def createManifest(def isAmd64, def isArm64) {
        def lifeCycleVersion = getImageLifeCycleVersion()
        containerExt([containerName: "packcli"]){ 
            packcliConfig.each{ key,val->
                def serviceName=key
                String imagePrefix=val.repoPrefix?:"psn"
                def dockerVersionTag = "${ECR_HOST_URL}/${imagePrefix}/${serviceName}:${env.APP_VERSION}"
                def dockerLifeCycleTag = "${ECR_HOST_URL}/${imagePrefix}/${serviceName}:${lifeCycleVersion}"
                
                // create manifest
                def manifestCreateLifeCycleTag = "docker manifest create ${dockerLifeCycleTag}"
                def manifestAnnotateCmd =""
                def manifestCreateVersionTag = "docker manifest create ${dockerVersionTag}"
                
                echo "isAmd64: ${isAmd64}"
                echo "isArm64: ${isArm64}"  

                if (isAmd64) {
                    manifestCreateLifeCycleTag += " ${dockerLifeCycleTag}-amd64"
                    manifestCreateVersionTag += " ${dockerLifeCycleTag}-amd64"
                    manifestAnnotateCmd += """
                        docker manifest annotate ${dockerLifeCycleTag} ${dockerLifeCycleTag}-amd64 --os linux --arch amd64
                    """
                }
                
                if (isArm64) {
                    manifestCreateLifeCycleTag += " ${dockerLifeCycleTag}-arm64"
                    manifestCreateVersionTag += " ${dockerLifeCycleTag}-arm64"
                    manifestAnnotateCmd += """
                        docker manifest annotate ${dockerLifeCycleTag} ${dockerLifeCycleTag}-arm64 --os linux --arch arm64
                    """
                }
                
                sh """
                    ${manifestCreateLifeCycleTag}
                    ${manifestAnnotateCmd}
                    docker manifest push ${dockerLifeCycleTag}
                    ${manifestCreateVersionTag} 
                    docker manifest push ${dockerVersionTag}
                """
            }
        }
    }

    @StageOrder(id=100)
    @BranchScope
    @MasterScope
    @PRScope
    @StageLabel(value="")
    def release() {
        stage("Release") {
            container("helm-ci") {
                dir(env.REPO_WORKDIR) {
                    sh label: "WORKDIR FILES", script: "pwd; ls -lath"
                    def helmUtils = new HelmUtils()
                    helmUtils.updateHelmFilesForService(pipelineDefinition, env.APP_VERSION.toString(), env.CHART_VERSION.toString())
                    dir(env.HELM_CHART_PATH) {
                        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "engine-artifactory-access", usernameVariable: 'username', passwordVariable: 'password']]) {
                            String yamlStr = fileExists("requirements.yaml") ? readFile("requirements.yaml") : readFile("Chart.yaml")
                            String helmCmd = yamlStr.contains(KmjEnv.ENGINE_CHARTS_REPO_NAME) ? "helm repo add ${KmjEnv.ENGINE_CHARTS_REPO_NAME} ${KmjEnv.ENGINE_CHARTS_REPO_URL} --username ${username} --password ${password}\n" : ""
                            helmCmd += yamlStr.contains(KmjEnv.ENGINE_CHARTS_PROD_VIRTUAL_REPO_NAME) ? "helm repo add ${KmjEnv.ENGINE_CHARTS_PROD_VIRTUAL_REPO_NAME} ${KmjEnv.ENGINE_CHARTS_PROD_VIRTUAL_REPO_URL} --username ${username} --password ${password}\n" : ""
                            sh """${helmCmd}
                                helm dep up
                                helm package ."""
                            def originalTgzName = "${env.REPO_NAME}-${env.CHART_VERSION}.tgz"
                            def targetTgzName = "${env.CHART_VERSION}-isoperf.tgz"
                            sh "mv ${originalTgzName} ${targetTgzName}"
                            archiveArtifacts artifacts: targetTgzName, fingerprint: true
                            echo "IsoPerf Build: Archived ${targetTgzName}"
                            build job: 'deploy-app',
                                    parameters: [
                                            string(name: 'CI_JOB', value: "${env.BUILD_URL}"),
                                            string(name: 'VERSION', value: "${env.CHART_VERSION}")
                                    ]
                        }
                    }
                }
            }
        }
    }

}


