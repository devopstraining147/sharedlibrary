package com.sony.sie.cicd.helpers.podTemplates

class LacoPodTemplates extends BasePodTemplates {
    def createNode(def conf, Closure body) {
        conf = [namespace: "core-jenkins-agents", kubernetes: [:]] << conf
        conf.kubernetes = [cloud: conf.cloud ?: "laco-k8s-agent",
                    serviceAccount: conf.serviceAccount ?: 'uks-1404-tools-usw2-0001-agent',
                    label:  conf.label ?: "lacoperf-pr0-${conf.templateType}"] << conf.kubernetes
        process conf, body
    }

    //Cluster container and will be converted to yaml.
    def getClusterContainerMap(String clusterId) {
        return getHelmContainerMap(clusterId)
    }
}
