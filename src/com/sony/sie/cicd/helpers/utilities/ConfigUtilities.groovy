package com.sony.sie.cicd.helpers.utilities

import org.codehaus.groovy.GroovyException

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson

def loadCDConfiguration(String fileName) {
    def jenkinsUtils = new JenkinsUtils()
    def confMap = null
    String configRepoName = "engine-cd-configurations"
    String branchName = "master"
    dir(configRepoName) {
        def confFileName = "unified-cd-config.yaml"
        jenkinsUtils.getFileFromGithub(configRepoName, branchName, "SIE", fileName, confFileName)
        log.info "read file: ${fileName}"
        if (fileExists(confFileName)) {
            confMap = readYaml file: confFileName
            confMap.approvers = [devApprover: confMap.permission.deploy.nonprod.join(","),
                                 coApprover: confMap.permission.deploy.prod.join(",")]
            confMap.permission = null
            if(!confMap.namespacePrefix) confMap.namespacePrefix = confMap.name
            if(confMap.deployEnvs != null) confMap.deployClusters = confMap.deployEnvs
            log.info "ConfigMap of ${fileName}: \n${prettyPrint(toJson(confMap))}"
        } else {
            log.info "The configuration file of ${fileName} can not been found in ${configRepoName}!", 31
            new GroovyException("The configuration file of ${fileName} can not been found in ${configRepoName}!, please check the name and try again.")
        }
    }
    return confMap
}

return this
