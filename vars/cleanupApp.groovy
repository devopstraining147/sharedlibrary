import com.sony.sie.cicd.ci.utilities.NotifyUtils
import com.sony.sie.cicd.helpers.enums.StageName
import com.sony.sie.cicd.helpers.utilities.EnvUtils
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import org.codehaus.groovy.GroovyException

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson

/*
	configFile = "gaminglife/cd/catalyst-example.yaml"
	infrastructure = "kamaji-cloud"
	cluster = "sandbox"
*/
def call(def configure) {
    pipelineDefinition = [:]
    configure.resolveStrategy = Closure.DELEGATE_FIRST
    configure.delegate = pipelineDefinition
    configure()
    updateProperties()
    JenkinsUtils jenkinsUtils = new JenkinsUtils()
    def failureReason = ""
    ansiColor('xterm') {
        try {
            timestamps {
                echo "Starting Cleanup Jenkins Job..."
                currentBuild.description = "Cleanup - Uninstall Deployment and it's dependencies"
                env.ACTION = "CLEAN_UP"

                echo "pipelineDefinition.infrastructure: ${pipelineDefinition.infrastructure}"
                EnvUtils envUtils = new EnvUtils()
                pipelineDefinition.clusterId = envUtils.getClusterId("isoperf", pipelineDefinition.infrastructure)
                stage("Delete resources") {
                    jenkinsUtils.jenkinsNode(templateType: 'helm', infrastructure: pipelineDefinition.infrastructure, clusterList: [pipelineDefinition.clusterId]) {
                        pipelineDefinition.slackChannels = new NotifyUtils().initiateNotification(pipelineDefinition, StageName.CLEANUP_RESOURCES.formattedName)
                        jenkinsUtils.k8sAccessConfig(pipelineDefinition)
                        container(pipelineDefinition.clusterId) {

                            def helmListString = "skuba helm list -q --namespace ${pipelineDefinition.namespace}"
                            def releases = sh(script: helmListString, returnStdout: true).trim()

                            if (releases) {
                                def releaseList = releases.split("\n")

                                for (String release: releaseList) {
                                    echo "uninstalling release: ${release}"
                                    sh "skuba helm uninstall ${release} --namespace ${pipelineDefinition.namespace}"
                                }

                                sh """
                                        skuba kubectl get pod -n ${pipelineDefinition.namespace}
                                """
                            }
                        }
                    }
                }
            }
        } catch (GroovyException err) {
            failureReason = err.getMessage()
            if(!jenkinsUtils.isBuildAborted()) {
                if (!failureReason) failureReason = 'Unknown error!'
                echo "Groovy Exception: " + failureReason
                currentBuild.result = "FAILURE"
            }
        } finally {
            new NotifyUtils().endOfPipelineNotify(pipelineDefinition, StageName.CLEANUP_RESOURCES.formattedName)
        }
    }
}

void updateProperties() {
    def settings = [
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
    echo "=== PARAMS ===\n${prettyPrint(toJson(params))}\n === /PARAMS ==="
}

