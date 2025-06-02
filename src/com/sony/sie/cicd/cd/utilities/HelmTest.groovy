package com.sony.sie.cicd.cd.utilities

def getContainers(String podName, String namespace) {
    def cmd = "skuba kubectl get pods ${podName} -o jsonpath='{..containers[*].name}' -n ${namespace} | xargs -n1 | sort -u"
    def rtn = sh(returnStdout: true, script: cmd)
    return rtn != "" ? rtn.split() : null
}

def getTestPods(String namespace, String releaseName){
    def cmd = "skuba kubectl get pods -o jsonpath='{..metadata.name}' -n ${namespace} | xargs -n1 | sort -u | grep ${releaseName}-helm-test"
    def rtn = sh(returnStdout: true, script: cmd)
    return rtn != "" ? rtn.split() : null
}

def getTestJobs(String namespace, String releaseName){
    def cmd = "skuba kubectl get jobs -o jsonpath='{..metadata.name}' -n ${namespace} | xargs -n1 | sort -u | grep ${releaseName}-helm-test"
    def rtn = sh(returnStdout: true, script: cmd)
    return rtn != "" ? rtn.split() : null
}

void cleanupTestPods(String releaseName, String namespace){
    def testPods = getTestPods(namespace, releaseName)
    if(testPods) {
        for (int i = 0; i < testPods.size(); i++) {
            String podName = testPods[i]
            try {
                sh "skuba kubectl delete pod/${podName} --namespace ${namespace}"
                ansi_echo "Deleted pod/${podName}"
            } catch (Exception err) {
                ansi_echo "Deleting pod/${podName} failed: " + err.getMessage(), 31
            }
        }
    }
}

void cleanupTestJobs(String releaseName, String namespace){
    def testJobs = getTestJobs(namespace, releaseName)
    if(testJobs) {
        for (int i = 0; i < testJobs.size(); i++) {
            String jobName = testJobs[i]
             try {
                 sh "skuba kubectl delete job/${jobName} --namespace ${namespace}"
                 ansi_echo "Deleted job/${jobName}"
             } catch (Exception err) {
                 ansi_echo "Deleting job/${jobName} failed: " + err.getMessage(), 31
             }
        }
    }
}

void getTestLogs(def testPods, String namespace){
    if(testPods) {
        boolean hasTomcat = false
        for (int i = 0; i < testPods.size(); i++) {
            def podName = testPods[i]
            def containers = getContainers(podName, namespace)
            if (containers) {
                ansi_echo "Show container logs for the pod of ${podName}"
                for (int ii = 0; ii < containers.size(); ii++) {
                    def containerName = containers[ii]
                    sh "skuba kubectl logs ${podName} ${containerName} -n ${namespace} "
                }
            } else {
                sh "skuba kubectl logs ${podName} -n ${namespace} "
            }
        }
    }
}

def cleanupConfig(String fileName, String namespace ) {
    try {
        sh "skuba kubectl delete -f ${fileName} -n ${namespace}"
    } catch (Exception err) {
        ansi_echo "cleanupConfig failed: " + err.getMessage(), 31
    }
}

def process(def conf) {
    ansi_echo "--- Executing Helm Test ---"
    String releaseName = conf.releaseName
    String namespace = conf.namespace
    testDefinition = [helmTestScope: "", timeout: "20m0s",  helmTestConfigMap: "helmTestConfigMap.yaml", helmTestSecretMap: "helmTestSecretMap.yaml"] << conf
    try {
        if(testDefinition.helmTestScope != "") updateTestConfigMap()
        sh "skuba helm test ${releaseName} --timeout ${testDefinition.timeout} -n ${namespace}"
    } catch (Exception err) {
        ansi_echo "helm test failed: " + err.getMessage(), 31
        throw err
    } finally {
        if(testDefinition.helmTestScope != "") cleanupConfig("${testDefinition.helmTestConfigMap}", namespace )
        def testPods = getTestPods(namespace, releaseName)
        getTestLogs(testPods, namespace)
        cleanupTestPods releaseName, namespace
        cleanupTestJobs releaseName, namespace
    }
}

void updateTestConfigMap() {
    String testScope = testDefinition.helmTestScope
    String namespace = testDefinition.namespace

    def configMap = [
            apiVersion: "v1",
            kind: "ConfigMap",
            metadata: [
                    name: 'helm-test-scope-cm',
                    labels: [name: 'helm-test-scope-cm'],
                    namespace: "${namespace}"

            ],
            data: [HELM_TEST_SCOPE: "${testScope}"]
    ]

    String fileName = testDefinition.helmTestConfigMap
    if(fileExists(fileName)){
        sh "rm ${fileName}"
    }
    writeYaml file: fileName, data: configMap
    sh "skuba kubectl apply -f ${fileName} --validate=false -n ${namespace}"
}

void updateHelmSecretMap() {
    String namespace = testDefinition.namespace
    def token = ""
    if(testDefinition.testTokenName == 'k8s-cert') {
        dir("sie-config${env.VERSION_TIMESTAMP}") {
            sh """
                cp /root/.kube/config sie-config
                chmod 600 sie-config 
            """
            token = sh(returnStdout: true, script: "cat sie-config")
        }
    } else {
        withCredentials([file(credentialsId: testDefinition.testTokenName, variable: 'cred')]) {
            token = "${cred}"
        }
    }
    def configMap = [
            apiVersion: "v1",
            kind      : "Secret",
            metadata  : [
                    name     : 'helm-test-cluster-secret',
                    labels   : [name: 'helm-test-cluster-secret'],
                    namespace: "${namespace}"

            ],
            type      : "Opaque",
            stringData: [HELM_TEST_TOKEN: "${token}"]
    ]

    String fileName = testDefinition.helmTestSecretMap
    if (fileExists(fileName)) {
        sh "rm ${fileName}"
    }
    writeYaml file: fileName, data: configMap
    sh "skuba kubectl apply -f ${fileName} --validate=false -n ${namespace}"
}

void ansi_echo(String txt, Integer color = 34) {
    //color code: black: 30, red: 31, green: 32, yellow: 33, blue: 34, purple: 35
    echo "\033[01;${color}m ${txt}...\033[00m"
}

return this
