package com.sony.sie.cicd.ci.pipelines.unified

import com.sony.sie.cicd.helpers.annotations.*
import com.sony.sie.cicd.ci.tasks.*
import com.sony.sie.cicd.ci.utilities.MavenUtils
import com.sony.sie.cicd.helpers.utilities.GitUtils
import com.sony.sie.cicd.ci.utilities.DockerComposeUtils

abstract class BaseMavenPipeline extends BasePipeline {
    def awsMavenSecret = ''
    BaseMavenPipeline(def pipelineDefinition) {
        super(pipelineDefinition)
    }

    def buildPreparation(){
        pipelineDefinition.defaultBuildContainer = 'maven-build'
        basePipelineClassList.add(BaseMavenPipeline.class)
    }

    @PRScope
    @MasterScope
    @BranchScope
    @StageLabel(value="")
    @StageOrder(id=1)
    def runPreparation() {
        awsMavenSecret =  jenkinsUtils.fetchMvnSecret(pipelineDefinition.infrastructure)
        container("maven-build") {
            jenkinsUtils.mavenConfig(pipelineDefinition.infrastructure, awsMavenSecret)
        }
        if(pipelineDefinition.enablePublishECR){
            ecrConfig()
        }
        if (pipelineDefinition.versionFileInfo.filepath.indexOf("/pom.xml")>0){
            def pomFileDir = pipelineDefinition.versionFileInfo.filepath.replace("/pom.xml", "")
            if(pomFileDir != "") env.REPO_WORKDIR = env.REPO_WORKDIR + "/" + pomFileDir
        }
        if(pipelineDefinition.preparation){
            stage('Setup') {
                exeClosure pipelineDefinition.preparation
            }
        }
    }

    //@PRScope
    @MasterScope
    @BranchScope
    void setGithubVersion() {
        env.REPO_WORKDIR = pipelineDefinition.repoInfo.projectDir ? env.REPO_NAME + "/" + pipelineDefinition.repoInfo.projectDir : env.REPO_NAME
        def pomFiles = findFiles(glob: "**/pom.xml", excludes: '**/build/**, **/target/**')
        containerExt([workDir: env.REPO_WORKDIR, containerName: pipelineDefinition.defaultBuildContainer]){ 
            if (pomFiles != null && pomFiles.size() > 0) {
                for(int i=0; i < pomFiles.size(); i++) {
                    pom = readMavenPom file: "${WORKSPACE}/${pomFiles[i]}"
                    if (pom.version != null) {
                        updateDirCommand = "mvn -f ${workspace}/${pomFiles[i]} -B versions:set versions:commit -DnewVersion=${env.APP_VERSION}"
                        new MavenUtils().publish(updateDirCommand)
                    }
                }
            }
        }
    }

    //This method is for kamaji
    def mvnDeploy(boolean skipTests=true){
        String workDir = env.REPO_WORKDIR
        String skipTestOpt = skipTests ? "-DskipTests=true" : ""
        String publishCmd = "mvn -B deploy -T 1C -Dmaven.javadoc.skip=true -Dsource.skip=true -Dmaven.install.skip=true ${skipTestOpt}"
        containerExt([workDir: workDir, containerName: pipelineDefinition.defaultBuildContainer]){ new MavenUtils().publish(publishCmd) }
    }

    def mvnBuild() {
        def mavenBuildAndCodeAnalysis = new MavenBuildAndCodeAnalysis()
        containerExt(mavenBuildAndCodeAnalysis.mvnBuild(pipelineDefinition))
    }
    
    // def updateParent(){
    //     if  (pipelineDefinition.updateParentVersion) {
    //         echo "Parent Update..."
    //         command = "mvn -B versions:update-parent -DallowSnapshots=${pipelineDefinition.updateAllowSnapshots} -Dmaven.version.ignore='.+\\..+\\..+-(?!SNAPSHOT).*'"
    //         new MavenUtils().publish(command)
    //         def gitUtils=new GitUtils()
    //         def modPomfiles = []
    //         def pom = ''
    //         def pomBackup = ''
    //         try {
    //             dir ("${WORKSPACE}") {
    //                 modPomfiles = findFiles(glob: "**/pom.xml.versionsBackup")
    //             }
    //             if (modPomfiles != null && modPomfiles.size() > 0) {
    //                 for (int i = 0; i < modPomfiles.size(); i++) {
    //                     pomFile = "${WORKSPACE}/${modPomfiles[i].path}"
    //                     pom = readMavenPom file: "${WORKSPACE}/${modPomfiles[i].path}"
    //                     pomBackup = readMavenPom file: pomFile.replace(".versionsBackup", "")
    //                     echo "Updated parent version of ${pom.artifactId} from: ${pom.parent.version} -> ${pomBackup.parent.version}"
    //                     echo "git add ${pomFile.replace(".versionsBackup", "")}..."
    //                     sh "git add ${pomFile.replace(".versionsBackup", "")}"
    //                 }
    //                 sh "git checkout -b ${env.githubBranchName}"
    //                 gitUtils.gitPush([msgCommit: "Update Parents."])
    //             } else {
    //                 echo "No parent version updated"
    //             }
    //         } catch(e) {
    //             echo "Error Reading POM files: ${e}"
    //         }
    //     } else {
    //         echo "Parent update skipped..."
    //     }
    // }
}
