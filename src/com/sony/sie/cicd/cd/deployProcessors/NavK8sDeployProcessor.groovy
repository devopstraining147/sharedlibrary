
package com.sony.sie.cicd.cd.deployProcessors

class NavK8sDeployProcessor extends K8sBaseDeployProcessor {
    def getKubeConfig(Map conf) {
        sh "aws eks --region ${conf.region} update-kubeconfig --name ${conf.clusterId}"
    }
}
