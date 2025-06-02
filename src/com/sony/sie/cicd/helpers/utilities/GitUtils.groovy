package com.sony.sie.cicd.helpers.utilities


import com.sony.sie.cicd.helpers.api.GithubAPI

import static com.sony.sie.cicd.helpers.utilities.KmjEnv.GITHUB_API_URL
import org.codehaus.groovy.GroovyException
import groovy.json.JsonSlurperClassic

def checkOutReleaseSha(String shaFromInput='head'){
    echo "checkout release sha"
    def releaseSha = shaFromInput
    if(shaFromInput == 'head'){
        releaseSha = getHeadCommitSha('--short')
    }
    if(releaseSha!=null && releaseSha!=""){
        sh "git checkout $releaseSha"
        env.GIT_COMMIT = releaseSha
    }
    else{
        env.GIT_COMMIT = "HEAD"
    }

    sh """set +e
          export GIT_COMMIT=${env.GIT_COMMIT}
         """
}

def getHeadCommitSha(String opt=''){
    def headCommitSha = sh(
            script: "git rev-parse $opt HEAD",
            returnStdout: true
    ).trim()
    echo "Head Commit Sha: ${headCommitSha}"
    return headCommitSha
}

private def getTagCommitSha(String tagFromInput){
    repoUrl = "git@github.sie.sony.com:${env.ORG_NAME}/${env.REPO_NAME}.git"
    sh(script: "git remote set-url origin ${repoUrl} && git fetch --all --tags --prune", returnStdout: true)
    def commitSha = sh(
            script: "git rev-list -n 1 ${tagFromInput}",
            returnStdout: true
    ).trim()
    return commitSha
}

def checkOutTag(String tagFromInput){
    setupGithubEnv()
    def commitSha = ''
    if (tagFromInput==null || tagFromInput == 'system' || tagFromInput==''){
        commitSha = 'head'
    } else{
        commitSha = getTagCommitSha(tagFromInput)
    }
    if(commitSha!=null && commitSha!='') {
        checkOutReleaseSha(commitSha)
    } else {
        throw new GroovyException("The commit sha is not found for the Github tag of ${params.CHART_VERSION}!")
    }
}

boolean isTagExists(String gitTag) {
    if(gitTag!=null && gitTag != ''){
        def str = sh(
                script: "git tag -l $gitTag",
                returnStdout: true
        ).trim()
        return str!=''
    }
    return false
}

boolean isVersionFormatOK(String version) {
    return new CICDUtils().isVersionFormatOK(version)
}

