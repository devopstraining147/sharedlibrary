
package com.sony.sie.cicd.cd.deployProcessors

class LacoK8sDeployProcessor extends K8sBaseDeployProcessor {
    def getKubeConfig(Map conf) {
        def iamRole = "arn:aws:iam::578517639677:role/uks-jenkins-deploy-role-nonprod"
        if (conf.clusterId.contains("p1") ) {
            iamRole = "arn:aws:iam::207841167519:role/unified-jenkins-eks-access"
        }
        
        // def profileClusterId = "unified-eks-q1-np-us-west-2-01"
        def awsRegion = conf.region ?: "us-west-2"
        setupKubeConfig(conf.clusterId, iamRole, awsRegion)
    }
    
    def setupKubeConfig(def clusterId, def iamRole, def awsRegion = "us-west-2") {
        // configure the IRSA profile
        // the aws cli assume role system is not picking up the default
        // irsa profile since that itself is an assumed role
        // we need to manually configure it as a profile
        // source_credentials doesn't pick up the container role
        sh 'aws configure set profile.irsa.region ' + awsRegion
        sh 'aws configure set profile.irsa.role_arn ${AWS_ROLE_ARN}'
        sh 'aws configure set profile.irsa.web_identity_token_file ${AWS_WEB_IDENTITY_TOKEN_FILE}'
        // configure the assumed role for the target cluster
        sh 'aws configure set profile.target_cluster.region ' + awsRegion
        sh 'aws configure set profile.target_cluster.role_arn ' + iamRole
        sh 'aws configure set profile.target_cluster.source_profile irsa'           
        // test identity again to see the assumed role that has access to the cluster
        sh 'aws eks list-clusters --profile target_cluster'
        sh 'aws eks update-kubeconfig --name ' + clusterId + ' --profile target_cluster'
    }
}
