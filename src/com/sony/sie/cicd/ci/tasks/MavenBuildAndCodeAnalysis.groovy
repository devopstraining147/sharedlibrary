package com.sony.sie.cicd.ci.tasks

import com.sony.sie.cicd.helpers.utilities.KmjEnv

def mvnBuild(def pipelineDefinition) {
    def command = "mvn -B clean install"
    def defaultOpts = ' -Djacoco.destFile=target/jacoco.exec org.jacoco:jacoco-maven-plugin:prepare-agent -Pcode-coverage'
    if (pipelineDefinition.infrastructure == "kamaji-cloud") {
        defaultOpts += " -DexcludedGroups=integration"
    }
    if (pipelineDefinition.repoInfo.buildOptions) {
        command = command + " ${pipelineDefinition.repoInfo.buildOptions}" + defaultOpts
        echo "Using default maven build options: ${defaultOpts} with user set options: ${pipelineDefinition.buildOptions}"
    } else {
        command = command + defaultOpts
        echo "Using Default Maven Build Options: ${command}"
    }
    // TODO: This is a hack to get around using external binary for consul
    if (env.REPO_NAME == "aang") {
        def exportConsul = "export CONSUL_BINARY_CDN=${KmjEnv.ART_URL}engine-maven-thirdparty-prod-local/consul/ && "
        command = exportConsul + command
        echo "Using Aang Consul Export: ${command}"
    }
    echo "Using Maven Options: ${command}"
    return command
}

