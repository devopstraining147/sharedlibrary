package com.sony.sie.cicd.ci.tasks

import com.sony.sie.cicd.helpers.utilities.GitUtils

def processResolveRanges(int jobTimeOut = 20) {
    timeout(jobTimeOut) {
        echo "Starting Version Check"
        new GitUtils().resetVersionCheckStatuses("${env.ORG_NAME}/${env.REPO_NAME}", env.CHANGE_SHA)
        resolveRanges()
    }
}

def updateDependencies(int jobTimeOut = 20) {
    timeout(jobTimeOut) {
        String dependencyUpdatesInRange = getVersionDependencyUpdatesInRange()
        if (params.AUTO_RESOLVE_RANGE && dependencyUpdatesInRange != "") {
            new GitUtils().pushToGithub(env.CHANGE_BRANCH, "Auto Resolve Range in Jenkins")
        } else {
            sh "git reset --hard"
            new GitUtils().postDependencyInfoToGithub("${env.ORG_NAME}/${env.REPO_NAME}", env.CHANGE_SHA, dependencyUpdatesInRange)
        }
    }
}

boolean isVersionCheckReady(){
    return fileExists("${env.REPO_WORKDIR}/manifest.pom.xml")
}

private def getAllDependenciesString(){
    //get the change of versions
    String diffString = sh(returnStdout: true, script: 'git diff')
    echo "${diffString}"
    return diffString
}

private boolean isValidPropertyVersion(String currentLine){
    return (currentLine!="" && currentLine.indexOf("<")>=0 && currentLine.indexOf(">")>0 &&
            currentLine.indexOf("kamaji")>=0 && currentLine.indexOf(".version>")>0)
}

private def getXMLTab(String currentLine){
    int startIndex = currentLine.indexOf("<")
    int endIndex = currentLine.indexOf(">")
    if(startIndex>=0 && endIndex>0) {
        return currentLine.substring(startIndex+1, endIndex)
    }
    return ""
}

private def getPropertyVersion(String currentLine, String versionLogs){
    String versionTab = getXMLTab(currentLine)
    if(versionTab!="") {
        String oldVersion = getXmlData(currentLine, versionTab)
        String newVersion = getXmlData(versionLogs, versionTab)
        if(newVersion!="" && oldVersion!="" && newVersion!=oldVersion){
            String strHeader = "$versionTab ........................................................"
            String strVersions = "$oldVersion -> $newVersion"
            return strHeader.substring(0, 50) + " " + strVersions
        }
    }
    return ""
}

private def processParentVersions(String versionLogs){
    String startStr = "<parent>"
    //String endStr = "</parent>"
    String endline = "\n"
    while(versionLogs!="" && versionLogs.indexOf(startStr)>=0 && versionLogs.indexOf("-")>=0 && versionLogs.indexOf("+")>0){
        int startIndex = versionLogs.indexOf(startStr)
        String temp = versionLogs
        int endIndex = -1
        int lineStartIndex = versionLogs.indexOf(endline, startIndex)+1
        while(temp!=""){
            endIndex = versionLogs.indexOf(endline, lineStartIndex)
            if(endIndex<0)
                break
            else if (endIndex==0){
                lineStartIndex+=1
                continue
            }
            String currentLine = versionLogs.substring(lineStartIndex, endIndex)
            if(currentLine!="") currentLine = currentLine.trim()
            if(currentLine.indexOf("+")==0 && currentLine.indexOf("<version>")>0)
                break
            lineStartIndex = endIndex+1
            temp =  versionLogs.substring(lineStartIndex)
        }
        if(endIndex>=0){
            String data = versionLogs.substring(startIndex, endIndex)
            str = getDependencyString(data)
            if(str == "error")
                return "Either the manifest.pom.xml or pom.xml was not configured correctly. You may consult with the Catalyst team for more details."
            else if(str!="" && outOfdate.indexOf(str)==-1)
                outOfdate+=str+endline
            versionLogs = versionLogs.substring(endIndex)
        }
        else{ break }
    }
    return ""
}

private def processPropertyVersions(String versionLogs){
    String endline = "\n"
    String startStr = "<kamaji"
    while(versionLogs!="" && versionLogs.indexOf(startStr)>=0 && versionLogs.indexOf("-")>=0 && versionLogs.indexOf("+")>0){
        int startIndex = versionLogs.indexOf(startStr)
        versionLogs = versionLogs.substring(startIndex)
        String versionTab = getXMLTab(versionLogs)
        if(versionTab=="") break
        String endStr="</$versionTab>"
        int endIndex = versionLogs.indexOf(endStr)
        if(endIndex==-1) break
        String currentLine = versionLogs.substring(0, endIndex)+endStr
        versionLogs = versionLogs.replaceAll(currentLine, "")
        if(isValidPropertyVersion(currentLine)) {
            String str = getPropertyVersion(currentLine, versionLogs)
            if(str == "error")
                return "Either the manifest.pom.xml or pom.xml was not configured correctly. You may consult with the Catalyst team for more details."
            else if(str!="" && outOfdate.indexOf(str)==-1)
                outOfdate+=str+endline
        }
    }
    return ""
}

