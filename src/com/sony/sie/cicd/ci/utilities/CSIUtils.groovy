package com.sony.sie.cicd.ci.utilities
import org.codehaus.groovy.GroovyException
import com.sony.sie.cicd.helpers.notifications.SlackNotifications

def csiApiScanner(def specFiles) {
    String cmd = ""
    for (int i = 0; i < specFiles.size(); i++) {
        //verify the spec file passed in the Jenkinsfile exists
        if (fileExists(specFiles[i].toString())) {
            //check if the filename passed is the 'api_manifest.yaml'
            if (specFiles[i].toString().trim() == "api_manifest.yaml") {
                def data = readYaml file: "api_manifest.yaml"
                if(data.paths.oasYamls != null) {
                    def keys = data.paths.oasYamls.values() as List
                    for (int j=0; j < keys.size(); j++) {
                        //remove the pre-fix '/' if it exists since the file location is relative
                        keys[j] = keys[j].replaceAll(/^\//, '')
                        //verify the file listed in the api_manifest.yaml file exists
                        if (fileExists(keys[j].toString())) {
                            runSpectralCmd("${keys[j]}")
                        }
                        else
                            throw new GroovyException("The file ${keys[j]} listed in the api_manifest.yaml does not exist!")        
                    }
                }
                else
                    throw new GroovyException("data.paths.oasYamls does not exist in the api_manifest.yaml!")
            }
            //otherwise scan the non-api_manifest file passed
            else {
                runSpectralCmd("${specFiles[i]}")
            }
        }
        else {
            throw new GroovyException("The API Spec file ${specFiles[i]} listed in the Jenkinsfile does not exist.")
        }
    }
}

@NonCPS
def getErrorCount(){
    def build = manager.build
    def failCount = build.getAction(hudson.tasks.junit.TestResultAction.class).getFailCount()?: 0
    return failCount
}

int getWarningCount(){
    def testData = readJSON file: "spectral-report.json"
    int warningCount = testData.count { map -> map."severity" == 1 }
    return warningCount
}

String getReportMsg(def errorCount, int warningCount, String filename) {
    String report = "• *Repo Name:* `${env.REPO_NAME}`\n" +
        "• *App Version:* `${env.APP_VERSION}`\n" +
        "• *Filename:* `${filename}`\n" +
        "• *Error Count:* `${errorCount}`\n" +
        "• *Warning Count:* `${warningCount}`\n" +
        "• *Jenkins Test Report:* ${env.BUILD_URL}testReport"
    return report
}

String runSpectralCmd(String filename) {
    String ruleSet = "-r /usr/src/spectral/validationRules/custom-oas-validations.json"
    int warningCount = 0
    int errorCount = 0
    int errorThreshold = 100
    String reportMsg = ""
    String runLocation = ""
    String notificationChannel = "csi-compliance-report"

    try {
        //run with output to json to get warnings and errors
        sh "spectral lint ${filename} ${ruleSet} -f json -o spectral-report.json"
    }
    catch (Exception err) {
        //an error is thrown if there are errors in the report
    }
    finally {
        //get the warning count, even if there are no errors
        warningCount = getWarningCount()
    }

    try {
        //run output to junit to get error report in job
        sh "spectral lint ${filename} ${ruleSet} -f junit -o spectral-report.xml"
        junit skipPublishingChecks: true, skipMarkingBuildUnstable: true, testResults: 'spectral-report.xml', allowEmptyResults: true
    }
    catch (Exception err) {
        if (fileExists("spectral-report.xml")){
            junit skipPublishingChecks: true, skipMarkingBuildUnstable: true, testResults: 'spectral-report.xml'
            errorCount = getErrorCount()
        }
        else{
            echo "***ERROR: the spectral report was not created"
            currentBuild.result = "UNSTABLE"
        }
    }

    if(errorCount>0 || warningCount>0) {
        reportMsg = getReportMsg(errorCount, warningCount, filename)
        String buildStatus = "GOOD"
        if (errorCount > errorThreshold) buildStatus = "UNSTABLE"
        SlackNotifications slackNotifications = new SlackNotifications()
        if (env.CHANGE_ID){
            //if PR, send only slack
            slackNotifications.sendSimpleSlackNotification(notificationChannel, reportMsg, buildStatus)
        }
        else{
            //if Master, send email and slack
            //TODO: add email support
            slackNotifications.sendSimpleSlackNotification(notificationChannel, reportMsg, buildStatus)
        }
    }

    try{
        //run without any output to show the user in the Jenkins job console
        sh "spectral lint ${filename} ${ruleSet}"
    }
    catch (Exception err) {
        if (errorCount > errorThreshold) {
            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE', message:"The number of errors for the API Spec Lint is higher than ${errorThreshold}") {
                throw err
            }
        }
    }
}
