package com.sony.sie.cicd.helpers.podTemplates

import com.sony.sie.cicd.helpers.utilities.YamlFileUtils
import com.sony.sie.cicd.helpers.utilities.JenkinsSteps
import org.codehaus.groovy.GroovyException

abstract class BasePodTemplates extends JenkinsSteps {
    final String KMJ_ECR_HOST_URL = '761590636927.dkr.ecr.us-west-2.amazonaws.com'
    final String ECR_HOST_URL = "890655436785.dkr.ecr.us-west-2.amazonaws.com"
    abstract def createNode(def conf, Closure body)
    abstract def getClusterContainerMap(String clusterId)

    def process(def conf, Closure body) {
        conf = [languageVersion: '', isNewPod: false, supportDinD: true] << conf
        def configMap = [idleMinutes: "30", label: "${conf.infrastructure}-pr40-${conf.templateType}"] << conf.kubernetes
        configMap.yaml = getCiPodYaml(conf)
        if(conf.templateType == 'docker-compose') {
            configMap.workspaceVolume = hostPathWorkspaceVolume(hostPath: "/home/jenkins/agent")
        }
        if(conf.isNewPod) {
            configMap.idleMinutes = "0"
            String POD_TIMESTAMP = new Date().format("HHmmss")
            configMap.label += "${POD_TIMESTAMP}${env.BUILD_ID}"
        } else {
            //recycle the pod everyday
            configMap.label += "${new Date().format("dd")}"
            if(conf.languageVersion != "") configMap.label += conf.languageVersion
            if (conf.clusterList) {
                configMap.label += "-"
                for (int i = 0; i < conf.clusterList.size(); i++) {
                    configMap.label += "${conf.clusterList[i]}"
                }
            }
        }
        if(conf.templateType.contains("graviton")) configMap.label += "-arm64"
        int hashCode = Math.abs(configMap.yaml.hashCode())
        configMap.label = configMap.label + "-" + hashCode
        if (configMap.label.length() > 60) configMap.label = configMap.label.take(60)
        configMap.name = configMap.label
        echo "${configMap}"
        podTemplate(configMap) {
            node(configMap.label) {
                body()
            }
        }
    }

