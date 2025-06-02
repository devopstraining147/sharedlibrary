package com.sony.sie.cicd.helpers.podTemplates

class NavPodTemplates extends BasePodTemplates {
    def createNode(def conf, Closure body) {
        def clusterRealm = [namespace: 'navprodadmin-jenkins-agents', cloud: 'navprodadmin-k8s-jenkins-agent-workflow-ci', serviceAccount: 'navprodadmin-ukssa-jenkins-engine-workflow-ci', agentRealm:'prodadmin']
        if(conf.clusterList != null) {
            switch (conf.clusterList[0]) {
            case ~/.*uks-7888-.*/:
                clusterRealm = [namespace: 'navd21-jenkins-agents', cloud: 'navd21-k8s-jenkins-agent', serviceAccount: 'navd21-ukssa-jenkins-engine-workflow-deploy', agentRealm:'prodadmin']
                break   
            case ~/.*uks-9206-.*/:
                clusterRealm = [namespace: 'navprod-jenkins-agents', cloud: 'navprod-k8s-jenkins-agent', serviceAccount: 'navprod-ukssa-jenkins-engine-workflow-deploy', agentRealm:'prodadmin']
                break   
            case ~/.*uks-6785-.*/:
                clusterRealm = [namespace: 'navprodadmin-jenkins-agents', cloud: 'navprodadmin-k8s-jenkins-agent', serviceAccount: 'navprodadmin-ukssa-jenkins-engine-workflow-deploy', agentRealm:'prodadmin']
                break   
            case ~/.*uks-3017-.*/:
                clusterRealm = [namespace: 'navprodc1-jenkins-agents', cloud: 'navprodc1-k8s-jenkins-agent', serviceAccount: 'navprodc1-ukssa-jenkins-engine-workflow-deploy', agentRealm:'prodadmin']
                break   
            case ~/.*uks-4885-.*/:
                clusterRealm = [namespace: 'navnonprod-jenkins-agents', cloud: 'navnonprod-k8s-jenkins-agent', serviceAccount: 'navnonprod-ukssa-jenkins-engine-workflow-deploy',agentRealm:'prodadmin']
                break   
            }
        }        
        conf = [namespace: clusterRealm.namespace, kubernetes: [:]] << conf
        conf.kubernetes = [cloud: conf.cloud ?: clusterRealm.cloud,
                    serviceAccount: conf.serviceAccount ?: clusterRealm.serviceAccount,
                    label:  conf.label ?: "navperf-${conf.templateType}"] << conf.kubernetes
        process conf, body
    }

    //Cluster container and will be converted to yaml.
    def getClusterContainerMap(String clusterId) {
        def container = getHelmContainerMap(clusterId)
        container.env = [[name: "HTTPS_PROXY", value: "http://squid.internal.aws:3128"],
            [name: "HTTP_PROXY", value: "http://squid.internal.aws:3128"],
            [name: "https_proxy", value: "http://squid.internal.aws:3128"],
            [name: "http_proxy", value: "http://squid.internal.aws:3128"],
            [name: "AWS_REGION", value: "us-west-2"],
            [name: "no_proxy", value: "169.254.169.254,127.0.0.1,localhost,sonynei.net,.consul,us-west-2.compute.internal,voltron.rtnp.sonynei.net,.eks.amazonaws.com"]]
        return container
    }
}
