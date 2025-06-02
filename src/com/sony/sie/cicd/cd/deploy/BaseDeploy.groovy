
package com.sony.sie.cicd.cd.deploy

import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import com.sony.sie.cicd.helpers.utilities.JenkinsSteps
import com.sony.sie.cicd.cd.utilities.K8sDeployTest
import com.sony.sie.cicd.cd.utilities.Helm3KubeHelper
import com.sony.sie.cicd.cd.utilities.DeployUtils
import org.codehaus.groovy.GroovyException

abstract class BaseDeploy extends JenkinsSteps {
    final public def jenkinsUtils = new JenkinsUtils()
    public Map deployDefinition
    public Helm3KubeHelper helmDeployK8s
    final public String helmChartDir = "${env.REPO_WORKDIR}/${env.APP_CHART_PATH}"
    final public String labelDeploy = "Deploy"
    final public String projectName = env.REPO_NAME
    public K8sDeployTest k8sDeployTest

    public BaseDeploy(Map deployDefinition){
        this.deployDefinition = deployDefinition
        k8sDeployTest = new K8sDeployTest(deployDefinition)
        helmDeployK8s = new Helm3KubeHelper(deployDefinition.clusterId, deployDefinition.namespace)
    }

    abstract void start()

    boolean rollbackOnError(){
        Map conf = deployDefinition
        String lastReleaseName = conf.lastReleaseName
        String newReleaseName = conf.newReleaseName
        try{
            echo "Rollback to ${lastReleaseName} on ${conf.psenv}/${conf.clusterId}"
            return helmDeployK8s.rollback(conf.releaseRevision, lastReleaseName)
        } catch (Exception err){
            echo "Rollback to ${lastReleaseName} on ${conf.psenv}/${conf.clusterId} failed: ${err.getMessage()}"
            throw err
        }
        return false
    }

    boolean isBuildStatusOK(){
        return jenkinsUtils.isBuildStatusOK()
    }

    def processArgoRollouts(String argoRolloutNames, boolean rollback = false, Map conf) {
        int startMillis = System.currentTimeMillis()
        double timeoutMillis = 3600*1000
        try {
            timeout(time: timeoutMillis, unit: 'MILLISECONDS') {
                doArgoRollouts(argoRolloutNames, rollback, conf)
            }
        } catch (Exception err) {
            if(isBuildStatusOK()) {
                if(new DeployUtils().isTimeoutMilli(startMillis, timeoutMillis))
                    throw new GroovyException("The rollout of ${rolloutName} was taking more than one hour and it was aborted!")
                else
                    throw err
            } else {
                throw err
            }
        }
    }

    void doArgoRollouts(String argoRolloutNames, boolean rollback = false, Map conf){
        def arrRolloutNames = argoRolloutNames.split("\n")
        if (arrRolloutNames){
            def rolloutNameList = []
            for(int i=0; i < arrRolloutNames.size(); i++){
                def temp = arrRolloutNames[i]
                String rolloutName = temp.split()[0]
                if(rolloutName != ""){
                    rolloutNameList.add(rolloutName)
                }
            }
            int length = rolloutNameList.size()
            stepIndex = 0
            if (rollback) {
                for(int i=0; i < length; i++){
                    promoteArgoRollout(rolloutNameList[i], true, conf)
                }
            } else {
                def deployUtils = new DeployUtils()
                while (true) {
                    rolloutApprovalList = []
                    stepIndex++
                    stage ("Rollouts Progress #${stepIndex}") {
                        for(int i=0; i < length; i++){
                            promoteArgoRollout(rolloutNameList[i], false, conf)
                        }
                    }
                    if(rolloutApprovalList != []) {
                        stage ("Rollout Approval #${stepIndex}") {
                            deployUtils.argoRolloutApproval(helmDeployK8s, deployDefinition, rolloutApprovalList)
                        }
                    } else {
                        stage ("Rollouts Completion") {
                            echo "Argo Rollouts Successfully"
                        }
                        break
                    }
                }
            }
        }
    }

    void promoteArgoRollout(String rolloutName, boolean rollback, Map conf) {
        String status = ""
        int counter = 1
        DeployUtils deployUtils = new DeployUtils()
        echo "Argo Rollouts ${rolloutName} Step #${stepIndex}"
        status = helmDeployK8s.checkArgoRolloutStatus(rolloutName, false)
        while (status != "healthy") {
            switch (status) {
                case "progressing":
                    //run skuba diagnose every 20 tries
                    if (counter % 20 == 0) {
                        deployUtils.skubaDebugOnFailed(conf, true)
                    }
                    //wait 10 seconds
                    sleep 10
                    //increment counter
                    counter++
                    break
                case "paused":
                    if (rollback) {
                        //promote all when doing rollback
                        helmDeployK8s.promoteArgoRollout(rolloutName, "--full")
                    } else {
                        rolloutApprovalList.add(rolloutName)
                        return
                    }
                    break
                case "degraded":
                    throw new GroovyException("The rollout of ${rolloutName} is in a ${status} state")
                    break
                default:
                    echo "The rollout of ${rolloutName} is in a ${status} state"
            }
            // "true" sets returnOnFail to true so status is returned even if the command itself throws an error.
            status = helmDeployK8s.checkArgoRolloutStatus(rolloutName, true)
        }
    }
}