//create a new tag in github
def addNewTag(String gitTag) {
    if(gitTag!=null && gitTag != ''){
        if(isTagExists(gitTag)) return
        def msg = "This tag was created by CI Jenkins build #${env.BUILD_ID}"
        if (env.CHANGE_ID) {
            msg = msg + " for PR #${env.CHANGE_ID}"
        } else {
            msg = msg + " for ${env.BRANCH_NAME} branch"
        }
        withCredentials([usernamePassword(credentialsId: 'github-token-username', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]){
            sh """
                 git tag -a ${gitTag} -m '${msg}'
                 git push https://$USERNAME:$PASSWORD@github.sie.sony.com/${env.ORG_NAME}/${env.REPO_NAME}.git ${gitTag}
             """
        }
    } else throw new GroovyException("The format of the git tag name is not correct: ${gitTag}!")
}

def setupGithubEnv(def gitUrl = env.GIT_URL){
    withCredentials([usernamePassword(credentialsId: 'github-token-username', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]){
        sh """ 
            git config --global user.email "$USERNAME@jenkins.com"
            git config --global user.name "$USERNAME"
            git remote set-url origin ${gitUrl}
        """
    }
}

//I.E.: gitPush([orgName: "SIE", repoName: "catalyst-example", branchName: "master"])
def gitPull(def conf= [:]){
    conf = [orgName: env.ORG_NAME, repoName: env.REPO_NAME, branchName: env.BRANCH_NAME] << conf
    withCredentials([usernamePassword(credentialsId: 'github-token-username', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]){
        echo "Pull source from Github"
        sh """
            git checkout -B ${conf.branchName}
            git config user.email "$USERNAME@jenkins.com"
            git config user.name "$USERNAME"
            git pull https://$USERNAME:$PASSWORD@github.sie.sony.com/${conf.orgName}/${conf.repoName}.git ${conf.branchName}
        """
    }
}

//I.E.: gitPush([updatedFiles: [], msgCommit: "update", orgName: "SIE", repoName: "catalyst-example", branchName: "master"])
def gitPush(def conf = [:]) {
    conf = [msgCommit: "update", orgName: env.ORG_NAME, repoName: env.REPO_NAME, branchName: env.BRANCH_NAME] << conf
    withCredentials([usernamePassword(credentialsId: 'github-token-username', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]){
        echo "Pushing changes to Github"
        // add files to git, but do not error when no files are added, or ignored files are added
        if(conf.updatedFiles) {
            sh """
                git add ${conf.updatedFiles.join(' ')} || true
                """
        }
        sh """
            git config user.email "$USERNAME@jenkins.com"
            git config user.name "$USERNAME"
            git commit --no-verify -am "${conf.msgCommit}"
            git push https://$USERNAME:$PASSWORD@github.sie.sony.com/${conf.orgName}/${conf.repoName}.git ${conf.branchName}
        """
    }
}

def getDefaultBranch(String orgName, String repoName) {
    def defaultBranch=""
    try{
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'github-token-username', usernameVariable: 'githubUsername', passwordVariable: 'githubAccessToken']]) {
            def obj = new GithubAPI(GITHUB_API_URL, githubAccessToken)
            String repoUrl = "${GITHUB_API_URL}/repos/${orgName}/${repoName}"
            def response = obj.doGet(repoUrl)
            if (response) {
                defaultBranch=response.default_branch
            } else {
                echo "can not read info: ${repoName}"
            }
        }
    } catch (Exception err) {
        echo "Can not access ${repoName} due to ${err.getMessage()}"
        // currentBuild.result = "FAILURE"
    }
    return defaultBranch
}


def githubRelease(def pipelineDefinition, String appVersion, String chartVersion, boolean newTag = true) {
    echo "-- updating verions in gitHub --"
    try{
        def updatedFiles = []
        String releaseVersion = chartVersion == '' ? appVersion : chartVersion
        setupGithubEnv()
        gitPull()
        if (chartVersion != '') {
            echo "Update helm chart versions: chartVersion: ${chartVersion}, appVersion: ${appVersion}"
            HelmUtils helmUtils = new HelmUtils()
            if(pipelineDefinition.helmChartConfigs) {
                def helmChartList = pipelineDefinition.helmChartConfigs
                if(pipelineDefinition.releaseType == "community-helm"){
                    for (int i = 0; i < helmChartList.size(); i++) {
                        def fileName = "${helmChartList[i].helmChartPath}/Chart.yaml"
                        if (helmUtils.updateChartVersion4File(fileName, null, chartVersion)) {
                            updatedFiles.add(fileName)
                        }
                    }
                } else {
                    for (int i = 0; i < helmChartList.size(); i++) {
                        def fileName = "${helmChartList[i].helmChartPath}/Chart.yaml"
                        if (helmUtils.updateChartVersion4File(fileName, appVersion, chartVersion)) {
                            updatedFiles.add(fileName)
                        }
                        fileName = "${helmChartList[i].helmChartPath}/values.yaml"
                        if (helmUtils.updateChartVersion4File(fileName, appVersion, chartVersion)) {
                            updatedFiles.add(fileName)
                        }
                    }
                }
            }
        }
        def fileName = pipelineDefinition.versionFileInfo.filepath
        def files = findFiles(glob: "**/$fileName")
        for (int i = 0; i < files.size(); i++) {
            def filepath = "${files[i]}"
            if(!filepath.contains('build-output/') && !filepath.contains('target/')){
                updatedFiles.add(filepath)
            }
        }
        def pomFiles = findFiles(glob: "**/pom.xml", excludes: '**/build/**, **/target/**')
        for (int i = 0; i < pomFiles?.size(); i++) {
            def pomFilepath = "${pomFiles[i]}"
            updatedFiles.add(pomFilepath)
        }
        if(updateManifestVersion(appVersion)){
            updatedFiles.add("manifest.pom.xml")
        }
        if(fileExists("manifest.yaml")){
            updatedFiles.add("manifest.yaml")
        }
        if(updatedFiles != []) {
            gitPush([msgCommit: "Release the version ${releaseVersion}", updatedFiles: updatedFiles])
        }
        if (newTag) {
            addNewTag(releaseVersion)
        }
        pipelineDefinition.releaseVersion = releaseVersion
    } catch (Exception err) {
        echo "githubRelease failed: " + err.getMessage()
        throw err
    }
}

boolean updateManifestVersion(String appVersion){
    String fileName = 'manifest.pom.xml'
    if(fileExists(fileName)){
        def xmlFileUtils = new XmlFileUtils()
        def oldVersion = xmlFileUtils.getValue(fileName, 'version')
        if(appVersion != oldVersion){
            xmlFileUtils.setValue(fileName, 'version', appVersion)
            return true
        }
    }
    return false
}

// def setUserPrivateKey(){
//     try {
//         sh """
//             mkdir -p ~/.ssh
//             cat /var/run/secrets/kubernetes.io/ssh-key/id_rsa > ~/.ssh/id_rsa
//             cat /var/run/secrets/kubernetes.io/sshconfig/config > ~/.ssh/config
//             chmod 600 ~/.ssh/id_rsa
//         """
//     } catch (Exception err) {
//         echo "setUserPrivateKey failed: " + err.getMessage()
//         throw err
//     }
// }

def postDependencyInfoToGithub(String repoName, String commitSha, String versionLogs, String commentTitle="The following dependencies are out-of-date", String statusTitle = "Internal Dependencies Check"){
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'github-token-username', usernameVariable: 'githubUsername', passwordVariable: 'githubAccessToken']]) {
        def commentCode = 201
        def obj = new GithubAPI(GITHUB_API_URL, githubAccessToken)
        if(versionLogs!=null && versionLogs!=""){
            versionLogs = versionLogs.replaceAll("\n", "<br>")
            String str = "<div id=\\\"pr-coverage\\\">"
            str += "<div id=\\\"pr-coverage-warning\\\">"
            str += "<table><tr><td width=9% align=center>"
            str += "<img src=\\\"https://assets-cdn.github.com/images/icons/emoji/unicode/26a0.png\\\" width=25px height=25px border=0>"
            str += "</td><td width=73%><h3>${commentTitle}</h3>"
            str += "<h5>For more infomation, please view <a href=\\\"https://confluence.sie.sony.com/display/KTF/Auto+Resolve+Dependency+Ranges\\\">Catalyst confluence page</a>.</h5>"
            str += "</td><td width=18% align=right nowrap>"
            str += "<div id=\\\"pr-coverage-button\\\"><h4><a href=\\\"${env.JOB_URL}build?delay=0sec\\\">"
            str += "Resolve Range</a></h4></div>"
            str += "</td></tr></table>"
            str += "</div>"

            str += "<div id=\\\"pr-coverage-notes\\\">"
            str += versionLogs
            str += "</div>"
            str += "</div>"

            echo "post comment to github"
            echo "$str"
            commentCode = obj.postComment(repoName, env.CHANGE_ID, str, commentTitle)
            echo "post comment code = $commentCode"
        }
        if (commentCode >= 200 && commentCode <= 202){
            echo "post status to github"
            def statusCode=0
            if(versionLogs==null){
                statusCode = obj.postStatus(repoName, commitSha, "failure", statusTitle, "Failed! Please check Jenkins build console logs.")
            } else if(versionLogs!=""){
                statusCode = obj.postStatus(repoName, commitSha, "failure", statusTitle, "One or more internal dependencies are out-of-date!")
            } else {
                statusCode = obj.postStatus(repoName, commitSha, "success", statusTitle, "WOW! All internal dependencies are up-to-date!")
            }
            echo "post status code = $statusCode"

            //Network connection failed: can not post to Github
            if (statusCode < 200 || statusCode > 202){
                //delete the last comment
                obj.deleteComment(repoName, env.CHANGE_ID, commentTitle)
                throw new GroovyException("Network connection failed: Can not post a status to Github.")
            }
        } else {
            //set status to fail
            obj.postStatus(repoName, commitSha, "failure", statusTitle, "Network connection failed: Can not post a comment to Github.")
            //Network connection failed: can not post to Github
            throw new GroovyException("Network connection failed: Can not post a comment to Github.")
        }
    }
}

