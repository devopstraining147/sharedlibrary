package com.sony.sie.cicd.helpers.podTemplates

class RoadsterPodTemplates extends BasePodTemplates {
    def createNode(def conf, Closure body) {
        conf = [namespace: "roadster-engine-jenkins-nonprod", isNewPod: true, kubernetes: [:]] << conf
        if (conf.clusterList != null) {
            switch (conf.clusterList[0]) {
                case ~/.*-p1pl-.*/:
                case ~/.*-p1np-.*/:
                    conf.cloud = "roadster-k8s-agent-uks-prod"
                    conf.namespace = "roadster-engine-jenkins-prod"
                    conf.serviceAccount = "engine-jenkins-s0-prod"
                    break
                case ~/.*-dev-.*/:
                case ~/.*-d1np-.*/:
                    conf.namespace = "roadster-engine-jenkins-nonprod"
                    conf.serviceAccount = "engine-jenkins-t1-dev"
                    break
                case ~/.*-e1np-.*/:
                    conf.namespace = "roadster-engine-jenkins-nonprod"
                    conf.serviceAccount = "engine-jenkins-s0-e1np"
                    break
            }
        }
        conf.kubernetes = [cloud: conf.cloud ?: "roadster-k8s-agent-uks-nonprod",
                    serviceAccount: conf.serviceAccount ?: 'engine-jenkins',
                    label: "rdsperf-pr0-${conf.templateType}"] << conf.kubernetes
        process conf, body
    }

    //Cluster container and will be converted to yaml.
    def getClusterContainerMap(String clusterId) {
        return getHelmContainerMap(clusterId)
    }
}
