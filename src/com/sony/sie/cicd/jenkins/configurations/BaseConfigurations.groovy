package com.sony.sie.cicd.jenkins.configurations

import com.sony.sie.cicd.cd.utilities.HelmUtils
import com.sony.sie.cicd.cd.utilities.SecurityUtils
import com.sony.sie.cicd.helpers.utilities.ConfigUtilities
import com.sony.sie.cicd.helpers.utilities.JenkinsSteps
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import com.sony.sie.cicd.helpers.utilities.KmjEnv
import org.codehaus.groovy.GroovyException

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson

abstract class BaseConfigurations extends JenkinsSteps {
    final JenkinsUtils jenkinsUtils = new JenkinsUtils()
    def pipelineDefinition
    def checkoutConfig = [infrastructure: "navigator-cloud", clusterId: "uks-4885-e1-usw2-tgyh", region: "us-west-2"]

    BaseConfigurations(def pipelineDefinition){
        this.pipelineDefinition = pipelineDefinition
    }

    abstract def configDeploymentMap(def confMap, def deployInfo, String fileName, String cluster)

    def process() {
        def conf = null
        jenkinsUtils.jenkinsNode(templateType: 'helm', infrastructure: checkoutConfig.infrastructure, clusterList: [checkoutConfig.clusterId], awsRegion: checkoutConfig.region) {
            stage('checkout') {
                jenkinsUtils.k8sAccessConfig checkoutConfig
                pipelineDefinition.clusterInfoList = jenkinsUtils.loadCDDefaultSettings(pipelineDefinition.infrastructure)
                conf = getPipelineDefinition this.pipelineDefinition.configFile, "isoperf"
                conf.helmChartPath = pipelineDefinition.ciJob ? loadHelmChartFromCiJob(jenkinsUtils.readHelmValuesFromCiJob(pipelineDefinition.ciJob, pipelineDefinition.chartVersion)) :
                        loadHelmchartFromGithub(conf.helmChartPath, pipelineDefinition.chartVersion)
                String timeStamp = new Date().format("yyyyMMddHHmmss")
                env.STASH_HELM = "eng-cicd_helm-${timeStamp}_${env.BUILD_ID}"
                stash includes: "${conf.helmChartPath}/**", name: env.STASH_HELM

                env.APP_CHART_PATH = conf.helmChartPath
                env.REPO_WORKDIR = env.REPO_NAME
                conf.appVersion = new HelmUtils().getAppVersion()
                env.APP_VERSION = conf.appVersion
            
                if(env.APP_VERSION.contains("-")) {
                    //e.g. 1.37.0-20230216122741
                    def appVersionTimestamp = env.APP_VERSION.split("-")[-1]
                    //format: yyyy-MM-dd'T'HH:mm:ss
                    if(appVersionTimestamp.size() >= 14)
                        env.CI_RELEASE_TIME = appVersionTimestamp.substring(0, 4) + "-" + appVersionTimestamp.substring(4, 6) + "-" + appVersionTimestamp.substring(6, 8) + "T" + appVersionTimestamp.substring(8, 10) + ":" + appVersionTimestamp.substring(10, 12) + ":" + appVersionTimestamp.substring(12, 14)
                }
                conf.chartVersion = pipelineDefinition.chartVersion
                sh "env"
                conf.serviceNames = getServiceNames(env.APP_CHART_PATH, env.REPO_NAME, conf.valuesConfigFiles)

                echo "conf.serviceNames: ${conf.serviceNames}"
                if (conf && !conf.serviceNowConfig) {
                    conf.serviceNowConfig = []
                    for (int i = 0; i < conf.serviceNames.size(); i++) {
                        def item = [serviceName: conf.serviceNames[i], serviceNowCIName: conf.serviceNames[i]+"-psn"]
                        conf.serviceNowConfig.add(item)
                    }
                }
                echo "serviceNowConfig: ${conf.serviceNowConfig}"
            }
        }

        ansi_echo "Starting rollout..."
        ansi_echo "Rollout configurations:\n${prettyPrint(toJson(conf))}"
        return conf
    }

