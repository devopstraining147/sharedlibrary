import com.sony.sie.cicd.cd.utilities.Helm3KubeHelper
import com.sony.sie.cicd.helpers.utilities.*
import org.codehaus.groovy.GroovyException

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson

def call(def configure) {
    pipelineDefinition = [:]
    configure.resolveStrategy = Closure.DELEGATE_FIRST
    configure.delegate = pipelineDefinition
    configure()
    jenkinsUtils = new JenkinsUtils()
    setProperties()
    timestamps {
        ansiColor('xterm') {
            try {
                def conf = setDeploymentInfo()
                echo "=== deploymentInfo ===\n${prettyPrint(toJson(conf))}\n=== ==="
                def clusterList = ["${conf.clusterId}"]
                jenkinsUtils.jenkinsNode(templateType: 'helm', infrastructure: conf.infrastructure, clusterList: clusterList) {
                    container(conf.clusterList) {
                        execute(conf)
                    }
                }
            } catch (GroovyException err) {
                if (!jenkinsUtils.isBuildAborted()) {
                    String msg = err.getMessage()
                    if (!msg) msg = 'Unknown error!'
                    echo "Groovy Exception: " + msg
                    currentBuild.result = "FAILURE"
                }
            }
        }
    }
}

def execute(def conf) {
    echo "starting performance test"
    String releaseName = "perf-test"
    Helm3KubeHelper helmKubeHelper = new Helm3KubeHelper(conf.clusterId, conf.namespace)
    def testPodNames = []
    dir("${conf.repoName}/performance-test") {
        try {
            //Starting a new performance test by creating new pods and jobs
            def chartYaml = null
            stage("Checkout & Validate") {
                jenkinsUtils.k8sAccessConfig conf
                dir(conf.repoName) {
                    jenkinsUtils.checkoutGitSCM(conf.repoName, conf.branchName, conf.orgName)
                    def cdConfig = (new ConfigUtilities()).loadCDConfiguration(pipelineDefinition.configFile)
                    def releaseNameStatus = cdConfig.helmReleaseName ?: cdConfig.name
                    log.info "Validating release ${releaseNameStatus} exists..."
                    if(helmKubeHelper.getReleaseRevision(releaseNameStatus) == '') {
                        throw new GroovyException("Release does not exist.")
                    }

                    dir("performance-test/helm/performance-test") {
                        chartYaml = readYaml file: "Chart.yaml"
                        if(!conf.k6ScriptCmd && chartYaml.k6ScriptCmd) {
                            conf.k6ScriptCmd = chartYaml.k6ScriptCmd
                        }
                        currentBuild.description =
                                "Script: ${conf.k6ScriptCmd ?: "<a href=\"https://github.sie.sony.com/SIE/engine-performance-test/blob/main/helm/engine-performance-test/templates/batch-job.yaml\">default</a>"}"
                        def chartYamlBackup = readYaml file: "Chart.yaml"
                        def enginePerfTestChartYaml = null
                        def versionUpgraded = false
                        def repoUpgraded = false
                        GitUtils gitUtils = new GitUtils()
                        for (def dep : chartYaml.dependencies) {
                            def enginePerfTestDep = "engine-performance-test"
                            if (dep.name == enginePerfTestDep) {
                                // get version from github
                                dir(enginePerfTestDep) {
                                    gitUtils.getGitFileThroughCurl("SIE", enginePerfTestDep, "main", "helm/engine-performance-test/", "Chart.yaml", true)
                                    if(!enginePerfTestChartYaml) {
                                        enginePerfTestChartYaml = readYaml file: "Chart.yaml"
                                    }
                                    if(dep.version != enginePerfTestChartYaml.version) {
                                        log.info "Actual ${dep.name} version used ${dep.version}, upgrading in place to ${enginePerfTestChartYaml.version}"
                                        dep.version = enginePerfTestChartYaml.version
                                        versionUpgraded = true
                                    }
                                    if(dep.repository.contains("engine-helm-virtual")){
                                        dep.repository = "https://artifactory.sie.sony.com/artifactory/engine-charts-prod-virtual"
                                        log.info "Update the Artifactory repository from engine-helm-virtual to engine-charts-prod-virtual"
                                        repoUpgraded = true
                                    }
                                    
                                }
                                sh "rm -rf ${enginePerfTestDep}"
                            }
                        }
                        if(versionUpgraded || repoUpgraded) {
                            catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                                writeYaml file: "Chart.yaml", data: chartYaml, overwrite: true
                                // submit PR
                                container("build-tools") {
                                    def createPrResponse = gitUtils.createPR(conf.repoName, conf.orgName,
                                            "upgrade-engine-performance-test-chart", ["Chart.yaml"],
                                            "Recommended: upgrade engine-performance-test helm chart.",
                                            "Upgrading engine-performance-test helm chart dependency version and/or repository to engine-charts-prod-virtual in job ${env.BUILD_URL}",
                                            conf.branchName)
                                    echo "=== CREATE PR LINK === ${createPrResponse._links.html.href} === ==="
                                }
                                // rewrite updated yaml file after checkouts of PR function.
                                writeYaml file: "Chart.yaml", data: chartYaml, overwrite: true
                                error(message: "The latest version of the engine-performance-test helm chart version is outdated, submitted a PR to upgrade the version in your chart.")
                            }
                        }
                    }
                }
            }
            stage("Starting Test for ${conf.repoName}") {
                dir(conf.repoName) {
                    dir("performance-test/helm/performance-test") {
                        String parameters = ""
                        if (conf.perfTestPods) {
                            parameters += ' --set global.perfTestPods=' + conf.perfTestPods + ' '
                        }
                        if (conf.k6ScriptCmd) {
                            parameters += " --set global.k6ScriptCmd=\"${conf.k6ScriptCmd}\" "
                        }
                        def testId = "k6-engine-iso-perf-${conf.repoName}-${conf.versionTimestamp}"
                        echo "=== TEST ID ===\n${testId}\n=== ==="
                        parameters += " --set global.testId=${testId} "
                        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "engine-artifactory-access", usernameVariable: 'username', passwordVariable: 'password']]) {
                            String helmCmd = """
                                skuba helm repo add ${KmjEnv.ENGINE_CHARTS_PROD_VIRTUAL_REPO_NAME} ${KmjEnv.ENGINE_CHARTS_PROD_VIRTUAL_REPO_URL} --username ${username} --password ${password}
                                skuba helm dep up
                                skuba helm template ${releaseName} . ${parameters}
                                skuba helm install ${releaseName} . ${parameters} --namespace ${conf.namespace}
                            """
                            helmKubeHelper.runCommand helmCmd
                        }
                    }
                    sh "rm -rf ./performance-test/helm"
                }
                dir("${conf.repoName}/performance-test") {
                    sh "ls -l"
                    def timeoutInSeconds = 600
                    timeout(time: timeoutInSeconds, unit: 'SECONDS') {
                        helmKubeHelper.runCommand "skuba kubectl wait pods -n ${conf.namespace} -l name=perf-test --for condition=Ready --timeout=${timeoutInSeconds}s"
                    }
                    def cmdConf = [
                            command     : "skuba kubectl -n ${conf.namespace} get pod -o=jsonpath='{.items[?(@.metadata.ownerReferences[0].name==\"perf-test\")].metadata.name}'",
                            returnStdout: true]
                    String testPods = helmKubeHelper.runHelmCommand cmdConf
                    testPodNames = testPods.split(' ')
                    for (def testPodName : testPodNames) {
                        helmKubeHelper.runCommand """
                            skuba kubectl cp . ${conf.namespace}/${testPodName}:/home/k6;
                            touch K6_START.txt;
                            skuba kubectl cp K6_START.txt ${conf.namespace}/${testPodName}:/home/k6/K6_START.txt;
                            rm K6_START.txt;
                        """
                    }
                }
            }

            //Waiting for the performance test to complete and call back
            stage("Waiting for Completion of ${conf.repoName}") {
                echo "Waiting for performance testing to finish."
                def timeoutInSeconds = 3600
                def k6EndTxt = "K6_END.txt"
                timeout(time: timeoutInSeconds, unit: 'SECONDS') {
                    for (def testPodName : testPodNames) {
                        echo "Monitoring ${testPodName}."
                        while (!fileExists(k6EndTxt)) {
                            helmKubeHelper.runCommand """
                                skuba kubectl cp ${conf.namespace}/${testPodName}:/home/k6/K6_END.txt ./K6_END.txt ;
                            """
                            if (!fileExists(k6EndTxt)) {
                                sleep time: 60, unit: 'SECONDS'
                            } else {
                                // file exists, can download contents of output folder.
                                dir("${testPodName}-output") {
                                    helmKubeHelper.runCommand """
                                        skuba kubectl cp ${conf.namespace}/${testPodName}:/home/k6/output . ;
                                    """
                                    def archiveFiles = findFiles()
                                    if (archiveFiles) {
                                        echo "Archiving files [${archiveFiles.toString()}]"
                                        zip zipFile: "${testPodName}-output.zip", archive: true
                                    }
                                }
                                def returnCode = readFile k6EndTxt
                                if(returnCode.trim() != "0") {
                                    throw new GroovyException("Test execution failed!")
                                }
                            }
                        }
                        sh "rm " + k6EndTxt + ";"
                    }
                }
                echo "Performance test complete."
            }
        } catch (Exception err) {
            conf.printPodLogs = "TRUE"
            echo "performance test failed: " + err.getMessage()
            throw err
        } finally {
            //Cleanup the preformance test release
            jenkinsUtils.k8sAccessConfig conf
            if (helmKubeHelper.ifReleaseNameExist(releaseName)) {
                if(conf.printPodLogs == "TRUE") {
                    try {
                        stage("Logs for ${conf.repoName}") {
                            jenkinsUtils.k8sAccessConfig conf
                            dir("${conf.repoName}/k6-results/${conf.repoName}-${conf.versionTimestamp}") {
                                //Pulling the k6 results from the  test pods to local
                                container(conf.clusterId) {
                                    for (def testPodName : testPodNames) {
                                        def logFile = "k6-exec-${testPodName}.log"
                                        helmKubeHelper.runCommand "skuba kubectl logs ${testPodName} -n ${conf.namespace} > ${logFile}"
                                        sh "cat ${logFile}"
                                    }
                                    archiveArtifacts("k6-exec-*.log")
                                }
                            }
                        }
                    } catch (Exception ex) {
                        echo "Failed downloading logs due to " + ex.getMessage()
                    }
                }
                helmKubeHelper.deleteDeployment(releaseName, "--wait --timeout 600s")
            }
        }
    }
}

