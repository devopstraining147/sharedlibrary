
package com.sony.sie.cicd.ci.utilities

import groovy.json.JsonSlurperClassic
import org.codehaus.groovy.GroovyException
import com.sony.sie.cicd.helpers.utilities.JenkinsUtils

def checkAndCreateEcrRepo(String ecrRepoName, String region = 'us-west-2') {
    container('build-tools'){
        withEnv(assumeRole("413447738596", 'ECRPushPullRole', region, 'ecr')) {
            if (describeEcrRepo(ecrRepoName).contains("RepositoryNotFoundException")) {
                echo "repo does NOT exist - ready to create"
                createEcrRepo(ecrRepoName, region)
            } else {
                echo "repo DOES exist, no create needed"
            }
        }
    }
}

def describeEcrRepo(String repoName) {
    def repos
    try {
        repos = sh script: "aws ecr describe-repositories --region 'us-west-2' --repository-name ${repoName}", returnStdout: true
    } catch (Exception err) {
        String msg = err.getMessage()
        def exitCode = msg.split()[-1]
        if (exitCode == "255" || exitCode == "254") {
            return "RepositoryNotFoundException"
        } else {
            echo "describeEcrRepo failed: " + msg
            throw err
        }
    }
    return repos
}

def createEcrRepo(String newRepo, String region) {
    def repos = sh script: "aws ecr create-repository --repository-name ${newRepo} --region ${region}", returnStdout: true
    return repos
}

def assumeRole(String acctId, String role, String region, String roleName) {
    sts_output = sh script: "aws sts assume-role --role-arn arn:aws:iam::${acctId}:role/${role} --role-session-name ${roleName}", returnStdout: true
    return parseSTS(sts_output)
}


def getEcrLoginCmd(String acctId, String role, String region, String roleName) {
    String login_cmd = ''
    container('build-tools') {
        withEnv(assumeRole(acctId, role, region, roleName)) {
            login_cmd = sh script: "aws ecr get-login --region ${region} --registry-ids ${acctId} |sed -e 's/-e none //g'|tr -d '\r'", returnStdout: true
        }
    }
    return login_cmd
}

def parseSTS(sts_output) {
    def stsenv = []
    def parser = new JsonSlurperClassic().parseText(sts_output)
    stsenv.add("AWS_ACCESS_KEY_ID=${parser.Credentials.AccessKeyId}")
    stsenv.add("AWS_SECRET_ACCESS_KEY=${parser.Credentials.SecretAccessKey}")
    stsenv.add("AWS_SESSION_TOKEN=${parser.Credentials.SessionToken}")
    return stsenv
}

def pushToEcr(server, image) {
    login_cmd = getEcrLoginCmd('413447738596', 'ECRPushPullRole', 'us-west-2', 'ecr')
    container('build-tools'){
        sh login_cmd
        sh "docker push ${server}${image}"
    }
}

def pushToS3(source_file, destination, flags) {
    container('build-tools'){
        sh "aws s3 cp ${source_file} ${destination} ${flags}"
    }
}

def loadAwsCreds(credentialsId) {
    withCredentials([file(credentialsId: credentialsId, variable: 'cred')]) {
        sh """ 
            set +e
            rm -rf /root/.aws
            mkdir /root/.aws
            cat \${cred} > /root/.aws/credentials
        """
    }
}

def pushPackageToS3(def manifestData) {
    loadAwsCreds('lambda-credentials')
    def data = readYaml file: "manifest.yaml"
    withEnv(assumeRole('761590636927', 'kami-role-one-jenkins-lambda-core', 'us-west-2', 'lambda')) {
        String updatedfiles = ","
        for(int count=0; count < manifestData.size(); count++) {
            def item = manifestData[count]
            if(!updatedfiles.contains(item.local_file)) {
                sh """
                    aws s3 cp ${item.local_file} s3://kami-one-jenkins-lambda-builds-us-west-2/${env.REPO_NAME}/${item.file_name}
                """
                updatedfiles+=item.local_file + ","
            }
            data[count].file_name = item.file_name
        }
    }
    sh "rm manifest.yaml"
    writeYaml file: "manifest.yaml", data: data
}

def setNewFileNameVersion(String fileName, String newVersion) {
    //fileName format: moduleName + "-" + appVersion+ "." + fileType
    if(fileName.contains(".")) {
        String fileType = fileName.split("\\.")[-1].toLowerCase()
        if(fileType != "zip" && fileType != "jar") {
            throw new GroovyException("The file type of '${fileType}' is not supported. It should be either zip or jar!")
        }
        fileName = fileName.replace(".${fileType}", "")
        if(fileName.contains("-")) {
            String version = fileName.split("-")[-1]
            fileName = fileName.replace("-${version}", "")
        }
        return fileName+"-"+newVersion+"."+fileType
    } else {
        throw new GroovyException("The file type of '${fileName}' could not be found!")
    }
}

def exeClosure(Closure body) {
    if(body != null){
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body.delegate = this
        body()
    }
}

def fetchSecret(String secretName) {
    def asmOutput = ''
    def parser = ''
    def secretString = ''
    JenkinsUtils jenkinsUtils = new JenkinsUtils()
    asmOutput = jenkinsUtils.shWrapper("aws secretsmanager --region ${env.AWS_REGION} get-secret-value --secret-id ${secretName}  --output json",true)
    if (asmOutput != '') {
        parser = new JsonSlurperClassic().parseText(asmOutput)
        if (parser?.SecretString != null) secretString = parser.SecretString
    }
    return secretString
}

def getAWSSecret(String secretName, String secretKey) {
    container("build-tools") {
        String secretString = fetchSecret(secretName)
        def parser = new JsonSlurperClassic().parseText(secretString)
        // echo "parser: ${parser}"
        return parser[secretKey]
    }
}


return this