    def getCiPodYaml(def conf) {
        String yaml = libraryResource "pod-template.yml"
        def configMap = new YamlFileUtils().convertYamlToJson(yaml)
        configMap.metadata.namespace = conf.namespace
        configMap.spec.containers = []
        configMap.spec.volumes = []
        configMap.spec.imagePullSecrets = []
        if(conf.templateType.contains("graviton")){
            echo "loading unified-ci-graviton"
            configMap.spec.nodeSelector = ["nodepool-name": "unified-ci-graviton"]
            configMap.spec.tolerations = [[key: "uks.resource/unified-ci-graviton", operator: "Exists", effect: "NoSchedule"]]
        } 
        def clusterList = conf.clusterList
        if(clusterList) {
            for (int i = 0; i < clusterList.size(); i++) {
                def clusterId = clusterList[i]
                configMap.spec.containers.add(getClusterContainerMap(clusterId))
                if(conf.infrastructure == "kamaji-cloud" && clusterId != "helm") configMap.spec.volumes.add(setClusterVolumeMap(clusterId))
            }
            configMap.spec.volumes.add([name:"skuba-volume", emptyDir:[:]])
        }
        conf.containerList = []
        conf.containerList.add('build-tools')
        conf.containerList.add('jnlp')
        if(conf.supportDinD) conf.containerList.add('docker-in-docker')
        switch (conf.templateType) {
            case "docker-compose":
                conf.containerList.add('docker-compose')
                //use dedicated UKS node group
                if(conf.infrastructure != "kamaji-cloud" && !conf.supportDinD) {
                    configMap.spec.nodeSelector = ["provisioner-name": "unified-ci"]
                    configMap.spec.tolerations = [[key: "uks.resource/unified-ci", operator: "Exists", effect: "NoSchedule"]]
                }
                break
            case "mlscala":
            case "maven":
            case "rproject":
                conf.containerList.add('maven-build')
                // conf.containerList.add('csi')
                // conf.containerList.add('code-coverage')
                break
            case "mlpython":
                conf.containerList.add('maven-build')
                conf.containerList.add('sonar-scanner')
                break
            case 'bazel':
                conf.isNewPod = true
                conf.containerList.add('bazel-build')
                conf.containerList.add('python-build')
                break
            case 'gradle':
                conf.isNewPod = true
                conf.containerList.add('gradle-build')
                break
            case 'docker':
                conf.containerList.add('maven-build')
                break
            case "sbt":
                conf.containerList.add('sbt-build')
                break
            case "python":
                conf.isNewPod = true
                conf.containerList.add('python-build')
                break
            case "github-release":
                // conf.isNewPod = false
                break
            //-- security ----
            case "sec-maven-java":
            case "sec-maven-java11":
            case "sec-maven-java17":
                conf.containerList.add('maven-build')
                conf.containerList.add('fortify-java')
                conf.containerList.add('source-clear')
                break
            case "sec-maven-scala":
            case 'sec-mlscala-scala':
            case 'sec-maven-kotlin':
                conf.containerList.add('source-clear')
                break
            case 'sec-mlpython-python':
                conf.containerList.add('python-build')
                conf.containerList.add('fortify-java')
                break
            case 'sec-lambda-python':
                conf.containerList.add('lambda-python')
                conf.containerList.add('fortify-java')
                conf.isNewPod = true
                break
            case "sec-sbt-scala":
                conf.isNewPod = true
                conf.containerList.add('sbt-build')
                break
            case "sec-docker-js":
            case "sec-lambda-node-js":
            case "sec-docker-ts":
                conf.containerList.add('fortify-js')
                conf.containerList.add('npm-build')
                configMap.spec.hostAliases = [[ip: "43.148.3.158", hostnames: ["npm-registry.rancher.sie.sony.com"]]]
                break
            case 'sec-gradle-java11':
            case 'sec-gradle-java17':
                conf.isNewPod = true
                conf.containerList.add('gradle-build')
                conf.containerList.add('fortify-java')
                break
            case 'sec-bazel-java':
                conf.isNewPod = true
                conf.containerList.add('bazel-build')
                conf.containerList.add('fortify-java')
                break
            case "sec-docker-go":
                conf.containerList.add('go-build')
                break
            case "sec-docker-cpp":
                conf.containerList.add('cpp-build')
                break
            case "sec-mastermind-python":
                conf.containerList.add('mastermind-python')
                conf.containerList.add('fortify-java')
                conf.isNewPod = true
                break
            case "graviton-packcli":
            case "bp-image":
                conf.containerList.add('packcli')
                break
            case "maven-buildpack":
                conf.containerList.add('maven-build')
                // conf.containerList.add('csi')
                // conf.containerList.add('code-coverage')
                conf.containerList.add('packcli')
                break       
            case "gradle-buildpack":
                conf.containerList.add('gradle-build')
                //conf.containerList.add('csi')
                conf.containerList.add('packcli')
                break       
            case "go-buildpack":
            case "buildpack-go":
                conf.containerList.add('go-build')
                conf.containerList.add('packcli')
                break   
            case "checkout":
                break
            // --- end of security ----
            case "library-maven":
                conf.containerList.add('maven-build')
                // conf.containerList.add('code-coverage')
                break
            case "library-gradle":
                conf.containerList.add('gradle-build')
                break
        }
        //add volumes
        // workspace-volume added as emptyDir by default.
        if(conf.supportDinD) {
            configMap.spec.volumes.add([name: "dind-storage", emptyDir: [medium: ""]])
        } else {
            configMap.spec.volumes.add(setHostPathVolumeMap("socket-volume", env.DOCKER_SOCK))
        }

        configMap.spec.containers.addAll(getContainers(conf))
        configMap.spec.imagePullSecrets.add([name: "artcred"])
        return (new YamlFileUtils().convertMapToYaml(configMap))
    }

    def setClusterVolumeMap(String clusterId){
        return [name: clusterId, secret: [secretName: 'sie-kubeconfigs', items:[[key: clusterId, path: 'config']]]]
    }