def setDeploymentInfo() {
    EnvUtils envUtils = new EnvUtils()
    def clusterId = envUtils.getClusterId("isoperf", pipelineDefinition.infrastructure)
    def awsRegion = envUtils.getClusterRegion("isoperf", pipelineDefinition.infrastructure)
    def jenkinsUtils = new JenkinsUtils()
    def deploymentConf = [
            infrastructure  : pipelineDefinition.infrastructure,
            namespace       : pipelineDefinition.namespace,
            clusterId       : clusterId,
            region          : awsRegion,
            perfTestPods    : params.NUMBER_OF_TEST_PODS,
            archive_reports : true,
            branchName      : params.BRANCH_OVERRIDE ?: jenkinsUtils.fetchDefaultBranch(pipelineDefinition.orgName, pipelineDefinition.repoName),
            orgName         : pipelineDefinition.orgName,
            repoName        : pipelineDefinition.repoName,
            k6ScriptCmd     : params.K6_SCRIPT_CMD,
            versionTimestamp: new Date().format("yyyyMMddHHmmss"),
            gitUrl          : "git@github.sie.sony.com:${pipelineDefinition.repoName}.git",
            printPodLogs    : new String(pipelineDefinition.printPodLogs ?: "FALSE")
    ]
    return deploymentConf
}

def setProperties() {
    def settings = [
            string(name: 'BRANCH_OVERRIDE', defaultValue: '', description: 'Optional: defaults to main or master github branch'),
            string(name: 'NUMBER_OF_TEST_PODS', defaultValue: '1', description: 'Optional: number of pods to execute with, default 1.'),
            string(name: 'K6_SCRIPT_CMD', defaultValue: '', description: 'Optional: Job Param will override helm chart value, and helm chart value will override default value located in the pod definition <a href=\"https://github.sie.sony.com/SIE/engine-performance-test/blob/main/helm/engine-performance-test/templates/batch-job.yaml\">here</a>')
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
            pipelineTriggers([]),
            disableConcurrentBuilds()
    ])
}
