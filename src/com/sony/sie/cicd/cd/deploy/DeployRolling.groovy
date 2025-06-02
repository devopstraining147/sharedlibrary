package com.sony.sie.cicd.cd.deploy

class DeployRolling extends BaseDeploy {

    DeployRolling(Map deployDefinition){
        super(deployDefinition)
    }

    void start() {
        Map conf = deployDefinition
        String newReleaseName = conf.newReleaseName
        conf.lastReleaseName = newReleaseName
        String psenvCluster = "${conf.clusterName}"
        stage("Get Release Revision") {
            conf.releaseRevision = helmDeployK8s.getReleaseRevision(newReleaseName)
        }

        if (isBuildStatusOK()) {
            stage("${labelDeploy}: ${psenvCluster}") {
                echo "Starting ${psenvCluster} rollout"
                dir(helmChartDir) {
                    conf.supportHelmTest = fileExists("templates/tests")
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
                    helmDeployK8s.helmTemplate(newReleaseName, opt)
                    helmDeployK8s.upgradeDeployment(newReleaseName, opt)
                    helmDeployK8s.showAllPods()
                }
                String argoRolloutNames = helmDeployK8s.getArgoRolloutNames(newReleaseName)
                echo "argoRolloutNames: ${argoRolloutNames}"
                if(argoRolloutNames != "") {
                    processArgoRollouts(argoRolloutNames, false, conf)
                    helmDeployK8s.checkAnalysisrunStatus(argoRolloutNames)
                }

                String statefulSetRollout = ""
                if(argoRolloutNames == "") {
                    statefulSetRollout = helmDeployK8s.checkRolloutStatusStatefulSet(newReleaseName)
                    echo "statefulSetRollout=[${statefulSetRollout}]"
                }

                if(argoRolloutNames == "" && statefulSetRollout == "") {
                    helmDeployK8s.checkDeployStatus(newReleaseName)
                }
            }
            conf.deploymentOK = true
            if (isBuildStatusOK()) {
                if(conf.testClosure) {
                    stage("Rollout Test") {
                        container(conf.clusterId) {
                            k8sDeployTest.process(conf.testClosure)
                        }
                    }
                }
                if(conf.supportHelmTest) {
                    container(conf.clusterId) {
                        k8sDeployTest.helmTest()
                    }
                }
            }
        }
    }

    void scaleRollout() {
        Map conf = deployDefinition
        def cmd
        if(conf.isUsingKEDA) {
            cmd = """
                skuba kubectl get scaledobject ${conf.selectedRolloutName} -n ${conf.namespace} -o yaml > scaledobject.yaml
                sed -i 's/maxReplicaCount: .*/maxReplicaCount: ${conf.desiredMaxReplicasCount}/' scaledobject.yaml
                sed -i 's/minReplicaCount: .*/minReplicaCount: ${conf.desiredMinReplicasCount}/' scaledobject.yaml
                skuba kubectl apply -f scaledobject.yaml -n ${conf.namespace}
            """
        } else if (conf.isUsingHPA) {
            cmd = """
                skuba kubectl get hpa ${conf.selectedRolloutName} -n ${conf.namespace} -o yaml > hpa.yaml
                sed -i 's/maxReplicas: .*/maxReplicas: ${conf.desiredMaxReplicasCount}/' hpa.yaml
                sed -i 's/minReplicas: .*/minReplicas: ${conf.desiredMinReplicasCount}/' hpa.yaml
                sed -i '/.*resourceVersion/d' hpa.yaml
                skuba kubectl apply -f hpa.yaml -n ${conf.namespace}
            """
        } else {
            cmd = "skuba kubectl scale rollouts.argoproj.io/${conf.selectedRolloutName} --replicas=${conf.desiredReplicasCount} -n ${conf.namespace}"
        }
        helmDeployK8s.runCommand cmd
    }
}