def resetGithubStatuses(String repoName, String commitSha, boolean checkDependencyInRange){
    resetPRCoverStatuses(repoName, commitSha)
    if(checkDependencyInRange) resetVersionCheckStatuses(repoName, commitSha)
}

def resetVersionCheckStatuses(String repoName, String commitSha){
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'github-token-username', usernameVariable: 'githubUsername', passwordVariable: 'githubAccessToken']]) {
        def obj = new GithubAPI(GITHUB_API_URL, githubAccessToken)
        //reset internal dependencies in range
        echo "reset Internal Dependencies in range check status to pending"
        def code = obj.postStatus(repoName, commitSha, "pending", "Internal Dependencies Check", "Processing! please wait...")
        echo "post status code = $code"
        echo "delete last out-of-date in range comment"
        code = obj.deleteComment(repoName, env.CHANGE_ID, "The following dependencies are out-of-date")
        echo "delete comment code = $code"
    }
}

def resetPRCoverStatuses(String repoName, String commitSha){
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'github-token-username', usernameVariable: 'githubUsername', passwordVariable: 'githubAccessToken']]) {
        def obj = new GithubAPI(GITHUB_API_URL, githubAccessToken)
        //reset PR Coverage Report
        echo "reset PR Coverage status to pending"
        def code = obj.postStatus(repoName, commitSha, "pending", "PR Coverage", "Processing! please wait...")
        echo "post status code = $code"
        echo "delete last PR Coverage Report comment"
        String commentTitle="<div id=\"pr-coverage-title\"><h2>PR Coverage Report</h2></div>"
        code = obj.deleteComment(repoName, env.CHANGE_ID, commentTitle)
        echo "delete comment code = $code"
    }
}