    def getServiceNames(def helmChartPath, def repoName, def valuesConfigFiles) {
        def serviceNames = []
        dir(helmChartPath) {
            def chartMap = null
            if (fileExists("requirements.yaml")) {
                chartMap = readYaml file: "requirements.yaml"
            } else {
                chartMap = readYaml file: "Chart.yaml"
            }
            if (chartMap.dependencies) {
                for (int i = 0; i < chartMap.dependencies.size(); i++) {
                    def item = chartMap.dependencies[i]
                    if (item.alias) {
                        if (item.condition) {
                            // Get line-env values
                            if (valuesConfigFiles && valuesConfigFiles.size() > 0) {
                                valuesLineEnvMap = readYaml file: valuesConfigFiles[0]
                            }
                            // Get base values
                            valuesLineEnv = valuesLineEnvMap.get(item.alias)
                            if (valuesLineEnv && valuesLineEnv.enabled != null) {
                                // Override enabled field if line-env values has it
                                valuesMap = readYaml file: "values.yaml", text: "${item.alias}: {enabled: ${valuesLineEnv.enabled}}"
                            } else {
                                valuesMap = readYaml file: "values.yaml"
                            }
                            // Add to serviceNames if enabled
                            values = valuesMap.get(item.alias)
                            if (values && values.enabled) serviceNames.add(item.alias)
                        } else {
                            serviceNames.add(item.alias)
                        }
                    }
                }
            }
        }
        if(serviceNames == []) serviceNames.add(repoName)
        echo "serviceNames: ${serviceNames}"
        return serviceNames
    }

    def getPipelineDefinition(def fileName, String clusterLineEnvName) {
        def confMap = (new ConfigUtilities()).loadCDConfiguration(fileName)
        env.REPO_NAME = confMap.github.split("/")[1]
        env.ORG_NAME = confMap.github.split("/")[0]
        //get default cluster info
        def defaultClusterInfo = getClusterDefaultInfo(clusterLineEnvName)
        //get deployment info
        def conf = null

        int plCount = 0
        def deployInfo = null
        // FIXME Helm release name can be determined from top level config or per deployEnv config.
        // def deployClusters = confMap.deployClusters
        def deployClusters = [[ name: "isoperf" ]]
        for (int i = 0; i < deployClusters.size(); i++) {
            deployInfo = deployClusters[i]
            if (clusterLineEnvName == deployInfo.name) {
                deployInfo = defaultClusterInfo << deployInfo
                conf = configDeploymentMap(confMap, deployInfo, fileName, clusterLineEnvName)
                def postDeployment = deployInfo.postDeployment ?: confMap.postDeployment
                if(!postDeployment) {
                    postDeployment = [enabled: false]
                }
                conf.testConf = postDeployment
                conf.traffic = deployInfo.traffic == false ? false : true
                String lineEnv =  deployInfo.lineEnv ?: deployInfo.sieEnv
                String cleanLineEnv = (lineEnv.contains("-") ? lineEnv.replaceAll("-", "") : lineEnv)
                conf.namespace = deployInfo.namespace ?: confMap.namespace ?: confMap.namespacePrefix + "-" + cleanLineEnv
                confMap.helmReleaseName = deployInfo.helmReleaseName ?: confMap.helmReleaseName
            }
            switch(deployInfo.name) {
                case ~/.*p1-pmgt.*/:
                case ~/.*p1-mgmt.*/:
                case ~/.*p1-pqa.*/:
                case ~/.*p1-spint.*/:
                case "tools-prod":
                case "tools-preprod":
                    plCount += 1
                    break
            }
        }

        if(conf == null) {
            ansi_echo "The configurations of ${clusterLineEnvName} can not been found in ${fileName}!", 31
            new GroovyException("The configurations of ${clusterLineEnvName} can not been found in ${fileName}!, please check the settings in in ${fileName} and try again.")
        } else {
            conf.plCount = plCount
            def newReleaseName = confMap.helmReleaseName ?: confMap.name
            env.RELEASE_NAME = newReleaseName
            conf.helmChartPath = confMap.helmChartPath ?: ("helm-unified/" + confMap.artifactory.artifactId)
            boolean deployApproval = false 
            String deployApprover = ""
            conf = [repoName: env.REPO_NAME, repoDomain: env.ORG_NAME, infrastructure: confMap.infrastructure, deploymentStrategy: 'rolling',
                    newReleaseName: newReleaseName, appName: confMap.name,
                    slackChannels: confMap.slackChannels, approvers: confMap.approvers, deployApprovalTimeOut: 1,
                    deployApproval: deployApproval, deployApprover: deployApprover, 
                    serviceNowConfig: confMap.serviceNowConfig, requestedBy: "hyperloop"] << conf
        }

        if (conf.testConf?.enabled) {
            def testJobs = []
            for (int i = 0; i < conf.testConf.testJobs.size(); i++) {
                def testJob = [rollbackOnTestFailure: false] << conf.testConf.testJobs[i]
                def lineEnvsStr = ","+testJob.lineEnvs.join(",")+","
                if(lineEnvsStr.contains(",${clusterLineEnvName},")) testJobs.add(testJob)
            }
            if(testJobs == []) {
                conf.testConf.enabled = false
            } else {
                conf.testConf.testJobs = testJobs
            }
        }

        return conf
    }

