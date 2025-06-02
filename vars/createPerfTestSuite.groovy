import com.sony.sie.cicd.helpers.enums.StageName
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import org.codehaus.groovy.GroovyException

def call(def config) {
    catchError(buildResult: 'failure', stageResult: 'failure') {
        try {
            setJobProperties()
            jenkinsUtils = new JenkinsUtils()
            ansiColor('xterm') {
                timestamps {
                    jenkinsUtils.navNode(templateType: "generic") {
                        cleanWs()
                        container("build-tools") {
                            process(config)
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.error(ex)
            ex.printStackTrace()
            throw ex
        }
    }
}

def process(def config) {
    library('engine-cicd-utilities')
    //def pipelineDef = [:]
    String perfTestFolderViewTeams = null
    String perfTestFolderBuildTeams = null
    def parallelStages = [:]
    def configFile = new JenkinsUtils().removeWhiteSpaces(params.CONFIG_FILE ?: "") ?: "ALL_IN_CONTROLLER"
    def perfTestFolderPath = "iso-perf-test-jobs"

    stage("Preparation") {
        log.info "Preparing for the iso perf test jobs..."
        def branchName = config?.branchOverride ?: "master"
        String engineCdConfigRepo = "engine-cd-configurations"
        dir(engineCdConfigRepo) {
            jenkinsUtils.checkoutGitSCM(engineCdConfigRepo, branchName, "SIE")
        }
        currentBuild.description = configFile ?: "ALL_IN_CONTROLLER"
        def controller = new URI(env.JENKINS_URL).getPath().split("/")[1]
        log.info "Controller = ${controller}"
        log.info "Running with config file ${configFile}"

        List<String> files = configFile == "ALL_IN_CONTROLLER" ? [] : [configFile]
        if(!files) {
            dir(engineCdConfigRepo) {
                String toOnboard = libraryResource "onboarding-list.txt"
                //String fileStdout = sh(returnStdout: true, script: "find . -name '*.yaml' | cut -c 3-")
                files.addAll(toOnboard.split("\n"))
            }
        }

        for(String file : files) {
            if(file && controller.contains(file.split("/")[0])) {
                String cdConfigFile = file
                log.info "File ${cdConfigFile} is on controller ${controller}, proceeding."
                def pipelineDef = Collections.unmodifiableMap(getPipelineDefinition(engineCdConfigRepo, cdConfigFile))
                String viewTeams = pipelineDef.permission.browse.join(",")
                String buildTeams = pipelineDef.permission.deploy.nonprod.join(",") + "," + pipelineDef.permission.deploy.prod.join(",")
                perfTestFolderViewTeams = perfTestFolderViewTeams ? perfTestFolderViewTeams + "," + viewTeams : viewTeams
                perfTestFolderBuildTeams = perfTestFolderBuildTeams ? perfTestFolderBuildTeams + "," + buildTeams : buildTeams
                echo "${buildTeams}"

                // folder vs actual job path for API are different.
                String absFolderPath = perfTestFolderPath + "/" + pipelineDef.jenkinsJobPath
                String absParentJobPath = perfTestFolderPath + "/job/" + pipelineDef.jenkinsJobPath
                parallelStages[cdConfigFile] = {
                    stage(pipelineDef.name) {
                        stage("Parent Folder") {
                            def folderPermConf = [
                                    repoName: absParentJobPath,
                                    aloyGroupsToView: viewTeams,
                                    aloyGroupsToBuild: buildTeams
                            ]
                            log.info "Creating folder: ${absFolderPath} with config: ${folderPermConf}"
                            createFolder absFolderPath, pipelineDef.jenkinsJobPath
                            createCIJob.authorization4Existingteams(folderPermConf)
                        }
                        stage("Build Job") {
                            // create build
                            def buildJobPath = absFolderPath + "/build-app"
                            def folderPermConf = [
                                    repoName: absParentJobPath + "/job/build-app",
                                    aloyGroupsToView: viewTeams,
                                    aloyGroupsToBuild: buildTeams
                            ]
                            log.info "Creating [${buildJobPath}] with config: ${folderPermConf}"
                            createJob(cdConfigFile, pipelineDef, StageName.BUILD_APP.formattedName, buildJobPath)
                            createCIJob.authorization4Existingteams(folderPermConf)
                        }
                        stage("Deploy App Job") {
                            // create deploy job
                            def deployJobPath = absFolderPath + "/deploy-app"
                            def folderPermConf = [
                                    repoName: absParentJobPath + "/job/deploy-app",
                                    aloyGroupsToView: viewTeams,
                                    aloyGroupsToBuild: buildTeams
                            ]
                            log.info "Creating [${deployJobPath}] with config: ${folderPermConf}"
                            createJob(cdConfigFile, pipelineDef, StageName.DEPLOY_APP.formattedName, deployJobPath)
                            createCIJob.authorization4Existingteams(folderPermConf)
                        }
                        stage("Deploy Deps Job") {
                            def deployDepsJobPath = absFolderPath + "/deploy-deps"
                            // create deploy dependencies
                            def folderPermConf = [
                                    repoName         : absParentJobPath + "/job/deploy-deps",
                                    aloyGroupsToView : viewTeams,
                                    aloyGroupsToBuild: buildTeams
                            ]
                            log.info "Creating [${deployDepsJobPath}] with config: ${folderPermConf}"
                            createJob(cdConfigFile, pipelineDef, StageName.DEPLOY_DEPENDENCIES.formattedName, deployDepsJobPath)
                            createCIJob.authorization4Existingteams(folderPermConf)
                        }
                        stage("Iso Perf Test Job") {
                            // create iso perf test job
                            def testJobPath = absFolderPath + "/iso-perf-test"
                            def folderPermConf = [
                                    repoName         : absParentJobPath + "/job/iso-perf-test",
                                    aloyGroupsToView : viewTeams,
                                    aloyGroupsToBuild: buildTeams
                            ]
                            log.info "Creating [${testJobPath}] with config: ${folderPermConf}"
                            createJob(cdConfigFile, pipelineDef, StageName.TEST_JOB.formattedName, testJobPath)
                            createCIJob.authorization4Existingteams(folderPermConf)
                        }
                        stage("Orchestration Job") {
                            // create orch job
                            def orchJobPath = absFolderPath + "/orchestration"
                            def folderPermConf = [
                                    repoName         : absParentJobPath + "/job/orchestration",
                                    aloyGroupsToView : viewTeams,
                                    aloyGroupsToBuild: buildTeams
                            ]
                            log.info "Creating [${orchJobPath}] with config: ${folderPermConf}"
                            createJob(cdConfigFile, pipelineDef, StageName.ISOPERF_ORCH.formattedName, orchJobPath)
                            createCIJob.authorization4Existingteams(folderPermConf)
                        }

                        stage("Cleanup Job") {
                            // create cleanup job
                            def cleanupJobPath = absFolderPath + "/cleanup-resources"
                            def folderPermConf = [
                                    repoName         : absParentJobPath + "/job/cleanup-resources",
                                    aloyGroupsToView : viewTeams,
                                    aloyGroupsToBuild: buildTeams
                            ]
                            log.info "Creating [${cleanupJobPath}] with config: ${folderPermConf}"
                            createJob(cdConfigFile, pipelineDef, StageName.CLEANUP_RESOURCES.formattedName, cleanupJobPath)
                            createCIJob.authorization4Existingteams(folderPermConf)
                        }
                    }
                }
            }
        }
    }

    if(!parallelStages) {
        log.info "No stages to execute, exiting"
        return
    }

    def aggregateIsoPerfTestFolderPermConf = [
            repoName: perfTestFolderPath,
            aloyGroupsToView: perfTestFolderViewTeams,
            aloyGroupsToBuild: perfTestFolderBuildTeams
    ]

    stage("Create Iso Perf Test Folder") {
        log.info "Creating folder: ${perfTestFolderPath}"
        createFolder perfTestFolderPath, "iso-perf-test-jobs"
        createCIJob.authorization4Existingteams(aggregateIsoPerfTestFolderPermConf)
    }

    stage("Create Perf Test Jobs") {
        parallel(parallelStages)
    }

//    stage("Create Namespace") {
//        def createNsConf = [
//                clusterId: "uks-4278-sandbox-usw2-pner",
//                namespace: pipelineDef.namespacePrefix + "e1np",
//                infrastructure: pipelineDef.infrastructure,
//                createNamespace: true
//        ]
//        createNamespace(createNsConf)
//    }

    log.info "Creation complete."
}

def createFolder(String folderName, String jobName) {
    def scriptFile = """
        import com.cloudbees.hudson.plugins.folder.*
        import jenkins.model.Jenkins
        folder("${folderName}") {
            description("Folder containing all Iso Perf Test jobs for ${folderName}")
        }
    """
    createCDJob.runJobDsl "createCDFolder_${jobName.replaceAll("-","_")}.groovy", scriptFile
}

void createJob(String cdConfigFile, def pipelineDef, String pipelineVar, String jobName) {
    String[] repoInfo = pipelineDef.github.split("/")
    String pipelineScript = """library('engine-iso-perf-framework')
${pipelineVar} {
    configFile = "${cdConfigFile}"
    orgName = "${repoInfo[0]}"
    repoName = "${repoInfo[1]}"
    infrastructure = "${pipelineDef.infrastructure}"
    namespace = "${pipelineDef.namespacePrefix}-isoperf"
    printPodLogs = "FALSE"
}"""

    String versionParam = ""
    String branchOverride = ""
    String numberOfTestPods = ""
    String k6ScriptCmd = ""
    String dependencyPath = ""
    String cleanupEnabled = ""
    String serviceReleaseVersion = ""
    String ciJob = ""
    if(pipelineVar == StageName.DEPLOY_APP.formattedName) {
        versionParam = "stringParam('VERSION', '', 'Required: the chart version (or as applicable) of the given release, not the full name of the chart (E.g. \"1.0.0-20220808105720\", \"1.0.0\")')"
        ciJob = "stringParam('CI_JOB', '', 'Optional: When passed in, this job will attempt to retrieve a helm artifact from the specified CI job. The format of the file should match CHART_VERSION-isoperf.tgz')"
    } else if (pipelineVar == StageName.BUILD_APP.formattedName) {
        branchOverride = "stringParam('BRANCH_OVERRIDE', '', 'Optional: defaults to main or master github branch')"
    } else if(pipelineVar == StageName.DEPLOY_DEPENDENCIES.formattedName) {
        dependencyPath = "stringParam('DEPENDENCY_PATH', '', 'Required: Path to the helm chart for dependencies.')"
        branchOverride = "stringParam('BRANCH_OVERRIDE', '', 'Optional: defaults to main or master github branch')"
    } else if (pipelineVar == StageName.TEST_JOB.formattedName) {
        branchOverride = "stringParam('BRANCH_OVERRIDE', '', 'Optional: defaults to main or master github branch')"
        numberOfTestPods = "stringParam('NUMBER_OF_TEST_PODS', '1', 'Optional: number of pods to execute with, default 1.')"
        k6ScriptCmd = "stringParam('K6_SCRIPT_CMD', '', 'Optional: Job Param will override helm chart value, and helm chart value will override default value located in the pod definition <a href=\"https://github.sie.sony.com/SIE/engine-performance-test/blob/main/helm/engine-performance-test/templates/batch-job.yaml\">here</a>')"
    } else if (pipelineVar == StageName.ISOPERF_ORCH.formattedName) {
        branchOverride = "stringParam('BRANCH_OVERRIDE', '', 'Optional: defaults to main or master github branch')"
        serviceReleaseVersion = "stringParam('SERVICE_RELEASE_VERSION', '', 'Optional: When triggering IsoPerf for a service, pass its release version. BuildStage is skipped')"
        ciJob = "stringParam('CI_JOB', '', 'Optional: When passed in, this job will attempt to retrieve a helm artifact from the specified CI job. The format of the file should match CHART_VERSION-isoperf.tgz')"
        dependencyPath = "stringParam('DEPENDENCY_PATH', '', 'Optional: Comma separated repo specific path to each of the helm charts directories for dependencies.')"
        numberOfTestPods = "stringParam('NUMBER_OF_TEST_PODS', '1', 'Optional: number of pods to execute with, default 1.')"
        k6ScriptCmd = "stringParam('K6_SCRIPT_CMD', '', 'Optional: Job Param will override helm chart value, and helm chart value will override default value located in the pod definition <a href=\"https://github.sie.sony.com/SIE/engine-performance-test/blob/main/helm/engine-performance-test/templates/batch-job.yaml\">here</a>')"
        cleanupEnabled = "booleanParam('CLEANUP_ON_COMPLETE', false, 'if selected, the k8s resources created for the isoperf test are deleted')"
    }
    String description = "Perf Test Jobs for ${jobName}"

    def scriptFile = """
        import com.cloudbees.hudson.plugins.folder.*
        import jenkins.model.Jenkins
        pipelineJob("${jobName}") {
            description("${description}")
            parameters {
                ${versionParam}
                ${branchOverride}
                ${numberOfTestPods}
                ${k6ScriptCmd}
                ${dependencyPath}
                ${cleanupEnabled}
                ${serviceReleaseVersion}
                ${ciJob}
            }
            definition {
                cps {
                    script(\'\'\'${pipelineScript}\'\'\')
                    sandbox()
                }
            }
        }
    """
    log.info scriptFile
    createCDJob.runJobDsl "createCDJob_${pipelineDef.name.replaceAll("-","_")}.groovy", scriptFile
}

def getPipelineDefinition(String repoName, def fileName) {
    def conf = null
    dir(repoName) {
        echo "read file: ${fileName}"
        if (fileExists(fileName)) {
            conf = readYaml file: fileName
            if(conf.jenkinsJobPath == null) conf.jenkinsJobPath = conf.name
            echo "cd config:\n ${conf}"
        } else {
            echo "The file ${fileName} does not exist!"
            new GroovyException("The ${fileName} does not exist in ${repoName}, please check and try again.")
            currentBuild.result = "FAILURE"
        }
    }
    return conf
}

def info(String log) {
    echo "INFO: ${log}"
}

def setJobProperties() {
    def settings = [
            string(
                    defaultValue: '',
                    description: 'Optional: config file name of engine-cd-configurations. i.e. gaminglife/cd/gradle-catalyst-example.yaml',
                    name: 'CONFIG_FILE'
            )
    ]
    properties([
            buildDiscarder(
                    logRotator(
                            artifactDaysToKeepStr: '30',
                            artifactNumToKeepStr: '30',
                            daysToKeepStr: '60',
                            numToKeepStr: '20')
            ),
            parameters(settings),
            disableConcurrentBuilds()
            //pipelineTriggers([])
    ])
}
