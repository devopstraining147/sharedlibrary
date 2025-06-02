package com.sony.sie.cicd.ci.utilities

import org.codehaus.groovy.GroovyException

def createMapiJob(String yamlFile, String branch, String yamlKey, String tagName, boolean getModelName = false) {
    String mapiEnv = "mastermind-kc2e2e8821.ml.playstation.net:50051"
    if(branch=="staging"){
        mapiEnv = "mastermind-staging-kc2e2e8821.ml.playstation.net:50051"
    }

    String modelNameRequest = (getModelName) ? "--print_model_name" : ""

    try {
        def cmd = """
            cp ${yamlFile} /
            cd /
            python3 create-mapi-job.py --yaml_file ${yamlFile} --mapi_endpoint ${mapiEnv} --yaml_key ${yamlKey} --release ${tagName} ${modelNameRequest}
        """
        return sh(returnStdout: true, script: cmd)
    } catch (Exception err) {
        echo "Call to MAPI in environment ${mapiEnv} failed. Contact the caprica team to further debug the issue."
        throw new GroovyException("create-mapi-job failure: " + err.getMessage())
    }
}

def getArtifactoryTarget(String target, Boolean isModel = false) {
    def targetMap = [job: [prod: 'caprica-mastermind-python-prod-local', test: 'caprica-mastermind-python-staging-local'],
                     other: [prod: 'caprica-python-prod-sf', test: 'caprica-python-dev-sf']]

    def targets = isModel ? targetMap.job : targetMap.other

    if (target == "master" || target == "prod" || target == "main") {
        return targets.prod
    }
    return targets.test
}

def createArtifactoryUrl(String user, String pass, String target) {
    return "https://${user}:${pass}@artifactory.sie.sony.com/artifactory/api/pypi/${getArtifactoryTarget(target)}/simple"
}
