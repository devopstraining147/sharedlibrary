package com.sony.sie.cicd.cd.deploy

import com.sony.sie.cicd.helpers.lint.HelmTemplateLint

class DeployBatch extends BaseDeploy {

    DeployBatch(Map deployDefinition){
        super(deployDefinition)
    }

    void start() {
        Map conf = deployDefinition
        String newReleaseName = conf.newReleaseName
        conf.lastReleaseName = newReleaseName
        stage("Get Release Revision") {
            conf.releaseRevision = helmDeployK8s.getReleaseRevision(newReleaseName)
        }
        if (isBuildStatusOK()) {
            stage("${labelDeploy}: ${conf.clusterName}") {
                echo "Starting ${conf.clusterName} deployment"
                dir("${env.REPO_WORKDIR}/${env.HELM_CHART_PATH}") {
                    sh "ls -la"
                    def opt = ''
                    conf.ifNewReleaseCreated = true
                    if(conf.valuesConfigFiles) {
                        for(int i=0; i < conf.valuesConfigFiles.size(); i++) {
                            if(!opt.contains(conf.valuesConfigFiles[i])) opt += "-f ./${conf.valuesConfigFiles[i]} "
                        }
                    }
                    opt += "--set global.cluster=${conf.clusterId} "
                    opt += "--set global.serviceChartVersion=${conf.chartVersion} "
                    opt += "--set global.infrastructure=${conf.infrastructure} "
                    opt += "--set global.awsRegion=${conf.region} "
                    if (conf.configMapHash) {
                        conf.configMapHash.each { k, v -> opt += "--set ${k}.configMapHash=${v} " }
                    }
                    container(conf.clusterId) {
                        new HelmTemplateLint().runHelmTemplateLint()
                    }
                    helmDeployK8s.helmTemplate(newReleaseName, opt)
                    helmDeployK8s.upgradeDeployment(newReleaseName, opt)
                }
            }
        }
    }
}
