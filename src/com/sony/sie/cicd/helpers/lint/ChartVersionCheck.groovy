package com.sony.sie.cicd.helpers.lint

import com.sony.sie.cicd.helpers.utilities.KmjEnv
import com.sony.sie.cicd.helpers.api.GithubAPI
import com.sony.sie.cicd.helpers.notifications.RelayServerNotifications
import org.codehaus.groovy.GroovyException
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils

//Important assumption: current folder is a chart folder for helm unified 

def checkChartVersionsAndPost(){
    def fullDepReport = []
    String fileName = fileExists("requirements.yaml") ? "requirements.yaml" : "Chart.yaml"
    if (fileExists(fileName)) {
        def confMap = readYaml file: fileName
        if (confMap.dependencies != null) {
            def versionMap=getLatestVersionMapForDependency(confMap.dependencies)
            fullDepReport = outOfDateDepReport(confMap, versionMap, fileName)
        }
    }else{
        echo "${fileName} does not exist in folder ${pwd()}"
    }
    postOutOfDateChartComment(fullDepReport)
}

private def isCompliant(def v1, def v2) {
    return v2 == v1
}

private def outOfDateDepReport(def requirementsYaml, def versionMap, def fileName){
    def res = []
    def dependencies = requirementsYaml['dependencies']
    
    for (int i = 0; i < dependencies.size(); i++) {
        def dep = dependencies[i]
        def repo=dep.repository

        def latestVersion = versionMap[dep['name']]
        if(latestVersion != null && latestVersion != dep['version']) {
            res.add(['name'          : dep['name'],
                     'alias'         : dep['alias'],
                     'currentVersion': dep['version'],
                     'latestVersion' : latestVersion,
                     'compliant'     : isCompliant(dep['version'], latestVersion),
                     'fileName'      : fileName
            ])
        }
    }
    return res
}

def createGithubComment(def depReport){
    tableContents = ""
    for (int i = 0; i < depReport.size(); i++) {
        def dep = depReport[i]
        if (dep['alias'] == null) dep['alias'] = "--" // let's format these nicely!
        tableContents += "<tr><td>${dep['name']}</td><td>${dep['currentVersion']}</td><td>${dep['latestVersion']}</td><td>${dep['alias']}</td></tr>"
    }
    def res = """
    <div id=\\"chart-dependencies-are-non-compliant\\">
        <h3>These Chart Dependencies are Out of Date</h3><hr>
        <table>
          <tr><th>Chart Name</th><th>Defined Version</th><th>Latest Version</th><th>Alias</th></tr>
          ${tableContents}
        </table>
    </div>""".stripIndent().trim().replaceAll("[\r\n]+", "");
    return res
}

private def postOutOfDateChartComment(def depReport) {
    if (env.CHANGE_ID) {
        resetOutOfDateChartComment()
        if(depReport && depReport != []){
            echo "Some dependencies were out-of-date: ${depReport}"
            String commentTitle="<div id=\"chart-dependencies-non-compliant\">" // this title must match something unique in the message body
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'github-token-username', usernameVariable: 'githubUsername', passwordVariable: 'githubAccessToken']]) {
                def githubObj = new GithubAPI(KmjEnv.GITHUB_API_URL, githubAccessToken)
                def returnStr = createGithubComment(depReport)
                def code = githubObj.postComment("${env.ORG_NAME}/${env.REPO_NAME}", env.CHANGE_ID, returnStr, commentTitle)
                echo "code for comment post: ${code}"
            }
        }
    } else if(new JenkinsUtils().isMainBranch()) {
        if(depReport && depReport != []) {
            // Posts out-of-date dependencies to relay server
            def depStr = ""
            for (int i = 0; i < depReport.size(); i++) {
                def dep = depReport[i]
                if (dep['alias'] == null) dep['alias'] = "--" // let's format these nicely!
                if (depStr != "") depStr += ", "
                depStr += "{\"chart_name\": \"${dep['name']}\", \"file_name\": \"${dep['fileName']}\", \"defined_version\": \"${dep['currentVersion']}\", \"latest_version\": \"${dep['latestVersion']}\", \"alias\": \"${dep['alias']}\"}"
            }
            depStr = "[" + depStr + "]"
            new RelayServerNotifications().sendHelmDependencies(depStr)
        }
    }
}

