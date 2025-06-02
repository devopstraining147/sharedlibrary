
package com.sony.sie.cicd.ci.utilities
import com.sony.sie.cicd.helpers.utilities.HelmUtils
import com.sony.sie.cicd.helpers.utilities.GitUtils
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils

def updateAppVersion(def chartConfig) {
    for(int i=0; i<chartConfig.size(); i++){
        createPR(chartConfig[i])
    }  
}

def createPR(def conf) {
    conf.repoName = conf.github.split("/")[1]
    conf.orgName = conf.github.split("/")[0]
    String defaultBranch = conf.defaultBranch ?: "main"
    dir(conf.repoName) {
        def updatedFiles = ["${conf.helmChartPath}/values.yaml", "${conf.helmChartPath}/Chart.yaml"]
        new JenkinsUtils().checkoutGitSCM(conf.repoName, defaultBranch, conf.orgName)
        for(def fileName in updatedFiles) {
            new HelmUtils().updateChartFileVersion(fileName, env.APP_VERSION)
        }
        String branchName = "update-app-version-" + env.VERSION_TIMESTAMP
        String prTitle = "Update App Version to " + env.APP_VERSION
        String msgBody = prTitle + '. Make sure to review and test it before merging to master.'
        new GitUtils().createPR(conf.repoName, conf.orgName, branchName, updatedFiles, prTitle, msgBody, defaultBranch)
    }
}

return this
