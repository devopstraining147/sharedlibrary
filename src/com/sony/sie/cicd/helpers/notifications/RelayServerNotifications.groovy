
package com.sony.sie.cicd.helpers.notifications

import com.sony.sie.cicd.helpers.api.HttpClientApi

def sendMessage(def payload, String url, int rePostOnErrorIndex = 1) {
    try {
        def responseCode = new HttpClientApi().doPost(url, payload)
        echo "responseCode: ${responseCode}"
    } catch (Exception err) {
        if(rePostOnErrorIndex < 4) {
            sleep 10*rePostOnErrorIndex
            sendMessage(payload, url, rePostOnErrorIndex+1)
        } else {
            throw err
        }
    }
}

private String getBuildDate(){
    return new Date().format("yyyy-MM-dd HH:mm:ss").replaceAll(" ", "T")
}

def sendReleaseStatus(String buildStatus, String releaseVersion) {

}

def sendSwaggerInfo(String buildStatus) {

}

def sendPrStatus(String buildStatus) {

}

def sendBuildStatus(String buildStatus) {

}

def sendHelmDependencies(def depStr ) {
    
}

return this
