import com.sony.sie.cicd.helpers.utilities.JenkinsUtils
import com.sony.sie.cicd.cd.utilities.Helm3KubeHelper
import com.sony.sie.cicd.cd.utilities.HelmUtils
import org.codehaus.groovy.GroovyException

def call(def bCreateNamespace = true) {
    jenkinsUtils = new JenkinsUtils()
    ansiColor('xterm') {
        try {
            timestamps {
                echo "Starting Jenkins Job..."
                setProperties()

                conf = [:]
                conf.clusterId = params.CLUSTER_ID ? params.CLUSTER_ID.trim() : ""
                conf.namespace = params.NAMESPACE ? params.NAMESPACE.trim() : ""
                conf.infrastructure = params.INFRASTRUCTURE ? params.INFRASTRUCTURE.trim() : ""
                if (conf.infrastructure == "") throw new GroovyException("The infrastructure name was not selected, please select.")
                if (conf.namespace == "") throw new GroovyException("The namespace was not provided, please input.")
 
                jenkinsUtils.jenkinsNode(templateType: 'helm', infrastructure: params.INFRASTRUCTURE, clusterList: ["${conf.clusterId}"]) {
                    String labelStage = bCreateNamespace ? "Create Namespace" : "Delete Namespace"
                    stage(labelStage) {
                        echo "${conf}"
                        jenkinsUtils.k8sAccessConfig conf
                        Helm3KubeHelper helmKubeHelper = new Helm3KubeHelper(conf.clusterId, conf.namespace)
                        if (helmKubeHelper.ifNamespaceExist(conf.namespace)) {
                            if(bCreateNamespace == false) { //delete namespce
                                new HelmUtils().deleteNamespace(conf)
                            } else {
                                echo "The namespace exists already!"
                            }
                        } else {
                            if(bCreateNamespace == true) { //create namespce
                                new HelmUtils().createNamespace(conf)
                            } else {
                                echo "The namespace does not exists yet!"
                            }
                        }
                    }
                }
            }
        } catch (GroovyException err) {
            if(!jenkinsUtils.isBuildAborted()) {
                String msg = err.getMessage()
                if (!msg) msg = 'Unknown error!'
                echo msg
                currentBuild.result = "FAILURE"
            }
        }
    }
}

void setProperties() {
    def infrastructureList = ["", "kamaji-cloud", "laco-cloud", "navigator-cloud", "roadster-cloud"]
    def settings = [
        choice(
                choices: infrastructureList.join("\n"),
                description: 'Required: infrastructure name, i.e.: navigator-cloud',
                name: 'INFRASTRUCTURE'
        ),
        [$class: 'CascadeChoiceParameter',
            name: 'CLUSTER_ID',
            description: 'Required: cluster for performace test',
            choiceType: 'PT_SINGLE_SELECT',
            filterLength: 1,
            filterable: false,
            randomName: 'choice-parameter-02',
            referencedParameters: 'INFRASTRUCTURE',
            script: [$class: 'GroovyScript', fallbackScript: [classpath: [], sandbox: true, script: "return [' NOT APPLICABLE ']"],
                    script: [classpath: [], sandbox: true, script: """
                    if (INFRASTRUCTURE.equals("kamaji-cloud")) {
                        return ["uks-4278-sandbox-usw2-pner"]
                    } else if (INFRASTRUCTURE.equals("laco-cloud")) {
                        return ["uks-9677-d1np-usw2-0006"]
                    } else if (INFRASTRUCTURE.equals("navigator-cloud")) {
                        return ["uks-4885-d1np-usw2-gt3z"]
                    } else if (INFRASTRUCTURE.equals("roadster-cloud")) {
                        return ["uks-5512-dev-apne1-shr1"]
                    } else {
                        return ['NOT APPLICABLE']
                    }"""]
            ]
        ],
        string(
                defaultValue: '',
                description: 'Required: Namespace for performace test. i.e. catalyst-example-sandbox',
                name: 'NAMESPACE'
        )
    ]
    properties([
        buildDiscarder(
            logRotator(
                artifactDaysToKeepStr: '',
                artifactNumToKeepStr: '',
                daysToKeepStr: '180',
                numToKeepStr: '10')
        ),
        parameters(settings),
        disableConcurrentBuilds(),
        pipelineTriggers([])
    ])
    echo "INFRASTRUCTURE = ${params.INFRASTRUCTURE}"
    echo "CLUSTER_ID = ${params.CLUSTER_ID}"
    echo "NAMESPACE = ${params.NAMESPACE}"

}