    def setHostPathVolumeMap(String name, String hostPath){
        return [name: name, hostPath: [path: hostPath, type: 'Socket']]
    }

    def setSecretVolumeMap(String name, String secretName){
        return [name: name, secret: [secretName: secretName]]
    }

    def setVolumeMount(String name, String mountPath, String subPath = "", boolean readOnly = false){
        def conf = [name: name, mountPath: mountPath]
        if(subPath != "") conf.subPath = subPath
        if(readOnly) conf.readOnly = readOnly
        return conf
    }

    def getProxySettings(def container) {}

    //Following container will be converted to yaml.
    def getContainerMap(String containerName, String image) {
        return [
            name: containerName,
            image: image,
            tty: true,
            command: ['cat'],
            resources: [limits:[cpu: "1000m", memory: "1G"], requests: [cpu: "1000m", memory: "1G"]],
            volumeMounts: []
        ]
    }

    def getHelmContainerMap(def clusterId) {
        def image = clusterId == "helm-ci"
                ? "${ECR_HOST_URL}/engine/engine-helm3:release-1.1.0"
                : "${ECR_HOST_URL}/engine/skuba:v1.9.5"
        def container = getContainerMap(clusterId, image)
        container.volumeMounts.add(setVolumeMount("workspace-volume", "/home/jenkins/agent", "",false))
        container.volumeMounts.add(setVolumeMount("skuba-volume", "/root", "",false))
        container = [
            securityContext:[runAsUser: 0, runAsGroup: 0]
        ] << container
        container.resources = [limits:[cpu: "1000m", memory: "4G"], requests: [cpu: "1000m", memory: "2G"]]
        return container
    }