    // @NonCPS
    String getTimeStampFromVersion(String version){
        String timeStamp = ''
        if(version!=null && isVersionFormatOK(version)){
            if(version.contains("-")){
                timeStamp = version.split("-")[1]
            } else {
                timeStamp = version.split(".")[2]
            }
        }
        return timeStamp
    }

    // @NonCPS
    boolean isVersionFormatOK(String version) {
        return version ==~ /^(\d+)\.(\d+)\.(\d+)(\-\p{Alnum}+)?$/
    }

    void checkConfigParam(String paramName, def paramValue, String fileName) {
        if(paramValue == null || paramValue == "") {
            ansi_echo "The ${paramName} can not been found in ${fileName}!", 31
            new GroovyException("The ${paramName} can not been found in ${fileName}!, please check the settings in ${fileName} and try again.")
        }
    }

    def getClusterDefaultInfo(String configName) {
         //get default cluster info
        def defaultClusterInfo = [awsRegion: "us-west-2"]
        for (int i = 0; i < pipelineDefinition.clusterInfoList.size(); i++) {
            def clusterInfo = pipelineDefinition.clusterInfoList[i]
            if (configName == clusterInfo.name) {
                defaultClusterInfo = [awsRegion: "us-west-2"] << clusterInfo
                break
            }
        }
        return defaultClusterInfo
    }

    def loadHelmChartFromCiJob(def helmChartPath) {
        dir(helmChartPath) {
            container(checkoutConfig.clusterId) {
                String yamlStr = fileExists("requirements.yaml") ? readFile("requirements.yaml") : readFile("Chart.yaml")
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "engine-artifactory-access", usernameVariable: 'username', passwordVariable: 'password']]) {
                    String helmCmd = yamlStr.contains(KmjEnv.ENGINE_CHARTS_REPO_NAME) ? "skuba helm repo add ${KmjEnv.ENGINE_CHARTS_REPO_NAME} ${KmjEnv.ENGINE_CHARTS_REPO_URL} --username ${username} --password ${password}\n" : ""
                    helmCmd += yamlStr.contains(KmjEnv.ENGINE_CHARTS_PROD_VIRTUAL_REPO_NAME) ? "skuba helm repo add ${KmjEnv.ENGINE_CHARTS_PROD_VIRTUAL_REPO_NAME} ${KmjEnv.ENGINE_CHARTS_PROD_VIRTUAL_REPO_URL} --username ${username} --password ${password}\n" : ""
                    sh """
                            ${helmCmd}    
                            skuba helm dep up
                        """
                }
            }
        }

        return helmChartPath
    }

    def loadHelmchartFromGithub(def helmChartPath, def git_commit){
        try{
            jenkinsUtils.checkoutGitSCM env.REPO_NAME, git_commit, env.ORG_NAME
            dir(helmChartPath) {
                container(checkoutConfig.clusterId) {
                    String yamlStr = fileExists("requirements.yaml") ? readFile("requirements.yaml") : readFile("Chart.yaml")
                    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "engine-artifactory-access", usernameVariable: 'username', passwordVariable: 'password']]) {
                        String helmCmd = yamlStr.contains(KmjEnv.ENGINE_CHARTS_REPO_NAME) ? "skuba helm repo add ${KmjEnv.ENGINE_CHARTS_REPO_NAME} ${KmjEnv.ENGINE_CHARTS_REPO_URL} --username ${username} --password ${password}\n" : ""
                        helmCmd += yamlStr.contains(KmjEnv.ENGINE_CHARTS_PROD_VIRTUAL_REPO_NAME) ? "skuba helm repo add ${KmjEnv.ENGINE_CHARTS_PROD_VIRTUAL_REPO_NAME} ${KmjEnv.ENGINE_CHARTS_PROD_VIRTUAL_REPO_URL} --username ${username} --password ${password}\n" : ""
                        sh """
                            ${helmCmd}    
                            skuba helm dep up
                        """
                    }
                }
            }
            return helmChartPath
        } catch (Exception err) {
            String msg =  err.getMessage()
            if(!msg) msg = 'Unable to download engine chart from artifactory!'
            echo "loadHelmchartFromGithub failed: " + msg
            new SecurityUtils().sendSlackMessageOnError("engine-workflow-notify", "loadHelmchartFromGithub failed: " + msg, "FAILED","","@isoperf-flanker")
            throw err
        }
    }
}

