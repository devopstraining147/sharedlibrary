package com.sony.sie.cicd.ci.utilities

def checkAndCreateEcrRepoList(def dockerFileList, String region = 'us-west-2') {
    container('build-tools') {
        withCredentials([[$class: 'StringBinding', credentialsId: 'KMJ_ECR_AWS_ACCESS_KEY_ID', variable: 'AWS_ACCESS_KEY_ID'],
                         [$class: 'StringBinding', credentialsId: 'KMJ_ECR_AWS_SECRET_ACCESS_KEY', variable: 'AWS_SECRET_ACCESS_KEY']]) {
            for (int i = 0; i < dockerFileList.size(); i++) {
                def item = dockerFileList[i]
                item = [organization: 'engine', version: env.APP_VERSION] << item
                String mutability = item.version == env.APP_VERSION ? "IMMUTABLE" : "MUTABLE"
                checkAndCreateEcrRepo("${item.organization}/${item.appName}", region, mutability)
            }
        }
    }
}

def checkAndCreateEcrRepo(String ecrRepoName, String region, String mutability = "IMMUTABLE") {
//    echo "Creating new ECR repo"
    if (describeEcrRepo(ecrRepoName, region).contains("RepositoryNotFoundException")) {
        echo "${ecrRepoName} does NOT exist - ready to create"
        createEcrRepo(ecrRepoName, region, mutability)
        assignPermissions(ecrRepoName, region)
    } else {
        echo "${ecrRepoName} DOES exist, no creation needed"
    }
}

def assignPermissions(String ecrRepoName, String region) {
   sh """
        aws ecr set-repository-policy --repository-name ${ecrRepoName} --region ${region} \
        --policy-text  '{"Version":"2008-10-17","Statement":[{"Sid":"ReadOnlyCrossAccount","Effect":"Allow","Principal":{"AWS":["arn:aws:iam::128663048608:root","arn:aws:iam::789576034278:root","arn:aws:iam::294026244628:root","arn:aws:iam::761590636927:root","arn:aws:iam::413447738596:root","arn:aws:iam::808238840382:root","arn:aws:iam::608442927737:root","arn:aws:iam::547422685590:root","arn:aws:iam::840450103176:root"]},"Action":["ecr:GetAuthorizationToken","ecr:BatchCheckLayerAvailability","ecr:GetDownloadUrlForLayer","ecr:DescribeRepositories","ecr:ListImages","ecr:DescribeImages","ecr:BatchGetImage","ecr:GetLifecyclePolicy","ecr:GetLifecyclePolicyPreview","ecr:ListTagsForResource"]}]}'
    """
}

def describeEcrRepo(String repoName, String region) {
    try {
        return sh (script: "aws ecr describe-repositories --region ${region} --repository-name ${repoName}", returnStdout: true)
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
}

def createEcrRepo(String newRepo, String region, String mutability = "IMMUTABLE") {
    def txtScript = """
            aws ecr create-repository --repository-name ${newRepo} \
             --region ${region} \
             --tags "Key=BillTo,Value=Infrastructure" \
                    "Key=Service,Value=Kubernetes" \
                    "Key=Application,Value=Kubernetes"\
                    "Key=Environment,Value=tools" \
             --image-tag-mutability ${mutability}         
        """
    try {
        return sh (script: txtScript, returnStdout: true)
    } catch (Exception err) {
        echo "createEcrRepo failed: " + err.getMessage()
        throw err
    }

}

return this
