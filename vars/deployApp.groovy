import com.sony.sie.cicd.ci.utilities.NotifyUtils
import com.sony.sie.cicd.helpers.enums.StageName
import com.sony.sie.cicd.jenkins.configurations.*
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import org.codehaus.groovy.GroovyException
import com.sony.sie.cicd.cd.utilities.PipelineFactory
import com.sony.sie.cicd.cd.utilities.PipelineProcessor

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
    def slackChannelList = []
    ansiColor('xterm') {
        try {
            timestamps {
                echo "Starting Jenkins Job..."

                jenkinsUtils.jenkinsNode(templateType: 'basic', infrastructure: pipelineDefinition.infrastructure) {
                    slackChannelList = new NotifyUtils().initiateNotification(pipelineDefinition, StageName.DEPLOY_APP.formattedName)
                }
                String chartVersion = jenkinsUtils.removeWhiteSpaces(params.VERSION)
                String ciJob = jenkinsUtils.removeWhiteSpaces(params.CI_JOB)
                if (chartVersion == "") throw new GroovyException("The chart version is not provided! Please input.")
                currentBuild.description = "Rollout: ${chartVersion}"
                env.ACTION = "DEPLOYMENT"
                env.CHART_VERSION = chartVersion
                env.REPO_NAME = pipelineDefinition.repoName
                pipelineDefinition.chartVersion = chartVersion
                pipelineDefinition.ciJob = ciJob
                switch (pipelineDefinition.infrastructure) {
                    case "kamaji-cloud":
                        pipelineDefinition = new KmjConfigurations(pipelineDefinition).process()
                        break
                    case "navigator-cloud":
                        pipelineDefinition = new NavConfigurations(pipelineDefinition).process()
                        break
                    case "laco-cloud":
                        pipelineDefinition = new LacoConfigurations(pipelineDefinition).process()
                        break
                    case "roadster-cloud":
                        pipelineDefinition = new RoadsterConfigurations(pipelineDefinition).process()
                        break
                    default:
                        throw new GroovyException("The infrastructure field is not provided!")
                }
                pipelineDefinition.ACTION = env.ACTION
                echo "=== pipelineDefinition ===\n${prettyPrint(toJson(pipelineDefinition))}\n=== /pipelineDefinition ==="

                def objPipeline = new PipelineFactory(pipelineDefinition).createPipeline()
                if(objPipeline){
                    new PipelineProcessor().process objPipeline
                } else {
                    throw new GroovyException("The pipeline can not be found! Please check the deployment settings in engine-cd-configuations")
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
            pipelineDefinition.slackChannels = slackChannelList
            new NotifyUtils().endOfPipelineNotify(pipelineDefinition, StageName.DEPLOY_APP.formattedName)
        }
    }
}

void updateProperties() {
    def settings = [
            string(name: 'VERSION', defaultValue: '', description: 'Required: the chart version (or as applicable) of the given release, not the full name of the chart (E.g. \"1.0.0-20220808105720\", \"1.0.0\")'),
            string(name: 'CI_JOB', defaultValue: '', description: 'Optional: When passed in, this job will attempt to retrieve a helm artifact from the specified CI job. The format of the file should match CHART_VERSION-isoperf.tgz')
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