
package com.sony.sie.cicd.ci.utilities

void publish(String containerName = 'mlpython', Closure body) {
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "ml-artifactory-svcacct", usernameVariable: 'username', passwordVariable: 'password']]) {
        container(containerName) {
            exeClosure(body)
        }
    }
}

void build(String cmd) {
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "ml-artifactory-svcacct", usernameVariable: 'username', passwordVariable: 'password']]) {
        cmd = cmd + " --build-arg ArtifactoryUsername=${username} --build-arg ArtifactoryPassword=${password}"
        container('build-tools') {
            sh cmd
        }
    }
}

def exeClosure(Closure body) {
    if(body != null){
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body.delegate = this
        body()
    }
}
def pythonCompile(String artifactType) {
    def artifactoryUrl = new MLUtils().createArtifactoryUrl(username, password, artifactType)
    sh """
        python3 -m pip install --no-cache-dir -r requirements.txt -t dependencies --extra-index-url ${artifactoryUrl}
        if  [ -f "requirements-no-deps.txt" ]; then
            python3 -m pip install --no-deps -r requirements-no-deps.txt -t dependencies --extra-index-url ${artifactoryUrl}
        fi
        python3 -m pip install -r requirements-dev.txt -t dependencies-dev
    """
}

return this
