package com.sony.sie.cicd.ci.utilities

import com.sony.sie.cicd.helpers.utilities.KmjEnv


def setEnginePoms() {
    def modPomfiles = []
    def parentVersion = '1.0.0'

    // Engine Common Parent Version
    engineCommonParentVersion = getParentArtifactVersion("com.sony.sie.unified", "engine-common-parent").toString()
    // Setting these to use snapshot versions for now
    // Korra Common Parents Version
    korraCommonParentVersion = getParentArtifactVersion("com.sony.snei.korra", "engine-korra-common", true).toString()
    korraSpVersion = getParentArtifactVersion("com.sony.snei.korra.sp", "engine-korra-sp", true).toString()
    korraParentVersion = getParentArtifactVersion("com.sony.snei.korra.parent", "engine-parent", true).toString()
    korraImplParentVersion = getParentArtifactVersion("com.sony.snei.korra.sp", "engine-impl-parent", true).toString()
    korraClientParentVersion = getParentArtifactVersion("com.sony.snei.korra.sp", "engine-client-parent", true).toString()
    korraApiParentVersion = getParentArtifactVersion("com.sony.snei.korra.sp", "engine-api-parent", true).toString()
    korraOpenApiParentVersion = getParentArtifactVersion("com.sony.snei.korra.sp", "engine-api-parent", true).toString()
    // Aang Common Parents Version
    aangParentVersion = getParentArtifactVersion("com.sony.sie.aang", "engine-aang-parent", true).toString()
    aangImplParentVersion = getParentArtifactVersion("com.sony.sie.aang", "engine-aang-impl-parent", true).toString()
    aangClientsVersion = getParentArtifactVersion("com.sony.sie.aang", "engine-aang-clients", true).toString()
    aangClientTestsVersion = getParentArtifactVersion("com.sony.sie.aang", "engine-aang-client-tests", true).toString()
    aangExamplesVersion = getParentArtifactVersion("com.sony.sie.aang", "engine-aang-examples", true).toString()
    aangImplParentWebfluxVersion = getParentArtifactVersion("com.sony.sie.aang", "engine-aang-impl-parent-webflux", true).toString()
    aangImplParentServletVersion = getParentArtifactVersion("com.sony.sie.aang", "engine-aang-impl-parent-servlet", true).toString()

    try {
        echo "Using engine-common-parent version: ${engineCommonParentVersion}, engine-korra-common version: ${korraCommonParentVersion}, engine-korra-sp version: ${korraSpVersion}, engine-parent version: ${korraParentVersion}"
        dir("${WORKSPACE}") {
            modPomfiles = findFiles(glob: '**/pom.xml')
        }
        if (modPomfiles != null && modPomfiles.size() > 0) {
            for (int i = 0; i < modPomfiles.size(); i++) {
                echo "Reading POM: ${WORKSPACE}/${modPomfiles[i].path}"
                pom = readMavenPom file: "${WORKSPACE}/${modPomfiles[i].path}"
                if (pom.parent?.groupId != null && pom.parent.groupId == 'com.sony.sie.hyperloop' && (pom.parent.artifactId == 'java-parent' || pom.parent.artifactId == 'common-parent')) {
                    echo "${WORKSPACE}/${modPomfiles[i].path} uses ${pom.parent.artifactId}, changing to engine-common-parent..."
                    pom.parent.groupId = 'com.sony.sie.unified'
                    pom.parent.artifactId = 'engine-common-parent'
                    pom.parent.version = engineCommonParentVersion
                    pom.artifactId = 'engine-' + pom.artifactId
                    deployPomFile (pom, modPomfiles[i].path)
                } else if (pom.parent?.groupId != null && pom.parent.groupId == 'com.sony.snei.korra' && pom.parent.artifactId == 'korra-common') {
                    echo "${WORKSPACE}/${modPomfiles[i].path} uses ${pom.parent.artifactId}, changing to engine-${pom.parent.artifactId}..."
                    pom.parent.artifactId = 'engine-' + pom.parent.artifactId
                    pom.parent.version = korraCommonParentVersion != '' ? korraCommonParentVersion : pom.parent.version
                    pom.artifactId = 'engine-' + pom.artifactId
                    deployPomFile (pom, modPomfiles[i].path)
                } else if (pom.parent?.groupId != null && pom.parent.groupId == 'com.sony.snei.korra.sp' && pom.parent.artifactId == 'korra-sp') {
                    echo "${WORKSPACE}/${modPomfiles[i].path} uses ${pom.parent.artifactId}, changing to engine-${pom.parent.artifactId}..."
                    pom.parent.artifactId = 'engine-' + pom.parent.artifactId
                    pom.parent.version = korraSpVersion != '' ? korraSpVersion : pom.parent.version
                    pom.artifactId = 'engine-' + pom.artifactId
                    deployPomFile (pom, modPomfiles[i].path)
                } else if (pom.parent?.groupId != null && pom.parent.groupId == 'com.sony.snei.korra.sp' && pom.parent.artifactId == 'impl-parent') {
                    echo "${WORKSPACE}/${modPomfiles[i].path} uses ${pom.parent.artifactId}, changing to engine-${pom.parent.artifactId}..."
                    pom.parent.artifactId = 'engine-' + pom.parent.artifactId
                    pom.parent.version = korraImplParentVersion != '' ? korraImplParentVersion : pom.parent.version
                    pom.artifactId = 'engine-' + pom.artifactId
                    deployPomFile (pom, modPomfiles[i].path)
                } else if (pom.parent?.groupId != null && pom.parent.groupId == 'com.sony.snei.korra.sp' && pom.parent.artifactId == 'client-parent') {
                    echo "${WORKSPACE}/${modPomfiles[i].path} uses ${pom.parent.artifactId}, changing to engine-${pom.parent.artifactId}..."
                    pom.parent.artifactId = 'engine-' + pom.parent.artifactId
                    pom.parent.version = korraClientParentVersion != '' ? korraClientParentVersion : pom.parent.version
                    pom.artifactId = 'engine-' + pom.artifactId
                    deployPomFile (pom, modPomfiles[i].path)
                } else if (pom.parent?.groupId != null && pom.parent.groupId == 'com.sony.snei.korra.sp' && pom.parent.artifactId == 'api-parent') {
                    echo "${WORKSPACE}/${modPomfiles[i].path} uses ${pom.parent.artifactId}, changing to engine-${pom.parent.artifactId}..."
                    pom.parent.artifactId = 'engine-' + pom.parent.artifactId
                    pom.parent.version = korraApiParentVersion != '' ? korraApiParentVersion : pom.parent.version
                    pom.artifactId = 'engine-' + pom.artifactId
                    deployPomFile (pom, modPomfiles[i].path)
                } else if (pom.parent?.groupId != null && pom.parent.groupId == 'com.sony.snei.korra.sp' && pom.parent.artifactId == 'openapi-parent') {
                    echo "${WORKSPACE}/${modPomfiles[i].path} uses ${pom.parent.artifactId}, changing to engine-${pom.parent.artifactId}..."
                    pom.parent.artifactId = 'engine-' + pom.parent.artifactId
                    pom.parent.version = korraOpenApiParentVersion != '' ? korraOpenApiParentVersion : pom.parent.version
                    pom.artifactId = 'engine-' + pom.artifactId
                    deployPomFile (pom, modPomfiles[i].path)
                } else if (pom.parent?.groupId != null && pom.parent.groupId == 'com.sony.snei.korra.parent' && pom.parent.artifactId == 'parent') {
                    echo "${WORKSPACE}/${modPomfiles[i].path} uses ${pom.parent.artifactId}, changing to engine-${pom.parent.artifactId}..."
                    pom.parent.artifactId = 'engine-' + pom.parent.artifactId
                    pom.parent.version = korraParentVersion != '' ? korraParentVersion : pom.parent.version
                    pom.artifactId = 'engine-' + pom.artifactId
                    deployPomFile (pom, modPomfiles[i].path)
                } else if (pom.parent?.groupId != null && pom.parent.groupId == 'com.sony.sie.aang' && pom.parent.artifactId == 'aang-parent') {
                    echo "${WORKSPACE}/${modPomfiles[i].path} uses ${pom.parent.artifactId}, changing to engine-${pom.parent.artifactId}..."
                    pom.parent.artifactId = 'engine-' + pom.parent.artifactId
                    pom.parent.version = aangParentVersion != '' ? aangParentVersion : pom.parent.version
                    pom.artifactId = 'engine-' + pom.artifactId
                    deployPomFile (pom, modPomfiles[i].path)
                } else if (pom.parent?.groupId != null && (pom.parent.groupId == 'com.sony.sie.aang' && pom.parent.artifactId == 'aang-impl-parent')) {
                    echo "${WORKSPACE}/${modPomfiles[i].path} uses ${pom.parent.artifactId}, changing to engine-aang-impl-parent..."
                    pom.parent.artifactId = 'engine-aang-impl-parent'
                    pom.parent.version = aangImplParentVersion != '' ? aangImplParentVersion : pom.parent.version
                    pom.artifactId = 'engine-' + pom.artifactId
                    deployPomFile (pom, modPomfiles[i].path)
                } else if (pom.parent?.groupId != null && (pom.parent.groupId == 'com.sony.sie.aang' && pom.parent.artifactId == 'aang-impl-parent-webflux')) {
                    echo "${WORKSPACE}/${modPomfiles[i].path} uses ${pom.parent.artifactId}, changing to engine-aang-impl-parent-webflux..."
                    pom.parent.artifactId = 'engine-aang-impl-parent-webflux'
                    pom.parent.version = aangImplParentWebfluxVersion != '' ? aangImplParentWebfluxVersion : pom.parent.version
                    pom.artifactId = 'engine-' + pom.artifactId
                    deployPomFile (pom, modPomfiles[i].path) 
                } else if (pom.parent?.groupId != null && (pom.parent.groupId == 'com.sony.sie.aang' && pom.parent.artifactId == 'aang-impl-parent-servlet')) {
                    echo "${WORKSPACE}/${modPomfiles[i].path} uses ${pom.parent.artifactId}, changing to engine-aang-impl-parent-servlet..."
                    pom.parent.artifactId = 'engine-aang-impl-parent-servlet'
                    pom.parent.version = aangImplParentServletVersion != '' ? aangImplParentServletVersion : pom.parent.version
                    pom.artifactId = 'engine-' + pom.artifactId
                    deployPomFile (pom, modPomfiles[i].path) 
                } else if (pom.parent?.groupId != null && (pom.parent.groupId == 'com.sony.sie.aang' && pom.parent.artifactId == 'aang-clients')) {
                    echo "${WORKSPACE}/${modPomfiles[i].path} uses ${pom.parent.artifactId}, changing to engine-aang-clients..."
                    pom.parent.artifactId = 'engine-aang-clients'
                    pom.parent.version = aangClientsVersion != '' ? aangClientsVersion : pom.parent.version
                    pom.artifactId = 'engine-' + pom.artifactId
                    deployPomFile (pom, modPomfiles[i].path)
                } else if (pom.parent?.groupId != null && (pom.parent.groupId == 'com.sony.sie.aang' && pom.parent.artifactId == 'aang-client-tests')) {
                    echo "${WORKSPACE}/${modPomfiles[i].path} uses ${pom.parent.artifactId}, changing to engine-aang-client-tests..."
                    pom.parent.artifactId = 'engine-aang-client-tests'
                    pom.parent.version = aangClientTestsVersion != '' ? aangClientTestsVersion : pom.parent.version
                    pom.artifactId = 'engine-' + pom.artifactId
                    deployPomFile (pom, modPomfiles[i].path)
                }else if (pom.parent?.groupId != null && (pom.parent.groupId == 'com.sony.sie.aang' && pom.parent.artifactId == 'aang-examples')) {
                    echo "${WORKSPACE}/${modPomfiles[i].path} uses ${pom.parent.artifactId}, changing to engine-aang-examples..."
                    pom.parent.artifactId = 'engine-aang-examples'
                    pom.parent.version = aangExamplesVersion != '' ? aangExamplesVersion : pom.parent.version
                    pom.artifactId = 'engine-' + pom.artifactId
                    deployPomFile (pom, modPomfiles[i].path)
                }
            }
        } else {
            echo 'No POM Changes needed.'
        }
    } catch (e) {
        echo "Error Reading POM files: ${e}"
    }
}


    def deployPomFile (def pom, def modPomfilesPath) {
        def repoID = 'engine-maven-snapshot-local'
        echo "Deploying ${modPomfilesPath}"
        if (pom.groupId == null) {
            pom.groupId = pom.parent.groupId
        }
        if (pom.version == null) {
            pom.version = pom.parent.version
        }
        if (pom.version != null) {
            repoID = pom.version.contains('SNAPSHOT')? "engine-maven-snapshot-local":"engine-maven-release-local"
        } else {
            repoID = "engine-maven-snapshot-local"
        }
        
        def groupId = pom.groupId.replace('.','/')
        writeMavenPom model: pom , file: "${WORKSPACE}/${modPomfilesPath}"
        withCredentials([string(credentialsId: 'HYPERLOOPOPS_ARTIFACTORY_API_TOKEN', variable: 'TOKEN')]) {
            withEnv(["mavenSecret=${TOKEN}", "mavenUser=hyperloopops"]) {
                sh """
                    curl -u ${mavenUser}:${mavenSecret} -T ${WORKSPACE}/${modPomfilesPath} "${KmjEnv.ART_URL}${repoID}/${groupId}/${pom.artifactId}/${pom.version}/${pom.artifactId}-${pom.version}.pom"
                    """
            }
        }
    }

