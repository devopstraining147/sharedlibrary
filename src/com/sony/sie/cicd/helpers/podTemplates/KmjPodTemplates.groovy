package com.sony.sie.cicd.helpers.podTemplates

class KmjPodTemplates extends BasePodTemplates {
    def createNode(def conf, Closure body) {
        conf = [namespace: "engine-jenkins", kubernetes: [:]] << conf
        conf.kubernetes = [cloud: "kamaji-k8s-agent", serviceAccount: "engine-jenkins",
            label:  conf.label ?: "kmjperf-pr0-${conf.templateType}"] << conf.kubernetes
        process conf, body
    }
    
    //Cluster container and will be converted to yaml.
    def getClusterContainerMap(String clusterId) {
        def container = getHelmContainerMap(clusterId)
        container.volumeMounts.add(setVolumeMount(clusterId, "/tmp/.kube", "",true))
        return container
    }
}