private def processDependencyVersions(String versionLogs){
    if(versionLogs!=""){
        String str = processPropertyVersions(versionLogs)
        if(str!="") return str
        str = processParentVersions(versionLogs)
        if(str!="") return str
        String startStr = "<dependency>"
        int startStrLength = 12
        String endline = "\n"
        while(versionLogs!="" && versionLogs.indexOf(startStr)>=0 && versionLogs.indexOf("-")>=0 && versionLogs.indexOf("+")>0){
            int startIndex = versionLogs.indexOf(startStr)
            String temp = versionLogs
            int endIndex = versionLogs.indexOf(startStr, startIndex+startStrLength)
            if(endIndex==-1){
                int lineStartIndex = versionLogs.indexOf(endline, startIndex)+1
                while(temp!=""){
                    endIndex = versionLogs.indexOf(endline, lineStartIndex)
                    if(endIndex<0)
                        break
                    else if (endIndex==0){
                        lineStartIndex+=1
                        continue
                    }
                    String currentLine = versionLogs.substring(lineStartIndex, endIndex)
                    if(currentLine!="") currentLine = currentLine.trim()
                    if(currentLine.indexOf("+")==0 && currentLine.indexOf("<version>")>0)
                        break
                    lineStartIndex = endIndex+1
                    temp =  versionLogs.substring(lineStartIndex)
                }
            }

            if(endIndex>=0){
                String data = versionLogs.substring(startIndex, endIndex)
                str = getDependencyString(data)
                if(str == "error")
                    return "Either the manifest.pom.xml or pom.xml was not configured correctly. You may consult with the Catalyst team for more details."
                else if(str!="" && outOfdate.indexOf(str)==-1)
                    outOfdate+=str+endline

                versionLogs = versionLogs.substring(endIndex)
            }
            else{ break }
        }
    }
    return ""
}

def getVersionDependencyUpdatesInRange(){
    echo "get version display dependency updates in range"
    String versionLogs = getAllDependenciesString()
    echo versionLogs
    outOfdate=""
    if(versionLogs!=""){
        String startStr = "diff --git"
        String endline = "\n"
        while(versionLogs!=""){
            int startIndex = versionLogs.indexOf(startStr)
            if(startIndex>=0) {
                int endIndex = versionLogs.indexOf(endline)
                if(endIndex>0){
                    endIndex = endIndex+1
                    if (endIndex<versionLogs.size())
                        versionLogs = versionLogs.substring(endIndex)
                    else break
                } else break
                endIndex = versionLogs.indexOf(startStr)
                String data = versionLogs
                if(endIndex>0){
                    data = versionLogs.substring(0, endIndex)
                    versionLogs = versionLogs.substring(endIndex)
                } else{
                    versionLogs = ""
                }
                String str = processDependencyVersions(data)
                if(str!="") return str
            } else {
                String str = processDependencyVersions(versionLogs)
                if(str!="") return str
                break
            }
        }
        echo outOfdate
    }
    return outOfdate
}

def resolveRanges(){
    try{
        //replace pom.xml with manifest.pom.xml
        sh "for i in \$( find . -name 'manifest.pom.xml' -type f ) ; do\n    DEST=\$( dirname \$i )\n  cp \$i \${DEST}/pom.xml\n done \n"
        //resolve range of dependencies
        sh "mvn -B -T 1C org.codehaus.mojo:versions-maven-plugin:resolve-ranges -DgenerateBackupPoms=false -Pdevelopment,ci,test"
    }
    catch (Exception err) {
        echo 'resolveRanges failed -------'
        echo "Exception cause: " + err.getMessage()
        throw err
    }
}

private def getDependencyString(String data) {
    String groupId = getGroupID(data)
    if (!isValidDependencyString(groupId)) return ""
    String artifactId = getArtifactID(data)
    if (artifactId == "") return ""
    String oldVersion = getXmlData(data, "version")
    int endIndex = data.indexOf("</version>")
    if (oldVersion == "" || endIndex == -1) return "error"
    data = data.substring(endIndex + 10)
    endIndex = data.indexOf("\n")
    if (endIndex == -1) return "error"
    data = data.substring(endIndex + 1)
    if (data == "" || data.indexOf("+") != 0)
        return "error"
    String newVersion = getXmlData(data, "version")
    if (newVersion == "") return "error"

    if (newVersion != oldVersion) {
        String strHeader = "$groupId:$artifactId ........................................................"
        String strVersions = "$oldVersion -> $newVersion"
        return strHeader.substring(0, 50) + " " + strVersions
    }
    return ""
}

//get XML foramt data: ie: <version>1.0.0</version> return 1.0.0
private def getXmlData(String data, String keyword) {
    String startStr = "<$keyword>"
    String endStr = "</$keyword>"
    int startIndex = data.indexOf(startStr)
    int endIndex = data.indexOf(endStr)
    if (startIndex >= 0 && endIndex > 0) {
        String str = data.substring(startIndex, endIndex)
        str = str.replace(startStr, "")
        str = str.replace(endStr, "")
        return str
    }
    return ""
}

private String getGroupID(String data) {
    String groupId = getXmlData(data, "groupId")
    if (groupId.indexOf("project.groupId") >= 0) groupId = env.PROJECT_GROUP_ID
    return groupId
}

private String getArtifactID(String data) {
    String artifactId = getXmlData(data, "artifactId")
    if (artifactId.indexOf("project.artifactId") >= 0) artifactId = env.PROJECT_ARTIFACT_ID
    return artifactId
}

//only support groups of com.sony.sie and com.sony.sie
private boolean isValidDependencyString(String groupId) {
    if (groupId != "" && (groupId.indexOf("com.sony.sie") >= 0 || groupId.indexOf("com.sony.snei") >= 0))
        return true
    else
        return false
}

return this