def getParentArtifactVersion(def groupId, def artifactId, boolean snapshot = false) {
    groupId=groupId.replace('.','/')
    def artifactVersion = ""
    def repoId = snapshot? "engine-maven-snapshot-local":"engine-maven-release-local"
    withCredentials([string(credentialsId: 'HYPERLOOPOPS_ARTIFACTORY_API_TOKEN', variable: 'TOKEN')]) {
        try {
            withEnv(["mavenSecret=${TOKEN}", "mavenUser=hyperloopops"]) {
                sh """
                curl -O -L -u ${mavenUser}:${mavenSecret} "${KmjEnv.ART_URL}${repoId}/${groupId}/${artifactId}/maven-metadata.xml"
                """
            }
        } catch (Exception e) {
            println "Error: ${e}"
        }
    }
    def metaDataFile = readFile("maven-metadata.xml")
    if (!metaDataFile.contains("File not found.")) {
        def xmlSlurper = new XmlSlurper()
        def metaData = xmlSlurper.parseText(metaDataFile)
        // Setting to null to prevent NonSerializableException
        xmlSlurper = null
        artifactVersion = snapshot? metaData.versioning.latest : metaData.versioning.release
        metaData = null
    } else {
        println "Error: ${metaDataFile}"
    }

    return artifactVersion
}