def pushToGithub(String branchName, String msg) {
    sh """ 
           find ${env.WORKSPACE}/${env.REPO_WORKDIR} -name 'pom.xml' -exec git add {} +
    """
    gitPush([msgCommit: msg, branchName: branchName])
}

def getLatestRelease(String orgName,String repoName){
    def response=null
    try{
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'github-token-username', usernameVariable: 'githubUsername', passwordVariable: 'githubAccessToken']]) {
            def obj = new GithubAPI(GITHUB_API_URL, githubAccessToken)
            String repoUrl = "${GITHUB_API_URL}/repos/${orgName}/${repoName}/releases/latest"
            response = obj.doGet(repoUrl)
        }
    }catch(Exception err){}
    return response
}

def generateRelease(def target_commitish,String newVer, String orgName,String repoName){
    def response=null
    try{
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'github-token-username', usernameVariable: 'githubUsername', passwordVariable: 'githubAccessToken']]) {
            def obj = new GithubAPI(GITHUB_API_URL, githubAccessToken)
            String apiUrl = "${GITHUB_API_URL}/repos/${orgName}/${repoName}/releases"
            echo "apiUrl: ${apiUrl}"
            String data="{\"tag_name\":\"${newVer}\",\"target_commitish\":\"${target_commitish}\",\"name\":\"${newVer}\",\"draft\":false,\"prerelease\":false,\"generate_release_notes\":true}"
            echo "data: ${data}"
            response = obj.doPostResponseHttpClient(apiUrl, data)
        }
    }catch(Exception err){}
    return response
}
def getClosedPRs(String orgName,String repoName){
    def response=null
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'github-token-username', usernameVariable: 'githubUsername', passwordVariable: 'githubAccessToken']]) {
        def obj = new GithubAPI(GITHUB_API_URL, githubAccessToken)
        String apiUrl = "${GITHUB_API_URL}/repos/${orgName}/${repoName}/pulls?state=closed&sort=updated&direction=desc"
        echo "apiUrl: ${apiUrl}"
        response = obj.doGet(apiUrl)
    }
    return response
}

