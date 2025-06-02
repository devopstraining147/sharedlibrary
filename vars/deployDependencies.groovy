import com.sony.sie.cicd.helpers.utilities.EnvUtils
import com.sony.sie.cicd.jenkins.configurations.*
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import org.codehaus.groovy.GroovyException
import com.sony.sie.cicd.cd.utilities.PipelineFactory
import com.sony.sie.cicd.cd.utilities.PipelineProcessor
import com.sony.sie.cicd.helpers.utilities.HelmUtils

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson

/*
	dependencyPath = "helm-unified/catalyst-example-dependencies"
	infrastructure = "kamaji-cloud"
	cluster = "sandbox"
    repoName = "sie/catalyst-example"
    namespace = "catalyst-example-e1np"
*/
def call(def configure) {
    pipelineDefinition = [:]
    configure.resolveStrategy = Closure.DELEGATE_FIRST
    configure.delegate = pipelineDefinition
    configure()
    updateProperties()
    jenkinsUtils = new JenkinsUtils()
    ansiColor('xterm') {
        try {
            timestamps {
                echo "Starting Deploy Dependencies Job..."
                setupEnvInfo()
                jenkinsUtils.navNode(templateType: "checkout", isNewPod: true) {
                    stage('Preparation') {
                        dir(env.REPO_WORKDIR) {
                            cleanWs()
                            jenkinsUtils.checkoutGitSCM(env.REPO_NAME, env.GIT_COMMIT, env.ORG_NAME)
                            env.CHART_VERSION = new HelmUtils().getNewChartVersion()
                            dir("${env.HELM_CHART_PATH}") {
                                def chartYaml = readYaml file: "Chart.yaml"
                                env.RELEASE_NAME = chartYaml.name
                                new HelmUtils().updateChartFileVersion("Chart.yaml", env.CHART_VERSION)
                            }
                            String timeStamp = new Date().format("yyyyMMddHHmmss")
                            env.STASH_HELM = "eng-cicd_helm-${timeStamp}_${env.BUILD_ID}"
                            stash includes: "${env.HELM_CHART_PATH}/**", name: env.STASH_HELM
                        }
                    }
                }
                //get cluster id
                EnvUtils envUtils = new EnvUtils()
                pipelineDefinition.clusterId = envUtils.getClusterId("isoperf", pipelineDefinition.infrastructure)
                pipelineDefinition.psenv = envUtils.getPSEnv("isoperf", pipelineDefinition.infrastructure)
                pipelineDefinition = [
                        workload: "k8s-dep",
                        newReleaseName: env.RELEASE_NAME,
                        chartVersion: env.CHART_VERSION,
                        awsRegion: "us-west-2",
                        ACTION: env.ACTION,
                        clusterName: "isoperf"
                ] << pipelineDefinition
                echo "=== pipelineDefinition ===\n${prettyPrint(toJson(pipelineDefinition))}\n=== /pipelineDefinition ==="
                currentBuild.description = "Deploy: ${env.RELEASE_NAME}<br/>Version: ${env.CHART_VERSION}"
                def objPipeline = new PipelineFactory(pipelineDefinition).createPipeline()
                if(objPipeline){
                    new PipelineProcessor().process objPipeline
                } else {
                    throw new GroovyException("The pipeline can not be found! Please check the deployment settings in engine-cd-configuations")
                }
            }
        } catch (GroovyException err) {
            if(!jenkinsUtils.isBuildAborted()) {
                String msg = err.getMessage()
                if (!msg) msg = 'Unknown error!'
                echo "Groovy Exception: " + msg
                currentBuild.result = "FAILURE"
            }
        }
    }
}

void updateProperties() {
    def settings = [
            string(name: 'DEPENDENCY_PATH', defaultValue: '', description: 'Required: Path to the helm chart for dependencies.'),
            string(name: 'BRANCH_OVERRIDE', defaultValue: '', description: 'Optional: defaults to main or master github branch')
    ]
    if(params.LIB_VERSION) {
        settings.add(string(name: 'LIB_VERSION', defaultValue: 'main', description: 'CICD USE ONLY. Version/branch of the engine-iso-perf-framework to use.'))
    }
    properties([
            parameters(settings),
            buildDiscarder(
                    logRotator(
                            artifactDaysToKeepStr: '30',
                            artifactNumToKeepStr: '30',
                            daysToKeepStr: '60',
                            numToKeepStr: '20')
            ),
            disableConcurrentBuilds(),
    ])
    echo "=== PARAMS ===\n${prettyPrint(toJson(params))}\n=== /PARAMS ==="

}

void setupEnvInfo() {
    env.HELM_CHART_PATH = params.DEPENDENCY_PATH
    env.GIT_COMMIT = jenkinsUtils.removeWhiteSpaces(params.BRANCH_OVERRIDE) ?: new JenkinsUtils().fetchDefaultBranch(pipelineDefinition.orgName, pipelineDefinition.repoName)
    env.REPO_NAME = pipelineDefinition.repoName
    env.ORG_NAME = pipelineDefinition.orgName
    env.BRANCH_NAME = env.GIT_COMMIT
    env.VERSION_TIMESTAMP = new Date().format("yyyyMMddHHmmss")
    env.REPO_WORKDIR = env.REPO_NAME
    env.APP_CHART_PATH = "helm"
    env.ACTION = "DEPLOYMENT"
}
