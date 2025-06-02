
package com.sony.sie.cicd.cd.deployProcessors

class KmjK8sDeployProcessor extends K8sBaseDeployProcessor {
    def getKubeConfig(Map conf) {
        sh """
            rm -rf ~/.kube
            mkdir ~/.kube
            cp /tmp/.kube/config ~/.kube/config 
        """
    }
}