    def getContainers(def conf){
        def containers = []
        def containerList = conf.containerList
        for (int i = 0; i < containerList.size(); i++) {
            def containerName = containerList[i]
            def container = null
            String buildImage
            boolean mountSocket = true
            switch (containerName) {
            case "jnlp":
                String jlnpImage = "${ECR_HOST_URL}/uks-external/jenkins/inbound-agent:3206.vb_15dcf73f6a_9-2"
                if(conf.templateType.contains("graviton")){
                  jlnpImage = "${ECR_HOST_URL}/uks-external/jenkins/inbound-agent:3206.vb_15dcf73f6a_9-2-linux-arm64"     
                }    
                container = getContainerMap("jnlp", jlnpImage)
                container.tty = false
                container = container.minus(command: ['cat'])
                break
            case "build-tools":
                /** Now use https://github.sie.sony.com/SIE/engine-image-build-tools for both amd64 and arm64*/
                container = getContainerMap(containerName, "${ECR_HOST_URL}/engine/engine-image-build-tools:release-1.0.0-20241021134930")
                container = [args: ["cat"]] << container
                container.command = ["/bin/sh", "-c"]
                container.resources = [limits:[cpu: "2000m", memory: "8G"], requests:[cpu: "1000m", memory: "4G"]]
                container.volumeMounts.add(setVolumeMount("workspace-volume", "/home/jenkins/agent", "",false))
                getProxySettings(container)
                break
             case "csi":
                container = getContainerMap(containerName, "${ECR_HOST_URL}/engine/spectral:1.0.0-20220712T1427")
                container.resources = [limits:[cpu: "200m", memory: "1G"], requests:[cpu: "200m", memory: "1G"]]
                getProxySettings(container)
                break
            case "maven-build":
                switch(conf.languageVersion){
                    case "17":
                        buildImage = "${ECR_HOST_URL}/engine/maven-build-java17:release-0.1.0"
                        break
                    case "11":
                        buildImage = "${ECR_HOST_URL}/engine/maven-build-java11:release-0.0.6"
                        break
                    default:
                        buildImage = "${ECR_HOST_URL}/engine/maven-build-java8:release-0.0.5"
                        break
                }
                container = getContainerMap(containerName, "${buildImage}")
                getProxySettings(container)
                if(conf.templateType == "mlscala") {
                    container.resources = [limits:[cpu: "4000m", memory: "32G"], requests:[cpu: "4000m", memory: "32G"]]
               } else {
                    container.resources = [limits:[cpu: "2000m", memory: "6G"], requests:[cpu: "2000m", memory: "6G"]]
                }
                break
            case "source-clear":
                switch(conf.languageVersion){
                    case "17":
                        buildImage = "${ECR_HOST_URL}/engine/maven-build-java17:release-0.1.0"
                        break
                    default:
                        buildImage = "${ECR_HOST_URL}/engine/maven-build-java11:release-0.0.6"
                        break
                }
                container = getContainerMap(containerName, "${buildImage}")
                container.resources = [limits:[cpu: "2000m", memory: "8G"], requests:[cpu: "2000m", memory: "6G"]]
                getProxySettings(container)
                break
            case "fortify-java":
                container = getContainerMap(containerName, "${ECR_HOST_URL}/engine/fortify:release-22.1.1-20230316T1100")
                container.resources = [limits:[cpu: "12000m", memory: "32G"], requests:[cpu: "12000m", memory: "32G"]]
                getProxySettings(container)
                break
            case "fortify-js":
                container = getContainerMap(containerName, "${ECR_HOST_URL}/engine/fortify:release-22.1.1-20230316T1100")
                container.resources = [limits:[cpu: "12000m", memory: "64G"], requests:[cpu: "12000m", memory: "64G"]]
                getProxySettings(container)
                break
            case "npm-build":
                String imgVersion = conf.languageVersion == "14" ? "37-12.0-srcclr" : "16.0.20211029225344"
                container = getContainerMap(containerName, "${ECR_HOST_URL}/engine/node:release-${imgVersion}")
                container.resources = [limits:[cpu: "4000m", memory: "12G"], requests:[cpu: "4000m", memory: "12G"]]
                getProxySettings(container)
                break
            case "gradle-build":
                switch(conf.languageVersion){
                    case "17":
                        buildImage = "${ECR_HOST_URL}/engine/gradle-build-java17:release-0.0.2"
                        break
                    default:
                        buildImage = "${ECR_HOST_URL}/engine/gradle-build-java11:release-20230510103200"
                        break
                }
                container = getContainerMap(containerName, "${buildImage}")
                getProxySettings(container)
                container.resources = [limits:[cpu: "2000m", memory: "4G"], requests:[cpu: "1000m", memory: "2G"]]
                break
            case "go-build":
                container = getContainerMap(containerName, "${ECR_HOST_URL}/uks-external/library/golang:1.23.2")
                container.resources = [limits:[cpu: "2000m", memory: "4G"], requests:[cpu: "2000m", memory: "4G"]]
                break
            case "sbt-build":
                container = getContainerMap(containerName, "${ECR_HOST_URL}/engine/sbt-openjdk:1.2.8-11")
                container.resources = [limits:[cpu: "2000m", memory: "8G"], requests:[cpu: "2000m", memory: "8G"]]
                break
            case "lambda-python":
                container = getContainerMap(containerName, "${ECR_HOST_URL}/catalyst/srcclr:37-1.1-lambda-python")
                container.resources = [limits: [cpu: "1000m", memory: "2G"], requests: [cpu: "1000m", memory: "2G"]]
                break
            case "python-build":
                container = getContainerMap(containerName, "${ECR_HOST_URL}/engine/srcclr:release-37-python37-0.0.1")
                container.resources = [limits: [cpu: "2000m", memory: "4G"], requests: [cpu: "1000m", memory: "4G"]]
                break
            case "cpp-build":
                container = getContainerMap(containerName, "${ECR_HOST_URL}/catalyst/jre11:corretto-dd-20200713T171957Z")
                container.resources = [limits: [cpu: "2000m", memory: "4G"], requests: [cpu: "1000m", memory: "2G"]]
                break
            case "bazel-build":
                container = getContainerMap(containerName, "${ECR_HOST_URL}/xyz/build-bazel:v20210310-37ab83b")
                container.resources = [limits: [cpu: "4000m", memory: "16G"], requests: [cpu: "4000m", memory: "16G"]]
                break
            case "sonar-scanner":
                container = getContainerMap(containerName, "${ECR_HOST_URL}/catalyst/sonarscanner:4.4.0")
                container.resources = [limits: [cpu: "1000m", memory: "2G"], requests: [cpu: "1000m", memory: "2G"]]
                break
            case "mastermind-python":
                container = getContainerMap(containerName, "${ECR_HOST_URL}/catalyst/srcclr:37-python37-20210723T000000Z")
                container.resources = [limits:[cpu: "2000m", memory: "4G"], requests:[cpu: "2000m", memory: "4G"]]
                break
            // case "awscli":
            //     container = getContainerMap(containerName, "${ECR_HOST_URL}/engine/awscli:release-0.0.2")
            //     container.resources = [limits:[cpu: "200m", memory: "1G"], requests:[cpu: "150m", memory: "1G"]]
            //     getProxySettings(container)
            //     break
            // case "code-coverage":
            //     buildImage = "${ECR_HOST_URL}/engine/maven-build-java11:release-0.0.5"
            //     container = getContainerMap(containerName, "${buildImage}")
            //     getProxySettings(container)
            //     if(conf.templateType == "mlscala") {
            //         container.resources = [limits:[cpu: "4000m", memory: "16G"], requests:[cpu: "4000m", memory: "32G"]]
            //     } else {
            //         container.resources = [limits:[cpu: "2000m", memory: "8G"], requests:[cpu: "2000m", memory: "6G"]]
            //     }
            //     break
            case "packcli":
                container = getContainerMap(containerName, "${ECR_HOST_URL}/engine/engine-image-packcli:release-1.0.2-20241028151505")
                container.resources = [limits: [cpu: "4000m", memory: "16G"], requests: [cpu: "4000m", memory: "16G"]]
                getProxySettings(container)
                break
            case "docker-compose":
                container = getContainerMap(containerName, "${ECR_HOST_URL}/engine/docker-compose:release-0.0.1-test");
                container = [args: ["cat"]] << container
                container.command = ["/bin/sh", "-c"]
                container.volumeMounts.add(setVolumeMount("workspace-volume", "/home/jenkins/agent", "",false))
                container.resources = [limits:[cpu: "1000m", memory: "2G"], requests:[cpu: "1000m", memory: "2G"]]
                getProxySettings(container)
                break
            case "docker-in-docker":
                mountSocket = false
                if(conf.templateType.contains("graviton"))
                    container = getContainerMap("dind", "${ECR_HOST_URL}/uks-external/library/docker:23.0.5-dind-linux-arm64-v8");
                else 
                    container = getContainerMap("dind", "${ECR_HOST_URL}/uks-external/library/docker:23.0-dind");
                if(conf.templateType == 'docker-compose') {
                    container.resources = [limits:[cpu: "4000m", memory: "32G"], requests:[cpu: "2000m", memory: "4G"]]
                } else {
                    container.resources = [limits:[cpu: "4000m", memory: "16G"], requests:[cpu: "1000m", memory: "2G"]]
                }
               container.ports = [[containerPort: 2375]]
                container.securityContext = [privileged: true]
                container.env = [[name: 'DOCKER_TLS_CERTDIR', value: '']]
                container.volumeMounts.add(setVolumeMount('dind-storage', "/var/lib/docker"))
                container = container.minus(command: ['cat'])
                container = container.minus(tty: true)
                break
            }
            
            if(container) {
                if(mountSocket){
                    if(conf.supportDinD){
                        if(!container.env) container.env = []
                        container.env.add([name: 'DOCKER_HOST', value: 'tcp://localhost:2375'])
                    } else {
                        container.volumeMounts.add(setVolumeMount('socket-volume', env.DOCKER_SOCK))
                    }
                }                                
                containers.add(container)
            } else {
                throw new GroovyException("The container " + containerName + " was not properly defined.")
            }
        }

        return containers
    }

    void addContainerEnv(def container){
        if(container.env==null)
            container.env=[]
        container.env.add([name: "TESTCONTAINERS_TINYIMAGE_CONTAINER_IMAGE", value:"library/alpine:3.16"])
        container.env.add([name: "TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX", value:"890655436785.dkr.ecr.us-west-2.amazonaws.com/uks-external/"])
    }
}
