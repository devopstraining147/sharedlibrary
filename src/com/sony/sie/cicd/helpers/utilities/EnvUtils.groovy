package com.sony.sie.cicd.helpers.utilities

import com.sony.sie.cicd.helpers.utilities.JenkinsUtils

def getClusterId(def cluster, def infrastructure) {
    //get cluster id
    def clusterId = ""
    def clusterInfoList = new JenkinsUtils().loadCDDefaultSettings(infrastructure)
    for (int i = 0; i < clusterInfoList.size(); i++) {
        def clusterInfo = clusterInfoList[i]
        if (cluster == clusterInfo.name) {
            clusterId = clusterInfo.clusterId
            break
        }
    }
    return clusterId
}

def getClusterRegion(def cluster, def infrastructure) {
    //get aws region
    def awsRegion = ""
    def clusterInfoList = new JenkinsUtils().loadCDDefaultSettings(infrastructure)
    for (int i = 0; i < clusterInfoList.size(); i++) {
        def clusterInfo = clusterInfoList[i]
        if (cluster == clusterInfo.name) {
            awsRegion = clusterInfo.awsRegion
            break
        }
    }
    return awsRegion
}

def getPSEnv(def cluster, def infrastructure) {
    //get cluster id
    def psenv = ""
    def clusterInfoList = new JenkinsUtils().loadCDDefaultSettings(infrastructure)
    for (int i = 0; i < clusterInfoList.size(); i++) {
        def clusterInfo = clusterInfoList[i]
        if (cluster == clusterInfo.name) {
            psenv = clusterInfo.sieEnv
            break
        }
    }
    return psenv
}

return this