private def resetOutOfDateChartComment(){
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'github-token-username', usernameVariable: 'githubUsername', passwordVariable: 'githubAccessToken']]) {
        def githubAPI = new GithubAPI(KmjEnv.GITHUB_API_URL, githubAccessToken)
        echo "delete last chart-dependencies-are-non-compliant in range comment"
        String commentTitle="<div id=\"chart-dependencies-are-non-compliant\">"
        def code = githubAPI.deleteComment("${env.ORG_NAME}/${env.REPO_NAME}", env.CHANGE_ID, commentTitle)
        echo "delete comment code = $code"
    }
}

private def getLatestVersionMapForDependency(def dependencies){
    //assume all repositories are from https://artifactory.sie.sony.com/artifactory 
    def repoLst=[]
    echo "Get latest version for dependency"
    (status,result)=runShellScript("skuba helm repo list -o json")
    if(status==0){
        repoLst=readJSON text:result  //list of {name, url} 
    }
    def versionMap=[:]
    def updated=[:]
    for (int i = 0; i < dependencies.size(); i++) {
        def dep = dependencies[i]
        if(!versionMap[dep.name]){
            def repoName;
            def url;
            if(dep.repository.contains(KmjEnv.ENGINE_CHARTS_REPO_NAME)){
                repoName = KmjEnv.ENGINE_CHARTS_REPO_NAME
                url = KmjEnv.ENGINE_CHARTS_REPO_URL
            }
            if(dep.repository.contains(KmjEnv.ENGINE_CHARTS_PROD_VIRTUAL_REPO_NAME)){
                repoName = KmjEnv.ENGINE_CHARTS_PROD_VIRTUAL_REPO_NAME
                url = KmjEnv.ENGINE_CHARTS_PROD_VIRTUAL_REPO_URL
            }else{
                repoName=getRepoNameFromUrl(dep.repository)
                url=dep.repository
            }
            echo "Repo name: ${repoName}"
            if(repoName){
                if(!repoLst.any{it.name==repoName}){
                    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "engine-artifactory-access", usernameVariable: 'username', passwordVariable: 'password']]) {
                        sh """
                            skuba helm repo add ${repoName} ${url} --username ${username} --password ${password}
                            skuba helm repo update ${repoName}
                        """
                        repoLst<<['name':repoName,'url':url]
                        updated[repoName]=true
                    }
                }else if(!updated[repoName]){
                    sh "skuba helm repo update ${repoName}"
                    updated[repoName]=true
                }
            
                def versions=[]
                def script="skuba helm search repo ${repoName}/${dep.name} -l -o json"
                (status,result)=runShellScript(script)
                if(status==0 && result){
                    versions=readJSON text:result
                    if(versions){
                        versionMap[dep.name]=versions[0].version
                    }
                }
            }
        }
    }
    echo "${versionMap}"
    return versionMap;    
}

@NonCPS
private def getRepoNameFromUrl(String url){
    def repoName=""
    if(url){
        url=url.replaceAll("[ /]*\$","")
        repoName=url.substring(url.lastIndexOf("/")+1)
    }
    return repoName
}
private def runShellScript(script) {    
    Date tm = new Date(); 
    def stdoutFile = "${tm.getTime()}.out"    
    script = script + " > " + stdoutFile

    def status = sh(returnStatus: true, script: script)    
    def stdout=status>0?"":sh(returnStdout: true, script: "cat " + stdoutFile)
    sh(returnStatus: true, script: "rm -f " + stdoutFile)
    return [status,stdout.trim()]
}
return this
