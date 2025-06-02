package com.sony.sie.cicd.helpers.utilities

import com.sony.sie.cicd.helpers.utilities.SemVer
import com.sony.sie.cicd.ci.pipelines.*
import com.sony.sie.cicd.helpers.enums.BuildAction
import org.codehaus.groovy.GroovyException

def getGitCommit() {
    dir(env.REPO_WORKDIR) {
        // checkoutGitSCM()
        if (env.CHANGE_ID) {
            env.CHANGE_SHA = getHeadCommitSha()
            env.GIT_COMMIT = env.CHANGE_SHA
        } else if(env.ACTION != "CI_TEST") {
            env.GIT_COMMIT = getHeadCommitSha('--short')
        }
    }
}

def getHeadCommitSha(String opt = '') {
    def headCommitSha = sh(script: "git rev-parse $opt HEAD", returnStdout: true).trim()
    echo "Head Commit Sha: ${headCommitSha}"
    return headCommitSha
}

def checkoutGitSCM(def repoName = env.REPO_NAME, def org = env.ORG_NAME) {
    echo "checkout source code"
    def branchName = env.CHANGE_BRANCH ? env.CHANGE_BRANCH : env.BRANCH_NAME
    if (env.RELEASE_VERSION && env.RELEASE_VERSION != '' && !env.RELEASE_VERSION.contains("NOT APPLICABLE")) {
        branchName = env.RELEASE_VERSION
    }
    new JenkinsUtils().checkoutGitSCM(repoName, branchName, org)
}

@NonCPS
String getTimeStampFromVersion(String version){
    String timeStamp = ''
    if(version!=null && isVersionFormatOK(version)){
        if(version.contains("-")){
            timeStamp = version.split("-")[1]
        } else {
            def arr  = version.split("\\.")
            timeStamp = arr.size() > 2 ? arr[2] : env.VERSION_TIMESTAMP
        }
    }
    return timeStamp
}

@NonCPS
boolean isVersionFormatOK(String version) {
    return version ==~ /^(\d+)\.(\d+)\.(\d+)(\-\p{Alnum}+)?$/
}


String createNewAppVersion(String version, String newTimestamps){
    String newVersion=createNewVersion(version, newTimestamps)
    if(newVersion == ''){
        throw new GroovyException("Unable to create a new app version. Please check the version format in pom.xml")
    }
    return newVersion
}


String createNewChartVersion(String version, String newTimestamps ){
    String newVersion=createNewVersion(version, newTimestamps)
    if(newVersion == ''){
        throw new GroovyException("Unable to creaye a new chart version. Please check the version format in Chart.yaml")
    }
    return newVersion
}

String createNewVersion(String version, String newTimestamps){
    if(version.contains("-")) {
        stripTimeStamp = version.split('-')
        version = stripTimeStamp[0]
    } else {
        def patch = getTimeStampFromVersion(version)
        if(patch != "" && patch.size() > 5) {
            def arr = version.split("\\.")
            version  = "${arr[0]}.${arr[1]}.0"
        }
    }
    String newVersion = version
    if (env.CHANGE_ID) {
        if(env.RELEASE_PATTERN!="image-release"){
            newVersion = version+"-pr${env.CHANGE_ID}"
        }
    } else if (new JenkinsUtils().isMainBranch()) {
        switch(env.RELEASE_PATTERN){
            case 'ga-release':
                newTimestamps = ''
                break
            case 'pre-release':
                newTimestamps = 'beta.' + newTimestamps
                break
            case 'git-release':
                newVersion = SemVer.bump(version, '-2')
                newTimestamps = 'SNAPSHOT'
                break
            case 'snapshot':
                // newVersion = SemVer.bump(version, '-2')
                newTimestamps = 'SNAPSHOT'
                break;
            case 'image-release':
                break;
            default:
                newVersion = SemVer.bump(version, '-2')
        }
    } else /*if(env.BRANCH_NAME.toLowerCase().contains('hotfix-'))*/ {
        //the patch will be incremented for non main branch
        newVersion = SemVer.bump(version, '-1')
        if(env.RELEASE_PATTERN == "hotfix-release") newTimestamps = ''
    } 

    if (newTimestamps != '') {
        newVersion += "-${newTimestamps}"
    }
    return newVersion
}

return this