def createPR(String repoName, String orgName, String branchName, def fileList, String prTitle, String msgBody, String baseBranch="master"){
    withCredentials([usernamePassword(credentialsId: 'github-token-username', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]){
        echo "Pushing changes to Github"
        sh """
            git checkout -b ${branchName}
            git add ${fileList.join(" ")}
            git config user.email "$USERNAME@jenkins.com"
            git config user.name "$USERNAME"
            git commit -m "${prTitle}"
            git push https://$USERNAME:$PASSWORD@github.sie.sony.com/${orgName}/${repoName}.git $branchName
            git checkout ${baseBranch}
          """
        echo "Making PR"
        return createGitHubPR(repoName, orgName, branchName, PASSWORD, prTitle, msgBody, baseBranch)
    }
}

//Create github PR
def createGitHubPR(String repoName, String orgName, String branchName, String githubAccessToken, String prTitle, String msgBody, String baseBranch="master") {
    def response=null
    String createPRUrl = "https://github.sie.sony.com/api/v3/repos/${orgName}/${repoName}/pulls"
    String createGithubPRData = "{\"title\":\"${prTitle}\",\"body\": \"${msgBody}\",\"base\": \"${baseBranch}\",\"head\": \"${branchName}\"}"
    def obj = new GithubAPI(createPRUrl, githubAccessToken)
    def jsonParser = new JsonSlurperClassic()
    def txt = obj.doPostResponseHttpClient(createPRUrl, createGithubPRData)
    if (txt != '' && txt != null) {
        response=jsonParser.parseText(txt)
    } else {
        throw new GroovyException("Post to create Github PR failed!")
    }
    return response
}

// https://developer.github.com/v3/repos/contents/#get-contents
def getGitFileThroughCurl(String orgName, String repoName, String branch, String directory, String fileName, boolean echoCommand = true) {
    // Get jenkins.deploy.properties
    if (directory == './') {
        directory = ''
    }
    if (branch != null && branch != '') {
        branch = "?ref=${branch}"
    }
    def githubUrl = "https://github.sie.sony.com/api/v3/repos/${orgName}/${repoName}/contents/${directory}${fileName}${branch}"
    return _githubCurl("-L ${githubUrl} -o ${fileName}", 'application/vnd.github.v3.raw', echoCommand)
}

def _githubCurl(String command, String accept = 'application/vnd.github.v3.raw', Boolean echoCommand = true) {
    withCredentials([string(credentialsId: 'HYPERLOOPOPS_GITHUB_TOKEN', variable: 'HYPERLOOPOPS_GITHUB_TOKEN')]) {
        curlSilent = (echoCommand) ? '' : '-s '
        String stdout = sh(script: "curl ${curlSilent} -H 'Authorization: token ${HYPERLOOPOPS_GITHUB_TOKEN}' -H 'Accept: ${accept}' ${command} --fail-with-body", returnStdout: echoCommand)
        return stdout
    }
}

def gitDeleteBranch(def conf = [:]) {
    conf = [orgName: env.ORG_NAME, repoName: env.REPO_NAME, branchName: "temp-tag-branch"] << conf
    withCredentials([usernamePassword(credentialsId: 'github-token-username', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]){
        echo "Delete branch of ${conf.branchName} in Github"
        sh """
            git config user.email "$USERNAME@jenkins.com"
            git config user.name "$USERNAME"
            git push https://$USERNAME:$PASSWORD@github.sie.sony.com/${conf.orgName}/${conf.repoName}.git --delete --force ${conf.branchName}
        """
    }
}

//static final String GITHUB_API_URL = 'https://github.sie.sony.com/api/v3'

def readSlackNotificationsFile(orgName, repoName, branch) {
    def response = [:]
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'github-token-username', usernameVariable: 'githubUsername', passwordVariable: 'githubAccessToken']]) {
        String apiUrl = "${GITHUB_API_URL}/repos/${orgName}/${repoName}/contents/performance-test/iso-perf/iso-perf-notifications.yaml?ref=${branch}"
        def responseString = doGetYaml(apiUrl, githubAccessToken)
        if (responseString) {
            def base64Text = readJSON text: responseString
            base64Text = base64Text.content
            response = readYaml text: new String(base64Text.decodeBase64())
        }
    }
    return response
}

def doGetYaml(String url, String token) {
    def conn = new URL(url).openConnection() as HttpURLConnection
    String basicAuth = Base64.getEncoder().encodeToString((token+":").getBytes());
    conn.setRequestProperty ("Authorization", "Basic "+basicAuth);
    String response = ""
    if(conn.getResponseCode() == 200){
        response = conn.inputStream.text
        if (response.startsWith('\uFEFF')) {
            response = response.substring(1)
        }
        return response
    }
    return response
}
return this